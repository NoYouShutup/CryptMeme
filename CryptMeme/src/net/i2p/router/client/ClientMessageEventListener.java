package net.i2p.router.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Properties;

import net.i2p.CoreVersion;
import net.i2p.data.Hash;
import net.i2p.data.Payload;
import net.i2p.data.i2cp.BandwidthLimitsMessage;
import net.i2p.data.i2cp.CreateLeaseSetMessage;
import net.i2p.data.i2cp.CreateSessionMessage;
import net.i2p.data.i2cp.DestLookupMessage;
import net.i2p.data.i2cp.DestroySessionMessage;
import net.i2p.data.i2cp.GetBandwidthLimitsMessage;
import net.i2p.data.i2cp.GetDateMessage;
import net.i2p.data.i2cp.HostLookupMessage;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.I2CPMessageReader;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.MessagePayloadMessage;
import net.i2p.data.i2cp.ReceiveMessageBeginMessage;
import net.i2p.data.i2cp.ReceiveMessageEndMessage;
import net.i2p.data.i2cp.ReconfigureSessionMessage;
import net.i2p.data.i2cp.SendMessageMessage;
import net.i2p.data.i2cp.SendMessageExpiresMessage;
import net.i2p.data.i2cp.SessionConfig;
import net.i2p.data.i2cp.SessionId;
import net.i2p.data.i2cp.SessionStatusMessage;
import net.i2p.data.i2cp.SetDateMessage;
import net.i2p.router.ClientTunnelSettings;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.PasswordManager;
import net.i2p.util.RandomSource;

/**
 * Receive events from the client and handle them accordingly (updating the runner when
 * necessary)
 *
 */
class ClientMessageEventListener implements I2CPMessageReader.I2CPMessageEventListener {
    private final Log _log;
    protected final RouterContext _context;
    protected final ClientConnectionRunner _runner;
    private final boolean  _enforceAuth;
    private volatile boolean _authorized;
    
    private static final String PROP_AUTH = "i2cp.auth";
    /** if true, user/pw must be in GetDateMessage */
    private static final String PROP_AUTH_STRICT = "i2cp.strictAuth";

    /**
     *  @param enforceAuth set false for in-JVM, true for socket access
     */
    public ClientMessageEventListener(RouterContext context, ClientConnectionRunner runner, boolean enforceAuth) {
        _context = context;
        _log = _context.logManager().getLog(ClientMessageEventListener.class);
        _runner = runner;
        _enforceAuth = enforceAuth;
        if ((!_enforceAuth) || !_context.getBooleanProperty(PROP_AUTH))
            _authorized = true;
        _context.statManager().createRateStat("client.distributeTime", "How long it took to inject the client message into the router", "ClientMessages", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
    }
    
    /**
     * Handle an incoming message and dispatch it to the appropriate handler
     *
     */
    public void messageReceived(I2CPMessageReader reader, I2CPMessage message) {
        if (_runner.isDead()) return;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Message received: \n" + message);
        int type = message.getType();
        if (!_authorized) {
            // TODO change to default true
            boolean strict = _context.getBooleanProperty(PROP_AUTH_STRICT);
            if ((strict && type != GetDateMessage.MESSAGE_TYPE) ||
                (type != CreateSessionMessage.MESSAGE_TYPE &&
                 type != GetDateMessage.MESSAGE_TYPE &&
                 type != DestLookupMessage.MESSAGE_TYPE &&
                 type != GetBandwidthLimitsMessage.MESSAGE_TYPE)) {
                _log.error("Received message type " + type + " without required authentication");
                _runner.disconnectClient("Authorization required");
                return;
            }
        }
        switch (message.getType()) {
            case GetDateMessage.MESSAGE_TYPE:
                handleGetDate((GetDateMessage)message);
                break;
            case SetDateMessage.MESSAGE_TYPE:
                handleSetDate((SetDateMessage)message);
                break;
            case CreateSessionMessage.MESSAGE_TYPE:
                handleCreateSession((CreateSessionMessage)message);
                break;
            case SendMessageMessage.MESSAGE_TYPE:
                handleSendMessage((SendMessageMessage)message);
                break;
            case SendMessageExpiresMessage.MESSAGE_TYPE:
                handleSendMessage((SendMessageExpiresMessage)message);
                break;
            case ReceiveMessageBeginMessage.MESSAGE_TYPE:
                handleReceiveBegin((ReceiveMessageBeginMessage)message);
                break;
            case ReceiveMessageEndMessage.MESSAGE_TYPE:
                handleReceiveEnd((ReceiveMessageEndMessage)message);
                break;
            case CreateLeaseSetMessage.MESSAGE_TYPE:
                handleCreateLeaseSet((CreateLeaseSetMessage)message);
                break;
            case DestroySessionMessage.MESSAGE_TYPE:
                handleDestroySession((DestroySessionMessage)message);
                break;
            case DestLookupMessage.MESSAGE_TYPE:
                handleDestLookup((DestLookupMessage)message);
                break;
            case HostLookupMessage.MESSAGE_TYPE:
                handleHostLookup((HostLookupMessage)message);
                break;
            case ReconfigureSessionMessage.MESSAGE_TYPE:
                handleReconfigureSession((ReconfigureSessionMessage)message);
                break;
            case GetBandwidthLimitsMessage.MESSAGE_TYPE:
                handleGetBWLimits((GetBandwidthLimitsMessage)message);
                break;
            default:
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Unhandled I2CP type received: " + message.getType());
        }
    }

    /**
     * Handle notification that there was an error
     *
     */
    public void readError(I2CPMessageReader reader, Exception error) {
        if (_runner.isDead()) return;
        if (_log.shouldLog(Log.ERROR))
            _log.error("Error occurred", error);
        // Is this is a little drastic for an unknown message type?
        // Send the whole exception string over for diagnostics
        _runner.disconnectClient(error.toString());
        _runner.stopRunning();
    }
  
    public void disconnected(I2CPMessageReader reader) {
        if (_runner.isDead()) return;
        _runner.disconnected();
    }
    
    private void handleGetDate(GetDateMessage message) {
        // sent by clients >= 0.8.7
        String clientVersion = message.getVersion();
        if (clientVersion != null)
            _runner.setClientVersion(clientVersion);
        Properties props = message.getOptions();
        if (!checkAuth(props))
            return;
        try {
            // only send version if the client can handle it (0.8.7 or greater)
            _runner.doSend(new SetDateMessage(clientVersion != null ? CoreVersion.VERSION : null));
        } catch (I2CPMessageException ime) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error writing out the setDate message", ime);
        }
    }

    /**
     *  As of 0.8.7, does nothing. Do not allow a client to set the router's clock.
     */
    private void handleSetDate(SetDateMessage message) {
        //_context.clock().setNow(message.getDate().getTime());
    }
	
    
    /** 
     * Handle a CreateSessionMessage.
     * On errors, we could perhaps send a SessionStatusMessage with STATUS_INVALID before
     * sending the DisconnectMessage... but right now the client will send _us_ a
     * DisconnectMessage in return, and not wait around for our DisconnectMessage.
     * So keep it simple.
     */
    private void handleCreateSession(CreateSessionMessage message) {
        SessionConfig in = message.getSessionConfig();
        if (in.verifySignature()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Signature verified correctly on create session message");
        } else {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Signature verification *FAILED* on a create session message.  Hijack attempt?");
            // For now, we do NOT send a SessionStatusMessage - see javadoc above
            _runner.disconnectClient("Invalid signature on CreateSessionMessage");
            return;
        }

        // Auth, since 0.8.2
        Properties inProps = in.getOptions();
        if (!checkAuth(inProps))
            return;

        SessionId id = _runner.getSessionId();
        if (id != null) {
            _runner.disconnectClient("Already have session " + id);
            return;
        }

        // Copy over the whole config structure so we don't later corrupt it on
        // the client side if we change settings or later get a
        // ReconfigureSessionMessage
        SessionConfig cfg = new SessionConfig(in.getDestination());
        cfg.setSignature(in.getSignature());
        Properties props = new Properties();
        props.putAll(in.getOptions());
        cfg.setOptions(props);
        int status = _runner.sessionEstablished(cfg);
        if (status != SessionStatusMessage.STATUS_CREATED) {
            // For now, we do NOT send a SessionStatusMessage - see javadoc above
            if (_log.shouldLog(Log.ERROR))
                _log.error("Session establish failed: code = " + status);
            String msg;
            if (status == SessionStatusMessage.STATUS_INVALID)
                msg = "duplicate destination";
            else if (status == SessionStatusMessage.STATUS_REFUSED)
                msg = "session limit exceeded";
            else
                msg = "unknown error";
            _runner.disconnectClient(msg);
            return;
        }
        sendStatusMessage(status);

        if (_log.shouldLog(Log.INFO))
            _log.info("Session " + _runner.getSessionId() + " established for " + _runner.getDestHash());
        startCreateSessionJob();
    }
    
    /**
     *  Side effect - sets _authorized.
     *  Side effect - disconnects session if not authorized.
     *
     *  @param props contains i2cp.username and i2cp.password, may be null
     *  @return success
     *  @since 0.9.11
     */
    private boolean checkAuth(Properties props) {
        if (_authorized)
            return true;
        if (_enforceAuth && _context.getBooleanProperty(PROP_AUTH)) {
            String user = null;
            String pw = null;
            if (props != null) {
                user = props.getProperty("i2cp.username");
                pw = props.getProperty("i2cp.password");
            }
            if (user == null || user.length() == 0 || pw == null || pw.length() == 0) {
                _log.error("I2CP auth failed");
                _runner.disconnectClient("Authorization required, specify i2cp.username and i2cp.password in options");
                _authorized = false;
                return false;
            }
            PasswordManager mgr = new PasswordManager(_context);
            if (!mgr.checkHash(PROP_AUTH, user, pw)) {
                _log.error("I2CP auth failed user: " + user);
                _runner.disconnectClient("Authorization failed, user = " + user);
                _authorized = false;
                return false;
            }
            if (_log.shouldLog(Log.INFO))
                _log.info("I2CP auth success user: " + user);
        }
        _authorized = true;
        return true;
    }

    /**
     *  Override for testing
     *  @since 0.9.8
     *
     */
    protected void startCreateSessionJob() {
        _context.jobQueue().addJob(new CreateSessionJob(_context, _runner));
    }
    
    /**
     * Handle a SendMessageMessage: give it a message Id, have the ClientManager distribute
     * it, and send the client an ACCEPTED message
     *
     */
    private void handleSendMessage(SendMessageMessage message) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("handleSendMessage called");
        long beforeDistribute = _context.clock().now();
        MessageId id = _runner.distributeMessage(message);
        long timeToDistribute = _context.clock().now() - beforeDistribute;
        _runner.ackSendMessage(id, message.getNonce());
        _context.statManager().addRateData("client.distributeTime", timeToDistribute);
        if ( (timeToDistribute > 50) && (_log.shouldLog(Log.WARN)) )
            _log.warn("Took too long to distribute the message (which holds up the ack): " + timeToDistribute);
    }

    
    /**
     * The client asked for a message, so we send it to them.  
     *
     */
    private void handleReceiveBegin(ReceiveMessageBeginMessage message) {
        if (_runner.isDead()) return;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handling recieve begin: id = " + message.getMessageId());
        MessagePayloadMessage msg = new MessagePayloadMessage();
        msg.setMessageId(message.getMessageId());
        msg.setSessionId(_runner.getSessionId().getSessionId());
        Payload payload = _runner.getPayload(new MessageId(message.getMessageId()));
        if (payload == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Payload for message id [" + message.getMessageId() 
                           + "] is null!  Dropped or Unknown message id");
            return;
        }
        msg.setPayload(payload);
        try {
            _runner.doSend(msg);
        } catch (I2CPMessageException ime) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error delivering the payload", ime);
            _runner.removePayload(new MessageId(message.getMessageId()));
        }
    }
    
    /**
     * The client told us that the message has been recieved completely.  This currently
     * does not do any security checking prior to removing the message from the 
     * pending queue, though it should.
     *
     */
    private void handleReceiveEnd(ReceiveMessageEndMessage message) {
        _runner.removePayload(new MessageId(message.getMessageId()));
    }
    
    private void handleDestroySession(DestroySessionMessage message) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Destroying client session " + _runner.getSessionId());
        _runner.stopRunning();
    }
    
    /** override for testing */
    protected void handleCreateLeaseSet(CreateLeaseSetMessage message) {	
        if ( (message.getLeaseSet() == null) || (message.getPrivateKey() == null) || (message.getSigningPrivateKey() == null) ) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Null lease set granted: " + message);
            _runner.disconnectClient("Invalid CreateLeaseSetMessage");
            return;
        }

        _context.keyManager().registerKeys(message.getLeaseSet().getDestination(), message.getSigningPrivateKey(), message.getPrivateKey());
        try {
            _context.netDb().publish(message.getLeaseSet());
        } catch (IllegalArgumentException iae) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Invalid leaseset from client", iae);
            _runner.disconnectClient("Invalid leaseset: " + iae);
            return;
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("New lease set granted for destination " 
                      + _runner.getDestHash());

        // leaseSetCreated takes care of all the LeaseRequestState stuff (including firing any jobs)
        _runner.leaseSetCreated(message.getLeaseSet());
    }

    /** override for testing */
    protected void handleDestLookup(DestLookupMessage message) {
        _context.jobQueue().addJob(new LookupDestJob(_context, _runner, message.getHash(),
                                                     _runner.getDestHash()));
    }

    /**
     * override for testing
     * @since 0.9.11
     */
    protected void handleHostLookup(HostLookupMessage message) {
        _context.jobQueue().addJob(new LookupDestJob(_context, _runner, message.getReqID(),
                                                     message.getTimeout(), message.getSessionId(),
                                                     message.getHash(), message.getHostname(),
                                                     _runner.getDestHash()));
    }

    /**
     * Message's Session ID ignored. This doesn't support removing previously set options.
     * Nor do we bother with message.getSessionConfig().verifySignature() ... should we?
     * Nor is the Date checked.
     *
     * Note that this does NOT update the few options handled in
     * ClientConnectionRunner.sessionEstablished(). Those can't be changed later.
     */
    private void handleReconfigureSession(ReconfigureSessionMessage message) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Updating options - old: " + _runner.getConfig() + " new: " + message.getSessionConfig());
        if (!message.getSessionConfig().getDestination().equals(_runner.getConfig().getDestination())) {
            _log.error("Dest mismatch");
            sendStatusMessage(SessionStatusMessage.STATUS_INVALID);
            _runner.stopRunning();
            return;
        }
        _runner.getConfig().getOptions().putAll(message.getSessionConfig().getOptions());
        Hash dest = _runner.getDestHash();
        ClientTunnelSettings settings = new ClientTunnelSettings(dest);
        Properties props = new Properties();
        props.putAll(_runner.getConfig().getOptions());
        settings.readFromProperties(props);
        _context.tunnelManager().setInboundSettings(dest,
                                                    settings.getInboundSettings());
        _context.tunnelManager().setOutboundSettings(dest,
                                                     settings.getOutboundSettings());
        sendStatusMessage(SessionStatusMessage.STATUS_UPDATED);
    }
    
    private void sendStatusMessage(int status) {
        SessionStatusMessage msg = new SessionStatusMessage();
        SessionId id = _runner.getSessionId();
        if (id == null)
            id = ClientManager.UNKNOWN_SESSION_ID;
        msg.setSessionId(id);
        msg.setStatus(status);
        try {
            _runner.doSend(msg);
        } catch (I2CPMessageException ime) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error writing out the session status message", ime);
        }
    }

    /**
     * Divide router limit by 1.75 for overhead.
     * This could someday give a different answer to each client.
     * But it's not enforced anywhere.
     */
    protected void handleGetBWLimits(GetBandwidthLimitsMessage message) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Got BW Limits request");
        int in = _context.bandwidthLimiter().getInboundKBytesPerSecond() * 4 / 7;
        int out = _context.bandwidthLimiter().getOutboundKBytesPerSecond() * 4 / 7;
        BandwidthLimitsMessage msg = new BandwidthLimitsMessage(in, out);
        try {
            _runner.doSend(msg);
        } catch (I2CPMessageException ime) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error writing bw limits msg", ime);
        }
    }

}
