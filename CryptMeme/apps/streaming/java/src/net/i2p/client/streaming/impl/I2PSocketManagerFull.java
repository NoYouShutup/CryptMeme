package net.i2p.client.streaming.impl;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 * Centralize the coordination and multiplexing of the local client's streaming.
 * There should be one I2PSocketManager for each I2PSession, and if an application
 * is sending and receiving data through the streaming library using an
 * I2PSocketManager, it should not attempt to call I2PSession's setSessionListener
 * or receive any messages with its .receiveMessage
 *
 * This is what I2PSocketManagerFactory.createManager() returns.
 * Direct instantiation by others is deprecated.
 */
public class I2PSocketManagerFull implements I2PSocketManager {
    private final I2PAppContext _context;
    private final Log _log;
    private final I2PSession _session;
    private final I2PServerSocketFull _serverSocket;
    private StandardServerSocket _realServerSocket;
    private final ConnectionOptions _defaultOptions;
    private long _acceptTimeout;
    private String _name;
    private static final AtomicInteger __managerId = new AtomicInteger();
    private final ConnectionManager _connectionManager;
    private final AtomicBoolean _isDestroyed = new AtomicBoolean();
    
    /**
     * How long to wait for the client app to accept() before sending back CLOSE?
     * This includes the time waiting in the queue.  Currently set to 5 seconds.
     */
    private static final long ACCEPT_TIMEOUT_DEFAULT = 5*1000;

    /**
     * @deprecated use 4-arg constructor
     * @throws UnsupportedOperationException always
     */
    public I2PSocketManagerFull() {
        throw new UnsupportedOperationException();
    }
    
    /**
     * @deprecated use 4-arg constructor
     * @throws UnsupportedOperationException always
     */
    public void init(I2PAppContext context, I2PSession session, Properties opts, String name) {
        throw new UnsupportedOperationException();
    }

    /**
     * This is what I2PSocketManagerFactory.createManager() returns.
     * Direct instantiation by others is deprecated.
     * 
     * @param context non-null
     * @param session non-null
     * @param opts may be null
     * @param name non-null
     */
    public I2PSocketManagerFull(I2PAppContext context, I2PSession session, Properties opts, String name) {
        _context = context;
        _session = session;
        _log = _context.logManager().getLog(I2PSocketManagerFull.class);
        
        _name = name + " " + (__managerId.incrementAndGet());
        _acceptTimeout = ACCEPT_TIMEOUT_DEFAULT;
        _defaultOptions = new ConnectionOptions(opts);
        _connectionManager = new ConnectionManager(_context, _session, _defaultOptions);
        _serverSocket = new I2PServerSocketFull(this);
        
        if (_log.shouldLog(Log.INFO)) {
            _log.info("Socket manager created.  \ndefault options: " + _defaultOptions
                      + "\noriginal properties: " + opts);
        }
        debugInit(context);
    }

    /**
     *  Create a copy of the current options, to be used in a setDefaultOptions() call.
     */
    public I2PSocketOptions buildOptions() { return buildOptions(null); }

    /**
     *  Create a modified copy of the current options, to be used in a setDefaultOptions() call.
     *
     *  As of 0.9.19, defaults in opts are honored.
     *
     *  @param opts The new options, may be null
     */
    public I2PSocketOptions buildOptions(Properties opts) {
        ConnectionOptions curOpts = new ConnectionOptions(_defaultOptions);
        curOpts.setProperties(opts);
        return curOpts;
    }
    
    /**
     *  @return the session, non-null
     */
    public I2PSession getSession() {
        return _session;
    }
    
    public ConnectionManager getConnectionManager() {
        return _connectionManager;
    }

    /**
     * The accept() call.
     * 
     * @return connected I2PSocket, or null through 0.9.16, non-null as of 0.9.17
     * @throws I2PException if session is closed
     * @throws ConnectException (since 0.9.17; I2PServerSocket interface always declared it)
     * @throws SocketTimeoutException if a timeout was previously set with setSoTimeout and the timeout has been reached.
     */
    public I2PSocket receiveSocket() throws I2PException, ConnectException, SocketTimeoutException {
        verifySession();
        Connection con = _connectionManager.getConnectionHandler().accept(_connectionManager.getSoTimeout());
        I2PSocketFull sock = new I2PSocketFull(con, _context);
        con.setSocket(sock);
        return sock;
    }
    
    /**
     * Ping the specified peer, returning true if they replied to the ping within 
     * the timeout specified, false otherwise.  This call blocks.
     *
     * Uses the ports from the default options.
     * 
     * @param peer
     * @param timeoutMs timeout in ms, greater than zero
     * @return true on success, false on failure
     * @throws IllegalArgumentException
     */
    public boolean ping(Destination peer, long timeoutMs) {
        if (timeoutMs <= 0)
            throw new IllegalArgumentException("bad timeout");
        return _connectionManager.ping(peer, _defaultOptions.getLocalPort(),
                                       _defaultOptions.getPort(), timeoutMs);
    }

    /**
     * Ping the specified peer, returning true if they replied to the ping within 
     * the timeout specified, false otherwise.  This call blocks.
     *
     * Uses the ports specified.
     *
     * @param peer Destination to ping
     * @param localPort 0 - 65535
     * @param remotePort 0 - 65535
     * @param timeoutMs timeout in ms, greater than zero
     * @return success or failure
     * @throws IllegalArgumentException
     * @since 0.9.12
     */
    public boolean ping(Destination peer, int localPort, int remotePort, long timeoutMs) {
        if (localPort < 0 || localPort > 65535 ||
            remotePort < 0 || remotePort > 65535)
            throw new IllegalArgumentException("bad port");
        if (timeoutMs <= 0)
            throw new IllegalArgumentException("bad timeout");
        return _connectionManager.ping(peer, localPort, remotePort, timeoutMs);
    }

    /**
     * Ping the specified peer, returning true if they replied to the ping within 
     * the timeout specified, false otherwise.  This call blocks.
     *
     * Uses the ports specified.
     *
     * @param peer Destination to ping
     * @param localPort 0 - 65535
     * @param remotePort 0 - 65535
     * @param timeoutMs timeout in ms, greater than zero
     * @param payload to include in the ping
     * @return the payload received in the pong, zero-length if none, null on failure or timeout
     * @throws IllegalArgumentException
     * @since 0.9.18
     */
    public byte[] ping(Destination peer, int localPort, int remotePort, long timeoutMs, byte[] payload) {
        if (localPort < 0 || localPort > 65535 ||
            remotePort < 0 || remotePort > 65535)
            throw new IllegalArgumentException("bad port");
        if (timeoutMs <= 0)
            throw new IllegalArgumentException("bad timeout");
        return _connectionManager.ping(peer, localPort, remotePort, timeoutMs, payload);
    }

    /**
     * How long should we wait for the client to .accept() a socket before
     * sending back a NACK/Close?  
     *
     * @param ms milliseconds to wait, maximum
     */
    public void setAcceptTimeout(long ms) { _acceptTimeout = ms; }
    public long getAcceptTimeout() { return _acceptTimeout; }

    /**
     *  Update the options on a running socket manager.
     *  Parameters in the I2PSocketOptions interface may be changed directly
     *  with the setters; no need to use this method for those.
     *  This does NOT update the underlying I2CP or tunnel options; use getSession().updateOptions() for that.
     *
     *  @param options as created from a call to buildOptions(properties), non-null
     */
    public void setDefaultOptions(I2PSocketOptions options) {
        if (!(options instanceof ConnectionOptions))
            throw new IllegalArgumentException();
        if (_log.shouldLog(Log.WARN))
            _log.warn("Changing options from:\n " + _defaultOptions + "\nto:\n " + options);
        _defaultOptions.updateAll((ConnectionOptions) options);
        _connectionManager.updateOptions();
    }

    /**
     *  Current options, not a copy, setters may be used to make changes.
     */
    public I2PSocketOptions getDefaultOptions() {
        return _defaultOptions;
    }

    /**
     *  Returns non-null socket.
     *  This method does not throw exceptions, but methods on the returned socket
     *  may throw exceptions if the socket or socket manager is closed.
     *
     *  @return non-null
     */
    public I2PServerSocket getServerSocket() {
        _connectionManager.setAllowIncomingConnections(true);
        return _serverSocket;
    }

    /**
     *  Like getServerSocket but returns a real ServerSocket for easier porting of apps.
     *  @since 0.8.4
     */
    public synchronized ServerSocket getStandardServerSocket() throws IOException {
        if (_realServerSocket == null)
            _realServerSocket = new StandardServerSocket(_serverSocket);
        _connectionManager.setAllowIncomingConnections(true);
        return _realServerSocket;
    }

    private void verifySession() throws I2PException {
        if (_isDestroyed.get())
            throw new I2PException("Session was closed");
        if (!_connectionManager.getSession().isClosed())
            return;
        _connectionManager.getSession().connect();
    }
    
    /**
     * Create a new connected socket. Blocks until the socket is created,
     * unless the connectDelay option (i2p.streaming.connectDelay) is
     * set and greater than zero. If so this will return immediately,
     * and the client may quickly write initial data to the socket and
     * this data will be bundled in the SYN packet.
     *
     * @param peer Destination to connect to
     * @param options I2P socket options to be used for connecting, may be null
     *
     * @return I2PSocket if successful
     * @throws NoRouteToHostException if the peer is not found or not reachable
     * @throws I2PException if there is some other I2P-related problem
     */
    public I2PSocket connect(Destination peer, I2PSocketOptions options) 
                             throws I2PException, NoRouteToHostException {
        verifySession();
        if (options == null)
            options = _defaultOptions;
        ConnectionOptions opts = null;
        if (options instanceof ConnectionOptions)
            opts = new ConnectionOptions((ConnectionOptions)options);
        else
            opts = new ConnectionOptions(options);
        
        if (_log.shouldLog(Log.INFO))
            _log.info("Connecting to " + peer.calculateHash().toBase64().substring(0,6) 
                      + " with options: " + opts);
        // the following blocks unless connect delay > 0
        Connection con = _connectionManager.connect(peer, opts);
        if (con == null)
            throw new TooManyStreamsException("Too many streams, max " + _defaultOptions.getMaxConns());
        I2PSocketFull socket = new I2PSocketFull(con,_context);
        con.setSocket(socket);
        if (con.getConnectionError() != null) { 
            con.disconnect(false);
            throw new NoRouteToHostException(con.getConnectionError());
        }
        return socket;
    }

    /**
     * Create a new connected socket. Blocks until the socket is created,
     * unless the connectDelay option (i2p.streaming.connectDelay) is
     * set and greater than zero in the default options. If so this will return immediately,
     * and the client may quickly write initial data to the socket and
     * this data will be bundled in the SYN packet.
     *
     * @param peer Destination to connect to
     *
     * @return I2PSocket if successful
     * @throws NoRouteToHostException if the peer is not found or not reachable
     * @throws I2PException if there is some other I2P-related problem
     */
    public I2PSocket connect(Destination peer) throws I2PException, NoRouteToHostException {
        return connect(peer, _defaultOptions);
    }

    /**
     *  Like connect() but returns a real Socket, and throws only IOE,
     *  for easier porting of apps.
     *  @since 0.8.4
     */
    public Socket connectToSocket(Destination peer) throws IOException {
        return connectToSocket(peer, _defaultOptions);
    }

    /**
     *  Like connect() but returns a real Socket, and throws only IOE,
     *  for easier porting of apps.
     *  @param timeout ms if > 0, forces blocking (disables connectDelay)
     *  @since 0.8.4
     */
    public Socket connectToSocket(Destination peer, int timeout) throws IOException {
        ConnectionOptions opts = new ConnectionOptions(_defaultOptions);
        opts.setConnectTimeout(timeout);
        if (timeout > 0)
            opts.setConnectDelay(-1);
        return connectToSocket(peer, opts);
    }

    /**
     *  Like connect() but returns a real Socket, and throws only IOE,
     *  for easier porting of apps.
     *  @param options may be null
     *  @since 0.8.4
     */
    private Socket connectToSocket(Destination peer, I2PSocketOptions options) throws IOException {
        try {
            I2PSocket sock = connect(peer, options);
            return new StandardSocket(sock);
        } catch (I2PException i2pe) {
            IOException ioe = new IOException("connect fail");
            ioe.initCause(i2pe);
            throw ioe;
        }
    }

    /**
     * Destroy the socket manager, freeing all the associated resources.  This
     * method will block until all the managed sockets are closed.
     *
     * CANNOT be restarted.
     */
    public void destroySocketManager() {
        if (!_isDestroyed.compareAndSet(false,true)) {
            // shouldn't happen, log a stack trace to find out why it happened
            _log.logCloseLoop("I2PSocketManager", getName());
            return;
        }
        _connectionManager.setAllowIncomingConnections(false);
        _connectionManager.shutdown();
        // should we destroy the _session too?
        // yes, since the old lib did (and SAM wants it to, and i dont know why not)
        if ( (_session != null) && (!_session.isClosed()) ) {
            try {
                _session.destroySession();
            } catch (I2PSessionException ise) {
                _log.warn("Unable to destroy the session", ise);
            }
            PcapWriter pcap = null;
            synchronized(_pcapInitLock) {
                pcap = pcapWriter;
            }
            if (pcap != null)
                pcap.flush();
        }
    }

    /**
     * Has the socket manager been destroyed?
     *
     * @since 0.9.9
     */
    public boolean isDestroyed() {
        return _isDestroyed.get();
    }

    /**
     * Retrieve a set of currently connected I2PSockets, either initiated locally or remotely.
     *
     * @return set of currently connected I2PSockets
     */
    public Set<I2PSocket> listSockets() {
        Set<Connection> connections = _connectionManager.listConnections();
        Set<I2PSocket> rv = new HashSet<I2PSocket>(connections.size());
        for (Connection con : connections) {
            if (con.getSocket() != null)
                rv.add(con.getSocket());
        }
        return rv;
    }

    /**
     *  For logging / diagnostics only
     */
    public String getName() { return _name; }

    /**
     *  For logging / diagnostics only
     */
    public void setName(String name) { _name = name; }
    
    
    public void addDisconnectListener(I2PSocketManager.DisconnectListener lsnr) { 
        _connectionManager.getMessageHandler().addDisconnectListener(lsnr);
    }
    public void removeDisconnectListener(I2PSocketManager.DisconnectListener lsnr) {
        _connectionManager.getMessageHandler().removeDisconnectListener(lsnr);
    }

    private static final Object _pcapInitLock = new Object();
    private static boolean _pcapInitialized;
    static PcapWriter pcapWriter;
    static final String PROP_PCAP = "i2p.streaming.pcap";
    private static final String PCAP_FILE = "streaming.pcap";

    private static void debugInit(I2PAppContext ctx) {
        if (!ctx.getBooleanProperty(PROP_PCAP))
            return;
        synchronized(_pcapInitLock) {
            if (!_pcapInitialized) {
                try {
                    pcapWriter = new PcapWriter(ctx, PCAP_FILE);
                } catch (java.io.IOException ioe) {
                     System.err.println("pcap init ioe: " + ioe);
                }
                _pcapInitialized = true;
            }
        }
    }
}
