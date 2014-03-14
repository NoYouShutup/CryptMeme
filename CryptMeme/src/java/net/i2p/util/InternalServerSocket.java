package net.i2p.util;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 *  A simple in-JVM ServerSocket using Piped Streams.
 *  We use port numbers just like regular sockets.
 *  Can only be connected by InternalSocket.
 *
 *  Warning - this uses Piped Streams, which don't like multiple writers from threads
 *  that may vanish. If you do use multipe writers,
 *  you may get intermittent 'write end dead' or 'pipe broken' IOExceptions on the reader side.
 *  See http://techtavern.wordpress.com/2008/07/16/whats-this-ioexception-write-end-dead/
 * @since 0.7.9
 */
public class InternalServerSocket extends ServerSocket {
    private static final ConcurrentHashMap<Integer, InternalServerSocket> _sockets = new ConcurrentHashMap<Integer, InternalServerSocket>(4);
    private final BlockingQueue<InternalSocket> _acceptQueue;
    private final Integer _port;
    private volatile boolean _running;
    //private static Log _log = I2PAppContext.getGlobalContext().logManager().getLog(InternalServerSocket.class);

    /**
     *  @param port > 0
     */
    public InternalServerSocket(int port) throws IOException {
         if (port <= 0)
             throw new IOException("Bad port: " + port);
         _port = Integer.valueOf(port);
         InternalServerSocket previous = _sockets.putIfAbsent(_port, this);
         if (previous != null)
             throw new IOException("Internal port in use: " + port);
         _running = true;
         _acceptQueue = new LinkedBlockingQueue<InternalSocket>();
         //if (_log.shouldLog(Log.DEBUG))
         //    _log.debug("Registered " + _port);
    }

    @Override
    public void close() {
         //if (_log.shouldLog(Log.DEBUG))
         //   _log.debug("Closing " + _port);
        _running = false;
        _sockets.remove(_port);
        _acceptQueue.clear();
        try {
            // use null streams as a poison
            _acceptQueue.put(new InternalSocket(null, null));
        } catch (InterruptedException ie) {}
    }

    @Override
    public Socket accept() throws IOException {
        InternalSocket serverSock = null;
        while (_running) {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Accepting " + _port);
            try {
                serverSock = _acceptQueue.take();
            } catch (InterruptedException ie) {
                continue;
            }
            if (serverSock.getInputStream() == null) // poison
                throw new IOException("closed");
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Accepted " + _port);
            break;
        }
        return serverSock;
    }

    @Override
    public String toString() {
        return ("Internal server socket on port " + _port);
    }

    /**
     *  This is how the client connects.
     *
     *  @param port > 0
     */
    static void internalConnect(int port, InternalSocket clientSock) throws IOException {
        InternalServerSocket iss = _sockets.get(Integer.valueOf(port));
        if (iss == null)
             throw new IOException("No server for port: " + port);
        PipedInputStream cis = BigPipedInputStream.getInstance();
        PipedInputStream sis = BigPipedInputStream.getInstance();
        PipedOutputStream cos = new PipedOutputStream(sis);
        PipedOutputStream sos = new PipedOutputStream(cis);
        clientSock.setInputStream(cis);
        clientSock.setOutputStream(cos);
        iss.queueConnection(new InternalSocket(sis, sos));
    }

    private void queueConnection(InternalSocket sock) throws IOException {
        if (!_running)
             throw new IOException("Server closed for port: " + _port);
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Queueing " + _port);
        try {
            _acceptQueue.put(sock);
        } catch (InterruptedException ie) {}
    }

    @Override
    public int getLocalPort() {
        return _port.intValue();
    }

    // ignored stuff

    /** warning - unsupported */
    @Override
    public void setSoTimeout(int timeout) {}

    @Override
    public int getSoTimeout () {
        return 0;
    }

    // everything below here unsupported
    /** @deprecated unsupported */
    @Override
    public void bind(SocketAddress endpoint) {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public void bind(SocketAddress endpoint, int backlog) {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public ServerSocketChannel getChannel() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public InetAddress getInetAddress() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public SocketAddress getLocalSocketAddress() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public int getReceiveBufferSize() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public boolean getReuseAddress() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public boolean isBound() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public boolean isClosed() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public void setReceiveBufferSize(int size) {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public void setReuseAddress(boolean on) {
        throw new IllegalArgumentException("unsupported");
    }
}
