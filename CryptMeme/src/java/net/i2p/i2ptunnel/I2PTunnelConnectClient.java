/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.Locale;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.util.EventDispatcher;
import net.i2p.util.Log;
import net.i2p.util.PortMapper;

/**
 * Supports the following:
 *<pre>
 *   (where protocol is generally HTTP/1.1 but is ignored)
 *   (where host is one of:
 *      example.i2p
 *      52chars.b32.i2p
 *      516+charsbase64
 *      example.com (sent to one of the configured proxies)
 *   )
 *
 *   (port and protocol are ignored for i2p destinations)
 *   CONNECT host
 *   CONNECT host protocol
 *   CONNECT host:port
 *   CONNECT host:port protocol (this is the standard)
 *</pre>
 *
 * Additional lines after the CONNECT line but before the blank line are ignored and stripped.
 * The CONNECT line is removed for .i2p accesses
 * but passed along for outproxy accesses.
 *
 * Ref:
 *<pre>
 *  INTERNET-DRAFT                                              Ari Luotonen
 *  Expires: September 26, 1997          Netscape Communications Corporation
 *  <draft-luotonen-ssl-tunneling-03.txt>                     March 26, 1997
 *                     Tunneling SSL Through a WWW Proxy
 *</pre>
 *
 * @author zzz a stripped-down I2PTunnelHTTPClient
 */
public class I2PTunnelConnectClient extends I2PTunnelHTTPClientBase implements Runnable {

    public static final String AUTH_REALM = "I2P SSL Proxy";

    private final static byte[] ERR_DESTINATION_UNKNOWN =
        ("HTTP/1.1 503 Service Unavailable\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-control: no-cache\r\n"+
         "\r\n"+
         "<html><body><H1>I2P ERROR: DESTINATION NOT FOUND</H1>"+
         "That I2P Destination was not found. "+
         "The host (or the outproxy, if you're using one) could also "+
	 "be temporarily offline.  You may want to <b>retry</b>.  "+
         "Could not find the following Destination:<BR><BR><div>")
        .getBytes();
    
    private final static byte[] ERR_BAD_PROTOCOL =
        ("HTTP/1.1 405 Bad Method\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-control: no-cache\r\n"+
         "\r\n"+
         "<html><body><H1>I2P ERROR: METHOD NOT ALLOWED</H1>"+
         "The request uses a bad protocol. "+
         "The Connect Proxy supports CONNECT requests ONLY. Other methods such as GET are not allowed - Maybe you wanted the HTTP Proxy?.<BR>")
        .getBytes();
    
    private final static byte[] ERR_LOCALHOST =
        ("HTTP/1.1 403 Access Denied\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-control: no-cache\r\n"+
         "\r\n"+
         "<html><body><H1>I2P ERROR: REQUEST DENIED</H1>"+
         "Your browser is misconfigured. Do not use the proxy to access the router console or other localhost destinations.<BR>")
        .getBytes();
    
    final static byte[] SUCCESS_RESPONSE =
        ("HTTP/1.1 200 Connection Established\r\n"+
         "Proxy-agent: I2P\r\n"+
         "\r\n")
        .getBytes();
    
    /**
     * @throws IllegalArgumentException if the I2PTunnel does not contain
     *                                  valid config to contact the router
     */
    public I2PTunnelConnectClient(int localPort, Logging l, boolean ownDest, 
                               String wwwProxy, EventDispatcher notifyThis, 
                               I2PTunnel tunnel) throws IllegalArgumentException {
        super(localPort, ownDest, l, notifyThis, "HTTPS Proxy on " + tunnel.listenHost + ':' + localPort, tunnel);

        if (waitEventValue("openBaseClientResult").equals("error")) {
            notifyEvent("openConnectClientResult", "error");
            return;
        }

        if (wwwProxy != null) {
            StringTokenizer tok = new StringTokenizer(wwwProxy, ", ");
            while (tok.hasMoreTokens())
                _proxyList.add(tok.nextToken().trim());
        }

        setName("HTTPS Proxy on " + tunnel.listenHost + ':' + localPort);

        startRunning();
    }

    /** 
     * Create the default options (using the default timeout, etc).
     * Warning, this does not make a copy of I2PTunnel's client options,
     * it modifies them directly.
     */
    @Override
    protected I2PSocketOptions getDefaultOptions() {
        Properties defaultOpts = getTunnel().getClientOptions();
        if (!defaultOpts.contains(I2PSocketOptions.PROP_READ_TIMEOUT))
            defaultOpts.setProperty(I2PSocketOptions.PROP_READ_TIMEOUT, ""+DEFAULT_READ_TIMEOUT);
        if (!defaultOpts.contains("i2p.streaming.inactivityTimeout"))
            defaultOpts.setProperty("i2p.streaming.inactivityTimeout", ""+DEFAULT_READ_TIMEOUT);
        // delayed start
        verifySocketManager();
        I2PSocketOptions opts = sockMgr.buildOptions(defaultOpts);
        if (!defaultOpts.containsKey(I2PSocketOptions.PROP_CONNECT_TIMEOUT))
            opts.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
        return opts;
    }
    
    @Override
    public void startRunning() {
        super.startRunning();
        _context.portMapper().register(PortMapper.SVC_HTTPS_PROXY, getLocalPort());
    }

    @Override
    public boolean close(boolean forced) {
        int reg = _context.portMapper().getPort(PortMapper.SVC_HTTPS_PROXY);
        if (reg == getLocalPort())
            _context.portMapper().unregister(PortMapper.SVC_HTTPS_PROXY);
        return super.close(forced);
    }

    /** @since 0.9.4 */
    protected String getRealm() {
        return AUTH_REALM;
    }

    protected void clientConnectionRun(Socket s) {
        InputStream in = null;
        OutputStream out = null;
        String targetRequest = null;
        boolean usingWWWProxy = false;
        String currentProxy = null;
        long requestId = __requestId.incrementAndGet();
        try {
            out = s.getOutputStream();
            in = s.getInputStream();
            String line, method = null, host = null, destination = null, restofline = null;
            StringBuilder newRequest = new StringBuilder();
            String authorization = null;
            while (true) {
                // Use this rather than BufferedReader because we can't have readahead,
                // since we are passing the stream on to I2PTunnelRunner
                line = DataHelper.readLine(in);
                if(line == null) {
                    break;
                }
                line = line.trim();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(getPrefix(requestId) + "Line=[" + line + "]");
                
                if (method == null) { // first line CONNECT blah.i2p:80 HTTP/1.1
                    int pos = line.indexOf(" ");
                    if (pos == -1) break;    // empty first line
                    method = line.substring(0, pos);
                    String request = line.substring(pos + 1);

                    pos = request.indexOf(":");
                    if (pos == -1)
                       pos = request.indexOf(" ");
                    if (pos == -1) {
                        host = request;
                        restofline = "";
                    } else {
                        host = request.substring(0, pos);
                        restofline = request.substring(pos); // ":80 HTTP/1.1" or " HTTP/1.1"
                    }

                    if (host.toLowerCase(Locale.US).endsWith(".i2p")) {
                        // Destination gets the host name
                        destination = host;
                    } else if (host.indexOf(".") != -1) {
                        // The request must be forwarded to a outproxy
                        currentProxy = selectProxy();
                        if (currentProxy == null) {
                            if (_log.shouldLog(Log.WARN))
                                _log.warn(getPrefix(requestId) + "Host wants to be outproxied, but we dont have any!");
                            writeErrorMessage(ERR_NO_OUTPROXY, out);
                            s.close();
                            return;
                        }
                        destination = currentProxy;
                        usingWWWProxy = true;
                        newRequest.append("CONNECT ").append(host).append(restofline).append("\r\n"); // HTTP spec
                    } else if (host.toLowerCase(Locale.US).equals("localhost")) {
                        writeErrorMessage(ERR_LOCALHOST, out);
                        s.close();
                        return;
                    } else {  // full b64 address (hopefully)
                        destination = host;
                    }
                    targetRequest = host;

                    if (_log.shouldLog(Log.DEBUG)) {
                        _log.debug(getPrefix(requestId) + "METHOD:" + method + ":");
                        _log.debug(getPrefix(requestId) + "HOST  :" + host + ":");
                        _log.debug(getPrefix(requestId) + "REST  :" + restofline + ":");
                        _log.debug(getPrefix(requestId) + "DEST  :" + destination + ":");
                    }
                } else if (line.toLowerCase(Locale.US).startsWith("proxy-authorization: ")) {
                    // strip Proxy-Authenticate from the response in HTTPResponseOutputStream
                    // save for auth check below
                    authorization = line.substring(21);  // "proxy-authorization: ".length()
                    line = null;
                } else if (line.length() > 0) {
                    // Additional lines - shouldn't be too many. Firefox sends:
                    // User-Agent: blabla
                    // Proxy-Connection: keep-alive
                    // Host: blabla.i2p
                    //
                    // We could send these (filtered like in HTTPClient) on to the outproxy,
                    // but for now just chomp them all.
                    line = null;
                } else {
                    // Add Proxy-Authentication header for next hop (outproxy)
                    if (usingWWWProxy && Boolean.parseBoolean(getTunnel().getClientOptions().getProperty(PROP_OUTPROXY_AUTH))) {
                        // specific for this proxy
                        String user = getTunnel().getClientOptions().getProperty(PROP_OUTPROXY_USER_PREFIX + currentProxy);
                        String pw = getTunnel().getClientOptions().getProperty(PROP_OUTPROXY_PW_PREFIX + currentProxy);
                        if (user == null || pw == null) {
                            // if not, look at default user and pw
                            user = getTunnel().getClientOptions().getProperty(PROP_OUTPROXY_USER);
                            pw = getTunnel().getClientOptions().getProperty(PROP_OUTPROXY_PW);
                        }
                        if (user != null && pw != null) {
                            newRequest.append("Proxy-Authorization: Basic ")
                                      .append(Base64.encode((user + ':' + pw).getBytes(), true))    // true = use standard alphabet
                                      .append("\r\n");
                        }
                    }
                    newRequest.append("\r\n"); // HTTP spec
                    // do it
                    break;
                }
            }

            if (destination == null || method == null || !"CONNECT".equals(method.toUpperCase(Locale.US))) {
                writeErrorMessage(ERR_BAD_PROTOCOL, out);
                s.close();
                return;
            }
            
            // Authorization
            AuthResult result = authorize(s, requestId, method, authorization);
            if (result != AuthResult.AUTH_GOOD) {
                if (_log.shouldLog(Log.WARN)) {
                    if (authorization != null)
                        _log.warn(getPrefix(requestId) + "Auth failed, sending 407 again");
                    else
                        _log.warn(getPrefix(requestId) + "Auth required, sending 407");
                }
                out.write(getAuthError(result == AuthResult.AUTH_STALE).getBytes());
                s.close();
                return;
            }

            Destination clientDest = _context.namingService().lookup(destination);
            if (clientDest == null) {
                byte[] header;
                if (usingWWWProxy)
                    header = getErrorPage("dnfp-header.ht", ERR_DESTINATION_UNKNOWN);
                else
                    header = getErrorPage("dnfh-header.ht", ERR_DESTINATION_UNKNOWN);
                writeErrorMessage(header, out, targetRequest, usingWWWProxy, destination);
                s.close();
                return;
            }

            I2PSocket i2ps = createI2PSocket(clientDest, getDefaultOptions());
            byte[] data = null;
            byte[] response = null;
            if (usingWWWProxy)
                data = newRequest.toString().getBytes("ISO-8859-1");
            else
                response = SUCCESS_RESPONSE;
            Runnable onTimeout = new OnTimeout(s, s.getOutputStream(), targetRequest, usingWWWProxy, currentProxy, requestId);
            // starts itself
            new I2PTunnelRunner(s, i2ps, sockLock, data, response, mySockets, onTimeout);
        } catch (SocketException ex) {
            _log.info(getPrefix(requestId) + "Error trying to connect", ex);
            handleConnectClientException(ex, out, targetRequest, usingWWWProxy, currentProxy, requestId);
            closeSocket(s);
        } catch (IOException ex) {
            _log.info(getPrefix(requestId) + "Error trying to connect", ex);
            handleConnectClientException(ex, out, targetRequest, usingWWWProxy, currentProxy, requestId);
            closeSocket(s);
        } catch (I2PException ex) {
            _log.info("getPrefix(requestId) + Error trying to connect", ex);
            handleConnectClientException(ex, out, targetRequest, usingWWWProxy, currentProxy, requestId);
            closeSocket(s);
        } catch (OutOfMemoryError oom) {
            IOException ex = new IOException("OOM");
            _log.info("getPrefix(requestId) + Error trying to connect", ex);
            handleConnectClientException(ex, out, targetRequest, usingWWWProxy, currentProxy, requestId);
            closeSocket(s);
        }
    }

    private static class OnTimeout implements Runnable {
        private final Socket _socket;
        private final OutputStream _out;
        private final String _target;
        private final boolean _usingProxy;
        private final String _wwwProxy;
        private final long _requestId;

        public OnTimeout(Socket s, OutputStream out, String target, boolean usingProxy, String wwwProxy, long id) {
            _socket = s;
            _out = out;
            _target = target;
            _usingProxy = usingProxy;
            _wwwProxy = wwwProxy;
            _requestId = id;
        }

        public void run() {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Timeout occured requesting " + _target);
            handleConnectClientException(new RuntimeException("Timeout"), _out, 
                                      _target, _usingProxy, _wwwProxy, _requestId);
            closeSocket(_socket);
        }
    }
    
    private static void writeErrorMessage(byte[] errMessage, OutputStream out) throws IOException {
        if (out == null)
            return;
        out.write(errMessage);
        out.write("\n</body></html>\n".getBytes());
        out.flush();
    }

    private static void writeErrorMessage(byte[] errMessage, OutputStream out, String targetRequest,
                                          boolean usingWWWProxy, String wwwProxy) throws IOException {
        if (out != null) {
            out.write(errMessage);
            if (targetRequest != null) {
                out.write(targetRequest.getBytes());
                if (usingWWWProxy)
                    out.write(("<br />WWW proxy: " + wwwProxy).getBytes());
            }
            out.write("</div>".getBytes());
            out.write("\n</body></html>\n".getBytes());
            out.flush();
        }
    }

    private static void handleConnectClientException(Exception ex, OutputStream out, String targetRequest,
                                                  boolean usingWWWProxy, String wwwProxy, long requestId) {
        if (out == null)
            return;
        byte[] header;
        if (usingWWWProxy)
            header = getErrorPage(I2PAppContext.getGlobalContext(), "dnfp-header.ht", ERR_DESTINATION_UNKNOWN);
        else
            header = getErrorPage(I2PAppContext.getGlobalContext(), "dnf-header.ht", ERR_DESTINATION_UNKNOWN);
        try {
            writeErrorMessage(header, out, targetRequest, usingWWWProxy, wwwProxy);
        } catch (IOException ioe) {}
    }
}
