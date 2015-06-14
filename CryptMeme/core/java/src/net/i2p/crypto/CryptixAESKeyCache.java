package net.i2p.crypto;

import java.io.Serializable;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Cache the objects used in CryptixRijndael_Algorithm.makeKey to reduce
 * memory churn.  The KeyCacheEntry should be held onto as long as the 
 * data referenced in it is needed (which often is only one or two lines
 * of code)
 *
 * Unused as a class, as the keys are cached in the SessionKey objects,
 * but the static methods are used in FortunaStandalone.
 */
public final class CryptixAESKeyCache {
    private final LinkedBlockingQueue<KeyCacheEntry> _availableKeys;
    
    private static final int KEYSIZE = 32; // 256bit AES
    private static final int BLOCKSIZE = 16;
    private static final int ROUNDS = CryptixRijndael_Algorithm.getRounds(KEYSIZE, BLOCKSIZE);
    private static final int BC = BLOCKSIZE / 4;
    private static final int KC = KEYSIZE / 4; 
    
    private static final int MAX_KEYS = 64;
    
    /*
     * @deprecated unused, keys are now cached in the SessionKey objects
     */
    public CryptixAESKeyCache() {
        _availableKeys = new LinkedBlockingQueue<KeyCacheEntry>(MAX_KEYS);
    }
    
    /**
     * Get the next available structure, either from the cache or a brand new one
     *
     * @deprecated unused, keys are now cached in the SessionKey objects
     */
    public final KeyCacheEntry acquireKey() {
        KeyCacheEntry rv = _availableKeys.poll();
        if (rv != null)
            return rv;
        return createNew();
    }
    
    /**
     * Put this structure back onto the available cache for reuse
     *
     * @deprecated unused, keys are now cached in the SessionKey objects
     */
    public final void releaseKey(KeyCacheEntry key) {
        _availableKeys.offer(key);
    }
    
    public static final KeyCacheEntry createNew() {
        KeyCacheEntry e = new KeyCacheEntry();
        return e;
    }
    
    /**
     * all the data alloc'ed in a makeKey call
     */
    public static class KeyCacheEntry implements Serializable {
        /** encryption round keys */
        final int[][] Ke;
        /** decryption round keys */
        final int[][] Kd;
        final int[]   tk;
        /** Ke, Kd */
        final Object[] key;

        public KeyCacheEntry() {
            Ke = new int[ROUNDS + 1][BC];
            Kd = new int[ROUNDS + 1][BC];
            tk = new int[KC];
            key = new Object[] { Ke, Kd };
        }
    }
}
