package net.i2p.util;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.net.URL;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Formatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import gnu.getopt.Getopt;

import net.i2p.I2PAppContext;
import net.i2p.data.Base32;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.util.InternalSocket;

/**
 * EepGet [-p 127.0.0.1:4444]
 *        [-n #retries]
 *        [-o outputFile]
 *        [-m markSize lineLen]
 *        url
 */
public class EepGet {
    protected final I2PAppContext _context;
    protected final Log _log;
    protected final boolean _shouldProxy;
    private final String _proxyHost;
    private final int _proxyPort;
    protected final int _numRetries;
    private final long _minSize; // minimum and maximum acceptable response size, -1 signifies unlimited,
    private final long _maxSize; // applied both against whole responses and chunks
    protected final String _outputFile;
    protected final OutputStream _outputStream;
    /** url we were asked to fetch */
    protected final String _url;
    /** the URL we actually fetch from (may differ from the _url in case of redirect) */
    protected String _actualURL;
    private final String _postData;
    private boolean _allowCaching;
    protected final List<StatusListener> _listeners;
    protected List<String> _extraHeaders;

    protected boolean _keepFetching;
    protected Socket _proxy;
    protected OutputStream _proxyOut;
    protected InputStream _proxyIn;
    protected OutputStream _out;
    protected long _alreadyTransferred;
    protected long _bytesTransferred;
    protected long _bytesRemaining;
    protected int _currentAttempt;
    protected int _responseCode = -1;
    protected String _responseText;
    protected boolean _shouldWriteErrorToOutput;
    protected String _etag;
    protected String _lastModified;
    protected final String _etagOrig;
    protected final String _lastModifiedOrig;
    protected boolean _encodingChunked;
    protected boolean _notModified;
    protected String _contentType;
    protected boolean _transferFailed;
    protected boolean _headersRead;
    protected boolean _aborted;
    private long _fetchHeaderTimeout;
    private long _fetchEndTime;
    protected long _fetchInactivityTimeout;
    protected int _redirects;
    protected String _redirectLocation;
    protected boolean _isGzippedResponse;
    protected IOException _decompressException;

    // following for proxy digest auth
    // only created via addAuthorization()
    protected AuthState _authState;

    /** this will be replaced by the HTTP Proxy if we are using it */
    protected static final String USER_AGENT = "Wget/1.11.4";
    protected static final long CONNECT_TIMEOUT = 45*1000;
    protected static final long INACTIVITY_TIMEOUT = 60*1000;
    /** maximum times to try without getting any data at all, even if numRetries is higher @since 0.7.14 */
    protected static final int MAX_COMPLETE_FAILS = 5;

    public EepGet(I2PAppContext ctx, String proxyHost, int proxyPort, int numRetries, String outputFile, String url) {
        this(ctx, true, proxyHost, proxyPort, numRetries, outputFile, url);
    }

    public EepGet(I2PAppContext ctx, String proxyHost, int proxyPort, int numRetries, String outputFile, String url, boolean allowCaching) {
        this(ctx, true, proxyHost, proxyPort, numRetries, outputFile, url, allowCaching, null);
    }

    public EepGet(I2PAppContext ctx, int numRetries, String outputFile, String url) {
        this(ctx, false, null, -1, numRetries, outputFile, url);
    }

    public EepGet(I2PAppContext ctx, int numRetries, String outputFile, String url, boolean allowCaching) {
        this(ctx, false, null, -1, numRetries, outputFile, url, allowCaching, null);
    }

    public EepGet(I2PAppContext ctx, boolean shouldProxy, String proxyHost, int proxyPort, int numRetries, String outputFile, String url) {
        this(ctx, shouldProxy, proxyHost, proxyPort, numRetries, outputFile, url, true, null);
    }

    public EepGet(I2PAppContext ctx, boolean shouldProxy, String proxyHost, int proxyPort, int numRetries, String outputFile, String url, String postData) {
        this(ctx, shouldProxy, proxyHost, proxyPort, numRetries, -1, -1, outputFile, null, url, true, null, postData);
    }

    public EepGet(I2PAppContext ctx, boolean shouldProxy, String proxyHost, int proxyPort, int numRetries, String outputFile, String url, boolean allowCaching, String etag) {
        this(ctx, shouldProxy, proxyHost, proxyPort, numRetries, -1, -1, outputFile, null, url, allowCaching, etag, null);
    }

    public EepGet(I2PAppContext ctx, boolean shouldProxy, String proxyHost, int proxyPort, int numRetries, String outputFile, String url, boolean allowCaching, String etag, String lastModified) {
        this(ctx, shouldProxy, proxyHost, proxyPort, numRetries, -1, -1, outputFile, null, url, allowCaching, etag, lastModified, null);
    }

    public EepGet(I2PAppContext ctx, boolean shouldProxy, String proxyHost, int proxyPort, int numRetries, long minSize, long maxSize, String outputFile, OutputStream outputStream, String url, boolean allowCaching, String etag, String postData) {
        this(ctx, shouldProxy, proxyHost, proxyPort, numRetries, minSize, maxSize, outputFile, outputStream, url, allowCaching, etag, null, postData);
    }

    public EepGet(I2PAppContext ctx, boolean shouldProxy, String proxyHost, int proxyPort, int numRetries, long minSize, long maxSize,
                  String outputFile, OutputStream outputStream, String url, boolean allowCaching,
                  String etag, String lastModified, String postData) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _shouldProxy = (proxyHost != null) && (proxyHost.length() > 0) && (proxyPort > 0) && shouldProxy;
        _proxyHost = proxyHost;
        _proxyPort = proxyPort;
        _numRetries = numRetries;
        _minSize = minSize;
        _maxSize = maxSize;
        _outputFile = outputFile;     // if outputFile is set, outputStream must be null
        _outputStream = outputStream; // if both are set, outputStream overrides outputFile
        _url = url;
        _actualURL = url;
        _postData = postData;
        _bytesRemaining = -1;
        _fetchHeaderTimeout = CONNECT_TIMEOUT;
        _listeners = new ArrayList<StatusListener>(1);
        _etag = etag;
        _lastModified = lastModified;
        _etagOrig = etag;
        _lastModifiedOrig = lastModified;
    }

    /**
     * EepGet [-p 127.0.0.1:4444] [-n #retries] [-e etag] [-o outputFile] [-m markSize lineLen] url
     *
     */
    public static void main(String args[]) {
        String proxyHost = "127.0.0.1";
        int proxyPort = 4444;
        int numRetries = 5;
        int markSize = 1024;
        int lineLen = 40;
        long inactivityTimeout = INACTIVITY_TIMEOUT;
        String etag = null;
        String saveAs = null;
        List<String> extra = null;
        String username = null;
        String password = null;
        boolean error = false;
        //
        // note: if you add options, please update installer/resources/man/eepget.1
        //
        Getopt g = new Getopt("eepget", args, "p:cn:t:e:o:m:l:h:u:x:");
        try {
            int c;
            while ((c = g.getopt()) != -1) {
              switch (c) {
                case 'p':
                    String s = g.getOptarg();
                    int colon = s.indexOf(':');
                    if (colon >= 0) {
                        // Todo IPv6 [a:b:c]:4444
                        proxyHost = s.substring(0, colon);
                        String port = s.substring(colon + 1);
                        proxyPort = Integer.parseInt(port);
                    } else {
                        proxyHost = s;
                        // proxyPort remains default
                    }
                    break;

                case 'c':
                    // no proxy, same as -p :0
                    proxyHost = "";
                    proxyPort = 0;
                    break;

                case 'n':
                    numRetries = Integer.parseInt(g.getOptarg());
                    break;

                case 't':
                    inactivityTimeout = 1000 * Integer.parseInt(g.getOptarg());
                    break;

                case 'e':
                    etag = "\"" + g.getOptarg() + "\"";
                    break;

                case 'o':
                    saveAs = g.getOptarg();
                    break;

                case 'm':
                    markSize = Integer.parseInt(g.getOptarg());
                    break;

                case 'l':
                    lineLen = Integer.parseInt(g.getOptarg());
                    break;

                case 'h':
                    String a = g.getOptarg();
                    int eq = a.indexOf('=');
                    if (eq > 0) {
                        if (extra == null)
                            extra = new ArrayList<String>(2);
                        String key = a.substring(0, eq);
                        String val = a.substring(eq + 1);
                        extra.add(key);
                        extra.add(val);
                    } else {
                        error = true;
                    }
                    break;

                case 'u':
                    username = g.getOptarg();
                    break;

                case 'x':
                    password = g.getOptarg();
                    break;

                case '?':
                case ':':
                default:
                    error = true;
                    break;
              }  // switch
            } // while
        } catch (Exception e) {
            e.printStackTrace();
            error = true;
        }

        if (error || args.length - g.getOptind() != 1) {
            usage();
            System.exit(1);
        }
        String url = args[g.getOptind()];

        if (saveAs == null)
            saveAs = suggestName(url);

        EepGet get = new EepGet(I2PAppContext.getGlobalContext(), true, proxyHost, proxyPort, numRetries, saveAs, url, true, etag);
        if (extra != null) {
            for (int i = 0; i < extra.size(); i += 2) {
                get.addHeader(extra.get(i), extra.get(i + 1));
            }
        }
        if (username != null) {
            if (password == null) {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
                    do {
                        System.err.print("Proxy password: ");
                        password = r.readLine();
                        if (password == null)
                            throw new IOException();
                        password = password.trim();
                    } while (password.length() <= 0);
                } catch (IOException ioe) {
                    System.exit(1);
                }
            }
            get.addAuthorization(username, password);
        }
        get.addStatusListener(get.new CLIStatusListener(markSize, lineLen));
        if (!get.fetch(CONNECT_TIMEOUT, -1, inactivityTimeout))
            System.exit(1);
    }

    public static String suggestName(String url) {
        int last = url.lastIndexOf('/');
        if ((last < 0) || (url.lastIndexOf('#') > last))
            last = url.lastIndexOf('#');
        if ((last < 0) || (url.lastIndexOf('?') > last))
            last = url.lastIndexOf('?');
        if ((last < 0) || (url.lastIndexOf('=') > last))
            last = url.lastIndexOf('=');

        String name = null;
        if (last >= 0)
            name = sanitize(url.substring(last+1));
        if ( (name != null) && (name.length() > 0) )
            return name;
        else
            return sanitize(url);
    }


/* Blacklist borrowed from snark */

  private static final char[] ILLEGAL = new char[] {
        '<', '>', ':', '"', '/', '\\', '|', '?', '*',
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31,
        0x7f };

  /**
   * Removes 'suspicious' characters from the given file name.
   * http://msdn.microsoft.com/en-us/library/aa365247%28VS.85%29.aspx
   */


    private static String sanitize(String name) {
    if (name.equals(".") || name.equals(" "))
        return "_";
    String rv = name;
    if (rv.startsWith("."))
        rv = '_' + rv.substring(1);
    if (rv.endsWith(".") || rv.endsWith(" "))
        rv = rv.substring(0, rv.length() - 1) + '_';
    for (int i = 0; i < ILLEGAL.length; i++) {
        if (rv.indexOf(ILLEGAL[i]) >= 0)
            rv = rv.replace(ILLEGAL[i], '_');
    }
    return rv;
    }

    private static void usage() {
        System.err.println("eepget [-p 127.0.0.1[:4444]] [-c] [-o outputFile]\n" +
                           "       [-n #retries] (default 5)\n" +
                           "       [-m markSize] (default 1024)\n" +
                           "       [-l lineLen]  (default 40)\n" +
                           "       [-t timeout]  (default 60 sec)\n" +
                           "       [-e etag]\n" +
                           "       [-h headerName=headerValue]\n" +
                           "       [-u username] [-x password] url\n" +
                           "       (use -c or -p :0 for no proxy)");
    }
    
    public static interface StatusListener {
        /**
         *  alreadyTransferred - total of all attempts, not including currentWrite
         *                       If nonzero on the first call, a partial file of that length was found,
         *                       _and_ the server supports resume.
         *                       If zero on a subsequent call after some bytes are transferred
         *                       (and presumably after an attemptFailed), the server does _not_
         *                       support resume and we had to start over.
         *                       To track _actual_ transfer if the output file could already exist,
         *                       the listener should keep its own counter,
         *                       or subtract the initial alreadyTransferred value.
         *                       And watch out for alreadyTransferred resetting if a resume failed...
         *  currentWrite - since last call to the listener
         *  bytesTransferred - includes headers, retries, redirects, discarded partial downloads, ...
         *  bytesRemaining - on this attempt only, currentWrite already subtracted -
         *                   or -1 if chunked encoding or server does not return a length
         *
         *  Total length should be == alreadyTransferred + currentWrite + bytesRemaining for all calls
         *
         */
        public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url);
        public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified);
        public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause);
        public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt);

        /**
         *  Note: Headers are not processed, and this is not called, for most error response codes,
         *  unless setWriteErrorToOutput() is called before fetch().
         *  To be changed?
         */
        public void headerReceived(String url, int currentAttempt, String key, String val);

        public void attempting(String url);
    }
    protected class CLIStatusListener implements StatusListener {
        private final int _markSize;
        private final int _lineSize;
        private final long _startedOn;
        private long _written;
        private long _previousWritten;
        private long _discarded;
        private long _lastComplete;
        private boolean _firstTime;
        private final DecimalFormat _pct = new DecimalFormat("00.0%");
        private final DecimalFormat _kbps = new DecimalFormat("###,000.00");
        public CLIStatusListener() { 
            this(1024, 40);
        }
        public CLIStatusListener(int markSize, int lineSize) { 
            _markSize = markSize;
            _lineSize = lineSize;
            _lastComplete = _context.clock().now();
            _startedOn = _lastComplete;
            _firstTime = true;
        }
        public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {
            if (_firstTime) {
                if (alreadyTransferred > 0) {
                    _previousWritten = alreadyTransferred;
                    System.out.println("File found with length " + alreadyTransferred + ", resuming");
                }
                _firstTime = false;
            }
            if (_written == 0 && alreadyTransferred == 0 && _previousWritten > 0) {
                // boo
                System.out.println("Server does not support resume, discarding " + _previousWritten + " bytes");
                _discarded += _previousWritten;
                _previousWritten = 0;
            }
            for (int i = 0; i < currentWrite; i++) {
                _written++;
                if ( (_markSize > 0) && (_written % _markSize == 0) ) {
                    System.out.print("#");
                    
                    if ( (_lineSize > 0) && (_written % ((long)_markSize*(long)_lineSize) == 0l) ) {
                        long now = _context.clock().now();
                        long timeToSend = now - _lastComplete;
                        if (timeToSend > 0) {
                            StringBuilder buf = new StringBuilder(50);
                            Formatter fmt = new Formatter(buf);
                            buf.append(" ");
                            if ( bytesRemaining > 0 ) {
                                double pct = 100 * ((double)_written + _previousWritten) /
                                             ((double)alreadyTransferred + (double)currentWrite + bytesRemaining);
                                fmt.format("%4.1f", Double.valueOf(pct));
                                buf.append("%: ");
                            }
                            fmt.format("%8d", Long.valueOf(_written));
                            buf.append(" @ ");
                            double lineKBytes = ((double)_markSize * (double)_lineSize)/1024.0d;
                            double kbps = lineKBytes/(timeToSend/1000.0d);
                            fmt.format("%7.2f", Double.valueOf(kbps));
                            buf.append(" KBps");
                            
                            buf.append(" / ");
                            long lifetime = _context.clock().now() - _startedOn;
                            double lifetimeKBps = (1000.0d*(_written)/(lifetime*1024.0d));
                            fmt.format("%7.2f", Double.valueOf(lifetimeKBps));
                            buf.append(" KBps");
                            System.out.println(buf.toString());
                            fmt.close();
                        }
                        _lastComplete = now;
                    }
                }
            }
        }
        public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {
            long transferred;
            if (_firstTime)
                transferred = 0;
            else
                transferred = alreadyTransferred - _previousWritten;
            System.out.println();
            System.out.println("== " + new Date());
            if (notModified) {
                System.out.println("== Source not modified since last download");
            } else {
                if ( bytesRemaining > 0 ) {
                    System.out.println("== Transfer of " + url + " completed with " + transferred
                            + " transferred and " + (bytesRemaining - bytesTransferred) + " remaining" +
                            (_discarded > 0 ? (" and " + _discarded + " bytes discarded") : ""));
                } else {
                    System.out.println("== Transfer of " + url + " completed with " + transferred
                            + " bytes transferred" +
                            (_discarded > 0 ? (" and " + _discarded + " bytes discarded") : ""));
                }
                if (transferred > 0)
                    System.out.println("== Output saved to " + outputFile + " (" + alreadyTransferred + " bytes)");
            }
            long timeToSend = _context.clock().now() - _startedOn;
            System.out.println("== Transfer time: " + DataHelper.formatDuration(timeToSend));
            if (_etag != null)
                System.out.println("== ETag: " + _etag);            
            if (transferred > 0) {
                StringBuilder buf = new StringBuilder(50);
                buf.append("== Transfer rate: ");
                double kbps = (1000.0d*(transferred)/(timeToSend*1024.0d));
                synchronized (_kbps) {
                    buf.append(_kbps.format(kbps));
                }
                buf.append("KBps");
                System.out.println(buf.toString());
            }
        }
        public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause) {
            System.out.println();
            System.out.println("** " + new Date());
            System.out.println("** Attempt " + currentAttempt + " of " + url + " failed");
            System.out.println("** Transfered " + bytesTransferred
                               + " with " + (bytesRemaining < 0 ? "unknown" : ""+bytesRemaining) + " remaining");
            System.out.println("** " + cause.getMessage());
            _previousWritten += _written;
            _written = 0;
        }
        public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {
            System.out.println("== " + new Date());
            System.out.println("== Transfer of " + url + " failed after " + currentAttempt + " attempts");
            System.out.println("== Transfer size: " + bytesTransferred + " with "
                               + (bytesRemaining < 0 ? "unknown" : ""+bytesRemaining) + " remaining");
            long timeToSend = _context.clock().now() - _startedOn;
            System.out.println("== Transfer time: " + DataHelper.formatDuration(timeToSend));
            double kbps = (timeToSend > 0 ? (1000.0d*(bytesTransferred)/(timeToSend*1024.0d)) : 0);
            StringBuilder buf = new StringBuilder(50);
            buf.append("== Transfer rate: ");
            synchronized (_kbps) {
                buf.append(_kbps.format(kbps));
            }
            buf.append("KBps");
            System.out.println(buf.toString());
        }
        public void attempting(String url) {}
        public void headerReceived(String url, int currentAttempt, String key, String val) {}
    }
    
    public void addStatusListener(StatusListener lsnr) {
        synchronized (_listeners) { _listeners.add(lsnr); }
    }
    
    public void stopFetching() { _keepFetching = false; }

    /**
     * Blocking fetch, returning true if the URL was retrieved, false if all retries failed.
     *
     * Header timeout default 45 sec, total timeout default none, inactivity timeout default 60 sec.
     */
    public boolean fetch() { return fetch(_fetchHeaderTimeout); }

    /**
     * Blocking fetch, timing out individual attempts if the HTTP response headers
     * don't come back in the time given.  If the timeout is zero or less, this will
     * wait indefinitely.
     *
     * Total timeout default none, inactivity timeout default 60 sec.
     */
    public boolean fetch(long fetchHeaderTimeout) {
        return fetch(fetchHeaderTimeout, -1, -1);
    }

    /**
     * Blocking fetch.
     *
     * @param fetchHeaderTimeout <= 0 for none (proxy will timeout if none, none isn't recommended if no proxy)
     * @param totalTimeout <= 0 for default none
     * @param inactivityTimeout <= 0 for default 60 sec
     */
    public boolean fetch(long fetchHeaderTimeout, long totalTimeout, long inactivityTimeout) {
        _fetchHeaderTimeout = fetchHeaderTimeout;
        _fetchEndTime = (totalTimeout > 0 ? System.currentTimeMillis() + totalTimeout : -1);
        _fetchInactivityTimeout = inactivityTimeout;
        _keepFetching = true;

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Fetching (proxied? " + _shouldProxy + ") url=" + _actualURL);
        while (_keepFetching) {
            SocketTimeout timeout = null;
            if (_fetchHeaderTimeout > 0) {
                timeout = new SocketTimeout(_fetchHeaderTimeout);
                final SocketTimeout stimeout = timeout; // ugly - why not use sotimeout?
                timeout.setTimeoutCommand(new Runnable() {
                    public void run() {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("timeout reached on " + _url + ": " + stimeout);
                        _aborted = true;
                    }
                });
                timeout.setTotalTimeoutPeriod(_fetchEndTime);
            }
            try {
                for (int i = 0; i < _listeners.size(); i++) 
                    _listeners.get(i).attempting(_url);
                sendRequest(timeout);
                if (timeout != null)
                    timeout.resetTimer();
                doFetch(timeout);
                if (timeout != null)
                    timeout.cancel();
                if (!_transferFailed)
                    return true;
                break;
            } catch (IOException ioe) {
                if (timeout != null)
                    timeout.cancel();
                for (int i = 0; i < _listeners.size(); i++) 
                    _listeners.get(i).attemptFailed(_url, _bytesTransferred, _bytesRemaining, _currentAttempt, _numRetries, ioe);
                if (_log.shouldLog(Log.WARN))
                    _log.warn("ERR: doFetch failed ", ioe);
                if (ioe instanceof MalformedURLException ||
                    ioe instanceof UnknownHostException ||
                    ioe instanceof ConnectException) // proxy or nonproxied host Connection Refused
                    _keepFetching = false;
            } finally {
                if (_out != null) {
                    try {
                        _out.close();
                    } catch (IOException cioe) {}
                    _out = null;
                }
                if (_proxy != null) {
                    try { 
                        _proxy.close(); 
                        _proxy = null;
                    } catch (IOException ioe) {}
                }
            }

            _currentAttempt++;
            if (_currentAttempt > _numRetries ||
                (_alreadyTransferred == 0 && _currentAttempt > MAX_COMPLETE_FAILS) ||
                !_keepFetching) 
                break;
            _redirects = 0;
            try { 
                long delay = _context.random().nextInt(60*1000);
                Thread.sleep(5*1000+delay); 
            } catch (InterruptedException ie) {}
        }

        for (int i = 0; i < _listeners.size(); i++) 
            _listeners.get(i).transferFailed(_url, _bytesTransferred, _bytesRemaining, _currentAttempt);
        if (_log.shouldLog(Log.WARN))
            _log.warn("All attempts failed for " + _url);
        return false;
    }

    /**
     *  single fetch
     *  @param timeout may be null
     */
    protected void doFetch(SocketTimeout timeout) throws IOException {
        _headersRead = false;
        _aborted = false;
        try {
            readHeaders();
        } finally {
            _headersRead = true;
        }
        if (_aborted)
            throw new IOException("Timed out reading the HTTP headers");
        
        if (timeout != null) {
            timeout.resetTimer();
            if (_fetchInactivityTimeout > 0)
                timeout.setInactivityTimeout(_fetchInactivityTimeout);
            else
                timeout.setInactivityTimeout(INACTIVITY_TIMEOUT);
        }
        
        if (_redirectLocation != null) {
            // we also are here after a 407
            //try {
                if (_redirectLocation.startsWith("http://")) {
                    _actualURL = _redirectLocation;
                } else { 
                    // the Location: field has been required to be an absolute URI at least since
                    // RFC 1945 (HTTP/1.0 1996), so it isn't clear what the point of this is.
                    // This oddly adds a ":" even if no port, but that seems to work.
                    URL url = new URL(_actualURL);
		    if (_redirectLocation.startsWith("/"))
                        _actualURL = "http://" + url.getHost() + ":" + url.getPort() + _redirectLocation;
                    else
                        // this blows up completely on a redirect to https://, for example
                        _actualURL = "http://" + url.getHost() + ":" + url.getPort() + "/" + _redirectLocation;
                }
            // an MUE is an IOE
            //} catch (MalformedURLException mue) {
            //    throw new IOException("Redirected from an invalid URL");
            //}

            AuthState as = _authState;
            if (_responseCode == 407) {
                if (!_shouldProxy)
                    throw new IOException("Proxy auth response from non-proxy");
                if (as == null)
                    throw new IOException("Proxy requires authentication");
                if (as.authSent)
                    throw new IOException("Proxy authentication failed");  // ignore stale
                if (_log.shouldLog(Log.INFO)) _log.info("Adding auth");
                // actually happens in getRequest()
            } else {
                _redirects++;
                if (_redirects > 5)
                    throw new IOException("Too many redirects: to " + _redirectLocation);
                if (_log.shouldLog(Log.INFO)) _log.info("Redirecting to " + _redirectLocation);
                if (as != null)
                    as.authSent = false;
            }

            // reset some important variables, we don't want to save the values from the redirect
            _bytesRemaining = -1;
            _redirectLocation = null;
            _etag = _etagOrig;
            _lastModified = _lastModifiedOrig;
            _contentType = null;
            _encodingChunked = false;

            sendRequest(timeout);
            doFetch(timeout);
            return;
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Headers read completely, reading " + _bytesRemaining);
        
        boolean strictSize = (_bytesRemaining >= 0);

        // If minimum or maximum size defined, ensure they aren't exceeded
        if ((_minSize > 0) && (_bytesRemaining < _minSize))
            throw new IOException("HTTP response size " + _bytesRemaining + " violates minimum of " + _minSize + " bytes");
        if ((_maxSize > -1) && (_bytesRemaining > _maxSize))
            throw new IOException("HTTP response size " + _bytesRemaining + " violates maximum of " + _maxSize + " bytes");

        Thread pusher = null;
        _decompressException = null;
        if (_isGzippedResponse) {
            PipedInputStream pi = BigPipedInputStream.getInstance();
            PipedOutputStream po = new PipedOutputStream(pi);
            pusher = new I2PAppThread(new Gunzipper(pi, _out), "EepGet Decompressor");
            _out = po;
            pusher.start();
        }

        int remaining = (int)_bytesRemaining;
        byte buf[] = new byte[16*1024];
        while (_keepFetching && ( (remaining > 0) || !strictSize ) && !_aborted) {
            int toRead = buf.length;
            if (strictSize && toRead > remaining)
                toRead = remaining;
            int read = _proxyIn.read(buf, 0, toRead);
            if (read == -1)
                break;
            if (timeout != null)
                timeout.resetTimer();
            _out.write(buf, 0, read);
            _bytesTransferred += read;
            if ((_maxSize > -1) && (_alreadyTransferred + read > _maxSize)) // could transfer a little over maxSize
                throw new IOException("Bytes transferred " + (_alreadyTransferred + read) + " violates maximum of " + _maxSize + " bytes");
            remaining -= read;
            if (remaining==0 && _encodingChunked) {
                int char1 = _proxyIn.read();
                if (char1 == '\r') {
                    int char2 = _proxyIn.read();
                    if (char2 == '\n') {
                        remaining = (int) readChunkLength();
                    } else {
                        _out.write(char1);
                        _out.write(char2);
                        _bytesTransferred += 2;
                        remaining -= 2;
                        read += 2;
                    }
                } else {
                    _out.write(char1);
                    _bytesTransferred++;
                    remaining--;
                    read++;
                }
            }
            if (timeout != null)
                timeout.resetTimer();
            if (_bytesRemaining >= read) // else chunked?
                _bytesRemaining -= read;
            if (read > 0) {
                for (int i = 0; i < _listeners.size(); i++) 
                    _listeners.get(i).bytesTransferred(
                            _alreadyTransferred, 
                            read, 
                            _bytesTransferred, 
                            _encodingChunked?-1:_bytesRemaining, 
                            _url);
                // This seems necessary to properly resume a partial download into a stream,
                // as nothing else increments _alreadyTransferred, and there's no file length to check.
                // Do this after calling the listeners to keep the total correct
                _alreadyTransferred += read;
            }
        }
            
        if (_out != null)
            _out.close();
        _out = null;
        
        if (_isGzippedResponse) {
            try {
                pusher.join();
            } catch (InterruptedException ie) {}
            pusher = null;
            if (_decompressException != null) {
                // we can't resume from here
                _keepFetching = false;
                throw _decompressException;
            }
        }

        if (_aborted)
            throw new IOException("Timed out reading the HTTP data");
        
        if (timeout != null)
            timeout.cancel();
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Done transferring " + _bytesTransferred + " (ok? " + !_transferFailed + ")");


        if (_transferFailed) {
            // 404, etc - transferFailed is called after all attempts fail, by fetch() above
            if (!_listeners.isEmpty()) {
                String s;
                if (_responseText != null)
                    s = "Attempt failed: " + _responseCode + ' ' + _responseText;
                else
                    s = "Attempt failed: " + _responseCode;
                Exception e = new IOException(s);
                for (int i = 0; i < _listeners.size(); i++)  {
                    _listeners.get(i).attemptFailed(_url, _bytesTransferred, _bytesRemaining, _currentAttempt,
                                                    _numRetries, e);
                }
            }
        } else if ((_minSize > 0) && (_alreadyTransferred < _minSize)) {
            throw new IOException("Bytes transferred " + _alreadyTransferred + " violates minimum of " + _minSize + " bytes");
        } else if ( (_bytesRemaining == -1) || (remaining == 0) ) {
            for (int i = 0; i < _listeners.size(); i++) 
                _listeners.get(i).transferComplete(
                        _alreadyTransferred, 
                        _bytesTransferred, 
                        _encodingChunked?-1:_bytesRemaining, 
                        _url, 
                        _outputFile, 
                        _notModified);
        } else {
            throw new IOException("Disconnection on attempt " + _currentAttempt + " after " + _bytesTransferred);
        }
    }

    protected void readHeaders() throws IOException {
        String key = null;
        StringBuilder buf = new StringBuilder(32);

        boolean read = DataHelper.readLine(_proxyIn, buf);
        if (!read) throw new IOException("Unable to read the first line");
        _responseCode = handleStatus(buf.toString());
        boolean redirect = false;
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("rc: " + _responseCode + " for " + _actualURL);
        boolean rcOk = false;
        switch (_responseCode) {
            case 200: // full
                if (_outputStream != null)
                    _out = _outputStream;
		else
		    _out = new FileOutputStream(_outputFile, false);
                _alreadyTransferred = 0;
                rcOk = true;
                break;
            case 206: // partial
                if (_outputStream != null)
                    _out = _outputStream;
		else
		    _out = new FileOutputStream(_outputFile, true);
                rcOk = true;
                break;
            case 301: // various redirections
            case 302:
            case 303:
            case 307:
                _alreadyTransferred = 0;
                rcOk = true;
                redirect = true;
                break;
            case 304: // not modified
                _bytesRemaining = 0;
                _keepFetching = false;
                _notModified = true;
                return; 
            case 401: // server auth
            case 403: // bad req
            case 404: // not found
            case 409: // bad addr helper
            case 503: // no outproxy
                _transferFailed = true;
                if (_alreadyTransferred > 0 || !_shouldWriteErrorToOutput) {
                    _keepFetching = false;
                    return;
                }
                // output the error data to the stream
                rcOk = true;
                if (_out == null) {
                    if (_outputStream != null)
                        _out = _outputStream;
                    else
                        _out = new FileOutputStream(_outputFile, true);
                }
                break;
            case 407: // proxy auth
                // we will treat this is a redirect if we haven't sent auth yet
                //_redirectLocation will be set to _actualURL below
                _alreadyTransferred = 0;
                if (_authState != null)
                    rcOk = !_authState.authSent;
                else
                    rcOk = false;
                redirect = rcOk;
                _keepFetching = rcOk;
                break;
            case 416: // completed (or range out of reach)
                _bytesRemaining = 0;
                if (_alreadyTransferred > 0 || !_shouldWriteErrorToOutput) {
                    _keepFetching = false;
                    return;
                }
                // output the error data to the stream
                rcOk = true;
                if (_out == null) {
                    if (_outputStream != null)
                        _out = _outputStream;
                    else
                        _out = new FileOutputStream(_outputFile, true);
                }
                break;
            case 504: // gateway timeout
                if (_alreadyTransferred > 0 || (!_shouldWriteErrorToOutput) ||
                    _currentAttempt < _numRetries) {
                    // throw out of doFetch() to fetch() and try again
                    // why throw???
                    throw new IOException("HTTP Proxy timeout");
                }
                // output the error data to the stream
                rcOk = true;
                if (_out == null) {
                    if (_outputStream != null)
                        _out = _outputStream;
                    else
                        _out = new FileOutputStream(_outputFile, true);
                }
                _transferFailed = true;
                break;
            default:
                if (_alreadyTransferred > 0 || !_shouldWriteErrorToOutput) {
                    _keepFetching = false;
                } else {
                    // output the error data to the stream
                    rcOk = true;
                    if (_out == null) {
                        if (_outputStream != null)
                            _out = _outputStream;
                        else
                            _out = new FileOutputStream(_outputFile, true);
                    }
                }
                _transferFailed = true;
        }

        _isGzippedResponse = false;
        // clear out the arguments, as we use the same variables for return values
        _etag = null;
        _lastModified = null;

        buf.setLength(0);
        byte lookahead[] = new byte[3];
        while (true) {
            int cur = _proxyIn.read();
            switch (cur) {
                case -1: 
                    throw new IOException("Headers ended too soon");
                case ':':
                    if (key == null) {
                        key = buf.toString();
                        buf.setLength(0);
                        increment(lookahead, cur);
                        break;
                    } else {
                        buf.append((char)cur);
                        increment(lookahead, cur);
                        break;
                    }
                case '\n':
                case '\r':
                    if (key != null)
                        handle(key, buf.toString());

                    buf.setLength(0);
                    key = null;
                    increment(lookahead, cur);
                    if (isEndOfHeaders(lookahead)) {
                        if (!rcOk)
                            throw new IOException("Invalid HTTP response code: " + _responseCode + ' ' + _responseText);
                        if (_encodingChunked) {
                            _bytesRemaining = readChunkLength();
                        }
                        if (!redirect)
                            _redirectLocation = null;
                        else if (_responseCode == 407)
                            _redirectLocation = _actualURL;
                        return;
                    }
                    break;
                default:
                    buf.append((char)cur);
                    increment(lookahead, cur);
            }
            
            if (buf.length() > 1024)
                throw new IOException("Header line too long: " + buf.toString());
        }
    }
    
    protected long readChunkLength() throws IOException {
        StringBuilder buf = new StringBuilder(8);
        int nl = 0;
        while (true) {
            int cur = _proxyIn.read();
            switch (cur) {
                case -1: 
                    throw new IOException("Chunk ended too soon");
                case '\n':
                case '\r':
                    nl++;
                default:
                    buf.append((char)cur);
            }
            
            if (nl >= 2)
                break;
        }
        
        String len = buf.toString().trim();
        try {
            long bytes = Long.parseLong(len, 16);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Chunked length: " + bytes);
            return bytes;
        } catch (NumberFormatException nfe) {
            throw new IOException("Invalid chunk length [" + len + "]");
        }
    }

    /**
     * parse the first status line and grab the response code.
     * e.g. "HTTP/1.1 206 OK" vs "HTTP/1.1 200 OK" vs 
     * "HTTP/1.1 404 NOT FOUND", etc.  
     *
     * Side effect - stores status text in _responseText
     *
     * @return HTTP response code (200, 206, other)
     */
    private int handleStatus(String line) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Status line: [" + line + "]");
        String[] toks = line.split(" ", 3);
        if (toks.length < 2) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("ERR: status "+  line);
            return -1;
        }
        String rc = toks[1];
        try {
            if (toks.length >= 3)
                _responseText = toks[2].trim();
            else
                _responseText = null;
            return Integer.parseInt(rc); 
        } catch (NumberFormatException nfe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("ERR: status is invalid: " + line, nfe);
            return -1;
        }
    }

    private void handle(String key, String val) {
        key = key.trim();
        val = val.trim();
        for (int i = 0; i < _listeners.size(); i++) 
            _listeners.get(i).headerReceived(_url, _currentAttempt, key, val);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Header line: [" + key + "] = [" + val + "]");
        key = key.toLowerCase(Locale.US);
        if (key.equals("content-length")) {
            try {
                _bytesRemaining = Long.parseLong(val);
            } catch (NumberFormatException nfe) {
                nfe.printStackTrace();
            }
        } else if (key.equals("etag")) {
            _etag = val;
        } else if (key.equals("last-modified")) {
            _lastModified = val;
        } else if (key.equals("transfer-encoding")) {
            _encodingChunked = val.toLowerCase(Locale.US).contains("chunked");
        } else if (key.equals("content-encoding")) {
            // This is kindof a hack, but if we are downloading a gzip file
            // we don't want to transparently gunzip it and save it as a .gz file.
            // A query string will also mess this up
            if ((!_actualURL.endsWith(".gz")) && (!_actualURL.endsWith(".tgz")))
                _isGzippedResponse = val.toLowerCase(Locale.US).contains("gzip");
        } else if (key.equals("content-type")) {
            _contentType=val;
        } else if (key.equals("location")) {
            _redirectLocation=val;
        } else if (key.equals("proxy-authenticate") && _responseCode == 407 && _authState != null && _shouldProxy) {
            _authState.setAuthChallenge(val);
        } else {
            // ignore the rest
        }
    }

    private static void increment(byte[] lookahead, int cur) {
        lookahead[0] = lookahead[1];
        lookahead[1] = lookahead[2];
        lookahead[2] = (byte)cur;
    }
    private static boolean isEndOfHeaders(byte lookahead[]) {
        byte first = lookahead[0];
        byte second = lookahead[1];
        byte third = lookahead[2];
        return (isNL(second) && isNL(third)) || //   \n\n
               (isNL(first) && isNL(third));    // \n\r\n
    }

    /** we ignore any potential \r, since we trim it on write anyway */
    private static final byte NL = '\n';
    private static boolean isNL(byte b) { return (b == NL); }

    /**
     *  @param timeout may be null
     */
    protected void sendRequest(SocketTimeout timeout) throws IOException {
        if (_outputStream != null) {
            // We are reading into a stream supplied by a caller,
            // for which we cannot easily determine how much we've written.
            // Assume that _alreadyTransferred holds the right value
            // (we should never be restarted to work on an old stream).
	} else {
            File outFile = new File(_outputFile);
            if (outFile.exists())
                _alreadyTransferred = outFile.length();
        }

        String req = getRequest();

        if (_proxyIn != null) try { _proxyIn.close(); } catch (IOException ioe) {}
        if (_proxyOut != null) try { _proxyOut.close(); } catch (IOException ioe) {}
        if (_proxy != null) try { _proxy.close(); } catch (IOException ioe) {}

        if (_shouldProxy) {
            _proxy = InternalSocket.getSocket(_proxyHost, _proxyPort);
        } else {
            //try {
                URL url = new URL(_actualURL);
                if ("http".equals(url.getProtocol())) {
                    String host = url.getHost();
                    String hostlc = host.toLowerCase(Locale.US);
                    if (hostlc.endsWith(".i2p"))
                        throw new UnknownHostException("I2P addresses must be proxied");
                    if (hostlc.endsWith(".onion"))
                        throw new UnknownHostException("Tor addresses must be proxied");
                    int port = url.getPort();
                    if (port == -1)
                        port = 80;
                    _proxy = new Socket(host, port);
                } else {
                    throw new MalformedURLException("URL is not supported:" + _actualURL);
                }
            // an MUE is an IOE
            //} catch (MalformedURLException mue) {
            //    throw new IOException("Request URL is invalid");
            //}
        }
        _proxyIn = _proxy.getInputStream();
        if (!(_proxy instanceof InternalSocket))
              _proxyIn = new BufferedInputStream(_proxyIn);
        _proxyOut = _proxy.getOutputStream();
        
        if (timeout != null)
            timeout.setSocket(_proxy);
        
        _proxyOut.write(DataHelper.getUTF8(req));
        _proxyOut.flush();
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Request flushed");
    }
    
    protected String getRequest() throws IOException {
        StringBuilder buf = new StringBuilder(2048);
        boolean post = false;
        if ( (_postData != null) && (_postData.length() > 0) )
            post = true;
        URL url = new URL(_actualURL);
        String host = url.getHost();
        if (host == null || host.length() <= 0)
            throw new MalformedURLException("Bad URL, no host");
        int port = url.getPort();
        String path = url.getPath();
        String query = url.getQuery();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Requesting " + _actualURL);
        // RFC 2616 sec 5.1.2 - full URL if proxied, absolute path only if not proxied
        String urlToSend;
        if (_shouldProxy) {
            urlToSend = _actualURL;
            if ((path == null || path.length()<= 0) &&
                (query == null || query.length()<= 0))
                urlToSend += "/";
        } else {
            urlToSend = path;
            if (urlToSend == null || urlToSend.length()<= 0)
                urlToSend = "/";
            if (query != null)
                urlToSend += '?' + query;
        }
        if (post) {
            buf.append("POST ");
        } else {
            buf.append("GET ");
        }
        buf.append(urlToSend).append(" HTTP/1.1\r\n");
        // RFC 2616 sec 5.1.2 - host + port (NOT authority, which includes userinfo)
        buf.append("Host: ").append(host);
        if (port >= 0)
            buf.append(':').append(port);
        buf.append("\r\n");
        if (_alreadyTransferred > 0) {
            buf.append("Range: bytes=");
            buf.append(_alreadyTransferred);
            buf.append("-\r\n");
        }
        if (!_allowCaching) {
            buf.append("Cache-control: no-cache\r\n" +
                       "Pragma: no-cache\r\n");
        }
        if ((_etag != null) && (_alreadyTransferred <= 0)) {
            buf.append("If-None-Match: ");
            buf.append(_etag);
            buf.append("\r\n");
        }
        if ((_lastModified != null) && (_alreadyTransferred <= 0)) {
            buf.append("If-Modified-Since: ");
            buf.append(_lastModified);
            buf.append("\r\n");
        }
        if (post)
            buf.append("Content-length: ").append(_postData.length()).append("\r\n");
        // This will be replaced if we are going through I2PTunnelHTTPClient
        buf.append("Accept-Encoding: ");
        if ((!_shouldProxy) &&
            // This is kindof a hack, but if we are downloading a gzip file
            // we don't want to transparently gunzip it and save it as a .gz file.
            (!path.endsWith(".gz")) && (!path.endsWith(".tgz")))
            buf.append("gzip");
        buf.append("\r\n");
        boolean uaOverridden = false;
        if (_extraHeaders != null) {
            for (String hdr : _extraHeaders) {
                if (hdr.toLowerCase(Locale.US).startsWith("user-agent: "))
                    uaOverridden = true;
                buf.append(hdr).append("\r\n");
            }
        }
        if(!uaOverridden)
            buf.append("User-Agent: " + USER_AGENT + "\r\n");
        if (_authState != null && _shouldProxy && _authState.authMode != AUTH_MODE.NONE) {
            buf.append("Proxy-Authorization: ");
            String method = post ? "POST" : "GET";
            buf.append(_authState.getAuthHeader(method, urlToSend));
            buf.append("\r\n");
        }
        buf.append("Connection: close\r\n\r\n");
        if (post)
            buf.append(_postData);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Request: [" + buf.toString() + "]");
        return buf.toString();
    }

    public String getETag() {
        return _etag;
    }
    
    public String getLastModified() {
        return _lastModified;
    }
    
    public boolean getNotModified() {
        return _notModified;
    }
    
    public String getContentType() {
        return _contentType;
    }
    
    /**
     *  The server response (200, etc).
     *  @return -1 if invalid, or if the proxy never responded,
     *  or if no proxy was used and the server never responded.
     *  If a non-proxied request partially succeeded (for example a redirect followed
     *  by a fail, or a partial fetch followed by a fail), this will
     *  be the last status code received.
     *  Note that fetch() may return false even if this returns 200.
     *
     *  @since 0.8.8
     */
    public int getStatusCode() {
        return _responseCode;
    }
    
    /**
     *  The server text ("OK", "Not Found",  etc).
     *  Note that the text may contain % encoding.
     *
     *  @return null if invalid, or if the proxy never responded,
     *  or if no proxy was used and the server never responded.
     *  If a non-proxied request partially succeeded (for example a redirect followed
     *  by a fail, or a partial fetch followed by a fail), this will
     *  be the last status code received.
     *  Note that fetch() may return false even if this returns "OK".
     *
      *  @since 0.9.9
     */
    public String getStatusText() {
        return _responseText;
    }

    /**
     *  If called (before calling fetch()),
     *  data from the server or proxy will be written to the
     *  output file or stream even on an error response code (4xx, 5xx, etc).
     *  The error data will only be written if no previous data was written
     *  on an earlier try.
     *  Caller must of course check getStatusCode() or the
     *  fetch() return value.
     *
     *  @since 0.8.8
     */
    public void setWriteErrorToOutput() {
        _shouldWriteErrorToOutput = true;
    }

    /**
     *  Add an extra header to the request.
     *  Must be called before fetch().
     *  Not supported by EepHead.
     *  As of 0.9.10, If name is User-Agent, this will replace the default User-Agent header.
     *  Note that headers may be subsequently modified or removed in the I2PTunnel HTTP Client proxy.
     *
     *  @since 0.8.8
     */
    public void addHeader(String name, String value) {
        if (_extraHeaders == null)
            _extraHeaders = new ArrayList<String>();
        _extraHeaders.add(name + ": " + value);
    }

    /**
     *  Add basic authorization header for the proxy.
     *  Only added if the request is going through a proxy.
     *  Must be called before fetch().
     *
     *  @since 0.8.9
     */
    public void addAuthorization(String userName, String password) {
        if (_shouldProxy) {
            // Could only do this for Basic
            // Now we always wait for the 407, in the hope we can use Digest
            //addHeader("Proxy-Authorization", 
            //          "Basic " + Base64.encode(DataHelper.getUTF8(userName + ':' + password), true));  // true = use standard alphabet
            if (_authState != null)
                throw new IllegalStateException();
            _authState = new AuthState(userName, password);
        }
    }

    /**
     *  Parse the args in an authentication header.
     *
     *  Modified from LoadClientAppsJob.
     *  All keys are mapped to lower case.
     *  Double quotes around values are stripped.
     *  Ref: RFC 2617
     *
     *  Public for I2PTunnelHTTPClientBase; use outside of tree at own risk, subject to change or removal
     *
     *  @param args non-null, starting after "Digest " or "Basic "
     *  @since 0.9.4, moved from I2PTunnelHTTPClientBase in 0.9.12
     */
    public static Map<String, String> parseAuthArgs(String args) {
        Map<String, String> rv = new HashMap<String, String>(8);
        char data[] = args.toCharArray();
        StringBuilder buf = new StringBuilder(32);
        boolean isQuoted = false;
        String key = null;
        for (int i = 0; i < data.length; i++) {
            switch (data[i]) {
                case '\"':
                    if (isQuoted) {
                        // keys never quoted
                        if (key != null) {
                            rv.put(key, buf.toString().trim());
                            key = null;
                        }
                        buf.setLength(0);
                    }
                    isQuoted = !isQuoted;
                    break;

                case ' ':
                case '\r':
                case '\n':
                case '\t':
                case ',':
                    // whitespace - if we're in a quoted section, keep this as part of the quote,
                    // otherwise use it as a delim
                    if (isQuoted) {
                        buf.append(data[i]);
                    } else {
                        if (key != null) {
                            rv.put(key, buf.toString().trim());
                            key = null;
                        }
                        buf.setLength(0);
                    }
                    break;

                case '=':
                    if (isQuoted) {
                        buf.append(data[i]);
                    } else {
                        key = buf.toString().trim().toLowerCase(Locale.US);
                        buf.setLength(0);
                    }
                    break;

                default:
                    buf.append(data[i]);
                    break;
            }
        }
        if (key != null)
            rv.put(key, buf.toString().trim());
        return rv;
    }


    /**
     *  @since 0.9.12
     */
    protected enum AUTH_MODE {NONE, BASIC, DIGEST, UNKNOWN}

    /**
     *  Manage the authentication parameters
     *  Ref: RFC 2617
     *  Supports both Basic and Digest, however i2ptunnel HTTP proxy
     *  has migrated all previous Basic support to Digest.
     *
     *  @since 0.9.12
     */
    protected class AuthState {
        private final String username;
        private final String password;
        // as recvd in 407
        public AUTH_MODE authMode = AUTH_MODE.NONE;
        // as recvd in 407, after the mode string
        private String authChallenge;
        public boolean authSent;
        private int nonceCount;
        private String cnonce;
        // as parsed from authChallenge
        private Map<String, String> args;

        public AuthState(String user, String pw) {
            username = user;
            password = pw;
        }

        /**
         *  May be called multiple times, save the best one
         */
        public void setAuthChallenge(String auth) {
            String authLC = auth.toLowerCase(Locale.US);
            if (authLC.startsWith("basic ")) {
                // better than anything but DIGEST
                if (authMode != AUTH_MODE.DIGEST) {
                    // use standard alphabet
                    authMode = AUTH_MODE.BASIC;
                    authChallenge = auth.substring(6);
                }
            } else if (authLC.startsWith("digest ")) {
                // better than anything
                authMode = AUTH_MODE.DIGEST;
                authChallenge = auth.substring(7);
            } else {
                // better than NONE only
                if (authMode == AUTH_MODE.NONE) {
                    authMode = AUTH_MODE.UNKNOWN;
                    authChallenge = null;
                }
            }
            nonceCount = 0;
            args = null;
        }

        public String getAuthHeader(String method, String uri) throws IOException {
            switch (authMode) {
                case BASIC:
                    authSent = true;
                    // use standard alphabet
                    return "Basic " +
                           Base64.encode(DataHelper.getUTF8(username + ':' + password), true);

                case DIGEST:
                    if (authChallenge == null)
                        throw new IOException("Bad proxy auth response");
                    if (args == null)
                        args = parseAuthArgs(authChallenge);
                    Map<String, String> outArgs = generateAuthArgs(method, uri);
                    if (outArgs == null)
                        throw new IOException("Bad proxy auth response");
                    StringBuilder buf = new StringBuilder(256);
                    buf.append("Digest");
                    for (Map.Entry<String, String> e : outArgs.entrySet()) {
                        buf.append(' ').append(e.getKey()).append('=').append(e.getValue());
                    }
                    authSent = true;
                    return buf.toString();

                default:
                    throw new IOException("Unknown proxy auth type " + authChallenge);
            }
        }

        /**
         *  Generate the digest authentication parameters
         *  Ref: RFC 2617
         *
         *  @since 0.9.12 modified from I2PTunnelHTTPClientBase.validateDigest()
         */
        public Map<String, String> generateAuthArgs(String method, String uri) throws IOException {
            Map<String, String> rv = new HashMap<String, String>(12);
            String realm = args.get("realm");
            String nonce = args.get("nonce");
            String qop = args.get("qop");
            String opaque = args.get("opaque");
            //String algorithm = args.get("algorithm");
            //String stale = args.get("stale");
            if (realm == null || nonce == null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Bad digest request: " + DataHelper.toString(args));
                throw new IOException("Bad auth response");
            }
            rv.put("username", '"' + username + '"');
            rv.put("realm", '"' + realm + '"');
            rv.put("nonce", '"' + nonce + '"');
            rv.put("uri", '"' + uri + '"');
            if (opaque != null)
                rv.put("opaque", '"' + opaque + '"');
            String kdMiddle;
            if ("auth".equals(qop)) {
                rv.put("qop", "\"auth\"");
                if (cnonce == null) {
                    byte[] rand = new byte[5];
                    _context.random().nextBytes(rand);
                    cnonce = Base32.encode(rand);
                }  // else reuse on redirect
                rv.put("cnonce", '"' + cnonce + '"');
                String nc = lc8hex(++nonceCount);
                rv.put("nc", nc);
                kdMiddle = ':' + nc + ':' + cnonce + ':' + qop;
            } else {
                kdMiddle = "";
            }

            // get H(A1)
            String ha1 = PasswordManager.md5Hex(username + ':' + realm + ':' + password);
            // get H(A2)
            String a2 = method + ':' + uri;
            String ha2 = PasswordManager.md5Hex(a2);
            // response
            String kd = ha1 + ':' + nonce + kdMiddle + ':' + ha2;
            rv.put("response", '"' + PasswordManager.md5Hex(kd) + '"');
            return rv;
        }
    }

    /**
     *  @return 8 hex chars, lower case, e.g. 00000001
     *  @since 0.8.10
     */
    private static String lc8hex(int nc) {
        StringBuilder buf = new StringBuilder(8);
        for (int i = 28; i >= 0; i -= 4) {
            int v = (nc >> i) & 0xf;
            if (v < 10)
                buf.append((char) (v + '0'));
            else
                buf.append((char) (v + 'a' - 10));
        }
        return buf.toString();
    }

    /**
     *  Decompressor thread.
     *  Copied / modified from i2ptunnel HTTPResponseOutputStream (GPL)
     *
     *  @since 0.8.10
     */
    protected class Gunzipper implements Runnable {
        private final InputStream _inRaw;
        private final OutputStream _out;
        private static final int CACHE_SIZE = 8*1024;
        private final ByteCache _cache = ByteCache.getInstance(8, CACHE_SIZE);

        public Gunzipper(InputStream in, OutputStream out) {
            _inRaw = in;
            _out = out;
        }

        public void run() {
            ReusableGZIPInputStream in = ReusableGZIPInputStream.acquire();
            ByteArray ba = null;
            long written = 0;
            try {
                // blocking
                in.initialize(_inRaw);
                ba = _cache.acquire();
                byte buf[] = ba.getData();
                int read = -1;
                while ( (read = in.read(buf)) != -1) {
                    _out.write(buf, 0, read);
                }
            } catch (IOException ioe) {
                _decompressException = ioe;
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error decompressing: " + written + ", " + in.getTotalRead() + "/" + in.getTotalExpanded(), ioe);
            } catch (OutOfMemoryError oom) {
                _decompressException = new IOException("OOM in HTTP Decompressor");
                _log.error("OOM in HTTP Decompressor", oom);
            } finally {
                if (_out != null) try { 
                    _out.close(); 
                } catch (IOException ioe) {}
                ReusableGZIPInputStream.release(in);
                if (ba != null)
                    _cache.release(ba);
            }
        }
    }
}
