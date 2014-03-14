/* I2PSOCKSTunnel is released under the terms of the GNU GPL,
 * with an additional exception.  For further details, see the
 * licensing terms in I2PTunnel.java.
 *
 * Copyright (c) 2004 by human
 */
package net.i2p.i2ptunnel.socks;

import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.irc.IrcInboundFilter;
import net.i2p.i2ptunnel.irc.IrcOutboundFilter;
import net.i2p.i2ptunnel.Logging;
import net.i2p.util.EventDispatcher;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/*
 * Pipe SOCKS IRC connections through I2PTunnelIRCClient filtering,
 * to get the best of both worlds:
 *
 * - SOCKS lets you specify the host so you don't have to set up
 *   a tunnel for each IRC server in advance
 * - IRC filtering for security
 *
 * @since 0.7.12
 * @author zzz
 */
public class I2PSOCKSIRCTunnel extends I2PSOCKSTunnel {

    private static final AtomicInteger __clientId = new AtomicInteger();

    /** @param pkf private key file name or null for transient key */
    public I2PSOCKSIRCTunnel(int localPort, Logging l, boolean ownDest, EventDispatcher notifyThis, I2PTunnel tunnel, String pkf) {
        super(localPort, l, ownDest, notifyThis, tunnel, pkf);
        setName("SOCKS IRC Proxy on " + tunnel.listenHost + ':' + localPort);
    }

    /**
     *  Same as in I2PSOCKSTunnel, but run the filters from I2PTunnelIRCClient
     *  instead of I2PTunnelRunner
     */
    @Override
    protected void clientConnectionRun(Socket s) {
        try {
            //_log.error("SOCKS IRC Tunnel Start");
            SOCKSServer serv = SOCKSServerFactory.createSOCKSServer(s, getTunnel().getClientOptions());
            Socket clientSock = serv.getClientSocket();
            I2PSocket destSock = serv.getDestinationI2PSocket(this);
            StringBuffer expectedPong = new StringBuffer();
            int id = __clientId.incrementAndGet();
            Thread in = new I2PAppThread(new IrcInboundFilter(clientSock, destSock, expectedPong, _log),
                                         "SOCKS IRC Client " + id + " in", true);
            in.start();
            Thread out = new I2PAppThread(new IrcOutboundFilter(clientSock, destSock, expectedPong, _log),
                                          "SOCKS IRC Client " + id + " out", true);
            out.start();
        } catch (SOCKSException e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error from SOCKS connection", e);
            closeSocket(s);
        }
    }
}
