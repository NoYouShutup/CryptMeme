package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Date;

import net.i2p.I2PAppContext;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.Certificate;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.PrivateKey;
import net.i2p.data.i2np.GarlicClove;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.util.Log;

/**
 *  Read a GarlicMessage, decrypt it, and return the resulting CloveSet.
 *  Thread-safe, does not contain any state.
 *  Public as it's now in the RouterContext.
 */
public class GarlicMessageParser {
    private final Log _log;
    private final I2PAppContext _context;
    
    /**
     *  Huge limit just to reduce chance of trouble. Typ. usage is 3.
     *  As of 0.9.12. Was 255.
     */
    private static final int MAX_CLOVES = 32;

    public GarlicMessageParser(I2PAppContext context) { 
        _context = context;
        _log = _context.logManager().getLog(GarlicMessageParser.class);
    }
    
    /**
     *  @param skm use tags from this session key manager
     *  @return null on error
     */
    public CloveSet getGarlicCloves(GarlicMessage message, PrivateKey encryptionKey, SessionKeyManager skm) {
        byte encData[] = message.getData();
        byte decrData[];
        try {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Decrypting with private key " + encryptionKey);
            decrData = _context.elGamalAESEngine().decrypt(encData, encryptionKey, skm);
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error decrypting", dfe);
            return null;
        }
        if (decrData == null) {
            // This is the usual error path and it's logged at WARN level in GarlicMessageReceiver
            if (_log.shouldLog(Log.INFO))
                _log.info("Decryption of garlic message failed", new Exception("Decrypt fail"));
            return null;
        } else {
            try {
                return readCloveSet(decrData); 
            } catch (DataFormatException dfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Unable to read cloveSet", dfe);
                return null;
            }
        }
    }
    
    private CloveSet readCloveSet(byte data[]) throws DataFormatException {
        int offset = 0;
        
        int numCloves = (int)DataHelper.fromLong(data, offset, 1);
        offset++;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("# cloves to read: " + numCloves);
        if (numCloves <= 0 || numCloves > MAX_CLOVES)
            throw new DataFormatException("bad clove count " + numCloves);
        GarlicClove[] cloves = new GarlicClove[numCloves];
        for (int i = 0; i < numCloves; i++) {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Reading clove " + i);
                GarlicClove clove = new GarlicClove(_context);
                offset += clove.readBytes(data, offset);
                cloves[i] = clove;
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("After reading clove " + i);
        }
        //Certificate cert = new Certificate();
        //offset += cert.readBytes(data, offset);
        Certificate cert = Certificate.create(data, offset);
        offset += cert.size();
        long msgId = DataHelper.fromLong(data, offset, 4);
        offset += 4;
        //Date expiration = DataHelper.fromDate(data, offset);
        long expiration = DataHelper.fromLong(data, offset, 8);

        CloveSet set = new CloveSet(cloves, cert, msgId, expiration);
        return set;
    }
}
