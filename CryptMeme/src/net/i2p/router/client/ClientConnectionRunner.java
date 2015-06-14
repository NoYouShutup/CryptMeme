package net.i2p.router.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.client.I2PClient;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.crypto.TransientSessionKeyManager;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.Payload;
import net.i2p.data.i2cp.DisconnectMessage;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.I2CPMessageReader;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.MessageStatusMessage;
import net.i2p.data.i2cp.SendMessageMessage;
import net.i2p.data.i2cp.SendMessageExpiresMessage;
import net.i2p.data.i2cp.SessionConfig;
import net.i2p.data.i2cp.SessionId;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
 * Bridge the router and the client - managing state for a client.
 *
 * @author jrandom
 */
class ClientConnectionRunner {
    protected final Log _log;
    protected final RouterContext _context;
    protected final ClientManager _manager;
    /** socket for this particular peer connection */
    private final Socket _socket;
    /** output stream of the socket that I2CP messages bound to the client should be written to */
    private OutputStream _out;
    /** session ID of the current client */
    private SessionId _sessionId;
    /** user's config */
    private SessionConfig _config;
    private String _clientVersion;
    /** static mapping of MessageId to Payload, storing messages for retrieval */
    private final Map<MessageId, Payload> _messages; 
    /** lease set request state, or null if there is no request pending on at the moment */
    private LeaseRequestState _leaseRequest;
    private int _consecutiveLeaseRequestFails;
    /** currently allocated leaseSet, or null if none is allocated */
    private LeaseSet _currentLeaseSet;
    /** set of messageIds created but not yet ACCEPTED */
    private final Set<MessageId> _acceptedPending;
    /** thingy that does stuff */
    protected I2CPMessageReader _reader;
    /** just for this destination */
    private SessionKeyManager _sessionKeyManager;
    /** 
     * This contains the last 10 MessageIds that have had their (non-ack) status 
     * delivered to the client (so that we can be sure only to update when necessary)
     */
    private final List<MessageId> _alreadyProcessed;
    private ClientWriterRunner _writer;
    private Hash _destHashCache;
    /** are we, uh, dead */
    private volatile boolean _dead;
    /** For outbound traffic. true if i2cp.messageReliability = "none"; @since 0.8.1 */
    private boolean _dontSendMSM;
    /** For inbound traffic. true if i2cp.fastReceive = "true"; @since 0.9.4 */
    private boolean _dontSendMSMOnReceive;
    private final AtomicInteger _messageId; // messageId counter
    
    // Was 32767 since the beginning (04-2004).
    // But it's 4 bytes in the I2CP spec and stored as a long in MessageID....
    // If this is too low and wraps around, I2CP VerifyUsage could delete the wrong message,
    // e.g. on local access
    private static final int MAX_MESSAGE_ID = 0x4000000;

    private static final int MAX_LEASE_FAILS = 5;
    private static final int BUF_SIZE = 32*1024;

    /** @since 0.9.2 */
    private static final String PROP_TAGS = "crypto.tagsToSend";
    private static final String PROP_THRESH = "crypto.lowTagThreshold";

    /**
     * Create a new runner against the given socket
     *
     */
    public ClientConnectionRunner(RouterContext context, ClientManager manager, Socket socket) {
        _context = context;
        _log = _context.logManager().getLog(ClientConnectionRunner.class);
        _manager = manager;
        _socket = socket;
        // unused for fastReceive
        _messages = new ConcurrentHashMap<MessageId, Payload>();
        _alreadyProcessed = new ArrayList<MessageId>();
        _acceptedPending = new ConcurrentHashSet<MessageId>();
        _messageId = new AtomicInteger(_context.random().nextInt());
    }
    
    private static final AtomicInteger __id = new AtomicInteger();

    /**
     * Actually run the connection - listen for I2CP messages and respond.  This
     * is the main driver for this class, though it gets all its meat from the
     * {@link net.i2p.data.i2cp.I2CPMessageReader I2CPMessageReader}
     *
     */
    public synchronized void startRunning() throws IOException {
            if (_dead || _reader != null)
                throw new IllegalStateException();
            _reader = new I2CPMessageReader(new BufferedInputStream(_socket.getInputStream(), BUF_SIZE),
                                            createListener());
            _writer = new ClientWriterRunner(_context, this);
            I2PThread t = new I2PThread(_writer);
            t.setName("I2CP Writer " + __id.incrementAndGet());
            t.setDaemon(true);
            t.start();
            _out = new BufferedOutputStream(_socket.getOutputStream());
            _reader.startReading();
            // TODO need a cleaner for unclaimed items in _messages, but we have no timestamps...
    }
    
    /**
     *  Allow override for testing
     *  @since 0.9.8
     */
    protected I2CPMessageReader.I2CPMessageEventListener createListener() {
        return new ClientMessageEventListener(_context, this, true);
    }

    /**
     *  Die a horrible death. Cannot be restarted.
     */
    public synchronized void stopRunning() {
        if (_dead) return;
        if (_context.router().isAlive() && _log.shouldLog(Log.WARN)) 
            _log.warn("Stop the I2CP connection!  current leaseSet: " 
                      + _currentLeaseSet, new Exception("Stop client connection"));
        _dead = true;
        // we need these keys to unpublish the leaseSet
        if (_reader != null) _reader.stopReading();
        if (_writer != null) _writer.stopWriting();
        if (_socket != null) try { _socket.close(); } catch (IOException ioe) { }
        _messages.clear();
        _acceptedPending.clear();
        if (_sessionKeyManager != null)
            _sessionKeyManager.shutdown();
        _manager.unregisterConnection(this);
        if (_currentLeaseSet != null)
            _context.netDb().unpublish(_currentLeaseSet);
        _leaseRequest = null;
        synchronized (_alreadyProcessed) {
            _alreadyProcessed.clear();
        }
        //_config = null;
        //_manager = null;
    }
    
    /**
     *  Current client's config,
     *  will be null before session is established
     */
    public SessionConfig getConfig() { return _config; }

    /**
     *  The client version.
     *  @since 0.9.7
     */
    public void setClientVersion(String version) {
        _clientVersion = version;
    }

    /**
     *  The client version.
     *  @return null if unknown or less than 0.8.7
     *  @since 0.9.7
     */
    public String getClientVersion() {
        return _clientVersion;
    }

    /** current client's sessionkeymanager */
    public SessionKeyManager getSessionKeyManager() { return _sessionKeyManager; }

    /** currently allocated leaseSet */
    public LeaseSet getLeaseSet() { return _currentLeaseSet; }
    void setLeaseSet(LeaseSet ls) { _currentLeaseSet = ls; }

    /**
     *  Equivalent to getConfig().getDestination().calculateHash();
     *  will be null before session is established
     */
    public Hash getDestHash() { return _destHashCache; }
    
    /**
     * @return current client's sessionId or null if not yet set
     */
    SessionId getSessionId() { return _sessionId; }

    /**
     *  To be called only by ClientManager.
     *
     *  @throws IllegalStateException if already set
     */
    void setSessionId(SessionId id) {
        if (_sessionId != null)
            throw new IllegalStateException();
        _sessionId = id;
    }

    /** data for the current leaseRequest, or null if there is no active leaseSet request */
    LeaseRequestState getLeaseRequest() { return _leaseRequest; }

    /** @param req non-null */
    public void failLeaseRequest(LeaseRequestState req) { 
        boolean disconnect = false;
        synchronized (this) {
            if (_leaseRequest == req) {
                _leaseRequest = null;
                disconnect = ++_consecutiveLeaseRequestFails > MAX_LEASE_FAILS;
            }
        }
        if (disconnect)
            disconnectClient("Too many leaseset request fails");
    }

    /** already closed? */
    boolean isDead() { return _dead; }

    /**
     *  Only call if _dontSendMSMOnReceive is false, otherwise will always be null
     */
    Payload getPayload(MessageId id) { 
        return _messages.get(id); 
    }

    /**
     *  Only call if _dontSendMSMOnReceive is false
     */
    void setPayload(MessageId id, Payload payload) { 
        if (!_dontSendMSMOnReceive)
            _messages.put(id, payload); 
    }

    /**
     *  Only call if _dontSendMSMOnReceive is false
     */
    void removePayload(MessageId id) { 
        _messages.remove(id); 
    }
    
    /**
     *  Caller must send a SessionStatusMessage to the client with the returned code.
     *  Caller must call disconnectClient() on failure.
     *  Side effect: Sets the session ID.
     *
     *  @return SessionStatusMessage return code, 1 for success, != 1 for failure
     */
    public int sessionEstablished(SessionConfig config) {
        _destHashCache = config.getDestination().calculateHash();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("SessionEstablished called for destination " + _destHashCache.toBase64());
        _config = config;
        // We process a few options here, but most are handled by the tunnel manager.
        // The ones here can't be changed later.
        Properties opts = config.getOptions();
        if (opts != null) {
            _dontSendMSM = "none".equals(opts.getProperty(I2PClient.PROP_RELIABILITY, "").toLowerCase(Locale.US));
            _dontSendMSMOnReceive = Boolean.parseBoolean(opts.getProperty(I2PClient.PROP_FAST_RECEIVE));
        }
        // per-destination session key manager to prevent rather easy correlation
        if (_sessionKeyManager == null) {
            int tags = TransientSessionKeyManager.DEFAULT_TAGS;
            int thresh = TransientSessionKeyManager.LOW_THRESHOLD;
            if (opts != null) {
                String ptags = opts.getProperty(PROP_TAGS);
                if (ptags != null) {
                    try { tags = Integer.parseInt(ptags); } catch (NumberFormatException nfe) {}
                }
                String pthresh = opts.getProperty(PROP_THRESH);
                if (pthresh != null) {
                    try { thresh = Integer.parseInt(pthresh); } catch (NumberFormatException nfe) {}
                }
            }
            _sessionKeyManager = new TransientSessionKeyManager(_context, tags, thresh);
        } else {
            _log.error("SessionEstablished called for twice for destination " + _destHashCache.toBase64().substring(0,4));
        }
        return _manager.destinationEstablished(this);
    }
    
    /** 
     * Send a notification to the client that their message (id specified) was
     * delivered (or failed delivery)
     * Note that this sends the Guaranteed status codes, even though we only support best effort.
     * Doesn't do anything if i2cp.messageReliability = "none"
     *
     *  Do not use for status = STATUS_SEND_ACCEPTED; use ackSendMessage() for that.
     *
     *  @param status see I2CP MessageStatusMessage for success/failure codes
     */
    void updateMessageDeliveryStatus(MessageId id, int status) {
        if (_dead || _dontSendMSM)
            return;
        _context.jobQueue().addJob(new MessageDeliveryStatusUpdate(id, status));
    }

    /** 
     * called after a new leaseSet is granted by the client, the NetworkDb has been
     * updated.  This takes care of all the LeaseRequestState stuff (including firing any jobs)
     */
    void leaseSetCreated(LeaseSet ls) {
        LeaseRequestState state = null;
        synchronized (this) {
            state = _leaseRequest;
            if (state == null) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("LeaseRequest is null and we've received a new lease?! perhaps this is odd... " + ls);
                return;
            } else {
                state.setIsSuccessful(true);
                _currentLeaseSet = ls;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("LeaseSet created fully: " + state + " / " + ls);
                _leaseRequest = null;
                _consecutiveLeaseRequestFails = 0;
            }
        }
        if ( (state != null) && (state.getOnGranted() != null) )
            _context.jobQueue().addJob(state.getOnGranted());
    }
    
    /**
     *  Send a DisconnectMessage and log with level Log.ERROR.
     *  This is always bad.
     *  See ClientMessageEventListener.handleCreateSession()
     *  for why we don't send a SessionStatusMessage when we do this.
     *  @param reason will be truncated to 255 bytes
     */
    void disconnectClient(String reason) {
        disconnectClient(reason, Log.ERROR);
    }

    /**
     * @param reason will be truncated to 255 bytes
     * @param logLevel e.g. Log.WARN
     * @since 0.8.2
     */
    void disconnectClient(String reason, int logLevel) {
        if (_log.shouldLog(logLevel))
            _log.log(logLevel, "Disconnecting the client - " 
                     + reason
                     + " config: "
                     + _config);
        DisconnectMessage msg = new DisconnectMessage();
        if (reason.length() > 255)
            reason = reason.substring(0, 255);
        msg.setReason(reason);
        try {
            doSend(msg);
        } catch (I2CPMessageException ime) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error writing out the disconnect message", ime);
        }
        // give it a little time to get sent out...
        // even better would be to have stopRunning() flush it?
        try {
            Thread.sleep(50);
        } catch (InterruptedException ie) {}
        stopRunning();
    }
    
    /**
     * Distribute the message.  If the dest is local, it blocks until its passed 
     * to the target ClientConnectionRunner (which then fires it into a MessageReceivedJob).
     * If the dest is remote, it blocks until it is added into the ClientMessagePool
     *
     */
    MessageId distributeMessage(SendMessageMessage message) {
        Payload payload = message.getPayload();
        Destination dest = message.getDestination();
        MessageId id = new MessageId();
        id.setMessageId(getNextMessageId()); 
        long expiration = 0;
        int flags = 0;
        if (message.getType() == SendMessageExpiresMessage.MESSAGE_TYPE) {
            SendMessageExpiresMessage msg = (SendMessageExpiresMessage) message;
            expiration = msg.getExpirationTime();
            flags = msg.getFlags();
        }
        if (message.getNonce() != 0 && !_dontSendMSM)
            _acceptedPending.add(id);

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("** Receiving message " + id.getMessageId() + " with payload of size " 
                       + payload.getSize() + " for session " + _sessionId.getSessionId());
        //long beforeDistribute = _context.clock().now();
        // the following blocks as described above
        SessionConfig cfg = _config;
        if (cfg != null)
            _manager.distributeMessage(cfg.getDestination(), dest, payload, id, expiration, flags);
        // else log error?
        //long timeToDistribute = _context.clock().now() - beforeDistribute;
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.warn("Time to distribute in the manager to " 
        //              + dest.calculateHash().toBase64() + ": " 
        //              + timeToDistribute);
        return id;
    }
    
    /** 
     * Send a notification to the client that their message (id specified) was accepted 
     * for delivery (but not necessarily delivered)
     * Doesn't do anything if i2cp.messageReliability = "none"
     * or if the nonce is 0.
     *
     * @param id OUR id for the message
     * @param nonce HIS id for the message
     */
    void ackSendMessage(MessageId id, long nonce) {
        if (_dontSendMSM || nonce == 0)
            return;
        SessionId sid = _sessionId;
        if (sid == null) return;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Acking message send [accepted]" + id + " / " + nonce + " for sessionId " 
                       + sid);
        MessageStatusMessage status = new MessageStatusMessage();
        status.setMessageId(id.getMessageId()); 
        status.setSessionId(sid.getSessionId());
        status.setSize(0L);
        status.setNonce(nonce);
        status.setStatus(MessageStatusMessage.STATUS_SEND_ACCEPTED);
        try {
            doSend(status);
            _acceptedPending.remove(id);
        } catch (I2CPMessageException ime) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error writing out the message status message", ime);
        }
    }
    
    /**
     * Asynchronously deliver the message to the current runner
     *
     */ 
    void receiveMessage(Destination toDest, Destination fromDest, Payload payload) {
        if (_dead) return;
        MessageReceivedJob j = new MessageReceivedJob(_context, this, toDest, fromDest, payload, _dontSendMSMOnReceive);
        // This is fast and non-blocking, run in-line
        //_context.jobQueue().addJob(j);
        j.runJob();
    }
    
    /**
     * Send async abuse message to the client
     *
     */
    public void reportAbuse(String reason, int severity) {
        if (_dead) return;
        _context.jobQueue().addJob(new ReportAbuseJob(_context, this, reason, severity));
    }
        
    /**
     * Request that a particular client authorize the Leases contained in the 
     * LeaseSet, after which the onCreateJob is queued up.  If that doesn't occur
     * within the timeout specified, queue up the onFailedJob.  This call does not
     * block.
     *
     * @param set LeaseSet with requested leases - this object must be updated to contain the 
     *            signed version (as well as any changed/added/removed Leases)
     * @param expirationTime ms to wait before failing
     * @param onCreateJob Job to run after the LeaseSet is authorized, null OK
     * @param onFailedJob Job to run after the timeout passes without receiving authorization, null OK
     */
    void requestLeaseSet(LeaseSet set, long expirationTime, Job onCreateJob, Job onFailedJob) {
        if (_dead) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Requesting leaseSet from a dead client: " + set);
            if (onFailedJob != null)
                _context.jobQueue().addJob(onFailedJob);
            return;
        }
        // We can't use LeaseSet.equals() here because the dest, keys, and sig on
        // the new LeaseSet are null. So we compare leases one by one.
        // In addition, the client rewrites the expiration time of all the leases to
        // the earliest one, so we can't use Lease.equals() or Lease.getEndDate().
        // So compare by tunnel ID, and then by gateway.
        // (on the remote possibility that two gateways are using the same ID).
        // TunnelPool.locked_buildNewLeaseSet() ensures that leases are sorted,
        //  so the comparison will always work.
        int leases = set.getLeaseCount();
        // synch so _currentLeaseSet isn't changed out from under us
        synchronized (this) {
            if (_currentLeaseSet != null && _currentLeaseSet.getLeaseCount() == leases) {
                for (int i = 0; i < leases; i++) {
                    if (! _currentLeaseSet.getLease(i).getTunnelId().equals(set.getLease(i).getTunnelId()))
                        break;
                    if (! _currentLeaseSet.getLease(i).getGateway().equals(set.getLease(i).getGateway()))
                        break;
                    if (i == leases - 1) {
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Requested leaseSet hasn't changed");
                        if (onCreateJob != null)
                            _context.jobQueue().addJob(onCreateJob);
                        return; // no change
                    }
                }
            }
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Current leaseSet " + _currentLeaseSet + "\nNew leaseSet " + set);
        LeaseRequestState state = null;
        synchronized (this) {
            state = _leaseRequest;
            if (state != null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Already requesting " + state);
                LeaseSet requested = state.getRequested();
                LeaseSet granted = state.getGranted();
                long ours = set.getEarliestLeaseDate();
                if ( ( (requested != null) && (requested.getEarliestLeaseDate() > ours) ) || 
                     ( (granted != null) && (granted.getEarliestLeaseDate() > ours) ) ) {
                    // theirs is newer
                } else {
                    // ours is newer, so wait a few secs and retry
                    _context.simpleScheduler().addEvent(new Rerequest(set, expirationTime, onCreateJob, onFailedJob), 3*1000);
                }
                // fire onCreated?
                return; // already requesting
            } else {
                _leaseRequest = state = new LeaseRequestState(onCreateJob, onFailedJob, _context.clock().now() + expirationTime, set);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("New request: " + state);
            }
        }
        _context.jobQueue().addJob(new RequestLeaseSetJob(_context, this, state));
    }

    private class Rerequest implements SimpleTimer.TimedEvent {
        private final LeaseSet _ls;
        private final long _expirationTime;
        private final Job _onCreate;
        private final Job _onFailed;

        public Rerequest(LeaseSet ls, long expirationTime, Job onCreate, Job onFailed) {
            _ls = ls;
            _expirationTime = expirationTime;
            _onCreate = onCreate;
            _onFailed = onFailed;
        }

        public void timeReached() {
            requestLeaseSet(_ls, _expirationTime, _onCreate, _onFailed);
        }
    }
    
    void disconnected() {
        if (_log.shouldLog(Log.WARN))
            _log.warn("Disconnected", new Exception("Disconnected?"));
        stopRunning();
    }
    
    ////
    ////
    boolean getIsDead() { return _dead; }

    /**
     *  Not thread-safe. Blocking. Only used for external sockets.
     *  ClientWriterRunner thread is the only caller.
     *  Others must use doSend().
     */
    void writeMessage(I2CPMessage msg) {
        //long before = _context.clock().now();
        try {
            // We don't need synchronization here, ClientWriterRunner is the only writer.
            //synchronized (_out) {
                msg.writeMessage(_out);
                _out.flush();
            //}
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("after writeMessage("+ msg.getClass().getName() + "): " 
            //               + (_context.clock().now()-before) + "ms");
        } catch (I2CPMessageException ime) {
            _log.error("Error sending I2CP message to client", ime);
            stopRunning();
        } catch (EOFException eofe) {
            // only warn if client went away
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error sending I2CP message - client went away", eofe);
            stopRunning();
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR)) 
                _log.error("IO Error sending I2CP message to client", ioe);
            stopRunning();
        } catch (Throwable t) {
            _log.log(Log.CRIT, "Unhandled exception sending I2CP message to client", t);
            stopRunning();
        //} finally {
        //    long after = _context.clock().now();
        //    long lag = after - before;
        //    if (lag > 300) {
        //        if (_log.shouldLog(Log.WARN))
        //            _log.warn("synchronization on the i2cp message send took too long (" + lag 
        //                      + "ms): " + msg);
        //    }
        }
    }
    
    /**
     * Actually send the I2CPMessage to the peer through the socket
     *
     */
    void doSend(I2CPMessage msg) throws I2CPMessageException {
        if (_out == null) throw new I2CPMessageException("Output stream is not initialized");
        if (msg == null) throw new I2CPMessageException("Null message?!");
        //if (_log.shouldLog(Log.DEBUG)) {
        //    if ( (_config == null) || (_config.getDestination() == null) ) 
        //        _log.debug("before doSend of a "+ msg.getClass().getName() 
        //                   + " message on for establishing i2cp con");
        //    else
        //        _log.debug("before doSend of a "+ msg.getClass().getName() 
        //                   + " message on for " 
        //                   + _config.getDestination().calculateHash().toBase64());
        //}
        _writer.addMessage(msg);
        //if (_log.shouldLog(Log.DEBUG)) {
        //    if ( (_config == null) || (_config.getDestination() == null) ) 
        //        _log.debug("after doSend of a "+ msg.getClass().getName() 
        //                   + " message on for establishing i2cp con");
        //    else
        //        _log.debug("after doSend of a "+ msg.getClass().getName() 
        //                   + " message on for " 
        //                   + _config.getDestination().calculateHash().toBase64());
        //}
    }
    
    public int getNextMessageId() { 
        // Don't % so we don't get negative IDs
        return _messageId.incrementAndGet() & (MAX_MESSAGE_ID - 1);
    }
    
    /**
     * True if the client has already been sent the ACCEPTED state for the given
     * message id, false otherwise.
     *
     */
    private boolean alreadyAccepted(MessageId id) {
        if (_dead) return false;
        return !_acceptedPending.contains(id);
    }
    
    /**
     * If the message hasn't been state=ACCEPTED yet, we shouldn't send an update
     * since the client doesn't know the message id (and we don't know the nonce).
     * So, we just wait REQUEUE_DELAY ms before trying again.
     *
     */
    private final static long REQUEUE_DELAY = 500;
    private static final int MAX_REQUEUE = 60;  // 30 sec.
    
    private class MessageDeliveryStatusUpdate extends JobImpl {
        private final MessageId _messageId;
        private final int _status;
        private long _lastTried;
        private int _requeueCount;

        /**
         *  Do not use for status = STATUS_SEND_ACCEPTED; use ackSendMessage() for that.
         *
         *  @param status see I2CP MessageStatusMessage for success/failure codes
         */
        public MessageDeliveryStatusUpdate(MessageId id, int status) {
            super(ClientConnectionRunner.this._context);
            _messageId = id;
            _status = status;
        }

        public String getName() { return "Update Delivery Status"; }

        /**
         * Note that this sends the Guaranteed status codes, even though we only support best effort.
         */
        public void runJob() {
            if (_dead) return;

            MessageStatusMessage msg = new MessageStatusMessage();
            msg.setMessageId(_messageId.getMessageId());
            msg.setSessionId(_sessionId.getSessionId());
            // has to be >= 0, it is initialized to -1
            msg.setNonce(2);
            msg.setSize(0);
            msg.setStatus(_status);

            if (!alreadyAccepted(_messageId)) {
                if (_requeueCount++ > MAX_REQUEUE) {
                    // bug requeueing forever? failsafe
                    _log.error("Abandon update for message " + _messageId + " to " 
                          + MessageStatusMessage.getStatusString(msg.getStatus()) 
                          + " for session " + _sessionId.getSessionId());
                } else {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Almost send an update for message " + _messageId + " to "
                          + MessageStatusMessage.getStatusString(msg.getStatus())
                          + " for session " + _sessionId.getSessionId()
                          + " before they knew the messageId!  delaying .5s");
                    _lastTried = _context.clock().now();
                    requeue(REQUEUE_DELAY);
                }
                return;
            }

            boolean alreadyProcessed = false;
            long beforeLock = _context.clock().now();
            long inLock = 0;
            synchronized (_alreadyProcessed) {
                inLock = _context.clock().now();
                if (_alreadyProcessed.contains(_messageId)) {
                    _log.warn("Status already updated");
                    alreadyProcessed = true;
                } else {
                    _alreadyProcessed.add(_messageId);
                    while (_alreadyProcessed.size() > 10)
                        _alreadyProcessed.remove(0);
                }
            }
            long afterLock = _context.clock().now();

            if (afterLock - beforeLock > 50) {
                _log.warn("MessageDeliveryStatusUpdate.locking took too long: " + (afterLock-beforeLock)
                          + " overall, synchronized took " + (inLock - beforeLock));
            }
            
            if (alreadyProcessed) return;

            if (_lastTried > 0) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.info("Updating message status for message " + _messageId + " to " 
                              + MessageStatusMessage.getStatusString(msg.getStatus()) 
                              + " for session " + _sessionId.getSessionId() 
                              + " (with nonce=2), retrying after " 
                              + (_context.clock().now() - _lastTried));
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Updating message status for message " + _messageId + " to " 
                               + MessageStatusMessage.getStatusString(msg.getStatus()) 
                               + " for session " + _sessionId.getSessionId() + " (with nonce=2)");
            }

            try {
                doSend(msg);
            } catch (I2CPMessageException ime) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error updating the status for message ID " + _messageId, ime);
            }
        }
    }
}
