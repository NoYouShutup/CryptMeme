/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.SSLException;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.util.ByteCache;
import net.i2p.util.EventDispatcher;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.data.Base32;

/**
 * Simple extension to the I2PTunnelServer that filters the HTTP
 * headers sent from the client to the server, replacing the Host
 * header with whatever this instance has been configured with, and
 * if the browser set Accept-encoding: x-i2p-gzip, gzip the http 
 * message body and set Content-encoding: x-i2p-gzip.
 *
 */
public class I2PTunnelHTTPServer extends I2PTunnelServer {

    /** all of these in SECONDS */
    public static final String OPT_POST_WINDOW = "postCheckTime";
    public static final String OPT_POST_BAN_TIME = "postBanTime";
    public static final String OPT_POST_TOTAL_BAN_TIME = "postTotalBanTime";
    public static final String OPT_POST_MAX = "maxPosts";
    public static final String OPT_POST_TOTAL_MAX = "maxTotalPosts";
    public static final String OPT_REJECT_INPROXY = "rejectInproxy";
    public static final int DEFAULT_POST_WINDOW = 5*60;
    public static final int DEFAULT_POST_BAN_TIME = 30*60;
    public static final int DEFAULT_POST_TOTAL_BAN_TIME = 10*60;
    public static final int DEFAULT_POST_MAX = 3;
    public static final int DEFAULT_POST_TOTAL_MAX = 10;

    /** what Host: should we seem to be to the webserver? */
    private String _spoofHost;
    private static final String HASH_HEADER = "X-I2P-DestHash";
    private static final String DEST64_HEADER = "X-I2P-DestB64";
    private static final String DEST32_HEADER = "X-I2P-DestB32";
    private static final String[] CLIENT_SKIPHEADERS = {HASH_HEADER, DEST64_HEADER, DEST32_HEADER};
    private static final String SERVER_HEADER = "Server";
    private static final String X_POWERED_BY_HEADER = "X-Powered-By";
    private static final String[] SERVER_SKIPHEADERS = {SERVER_HEADER, X_POWERED_BY_HEADER};
    private static final long HEADER_TIMEOUT = 15*1000;
    private static final long TOTAL_HEADER_TIMEOUT = 2 * HEADER_TIMEOUT;
    private static final long START_INTERVAL = (60 * 1000) * 3;
    private long _startedOn = 0L;
    private ConnThrottler _postThrottler;

    private final static byte[] ERR_UNAVAILABLE =
        ("HTTP/1.1 503 Service Unavailable\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-control: no-cache\r\n"+
         "Connection: close\r\n"+
         "Proxy-Connection: close\r\n"+
         "\r\n"+
         "<html><head><title>503 Service Unavailable</title></head>\n"+
         "<body><h2>503 Service Unavailable</h2>\n" +
         "<p>This I2P eepsite is unavailable. It may be down or undergoing maintenance.</p>\n" +
         "</body></html>")
         .getBytes();

    private final static byte[] ERR_DENIED =
        ("HTTP/1.1 403 Denied\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-control: no-cache\r\n"+
         "Connection: close\r\n"+
         "Proxy-Connection: close\r\n"+
         "\r\n"+
         "<html><head><title>403 Denied</title></head>\n"+
         "<body><h2>403 Denied</h2>\n" +
         "<p>Denied due to excessive requests. Please try again later.</p>\n" +
         "</body></html>")
         .getBytes();

    private final static byte[] ERR_INPROXY =
        ("HTTP/1.1 403 Denied\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-control: no-cache\r\n"+
         "Connection: close\r\n"+
         "Proxy-Connection: close\r\n"+
         "\r\n"+
         "<html><head><title>403 Denied</title></head>\n"+
         "<body><h2>403 Denied</h2>\n" +
         "<p>Inproxy access denied. You must run <a href=\"https://geti2p.net/\">I2P</a> to access this site.</p>\n" +
         "</body></html>")
         .getBytes();

    public I2PTunnelHTTPServer(InetAddress host, int port, String privData, String spoofHost, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(host, port, privData, l, notifyThis, tunnel);
        setupI2PTunnelHTTPServer(spoofHost);
    }

    public I2PTunnelHTTPServer(InetAddress host, int port, File privkey, String privkeyname, String spoofHost, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(host, port, privkey, privkeyname, l, notifyThis, tunnel);
        setupI2PTunnelHTTPServer(spoofHost);
    }

    public I2PTunnelHTTPServer(InetAddress host, int port, InputStream privData, String privkeyname, String spoofHost, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(host, port, privData, privkeyname, l, notifyThis, tunnel);
        setupI2PTunnelHTTPServer(spoofHost);
    }

    private void setupI2PTunnelHTTPServer(String spoofHost) {
        _spoofHost = (spoofHost != null && spoofHost.trim().length() > 0) ? spoofHost.trim() : null;
        getTunnel().getContext().statManager().createRateStat("i2ptunnel.httpserver.blockingHandleTime", "how long the blocking handle takes to complete", "I2PTunnel.HTTPServer", new long[] { 60*1000, 10*60*1000, 3*60*60*1000 });
        getTunnel().getContext().statManager().createRateStat("i2ptunnel.httpNullWorkaround", "How often an http server works around a streaming lib or i2ptunnel bug", "I2PTunnel.HTTPServer", new long[] { 60*1000, 10*60*1000 });
    }

    @Override
    public void startRunning() {
        super.startRunning();
        // Would be better if this was set when the inbound tunnel becomes alive.
        _startedOn = getTunnel().getContext().clock().now();
        setupPostThrottle();
    }

    /** @since 0.9.9 */
    private void setupPostThrottle() {
        int pp = getIntOption(OPT_POST_MAX, 0);
        int pt = getIntOption(OPT_POST_TOTAL_MAX, 0);
        synchronized(this) {
            if (pp != 0 || pt != 0 || _postThrottler != null) {
                long pw = 1000L * getIntOption(OPT_POST_WINDOW, DEFAULT_POST_WINDOW);
                long pb = 1000L * getIntOption(OPT_POST_BAN_TIME, DEFAULT_POST_BAN_TIME);
                long px = 1000L * getIntOption(OPT_POST_TOTAL_BAN_TIME, DEFAULT_POST_TOTAL_BAN_TIME);
                if (_postThrottler == null)
                    _postThrottler = new ConnThrottler(pp, pt, pw, pb, px, "POST", _log);
                else
                    _postThrottler.updateLimits(pp, pt, pw, pb, px);
            }
        }
    }

    /** @since 0.9.9 */
    private int getIntOption(String opt, int dflt) {
        Properties opts = getTunnel().getClientOptions();
        String o = opts.getProperty(opt);
        if (o != null) {
            try {
                return Integer.parseInt(o);
            } catch (NumberFormatException nfe) {}
        }
        return dflt;
    }

    /** @since 0.9.9 */
    @Override
    public boolean close(boolean forced) {
        synchronized(this) {
            if (_postThrottler != null)
                _postThrottler.clear();
        }
        return super.close(forced);
    }

    /** @since 0.9.9 */
    @Override
    public void optionsUpdated(I2PTunnel tunnel) {
        if (getTunnel() != tunnel)
            return;
        setupPostThrottle();
        super.optionsUpdated(tunnel);
    }


    /**
     * Called by the thread pool of I2PSocket handlers
     *
     */
    @Override
    protected void blockingHandle(I2PSocket socket) {
        Hash peerHash = socket.getPeerDestination().calculateHash();
        if (_log.shouldLog(Log.INFO))
            _log.info("Incoming connection to '" + toString() + "' port " + socket.getLocalPort() +
                      " from: " + peerHash + " port " + socket.getPort());
        //local is fast, so synchronously. Does not need that many
        //threads.
        try {
            long afterAccept = getTunnel().getContext().clock().now();
            // The headers _should_ be in the first packet, but
            // may not be, depending on the client-side options
            socket.setReadTimeout(HEADER_TIMEOUT);

            InputStream in = socket.getInputStream();

            StringBuilder command = new StringBuilder(128);
            Map<String, List<String>> headers = readHeaders(in, command,
                CLIENT_SKIPHEADERS, getTunnel().getContext());
            long afterHeaders = getTunnel().getContext().clock().now();

            Properties opts = getTunnel().getClientOptions();
            if (Boolean.parseBoolean(opts.getProperty(OPT_REJECT_INPROXY)) &&
                (headers.containsKey("X-Forwarded-For") ||
                 headers.containsKey("X-Forwarded-Server") ||
                 headers.containsKey("X-Forwarded-Host"))) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Refusing inproxy access: " + peerHash.toBase64());
                try {
                    // Send a 403, so the user doesn't get an HTTP Proxy error message
                    // and blame his router or the network.
                    socket.getOutputStream().write(ERR_INPROXY);
                } catch (IOException ioe) {}
                try {
                    socket.close();
                } catch (IOException ioe) {}
                return;
            }

            if (_postThrottler != null &&
                command.length() >= 5 &&
                command.substring(0, 5).toUpperCase(Locale.US).equals("POST ")) {
                if (_postThrottler.shouldThrottle(peerHash)) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Refusing POST since peer is throttled: " + peerHash.toBase64());
                    try {
                        // Send a 403, so the user doesn't get an HTTP Proxy error message
                        // and blame his router or the network.
                        socket.getOutputStream().write(ERR_DENIED);
                    } catch (IOException ioe) {}
                    try {
                        socket.close();
                    } catch (IOException ioe) {}
                    return;
                }
            }
            
            addEntry(headers, HASH_HEADER, peerHash.toBase64());
            addEntry(headers, DEST32_HEADER, Base32.encode(peerHash.getData()) + ".b32.i2p");
            addEntry(headers, DEST64_HEADER, socket.getPeerDestination().toBase64());

            // Port-specific spoofhost
            String spoofHost;
            int ourPort = socket.getLocalPort();
            if (ourPort != 80 && ourPort > 0 && ourPort <= 65535 && opts != null) {
                String portSpoof = opts.getProperty("spoofedHost." + ourPort);
                if (portSpoof != null)
                    spoofHost = portSpoof.trim();
                else
                    spoofHost = _spoofHost;
            } else {
                spoofHost = _spoofHost;
            }
            if (spoofHost != null)
                setEntry(headers, "Host", spoofHost);
            setEntry(headers, "Connection", "close");
            // we keep the enc sent by the browser before clobbering it, since it may have 
            // been x-i2p-gzip
            String enc = getEntryOrNull(headers, "Accept-encoding");
            String altEnc = getEntryOrNull(headers, "X-Accept-encoding");
            
            // according to rfc2616 s14.3, this *should* force identity, even if
            // "identity;q=1, *;q=0" didn't.  
            setEntry(headers, "Accept-encoding", ""); 

            socket.setReadTimeout(readTimeout);
            Socket s = getSocket(socket.getLocalPort());
            long afterSocket = getTunnel().getContext().clock().now();
            // instead of i2ptunnelrunner, use something that reads the HTTP 
            // request from the socket, modifies the headers, sends the request to the 
            // server, reads the response headers, rewriting to include Content-encoding: x-i2p-gzip
            // if it was one of the Accept-encoding: values, and gzip the payload       
            boolean allowGZIP = true;
            if (opts != null) {
                String val = opts.getProperty("i2ptunnel.gzip");
                if ( (val != null) && (!Boolean.parseBoolean(val)) ) 
                    allowGZIP = false;
            }
            if (_log.shouldLog(Log.INFO))
                _log.info("HTTP server encoding header: " + enc + "/" + altEnc);
            boolean alt = (altEnc != null) && (altEnc.indexOf("x-i2p-gzip") >= 0);
            boolean useGZIP = alt || ( (enc != null) && (enc.indexOf("x-i2p-gzip") >= 0) );
            // Don't pass this on, outproxies should strip so I2P traffic isn't so obvious but they probably don't
            if (alt)
                headers.remove("X-Accept-encoding");

            String modifiedHeader = formatHeaders(headers, command);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Modified header: [" + modifiedHeader + "]");
            
            if (allowGZIP && useGZIP) {
                I2PAppThread req = new I2PAppThread(
                    new CompressedRequestor(s, socket, modifiedHeader, getTunnel().getContext(), _log),
                        Thread.currentThread().getName()+".hc");
                req.start();
            } else {
                new I2PTunnelRunner(s, socket, slock, null, modifiedHeader.getBytes(), null);
            }

            long afterHandle = getTunnel().getContext().clock().now();
            long timeToHandle = afterHandle - afterAccept;
            getTunnel().getContext().statManager().addRateData("i2ptunnel.httpserver.blockingHandleTime", timeToHandle, 0);
            if ( (timeToHandle > 1000) && (_log.shouldLog(Log.WARN)) )
                _log.warn("Took a while to handle the request for " + remoteHost + ':' + remotePort +
                          " [" + timeToHandle +
                          ", read headers: " + (afterHeaders-afterAccept) +
                          ", socket create: " + (afterSocket-afterHeaders) +
                          ", start runners: " + (afterHandle-afterSocket) +
                          "]");
        } catch (SocketException ex) {
            try {
                // Send a 503, so the user doesn't get an HTTP Proxy error message
                // and blame his router or the network.
                socket.getOutputStream().write(ERR_UNAVAILABLE);
            } catch (IOException ioe) {}
            try {
                socket.close();
            } catch (IOException ioe) {}
            // Don't complain too early, Jetty may not be ready.
            int level = getTunnel().getContext().clock().now() - _startedOn > START_INTERVAL ? Log.ERROR : Log.WARN;
            if (_log.shouldLog(level))
                _log.log(level, "Error connecting to HTTP server " + remoteHost + ':' + remotePort, ex);
        } catch (IOException ex) {
            try {
                socket.close();
            } catch (IOException ioe) {}
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error while receiving the new HTTP request", ex);
        } catch (OutOfMemoryError oom) {
            try {
                socket.close();
            } catch (IOException ioe) {}
            if (_log.shouldLog(Log.ERROR))
                _log.error("OOM in HTTP server", oom);
        }
    }
    
    private static class CompressedRequestor implements Runnable {
        private final Socket _webserver;
        private final I2PSocket _browser;
        private final String _headers;
        private final I2PAppContext _ctx;
        // shadows _log in super()
        private final Log _log;

        private static final int BUF_SIZE = 8*1024;

        public CompressedRequestor(Socket webserver, I2PSocket browser, String headers, I2PAppContext ctx, Log log) {
            _webserver = webserver;
            _browser = browser;
            _headers = headers;
            _ctx = ctx;
            _log = log;
        }

        public void run() {
            if (_log.shouldLog(Log.INFO))
                _log.info("Compressed requestor running");
            OutputStream serverout = null;
            OutputStream browserout = null;
            InputStream browserin = null;
            InputStream serverin = null;
            try {
                serverout = _webserver.getOutputStream();
                
                if (_log.shouldLog(Log.INFO))
                    _log.info("request headers: " + _headers);
                serverout.write(_headers.getBytes());
                browserin = _browser.getInputStream();
                // Don't spin off a thread for this except for POSTs
                // beware interference with Shoutcast, etc.?
                if ((!(_headers.startsWith("GET ") || _headers.startsWith("HEAD "))) ||
                    browserin.available() > 0) {  // just in case
                    I2PAppThread sender = new I2PAppThread(new Sender(serverout, browserin, "server: browser to server", _log),
                                                                      Thread.currentThread().getName() + "hcs");
                    sender.start();
                } else {
                    // todo - half close? reduce MessageInputStream buffer size?
                }
                browserout = _browser.getOutputStream();
                // NPE seen here in 0.7-7, caused by addition of socket.close() in the
                // catch (IOException ioe) block above in blockingHandle() ???
                // CRIT  [ad-130280.hc] net.i2p.util.I2PThread        : Killing thread Thread-130280.hc
                // java.lang.NullPointerException
                //     at java.io.FileInputStream.<init>(FileInputStream.java:131)
                //     at java.net.SocketInputStream.<init>(SocketInputStream.java:44)
                //     at java.net.PlainSocketImpl.getInputStream(PlainSocketImpl.java:401)
                //     at java.net.Socket$2.run(Socket.java:779)
                //     at java.security.AccessController.doPrivileged(Native Method)
                //     at java.net.Socket.getInputStream(Socket.java:776)
                //     at net.i2p.i2ptunnel.I2PTunnelHTTPServer$CompressedRequestor.run(I2PTunnelHTTPServer.java:174)
                //     at java.lang.Thread.run(Thread.java:619)
                //     at net.i2p.util.I2PThread.run(I2PThread.java:71)
                try {
                    serverin = new BufferedInputStream(_webserver.getInputStream(), BUF_SIZE);
                } catch (NullPointerException npe) {
                    throw new IOException("getInputStream NPE");
                }
                CompressedResponseOutputStream compressedOut = new CompressedResponseOutputStream(browserout);

                //Change headers to protect server identity
                StringBuilder command = new StringBuilder(128);
                Map<String, List<String>> headers = readHeaders(serverin, command,
                    SERVER_SKIPHEADERS, _ctx);
                String modifiedHeaders = formatHeaders(headers, command);
                compressedOut.write(modifiedHeaders.getBytes());

                Sender s = new Sender(compressedOut, serverin, "server: server to browser", _log);
                if (_log.shouldLog(Log.INFO))
                    _log.info("Before pumping the compressed response");
                s.run(); // same thread
                if (_log.shouldLog(Log.INFO))
                    _log.info("After pumping the compressed response: " + compressedOut.getTotalRead() + "/" + compressedOut.getTotalCompressed());
            } catch (SSLException she) {
                _log.error("SSL error", she);
                try {
                    if (browserout == null)
                        browserout = _browser.getOutputStream();
                    browserout.write(ERR_UNAVAILABLE);
                } catch (IOException ioe) {}
            } catch (IOException ioe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("error compressing", ioe);
            } finally {
                if (browserout != null) try { browserout.close(); } catch (IOException ioe) {}
                if (serverout != null) try { serverout.close(); } catch (IOException ioe) {}
                if (browserin != null) try { browserin.close(); } catch (IOException ioe) {}
                if (serverin != null) try { serverin.close(); } catch (IOException ioe) {}
            }
        }
    }

    private static class Sender implements Runnable {
        private final OutputStream _out;
        private final InputStream _in;
        private final String _name;
        // shadows _log in super()
        private final Log _log;
        private static final int BUF_SIZE = 8*1024;
        private static final ByteCache _cache = ByteCache.getInstance(16, BUF_SIZE);

        public Sender(OutputStream out, InputStream in, String name, Log log) {
            _out = out;
            _in = in;
            _name = name;
            _log = log;
        }

        public void run() {
            if (_log.shouldLog(Log.INFO))
                _log.info(_name + ": Begin sending");
            ByteArray ba = _cache.acquire();
            try {
                byte buf[] = ba.getData();
                int read = 0;
                long total = 0;
                while ( (read = _in.read(buf)) != -1) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info(_name + ": read " + read + " and sending through the stream");
                    _out.write(buf, 0, read);
                    total += read;
                }
                if (_log.shouldLog(Log.INFO))
                    _log.info(_name + ": Done sending: " + total);
                //_out.flush();
            } catch (IOException ioe) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Error sending", ioe);
            } finally {
                _cache.release(ba);
                if (_out != null) try { _out.close(); } catch (IOException ioe) {}
                if (_in != null) try { _in.close(); } catch (IOException ioe) {}
            }
        }
    }

    /**
     *  This plus a typ. HTTP response header will fit into a 1730-byte streaming message.
     */
    private static final int MIN_TO_COMPRESS = 1300;

    private static class CompressedResponseOutputStream extends HTTPResponseOutputStream {
        private InternalGZIPOutputStream _gzipOut;

        public CompressedResponseOutputStream(OutputStream o) {
            super(o);
            _dataExpected = -1;
        }
        
        /**
         * Overridden to peek at response code. Always returns line.
         */
        @Override
        protected String filterResponseLine(String line) {
            String[] s = line.split(" ", 3);
            if (s.length > 1 &&
                (s[1].startsWith("3") || s[1].startsWith("5")))
                _dataExpected = 0;
            return line;
        }
    
        /**
         *  Don't compress small responses or images.
         *  Compression is inline but decompression on the client side
         *  creates a new thread.
         */
        @Override
        protected boolean shouldCompress() {
            return (_dataExpected < 0 || _dataExpected >= MIN_TO_COMPRESS) &&
                   (_contentType == null ||
                    ((!_contentType.startsWith("audio/")) &&
                     (!_contentType.startsWith("image/")) &&
                     (!_contentType.startsWith("video/")) &&
                     (!_contentType.equals("application/compress")) &&
                     (!_contentType.equals("application/bzip2")) &&
                     (!_contentType.equals("application/gzip")) &&
                     (!_contentType.equals("application/x-bzip")) &&
                     (!_contentType.equals("application/x-bzip2")) &&
                     (!_contentType.equals("application/x-gzip")) &&
                     (!_contentType.equals("application/zip"))));
        }

        @Override
        protected void finishHeaders() throws IOException {
            //if (_log.shouldLog(Log.INFO))
            //    _log.info("Including x-i2p-gzip as the content encoding in the response");
            if (shouldCompress())
                out.write("Content-encoding: x-i2p-gzip\r\n".getBytes());
            super.finishHeaders();
        }

        @Override
        protected void beginProcessing() throws IOException {
            //if (_log.shouldLog(Log.INFO))
            //    _log.info("Beginning compression processing");
            //out.flush();
            if (shouldCompress()) {
                _gzipOut = new InternalGZIPOutputStream(out);
                out = _gzipOut;
            }
        }

        public long getTotalRead() { 
            InternalGZIPOutputStream gzipOut = _gzipOut;
            if (gzipOut != null)
                return gzipOut.getTotalRead();
            else
                return 0;
        }

        public long getTotalCompressed() { 
            InternalGZIPOutputStream gzipOut = _gzipOut;
            if (gzipOut != null)
                return gzipOut.getTotalCompressed();
            else
                return 0;
        }
    }

    /** just a wrapper to provide stats for debugging */
    private static class InternalGZIPOutputStream extends GZIPOutputStream {
        public InternalGZIPOutputStream(OutputStream target) throws IOException {
            super(target);
        }
        public long getTotalRead() { 
            try {
                return def.getTotalIn();
            } catch (Exception e) {
                // j2se 1.4.2_08 on linux is sometimes throwing an NPE in the getTotalIn() implementation
                return 0; 
            }
        }
        public long getTotalCompressed() { 
            try {
                return def.getTotalOut();
            } catch (Exception e) {
                // j2se 1.4.2_08 on linux is sometimes throwing an NPE in the getTotalOut() implementation
                return 0;
            }
        }
    }

    /**
     *  @return the command followed by the header lines
     */
    protected static String formatHeaders(Map<String, List<String>> headers, StringBuilder command) {
        StringBuilder buf = new StringBuilder(command.length() + headers.size() * 64);
        buf.append(command.toString().trim()).append("\r\n");
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            String name = e.getKey();
            for(String val: e.getValue()) {
                buf.append(name.trim()).append(": ").append(val.trim()).append("\r\n");
            }
        }
        buf.append("\r\n");
        return buf.toString();
    }
    
    /** ridiculously long, just to prevent OOM DOS @since 0.7.13 */
    private static final int MAX_HEADERS = 60;
    
    /**
     * Add an entry to the multimap.
     */
    private static void addEntry(Map<String, List<String>> headers, String key, String value) {
        List<String> entry = headers.get(key);
        if(entry == null) {
        	headers.put(key, entry = new ArrayList<String>());
        }
        entry.add(value);    	
    }
    
    /**
     * Remove the other matching entries and set this entry as the only one.
     */
    private static void setEntry(Map<String, List<String>> headers, String key, String value) {
    	List<String> entry = headers.get(key);
    	if(entry == null) {
    		headers.put(key, entry = new ArrayList<String>());
    	}
    	entry.clear();
    	entry.add(value);
    }
    
    /**
     * Get the first matching entry in the multimap
     * @return the first matching entry or null
     */
    private static String getEntryOrNull(Map<String, List<String>> headers, String key) {
    	List<String> entries = headers.get(key);
    	if(entries == null || entries.size() < 1) {
    		return null;
    	}
    	else {
    		return entries.get(0);
    	}
    }

    protected static Map<String, List<String>> readHeaders(InputStream in, StringBuilder command,
                                                           String[] skipHeaders, I2PAppContext ctx) throws IOException {
    	HashMap<String, List<String>> headers = new HashMap<String, List<String>>();
        StringBuilder buf = new StringBuilder(128);
        
        // slowloris / darkloris
        long expire = ctx.clock().now() + TOTAL_HEADER_TIMEOUT;
        boolean ok = DataHelper.readLine(in, command);
        if (!ok) throw new IOException("EOF reached while reading the HTTP command [" + command.toString() + "]");
        
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Read the http command [" + command.toString() + "]");
        
        // FIXME we probably don't need or want this in the outgoing direction
        int trimmed = 0;
        if (command.length() > 0) {
            for (int i = 0; i < command.length(); i++) {
                if (command.charAt(i) == 0) {
                    command = command.deleteCharAt(i);
                    i--;
                    trimmed++;
                }
            }
        }
        if (trimmed > 0)
            ctx.statManager().addRateData("i2ptunnel.httpNullWorkaround", trimmed, 0);
        
        int i = 0;
        while (true) {
            if (++i > MAX_HEADERS)
                throw new IOException("Too many header lines - max " + MAX_HEADERS);
            buf.setLength(0);
            ok = DataHelper.readLine(in, buf);
            if (!ok) throw new IOException("EOF reached before the end of the headers [" + buf.toString() + "]");
            if ( (buf.length() == 0) || 
                 ((buf.charAt(0) == '\n') || (buf.charAt(0) == '\r')) ) {
                // end of headers reached
                return headers;
            } else {
                if (ctx.clock().now() > expire)
                    throw new IOException("Headers took too long [" + buf.toString() + "]");
                int split = buf.indexOf(":");
                if (split <= 0) throw new IOException("Invalid HTTP header, missing colon [" + buf.toString() + "]");
                String name = buf.substring(0, split).trim();
                String value = null;
                if (buf.length() > split + 1)
                    value = buf.substring(split+1).trim(); // ":"
                else
                    value = "";

                String lcName = name.toLowerCase(Locale.US);
                if ("accept-encoding".equals(lcName))
                    name = "Accept-encoding";
                else if ("x-accept-encoding".equals(lcName))
                    name = "X-Accept-encoding";
                else if ("x-forwarded-for".equals(lcName))
                    name = "X-Forwarded-For";
                else if ("x-forwarded-server".equals(lcName))
                    name = "X-Forwarded-Server";
                else if ("x-forwarded-host".equals(lcName))
                    name = "X-Forwarded-Host";

                // For incoming, we remove certain headers to prevent spoofing.
                // For outgoing, we remove certain headers to improve anonymity.
                boolean skip = false;
                for (String skipHeader: skipHeaders) {
                    if (skipHeader.toLowerCase(Locale.US).equals(lcName)) {
                        skip = true;
                        break;
                    }
                }
                if(skip) {
                    continue;
                }

                addEntry(headers, name, value);
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("Read the header [" + name + "] = [" + value + "]");
            }
        }
    }
}

