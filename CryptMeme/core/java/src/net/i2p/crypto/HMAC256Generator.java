package net.i2p.crypto;

import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.spec.SecretKeySpec;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;

import org.bouncycastle.oldcrypto.macs.I2PHMac;

/**
 * Calculate the HMAC-SHA256 of a key+message.
 * This is compatible with javax.crypto.Mac.getInstance("HmacSHA256").
 *
 * As of 0.9.12, uses javax.crypto.Mac.
 *
 * Deprecated, used only by Syndie.
 */
public class HMAC256Generator extends HMACGenerator {

    /**
     *  @param context unused
     */
    public HMAC256Generator(I2PAppContext context) { super(context); }
    
    /**
     *  @deprecated unused (not even by Syndie)
     *  @throws UnsupportedOperationException since 0.9.12
     */
    @Override
    protected I2PHMac acquire() {
        throw new UnsupportedOperationException();
    }
    
    /**
     *  Calculate the HMAC of the data with the given key
     *
     *  @return the first 16 bytes contain the HMAC, the last 16 bytes are zero
     *  @deprecated unused (not even by Syndie)
     *  @throws UnsupportedOperationException always
     *  @since 0.9.12 overrides HMACGenerator
     */
    @Override
    public Hash calculate(SessionKey key, byte data[]) {
        throw new UnsupportedOperationException();
    }
    
    /**
     *  Calculate the HMAC of the data with the given key.
     *  Outputs 32 bytes to target starting at targetOffset.
     *
     *  @throws UnsupportedOperationException if the JVM does not support it
     *  @throws IllegalArgumentException for bad key or target too small
     *  @since 0.9.12 overrides HMACGenerator
     */
    @Override
    public void calculate(SessionKey key, byte data[], int offset, int length, byte target[], int targetOffset) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            Key keyObj = new SecretKeySpec(key.getData(), "HmacSHA256");
            mac.init(keyObj);
            mac.update(data, offset, length);
            mac.doFinal(target, targetOffset);
        } catch (NoSuchAlgorithmException e) {
            throw new UnsupportedOperationException("HmacSHA256", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("HmacSHA256", e);
        }
    }
    
    /**
     *  Verify the MAC inline, reducing some unnecessary memory churn.
     *
     *  @param key session key to verify the MAC with
     *  @param curData MAC to verify
     *  @param curOffset index into curData to MAC
     *  @param curLength how much data in curData do we want to run the HMAC over
     *  @param origMAC what do we expect the MAC of curData to equal
     *  @param origMACOffset index into origMAC
     *  @param origMACLength how much of the MAC do we want to verify, use 32 for HMAC256
     *  @since 0.9.12 overrides HMACGenerator
     */
    @Override
    public boolean verify(SessionKey key, byte curData[], int curOffset, int curLength,
                          byte origMAC[], int origMACOffset, int origMACLength) {
        byte calc[] = acquireTmp();
        calculate(key, curData, curOffset, curLength, calc, 0);
        boolean eq = DataHelper.eq(calc, 0, origMAC, origMACOffset, origMACLength);
        releaseTmp(calc);
        return eq;
    }

/******
    private static class Sha256ForMAC extends Sha256Standalone implements Digest {
        public String getAlgorithmName() { return "sha256 for hmac"; }
        public int getDigestSize() { return 32; }
        public int doFinal(byte[] out, int outOff) {
            byte rv[] = digest();
            System.arraycopy(rv, 0, out, outOff, rv.length);
            reset();
            return rv.length;
        }
        
    }
    
    public static void main(String args[]) {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        byte data[] = new byte[64];
        ctx.random().nextBytes(data);
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        Hash mac = ctx.hmac256().calculate(key, data);
        System.out.println(Base64.encode(mac.getData()));
    }
******/

    /**
     *  Test the BC and the JVM's implementations for speed
     *
     *  Results on 2012 hexcore box, OpenJDK 7:
     *    BC 9275 ms (before converting to MessageDigest)
     *    BC 8500 ms (after converting to MessageDigest)
     *    JVM 8065 ms
     *
     */
/****
    public static void main(String args[]) {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        byte[] rand = new byte[32];
        byte[] data = new byte[1500];
        Key keyObj = new SecretKeySpec(rand, "HmacSHA256");
        SessionKey key = new SessionKey(rand);

        HMAC256Generator gen = new HMAC256Generator(I2PAppContext.getGlobalContext());
        byte[] result = new byte[32];
        javax.crypto.Mac mac;
        try {
            mac = javax.crypto.Mac.getInstance("HmacSHA256");
        } catch (NoSuchAlgorithmException e) {
            System.err.println("Fatal: " + e);
            return;
        }
        // warmup and comparison
        System.out.println("Warmup and comparison:");
        int RUNS = 25000;
        for (int i = 0; i < RUNS; i++) {
            ctx.random().nextBytes(rand);
            ctx.random().nextBytes(data);
            keyObj = new SecretKeySpec(rand, "HmacSHA256");
            byte[] keyBytes = keyObj.getEncoded();
            if (!DataHelper.eq(rand, keyBytes))
                System.out.println("secret key in != out");
            key = new SessionKey(rand);
            gen.calculate(key, data, 0, data.length, result, 0);
            try {
                mac.init(keyObj);
            } catch (GeneralSecurityException e) {
                System.err.println("Fatal: " + e);
                return;
            }
            byte[] result2 = mac.doFinal(data);
            if (!DataHelper.eq(result, result2))
                throw new IllegalStateException();
        }

        // real thing
        System.out.println("Passed");
        System.out.println("BC Test:");
        RUNS = 500000;
        long start = System.currentTimeMillis();
        for (int i = 0; i < RUNS; i++) {
            gen.calculate(key, data, 0, data.length, result, 0);
        }
        long time = System.currentTimeMillis() - start;
        System.out.println("Time for " + RUNS + " HMAC-SHA256 computations:");
        System.out.println("BC time (ms): " + time);

        System.out.println("JVM Test:");
        start = System.currentTimeMillis();
        for (int i = 0; i < RUNS; i++) {
            try {
                mac.init(keyObj);
            } catch (GeneralSecurityException e) {
                System.err.println("Fatal: " + e);
            }
            byte[] sha = mac.doFinal(data);
        }
        time = System.currentTimeMillis() - start;

        System.out.println("JVM time (ms): " + time);
    }
****/
}
