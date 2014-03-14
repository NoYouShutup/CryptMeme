package net.i2p.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.SigType;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SimpleDataStructure;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.RequestLeaseSetMessage;
import net.i2p.util.Log;

/**
 * Handle I2CP RequestLeaseSetMessage from the router by granting all leases,
 * using the specified expiration time for each lease.
 *
 * @author jrandom
 */
class RequestLeaseSetMessageHandler extends HandlerImpl {
    private final Map<Destination, LeaseInfo> _existingLeaseSets;

    public RequestLeaseSetMessageHandler(I2PAppContext context) {
        this(context, RequestLeaseSetMessage.MESSAGE_TYPE);
    }

    /**
     *  For extension
     *  @since 0.9.7
     */
    protected RequestLeaseSetMessageHandler(I2PAppContext context, int messageType) {
        super(context, messageType);
        // not clear why there would ever be more than one
        _existingLeaseSets = new ConcurrentHashMap<Destination, LeaseInfo>(4);
    }
    
    public void handleMessage(I2CPMessage message, I2PSessionImpl session) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handle message " + message);
        RequestLeaseSetMessage msg = (RequestLeaseSetMessage) message;
        LeaseSet leaseSet = new LeaseSet();
        for (int i = 0; i < msg.getEndpoints(); i++) {
            Lease lease = new Lease();
            lease.setGateway(msg.getRouter(i));
            lease.setTunnelId(msg.getTunnelId(i));
            lease.setEndDate(msg.getEndDate());
            //lease.setStartDate(msg.getStartDate());
            leaseSet.addLease(lease);
        }
        signLeaseSet(leaseSet, session);
    }

    /**
     *  Finish creating and signing the new LeaseSet
     *  @since 0.9.7
     */
    protected void signLeaseSet(LeaseSet leaseSet, I2PSessionImpl session) {
        // also, if this session is connected to multiple routers, include other leases here
        leaseSet.setDestination(session.getMyDestination());

        // reuse the old keys for the client
        LeaseInfo li = _existingLeaseSets.get(session.getMyDestination());
        if (li == null) {
            li = new LeaseInfo(session.getMyDestination());
            _existingLeaseSets.put(session.getMyDestination(), li);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Creating new leaseInfo keys for "  
                           + session.getMyDestination().calculateHash().toBase64());
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Caching the old leaseInfo keys for " 
                           + session.getMyDestination().calculateHash().toBase64());
        }

        leaseSet.setEncryptionKey(li.getPublicKey());
        leaseSet.setSigningKey(li.getSigningPublicKey());
        boolean encrypt = Boolean.parseBoolean(session.getOptions().getProperty("i2cp.encryptLeaseSet"));
        String sk = session.getOptions().getProperty("i2cp.leaseSetKey");
        if (encrypt && sk != null) {
            SessionKey key = new SessionKey();
            try {
                key.fromBase64(sk);
                leaseSet.encrypt(key);
                _context.keyRing().put(session.getMyDestination().calculateHash(), key);
            } catch (DataFormatException dfe) {
                _log.error("Bad leaseset key: " + sk);
            }
        }
        try {
            leaseSet.sign(session.getPrivateKey());
            // Workaround for unparsable serialized signing private key for revocation
            // Send him a dummy DSA_SHA1 private key since it's unused anyway
            // See CreateLeaseSetMessage.doReadMessage()
            SigningPrivateKey spk = li.getSigningPrivateKey();
            if (!_context.isRouterContext() && spk.getType() != SigType.DSA_SHA1) {
                byte[] dummy = new byte[SigningPrivateKey.KEYSIZE_BYTES];
                _context.random().nextBytes(dummy);
                spk = new SigningPrivateKey(dummy);
            }
            session.getProducer().createLeaseSet(session, leaseSet, li.getSigningPrivateKey(), li.getPrivateKey());
            session.setLeaseSet(leaseSet);
        } catch (DataFormatException dfe) {
            session.propogateError("Error signing the leaseSet", dfe);
        } catch (I2PSessionException ise) {
            session.propogateError("Error sending the signed leaseSet", ise);
        }
    }

    private static class LeaseInfo {
        private final PublicKey _pubKey;
        private final PrivateKey _privKey;
        private final SigningPublicKey _signingPubKey;
        private final SigningPrivateKey _signingPrivKey;

        public LeaseInfo(Destination dest) {
            Object encKeys[] = KeyGenerator.getInstance().generatePKIKeypair();
            // must be same type as the Destination's signing key
            SimpleDataStructure signKeys[];
            try {
                signKeys = KeyGenerator.getInstance().generateSigningKeys(dest.getSigningPublicKey().getType());
            } catch (GeneralSecurityException gse) {
                throw new IllegalStateException(gse);
            }
            _pubKey = (PublicKey) encKeys[0];
            _privKey = (PrivateKey) encKeys[1];
            _signingPubKey = (SigningPublicKey) signKeys[0];
            _signingPrivKey = (SigningPrivateKey) signKeys[1];
        }

        public PublicKey getPublicKey() {
            return _pubKey;
        }

        public PrivateKey getPrivateKey() {
            return _privKey;
        }

        public SigningPublicKey getSigningPublicKey() {
            return _signingPubKey;
        }

        public SigningPrivateKey getSigningPrivateKey() {
            return _signingPrivKey;
        }
        
        @Override
        public int hashCode() {
            return DataHelper.hashCode(_pubKey) + 7 * DataHelper.hashCode(_privKey) + 7 * 7
                   * DataHelper.hashCode(_signingPubKey) + 7 * 7 * 7 * DataHelper.hashCode(_signingPrivKey);
        }
        
        @Override
        public boolean equals(Object obj) {
            if ((obj == null) || !(obj instanceof LeaseInfo)) return false;
            LeaseInfo li = (LeaseInfo) obj;
            return DataHelper.eq(_pubKey, li.getPublicKey()) && DataHelper.eq(_privKey, li.getPrivateKey())
                   && DataHelper.eq(_signingPubKey, li.getSigningPublicKey())
                   && DataHelper.eq(_signingPrivKey, li.getSigningPrivateKey());
        }
    }
}
