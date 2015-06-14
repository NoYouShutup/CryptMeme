package net.i2p.router.networkdb.reseed;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.router.RouterClock;
import net.i2p.router.RouterContext;
import net.i2p.router.util.EventLog;
import net.i2p.router.util.RFC822Date;
import net.i2p.util.EepGet;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SSLEepGet;
import net.i2p.util.Translate;

/**
 * Moved from ReseedHandler in routerconsole. See ReseedChecker for additional comments.
 *
 * Handler to deal with reseed requests.  This will reseed from the URLs
 * specified below unless the I2P configuration property "i2p.reseedURL" is
 * set.  It always writes to ./netDb/, so don't mess with that.
 *
 * This is somewhat complicated by trying to log to three places - the console,
 * the router log, and the wrapper log.
 */
public class Reseeder {
    private final RouterContext _context;
    private final Log _log;
    private final ReseedChecker _checker;

    // Reject unreasonably big files, because we download into a ByteArrayOutputStream.
    private static final long MAX_RESEED_RESPONSE_SIZE = 2 * 1024 * 1024;
    /** limit to spend on a single host, to avoid getting stuck on one that is seriously overloaded */
    private static final int MAX_TIME_PER_HOST = 7 * 60 * 1000;

    /**
     *  NOTE - URLs that are in both the standard and SSL groups must use the same hostname and path,
     *         so the reseed process will not download from both.
     *
     *  NOTE - Each seedURL must be a directory, it must end with a '/',
     *         it can't end with 'index.html', for example. Both because of how individual file
     *         URLs are constructed, and because SSLEepGet doesn't follow redirects.
     */
    public static final String DEFAULT_SEED_URL =
              //http://netdb.i2p2.de/" + "," +
              "http://reseed.i2p-projekt.de/" + "," +
             //"http://euve5653.vserver.de/netDb/" +  "," +
              "http://cowpuncher.drollette.com/netdb/" + "," +
              "http://i2p.mooo.com/netDb/" + "," +
              "http://193.150.121.66/netDb/" + "," +
              "http://netdb.i2p2.no/" + "," +
              "http://reseed.info/"  + "," +
              "http://reseed.pkol.de/" + "," +
              "http://uk.reseed.i2p2.no/" + "," +
              "http://i2p-netdb.innovatio.no/" + "," +
              "http://ieb9oopo.mooo.com";
              // Temp disabled since h2ik have been AWOL since 06-03-2013
              //"http://i2p.feared.eu/";

    /** @since 0.8.2 */
    public static final String DEFAULT_SSL_SEED_URL =
              //"https://netdb.i2p2.de/" + "," +
              "https://reseed.i2p-projekt.de/" + "," +
              //"https://euve5653.vserver.de/netDb/" + "," +
              "https://cowpuncher.drollette.com/netdb/" + "," +
              "https://i2p.mooo.com/netDb/" + "," +
              "https://193.150.121.66/netDb/" + "," +
              "https://netdb.i2p2.no/" + "," +
              "https://reseed.info/"  + "," +
              "https://reseed.pkol.de/" + "," +
              "https://uk.reseed.i2p2.no/" + "," +
              "https://i2p-netdb.innovatio.no/" + "," +
              "https://ieb9oopo.mooo.com";
              // Temp disabled since h2ik have been AWOL since 06-03-2013
              //"https://i2p.feared.eu/";

    public static final String PROP_PROXY_HOST = "router.reseedProxyHost";
    public static final String PROP_PROXY_PORT = "router.reseedProxyPort";
    /** @since 0.8.2 */
    public static final String PROP_PROXY_ENABLE = "router.reseedProxyEnable";
    /** @since 0.8.2 */
    public static final String PROP_SSL_DISABLE = "router.reseedSSLDisable";
    /** @since 0.8.2 */
    public static final String PROP_SSL_REQUIRED = "router.reseedSSLRequired";
    /** @since 0.8.3 */
    public static final String PROP_RESEED_URL = "i2p.reseedURL";
    /** all these @since 0.8.9 */
    public static final String PROP_PROXY_USERNAME = "router.reseedProxy.username";
    public static final String PROP_PROXY_PASSWORD = "router.reseedProxy.password";
    public static final String PROP_PROXY_AUTH_ENABLE = "router.reseedProxy.authEnable";
    public static final String PROP_SPROXY_HOST = "router.reseedSSLProxyHost";
    public static final String PROP_SPROXY_PORT = "router.reseedSSLProxyPort";
    public static final String PROP_SPROXY_ENABLE = "router.reseedSSLProxyEnable";
    public static final String PROP_SPROXY_USERNAME = "router.reseedSSLProxy.username";
    public static final String PROP_SPROXY_PASSWORD = "router.reseedSSLProxy.password";
    public static final String PROP_SPROXY_AUTH_ENABLE = "router.reseedSSLProxy.authEnable";
    /** @since 0.9 */
    public static final String PROP_DISABLE = "router.reseedDisable";

    // from PersistentDataStore
    private static final String ROUTERINFO_PREFIX = "routerInfo-";
    private static final String ROUTERINFO_SUFFIX = ".dat";

    Reseeder(RouterContext ctx, ReseedChecker rc) {
        _context = ctx;
        _log = ctx.logManager().getLog(Reseeder.class);
        _checker = rc;
    }

    void requestReseed() {
        ReseedRunner reseedRunner = new ReseedRunner();
        // set to daemon so it doesn't hang a shutdown
        Thread reseed = new I2PAppThread(reseedRunner, "Reseed", true);
        reseed.start();
    }

    private class ReseedRunner implements Runnable, EepGet.StatusListener {
        private boolean _isRunning;
        private String _proxyHost;
        private int _proxyPort;
        private SSLEepGet.SSLState _sslState;
        private int _gotDate;
        private long _attemptStarted;
        private static final int MAX_DATE_SETS = 2;

        public ReseedRunner() {
        }

        /*
         * Do it.
         */
        public void run() {
            try {
                run2();
            } finally {
                _checker.done();
            }
        }

        private void run2() {
            _isRunning = true;
            _checker.setError("");
            _checker.setStatus(_("Reseeding"));
            if (_context.getBooleanProperty(PROP_PROXY_ENABLE)) {
                _proxyHost = _context.getProperty(PROP_PROXY_HOST);
                _proxyPort = _context.getProperty(PROP_PROXY_PORT, -1);
            }
            System.out.println("Reseed start");
            int total = reseed(false);
            if (total >= 50) {
                System.out.println("Reseed complete, " + total + " received");
                _checker.setError("");
            } else if (total > 0) {
                System.out.println("Reseed complete, only " + total + " received");
                _checker.setError(ngettext("Reseed fetched only 1 router.",
                                                        "Reseed fetched only {0} routers.", total));
            } else {
                System.out.println("Reseed failed, check network connection");
                System.out.println(
                     "Ensure that nothing blocks outbound HTTP, check the logs, " +
                     "and if nothing helps, read the FAQ about reseeding manually.");
                _checker.setError(_("Reseed failed.") + ' '  +
                                               _("See {0} for help.",
                                                 "<a target=\"_top\" href=\"/configreseed\">" + _("reseed configuration page") + "</a>"));
            }	
            _isRunning = false;
            _checker.setStatus("");
            _context.router().eventLog().addEvent(EventLog.RESEED, Integer.toString(total));
        }

        // EepGet status listeners
        public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause) {
            // Since readURL() runs an EepGet with 0 retries,
            // we can report errors with attemptFailed() instead of transferFailed().
            // It has the benefit of providing cause of failure, which helps resolve issues.
            if (_log.shouldLog(Log.ERROR)) _log.error("EepGet failed on " + url, cause);
        }
        public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {}
        public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {}
        public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {}

        /**
         *  Use the Date header as a backup time source
         */
        public void headerReceived(String url, int attemptNum, String key, String val) {
            // We do this more than once, because
            // the first SSL handshake may take a while, and it may take the server
            // a while to render the index page.
            if (_gotDate < MAX_DATE_SETS && "date".equals(key.toLowerCase(Locale.US)) && _attemptStarted > 0) {
                long timeRcvd = System.currentTimeMillis();
                long serverTime = RFC822Date.parse822Date(val);
                if (serverTime > 0) {
                    // add 500ms since it's 1-sec resolution, and add half the RTT
                    long now = serverTime + 500 + ((timeRcvd - _attemptStarted) / 2);
                    long offset = now - _context.clock().now();
                    if (_context.clock().getUpdatedSuccessfully()) {
                        // 2nd time better than the first
                        if (_gotDate > 0)
                            _context.clock().setNow(now, RouterClock.DEFAULT_STRATUM - 2);
                        else
                            _context.clock().setNow(now, RouterClock.DEFAULT_STRATUM - 1);
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Reseed adjusting clock by " +
                                      DataHelper.formatDuration(Math.abs(offset)));
                    } else {
                        // No peers or NTP yet, this is probably better than the peer average will be for a while
                        // default stratum - 1, so the peer average is a worse stratum
                        _context.clock().setNow(now, RouterClock.DEFAULT_STRATUM - 1);
                        _log.logAlways(Log.WARN, "NTP failure, Reseed adjusting clock by " +
                                                 DataHelper.formatDuration(Math.abs(offset)));
                    }
                    _gotDate++;
                }
            }
        }

        /** save the start time */
        public void attempting(String url) {
            if (_gotDate < MAX_DATE_SETS)
                _attemptStarted = System.currentTimeMillis();
        }

        // End of EepGet status listeners

        /**
        * Reseed has been requested, so lets go ahead and do it.  Fetch all of
        * the routerInfo-*.dat files from the specified URL (or the default) and
        * save them into this router's netDb dir.
        *
        * - If list specified in the properties, use it randomly, without regard to http/https
        * - If SSL not disabled, use the https randomly then
        *   the http randomly
        * - Otherwise just the http randomly.
        *
        * @param echoStatus apparently always false
        * @return count of routerinfos successfully fetched
        */
        private int reseed(boolean echoStatus) {
            List<String> URLList = new ArrayList<String>();
            String URLs = _context.getProperty(PROP_RESEED_URL);
            boolean defaulted = URLs == null;
            boolean SSLDisable = _context.getBooleanProperty(PROP_SSL_DISABLE);
            boolean SSLRequired = _context.getBooleanProperty(PROP_SSL_REQUIRED);
            if (defaulted) {
                if (SSLDisable)
                    URLs = DEFAULT_SEED_URL;
                else
                    URLs = DEFAULT_SSL_SEED_URL;
            }
            StringTokenizer tok = new StringTokenizer(URLs, " ,");
            while (tok.hasMoreTokens())
                URLList.add(tok.nextToken().trim());
            Collections.shuffle(URLList, _context.random());
            if (defaulted && !SSLDisable && !SSLRequired) {
                // put the non-SSL at the end of the SSL
                List<String> URLList2 = new ArrayList<String>();
                tok = new StringTokenizer(DEFAULT_SEED_URL, " ,");
                while (tok.hasMoreTokens())
                    URLList2.add(tok.nextToken().trim());
                Collections.shuffle(URLList2, _context.random());
                URLList.addAll(URLList2);
            }
            int total = 0;
            for (int i = 0; i < URLList.size() && _isRunning; i++) {
                String url = URLList.get(i);
                int dl = reseedOne(url, echoStatus);
                if (dl > 0) {
                    total += dl;
                    // Don't go on to the next URL if we have enough
                    if (total >= 100)
                        break;
                    // remove alternate version if we haven't tried it yet
                    String alt;
                    if (url.startsWith("http://"))
                        alt = url.replace("http://", "https://");
                    else
                        alt = url.replace("https://", "http://");
                    int idx = URLList.indexOf(alt);
                    if (idx > i)
                        URLList.remove(i);
                }
            }
            return total;
        }

        /**
         *  Fetch a directory listing and then up to 200 routerInfo files in the listing.
         *  The listing must contain (exactly) strings that match:
         *           href="routerInfo-{hash}.dat">
         *  OR
         *           HREF="routerInfo-{hash}.dat">
         * and then it fetches the files
         *           {seedURL}routerInfo-{hash}.dat
         * after appending a '/' to seedURL if it doesn't have one.
         * Essentially this means that the seedURL must be a directory, it
         * can't end with 'index.html', for example.
         *
         * Jetty directory listings are not compatible, as they look like
         * HREF="/full/path/to/routerInfo-...
         *
         * We update the status here.
         *
         * @param echoStatus apparently always false
         * @return count of routerinfos successfully fetched
         **/
        private int reseedOne(String seedURL, boolean echoStatus) {
            try {
                // Don't use context clock as we may be adjusting the time
                final long timeLimit = System.currentTimeMillis() + MAX_TIME_PER_HOST;
                _checker.setStatus(_("Reseeding: fetching seed URL."));
                System.err.println("Reseeding from " + seedURL);
                URL dir = new URL(seedURL);
                byte contentRaw[] = readURL(dir);
                if (contentRaw == null) {
                    // Logging deprecated here since attemptFailed() provides better info
                    _log.warn("Failed reading seed URL: " + seedURL);
                    System.err.println("Reseed got no router infos from " + seedURL);
                    return 0;
                }
                String content = new String(contentRaw);
                // This isn't really URLs, but Base64 hashes
                // but they may include % encoding
                Set<String> urls = new HashSet<String>(1024);
                Hash ourHash = _context.routerHash();
                String ourB64 = ourHash != null ? ourHash.toBase64() : null;
                int cur = 0;
                int total = 0;
                while (total++ < 1000) {
                    int start = content.indexOf("href=\"" + ROUTERINFO_PREFIX, cur);
                    if (start < 0) {
                        start = content.indexOf("HREF=\"" + ROUTERINFO_PREFIX, cur);
                        if (start < 0)
                            break;
                    }

                    int end = content.indexOf(ROUTERINFO_SUFFIX + "\">", start);
                    if (end < 0)
                        break;
                    if (start - end > 200) {  // 17 + 3*44 for % encoding + just to be sure
                        cur = end + 1;
                        continue;
                    }
                    String name = content.substring(start + ("href=\"" + ROUTERINFO_PREFIX).length(), end);
                    // never load our own RI
                    if (ourB64 == null || !name.contains(ourB64)) {
                        urls.add(name);
                    } else {
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Skipping our own RI");
                    }
                    cur = end + 1;
                }
                if (total <= 0) {
                    _log.warn("Read " + contentRaw.length + " bytes from seed " + seedURL + ", but found no routerInfo URLs.");
                    System.err.println("Reseed got no router infos from " + seedURL);
                    return 0;
                }

                List<String> urlList = new ArrayList<String>(urls);
                Collections.shuffle(urlList, _context.random());
                int fetched = 0;
                int errors = 0;
                // 200 max from one URL
                for (Iterator<String> iter = urlList.iterator();
                     iter.hasNext() && fetched < 200 && System.currentTimeMillis() < timeLimit; ) {
                    try {
                        _checker.setStatus(
                            _("Reseeding: fetching router info from seed URL ({0} successful, {1} errors).", fetched, errors));

                        if (!fetchSeed(seedURL, iter.next()))
                            continue;
                        fetched++;
                        if (echoStatus) {
                            System.out.print(".");
                            if (fetched % 60 == 0)
                                System.out.println();
                        }
                    } catch (Exception e) {
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Failed fetch", e);
                        errors++;
                    }
                    // Give up on this host after lots of errors, or 10 with only 0 or 1 good
                    if (errors >= 50 || (errors >= 10 && fetched <= 1))
                        break;
                }
                System.err.println("Reseed got " + fetched + " router infos from " + seedURL + " with " + errors + " errors");

                if (fetched > 0)
                    _context.netDb().rescan();
                return fetched;
            } catch (Throwable t) {
                _log.warn("Error reseeding", t);
                System.err.println("Reseed got no router infos from " + seedURL);
                return 0;
            }
        }
    
        /**
         *  Always throws an exception if something fails.
         *  We do NOT validate the received data here - that is done in PersistentDataStore
         *
         *  @param peer The Base64 hash, may include % encoding. It is decoded and validated here.
         *  @return true on success, false if skipped
         */
        private boolean fetchSeed(String seedURL, String peer) throws IOException, URISyntaxException {
            // Use URI to do % decoding of the B64 hash (some servers escape ~ and =)
            // Also do basic hash validation. This prevents stuff like
            // .. or / in the file name
            URI uri = new URI(peer);
            String b64 = uri.getPath();
            if (b64 == null)
                throw new IOException("bad hash " + peer);
            byte[] hash = Base64.decode(b64);
            if (hash == null || hash.length != Hash.HASH_LENGTH)
                throw new IOException("bad hash " + peer);
            Hash ourHash = _context.routerHash();
            if (ourHash != null && DataHelper.eq(hash, ourHash.getData()))
                return false;

            URL url = new URL(seedURL + (seedURL.endsWith("/") ? "" : "/") + ROUTERINFO_PREFIX + peer + ROUTERINFO_SUFFIX);

            byte data[] = readURL(url);
            if (data == null || data.length <= 0)
                throw new IOException("Failed fetch of " + url);
            return writeSeed(b64, data);
        }

        /** @return null on error */
        private byte[] readURL(URL url) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4*1024);

            EepGet get;
            boolean ssl = url.toString().startsWith("https");
            if (ssl) {
                SSLEepGet sslget;
                // TODO SSL PROXY
                if (_sslState == null) {
                    sslget = new SSLEepGet(I2PAppContext.getGlobalContext(), baos, url.toString());
                    // save state for next time
                    _sslState = sslget.getSSLState();
                } else {
                    sslget = new SSLEepGet(I2PAppContext.getGlobalContext(), baos, url.toString(), _sslState);
                }
                get = sslget;
                // TODO SSL PROXY AUTH
            } else {
                // Do a (probably) non-proxied eepget into our ByteArrayOutputStream with 0 retries
                boolean shouldProxy = _proxyHost != null && _proxyHost.length() > 0 && _proxyPort > 0;
                get = new EepGet(I2PAppContext.getGlobalContext(), shouldProxy, _proxyHost, _proxyPort, 0, 0, MAX_RESEED_RESPONSE_SIZE,
                                 null, baos, url.toString(), false, null, null);
                if (shouldProxy && _context.getBooleanProperty(PROP_PROXY_AUTH_ENABLE)) {
                    String user = _context.getProperty(PROP_PROXY_USERNAME);
                    String pass = _context.getProperty(PROP_PROXY_PASSWORD);
                    if (user != null && user.length() > 0 &&
                        pass != null && pass.length() > 0)
                        get.addAuthorization(user, pass);
                }
            }
            get.addStatusListener(ReseedRunner.this);
            if (get.fetch())
                return baos.toByteArray();
            return null;
        }
    
        /**
         *  @param name valid Base64 hash
         *  @return true on success, false if skipped
         */
        private boolean writeSeed(String name, byte data[]) throws IOException {
            String dirName = "netDb"; // _context.getProperty("router.networkDatabase.dbDir", "netDb");
            File netDbDir = new SecureDirectory(_context.getRouterDir(), dirName);
            if (!netDbDir.exists()) {
                netDbDir.mkdirs();
            }
            File file = new File(netDbDir, ROUTERINFO_PREFIX + name + ROUTERINFO_SUFFIX);
            // don't overwrite recent file
            // TODO: even better would be to compare to last-mod date from eepget
            if (file.exists() && file.lastModified() > _context.clock().now() - 60*60*1000) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Skipping RI, ours is recent: " + file);
                return false;
            }
            FileOutputStream fos = null;
            try {
                fos = new SecureFileOutputStream(file);
                fos.write(data);
                if (_log.shouldLog(Log.INFO))
                    _log.info("Saved RI (" + data.length + " bytes) to " + file);
            } finally {
                try {
                    if (fos != null) fos.close();
                } catch (IOException ioe) {}
            }
            return true;
        }

    }

    private static final String BUNDLE_NAME = "net.i2p.router.web.messages";

    /** translate */
    private String _(String key) {
        return Translate.getString(key, _context, BUNDLE_NAME);
    }

    /** translate */
    private String _(String s, Object o) {
        return Translate.getString(s, o, _context, BUNDLE_NAME);
    }

    /** translate */
    private String _(String s, Object o, Object o2) {
        return Translate.getString(s, o, o2, _context, BUNDLE_NAME);
    }

    /** translate */
    private String ngettext(String s, String p, int n) {
        return Translate.getString(n, s, p, _context, BUNDLE_NAME);
    }

/******
    public static void main(String args[]) {
        if ( (args != null) && (args.length == 1) && (!Boolean.parseBoolean(args[0])) ) {
            System.out.println("Not reseeding, as requested");
            return; // not reseeding on request
        }
        System.out.println("Reseeding");
        Reseeder reseedHandler = new Reseeder();
        reseedHandler.requestReseed();
    }
******/
}
