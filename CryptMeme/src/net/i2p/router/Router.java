package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

import gnu.getopt.Getopt;

import net.i2p.data.Certificate;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.RouterInfo;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.router.message.GarlicMessageHandler;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.startup.StartupJob;
import net.i2p.router.startup.WorkingDir;
import net.i2p.router.tasks.*;
import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.router.util.EventLog;
import net.i2p.stat.RateStat;
import net.i2p.stat.StatManager;
import net.i2p.util.ByteCache;
import net.i2p.util.FileUtil;
import net.i2p.util.FortunaRandomSource;
import net.i2p.util.I2PAppThread;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SimpleByteCache;
import net.i2p.util.SystemVersion;
import net.i2p.util.Translate;

/**
 * Main driver for the router.
 *
 */
public class Router implements RouterClock.ClockShiftListener {
    private Log _log;
    private final RouterContext _context;
    private final Map<String, String> _config;
    /** full path */
    private String _configFilename;
    private RouterInfo _routerInfo;
    public final Object routerInfoFileLock = new Object();
    private final Object _configFileLock = new Object();
    private long _started;
    private boolean _higherVersionSeen;
    //private SessionKeyPersistenceHelper _sessionKeyPersistenceHelper;
    private boolean _killVMOnEnd;
    private volatile boolean _isAlive;
    private int _gracefulExitCode;
    private I2PThread.OOMEventListener _oomListener;
    private ShutdownHook _shutdownHook;
    /** non-cancellable shutdown has begun */
    private volatile boolean _shutdownInProgress;
    private I2PThread _gracefulShutdownDetector;
    private RouterWatchdog _watchdog;
    private Thread _watchdogThread;
    private final EventLog _eventLog;
    
    public final static String PROP_CONFIG_FILE = "router.configLocation";
    
    /** let clocks be off by 1 minute */
    public final static long CLOCK_FUDGE_FACTOR = 1*60*1000; 

    /** used to differentiate routerInfo files on different networks */
    public static final int NETWORK_ID = 2;
    
    /** coalesce stats this often - should be a little less than one minute, so the graphs get updated */
    public static final int COALESCE_TIME = 50*1000;

    /** this puts an 'H' in your routerInfo **/
    public final static String PROP_HIDDEN = "router.hiddenMode";
    /** this does not put an 'H' in your routerInfo **/
    public final static String PROP_HIDDEN_HIDDEN = "router.isHidden";
    public final static String PROP_DYNAMIC_KEYS = "router.dynamicKeys";
    public final static String PROP_INFO_FILENAME = "router.info.location";
    public final static String PROP_INFO_FILENAME_DEFAULT = "router.info";
    public final static String PROP_KEYS_FILENAME = "router.keys.location";
    public final static String PROP_KEYS_FILENAME_DEFAULT = "router.keys";
    public final static String PROP_SHUTDOWN_IN_PROGRESS = "__shutdownInProgress";
    public final static String DNS_CACHE_TIME = "" + (5*60);
    private static final String EVENTLOG = "eventlog.txt";
        
    private static final String originalTimeZoneID;
    static {
        // grumble about sun's java caching DNS entries *forever* by default
        // so lets just keep 'em for a short time
        System.setProperty("sun.net.inetaddr.ttl", DNS_CACHE_TIME);
        System.setProperty("sun.net.inetaddr.negative.ttl", DNS_CACHE_TIME);
        System.setProperty("networkaddress.cache.ttl", DNS_CACHE_TIME);
        System.setProperty("networkaddress.cache.negative.ttl", DNS_CACHE_TIME);
        System.setProperty("http.agent", "I2P");
        // (no need for keepalive)
        System.setProperty("http.keepAlive", "false");
        // Save it for LogManager
        originalTimeZoneID = TimeZone.getDefault().getID();
        System.setProperty("user.timezone", "GMT");
        // just in case, lets make it explicit...
        TimeZone.setDefault(TimeZone.getTimeZone("GMT"));
        // https://www.kb.cert.org/vuls/id/402580
        // http://docs.codehaus.org/display/JETTY/SystemProperties
        // Fixed in Jetty 5.1.15 but we are running 5.1.12
        // The default is true, unfortunately it was previously
        // set to false in wrapper.config thru 0.7.10 so we must set it back here.
        // Not in Jetty 7
        //System.setProperty("org.mortbay.util.FileResource.checkAliases", "true");
    }
    
    /**
     *  Instantiation only. Starts no threads. Does not install updates.
     *  RouterContext is created but not initialized.
     *  You must call runRouter() after any constructor to start things up.
     */
    public Router() { this(null, null); }

    public Router(Properties envProps) { this(null, envProps); }

    public Router(String configFilename) { this(configFilename, null); }

    public Router(String configFilename, Properties envProps) {
        _gracefulExitCode = -1;
        _config = new ConcurrentHashMap<String, String>();

        if (configFilename == null) {
            if (envProps != null) {
                _configFilename = envProps.getProperty(PROP_CONFIG_FILE);
            }
            if (_configFilename == null)
                _configFilename = System.getProperty(PROP_CONFIG_FILE, "router.config");
        } else {
            _configFilename = configFilename;
        }
                    
        // we need the user directory figured out by now, so figure it out here rather than
        // in the RouterContext() constructor.
        //
        // We have not read the config file yet. Therefore the base and config locations
        // are determined solely by properties (first envProps then System), for the purposes
        // of initializing the user's config directory if it did not exist.
        // If the base dir and/or config dir are set in the config file,
        // they wil be used after the initialization of the (possibly different) dirs
        // determined by WorkingDir.
        // So for now, it doesn't make much sense to set the base or config dirs in the config file -
        // use properties instead. If for some reason, distros need this, we can revisit it.
        //
        // Then add it to envProps (but not _config, we don't want it in the router.config file)
        // where it will then be available to all via _context.dir()
        //
        // This call also migrates all files to the new working directory,
        // including router.config
        //

        // Do we copy all the data files to the new directory? default false
        String migrate = System.getProperty("i2p.dir.migrate");
        boolean migrateFiles = Boolean.parseBoolean(migrate);
        String userDir = WorkingDir.getWorkingDir(envProps, migrateFiles);

        // Use the router.config file specified in the router.configLocation property
        // (default "router.config"),
        // if it is an abolute path, otherwise look in the userDir returned by getWorkingDir
        // replace relative path with absolute
        File cf = new File(_configFilename);
        if (!cf.isAbsolute()) {
            cf = new File(userDir, _configFilename);
            _configFilename = cf.getAbsolutePath();
        }

        readConfig();

        if (envProps == null)
            envProps = new Properties();
        envProps.putAll(_config);

        // This doesn't work, guess it has to be in the static block above?
        // if (Boolean.parseBoolean(envProps.getProperty("router.disableIPv6")))
        //    System.setProperty("java.net.preferIPv4Stack", "true");

        if (envProps.getProperty("i2p.dir.config") == null)
            envProps.setProperty("i2p.dir.config", userDir);
        // Save this in the context for the logger and apps that need it
        envProps.setProperty("i2p.systemTimeZone", originalTimeZoneID);

        // Make darn sure we don't have a leftover I2PAppContext in the same JVM
        // e.g. on Android - see finalShutdown() also
        List<RouterContext> contexts = RouterContext.getContexts();
        if (contexts.isEmpty()) {
            RouterContext.killGlobalContext();
        } else if (SystemVersion.isAndroid()) {
            System.err.println("Warning: Killing " + contexts.size() + " other routers in this JVM");
            contexts.clear();
            RouterContext.killGlobalContext();
        } else {
            System.err.println("Warning: " + contexts.size() + " other routers in this JVM");
        }

        // The important thing that happens here is the directory paths are set and created
        // i2p.dir.router defaults to i2p.dir.config
        // i2p.dir.app defaults to i2p.dir.router
        // i2p.dir.log defaults to i2p.dir.router
        // i2p.dir.pid defaults to i2p.dir.router
        // i2p.dir.base defaults to user.dir == $CWD
        _context = new RouterContext(this, envProps);
        _eventLog = new EventLog(_context, new File(_context.getRouterDir(), EVENTLOG));

        // This is here so that we can get the directory location from the context
        // for the ping file
        // Check for other router but do not start a thread yet so the update doesn't cause
        // a NCDFE
        for (int i = 0; i < 14; i++) {
            // Wrapper can start us up too quickly after a crash, the ping file
            // may still be less than LIVELINESS_DELAY (60s) old.
            // So wait at least 60s to be sure.
            if (isOnlyRouterRunning()) {
                if (i > 0)
                    System.err.println("INFO: No, there wasn't another router already running. Proceeding with startup.");
                break;
            }
            if (i < 13) {
                if (i == 0)
                    System.err.println("WARN: There may be another router already running. Waiting a while to be sure...");
                // yes this is ugly to sleep in the constructor.
                try { Thread.sleep(5000); } catch (InterruptedException ie) {}
            } else {
                _eventLog.addEvent(EventLog.ABORTED, "Another router running");
                System.err.println("ERROR: There appears to be another router already running!");
                System.err.println("       Please make sure to shut down old instances before starting up");
                System.err.println("       a new one.  If you are positive that no other instance is running,");
                System.err.println("       please delete the file " + getPingFile().getAbsolutePath());
                System.exit(-1);
            }
        }

        if (_config.get("router.firstVersion") == null) {
            // These may be useful someday. First added in 0.8.2
            _config.put("router.firstVersion", RouterVersion.VERSION);
            String now = Long.toString(System.currentTimeMillis());
            _config.put("router.firstInstalled", now);
            _config.put("router.updateLastInstalled", now);
            // First added in 0.8.13
            _config.put("router.previousVersion", RouterVersion.VERSION);
            saveConfig();
        }
        // *********  Start no threads before here ********* //
    }

    /**
     *  Initializes the RouterContext.
     *  Starts some threads. Does not install updates.
     *  All this was in the constructor.
     *  @since 0.8.12
     */
    private void startupStuff() {
        // *********  Start no threads before here ********* //
        //
        // NOW we can start the ping file thread.
        if (!SystemVersion.isAndroid())
            beginMarkingLiveliness();

        // Apps may use this as an easy way to determine if they are in the router JVM
        // But context.isRouterContext() is even easier...
        // Both of these as of 0.7.9
        System.setProperty("router.version", RouterVersion.VERSION);

        // NOW we start all the activity
        _context.initAll();

        // Set wrapper.log permissions.
        // Just hope this is the right location, we don't know for sure,
        // but this is the same method used in LogsHelper and we have no complaints.
        // (we could look for the wrapper.config file and parse it I guess...)
        // If we don't have a wrapper, RouterLaunch does this for us.
        if (_context.hasWrapper()) {
            File f = new File(System.getProperty("java.io.tmpdir"), "wrapper.log");
            if (!f.exists())
                f = new File(_context.getBaseDir(), "wrapper.log");
            if (f.exists())
                SecureFileOutputStream.setPerms(f);
        }

        _routerInfo = null;
        _higherVersionSeen = false;
        _log = _context.logManager().getLog(Router.class);
        _log.info("New router created with config file " + _configFilename);
        //_sessionKeyPersistenceHelper = new SessionKeyPersistenceHelper(_context);
        _killVMOnEnd = true;
        _oomListener = new OOMListener(_context);

        _shutdownHook = new ShutdownHook(_context);
        _gracefulShutdownDetector = new I2PAppThread(new GracefulShutdown(_context), "Graceful shutdown hook", true);
        _gracefulShutdownDetector.setPriority(Thread.NORM_PRIORITY + 1);
        _gracefulShutdownDetector.start();
        
        _watchdog = new RouterWatchdog(_context);
        _watchdogThread = new I2PAppThread(_watchdog, "RouterWatchdog", true);
        _watchdogThread.setPriority(Thread.NORM_PRIORITY + 1);
        _watchdogThread.start();
        
    }
    
    /** @since 0.8.8 */
    public static final void clearCaches() {
        ByteCache.clearAll();
        SimpleByteCache.clearAll();
        Destination.clearCache();
        Translate.clearCache();
    }

    /**
     * Configure the router to kill the JVM when the router shuts down, as well
     * as whether to explicitly halt the JVM during the hard fail process.
     *
     */
    public void setKillVMOnEnd(boolean shouldDie) { _killVMOnEnd = shouldDie; }

    /** @deprecated unused */
    public boolean getKillVMOnEnd() { return _killVMOnEnd; }
    
    /** @return absolute path */
    public String getConfigFilename() { return _configFilename; }

    /** @deprecated unused */
    public void setConfigFilename(String filename) { _configFilename = filename; }
    
    public String getConfigSetting(String name) { 
            return _config.get(name); 
    }

    /**
     *  Warning, race between here and saveConfig(),
     *  saveConfig(String name, String value) or saveConfig(Map toAdd, Set toRemove) is recommended.
     *
     *  @since 0.8.13
     */
    public void setConfigSetting(String name, String value) { 
            _config.put(name, value); 
    }

    /**
     *  Warning, race between here and saveConfig(),
     *  saveConfig(String name, String value) or saveConfig(Map toAdd, Set toRemove) is recommended.
     *
     *  @since 0.8.13
     */
    public void removeConfigSetting(String name) { 
            _config.remove(name); 
            // remove the backing default also
            _context.removeProperty(name);
    }

    /**
     *  @return unmodifiable Set, unsorted
     */
    public Set<String> getConfigSettings() { 
        return Collections.unmodifiableSet(_config.keySet()); 
    }

    /**
     *  @return unmodifiable Map, unsorted
     */
    public Map<String, String> getConfigMap() { 
        return Collections.unmodifiableMap(_config); 
    }
    
    /**
     *  Warning, may be null if called very early
     */
    public RouterInfo getRouterInfo() { return _routerInfo; }

    /**
     *  Caller must ensure info is valid - no validation done here
     */
    public void setRouterInfo(RouterInfo info) { 
        _routerInfo = info; 
        if (_log.shouldLog(Log.INFO))
            _log.info("setRouterInfo() : " + info, new Exception("I did it"));
        if (info != null)
            _context.jobQueue().addJob(new PersistRouterInfoJob(_context));
    }

    /**
     * True if the router has tried to communicate with another router who is running a higher
     * incompatible protocol version.  
     * @deprecated unused
     */
    public boolean getHigherVersionSeen() { return _higherVersionSeen; }

    /**
     * True if the router has tried to communicate with another router who is running a higher
     * incompatible protocol version.  
     * @deprecated unused
     */
    public void setHigherVersionSeen(boolean seen) { _higherVersionSeen = seen; }
    
    public long getWhenStarted() { return _started; }

    /** wall clock uptime */
    public long getUptime() { 
        if ( (_context == null) || (_context.clock() == null) ) return 1; // racing on startup
        return Math.max(1, _context.clock().now() - _context.clock().getOffset() - _started);
    }
    
    public RouterContext getContext() { return _context; }
    
    /**
     *  This must be called after instantiation.
     *  Starts the threads. Does not install updates.
     *  Most users will just call main() instead.
     *  @since public as of 0.9 for Android and other embedded uses
     */
    public synchronized void runRouter() {
        if (_isAlive)
            throw new IllegalStateException();
        String last = _config.get("router.previousFullVersion");
        if (last != null) {
            _eventLog.addEvent(EventLog.UPDATED, "from " + last + " to " + RouterVersion.FULL_VERSION);
            saveConfig("router.previousFullVersion", null);
        }
        _eventLog.addEvent(EventLog.STARTED, RouterVersion.FULL_VERSION);
        startupStuff();
        _isAlive = true;
        _started = _context.clock().now();
        try {
            Runtime.getRuntime().addShutdownHook(_shutdownHook);
        } catch (IllegalStateException ise) {}
        I2PThread.addOOMEventListener(_oomListener);
        
        _context.keyManager().startup();
        
        setupHandlers();
        //if (ALLOW_DYNAMIC_KEYS) {
        //    if ("true".equalsIgnoreCase(_context.getProperty(Router.PROP_HIDDEN, "false")))
        //        killKeys();
        //}

        _context.messageValidator().startup();
        _context.tunnelDispatcher().startup();
        _context.inNetMessagePool().startup();
        startupQueue();
        //_context.jobQueue().addJob(new CoalesceStatsJob(_context));
        _context.simpleScheduler().addPeriodicEvent(new CoalesceStatsEvent(_context), COALESCE_TIME);
        _context.jobQueue().addJob(new UpdateRoutingKeyModifierJob(_context));
        warmupCrypto();
        //_sessionKeyPersistenceHelper.startup();
        //_context.adminManager().startup();
        _context.blocklist().startup();
        
        // let the timestamper get us sync'ed
        // this will block for quite a while on a disconnected machine
        long before = System.currentTimeMillis();
        _context.clock().getTimestamper().waitForInitialization();
        long waited = System.currentTimeMillis() - before;
        if (_log.shouldLog(Log.INFO))
            _log.info("Waited " + waited + "ms to initialize");

        _context.jobQueue().addJob(new StartupJob(_context));
    }
    
    /**
     * This updates the config with all settings found in the file.
     * It does not clear the config first, so settings not found in
     * the file will remain in the config.
     *
     * This is synchronized with saveConfig()
     */
    public void readConfig() {
        synchronized(_configFileLock) {
            String f = getConfigFilename();
            Properties config = getConfig(_context, f);
            // to avoid compiler errror
            Map foo = _config;
            foo.putAll(config);
        }
    }
    
    /**
     *  this does not use ctx.getConfigDir(), must provide a full path in filename
     *  Caller must synchronize
     *
     *  @param ctx will be null at startup when called from constructor
     */
    private static Properties getConfig(RouterContext ctx, String filename) {
        Log log = null;
        if (ctx != null) {
            log = ctx.logManager().getLog(Router.class);
            if (log.shouldLog(Log.DEBUG))
                log.debug("Config file: " + filename, new Exception("location"));
        }
        Properties props = new Properties();
        try {
            File f = new File(filename);
            if (f.canRead()) {
                DataHelper.loadProps(props, f);
                // dont be a wanker
                props.remove(PROP_SHUTDOWN_IN_PROGRESS);
            } else {
                if (log != null)
                    log.warn("Configuration file " + filename + " does not exist");
                // normal not to exist at first install
                //else
                //    System.err.println("WARNING: Configuration file " + filename + " does not exist");
            }
        } catch (Exception ioe) {
            if (log != null)
                log.error("Error loading the router configuration from " + filename, ioe);
            else
                System.err.println("Error loading the router configuration from " + filename + ": " + ioe);
        }
        return props;
    }
    
    public boolean isAlive() { return _isAlive; }
    
    /**
     * Rebuild and republish our routerInfo since something significant 
     * has changed.
     */
    public void rebuildRouterInfo() { rebuildRouterInfo(false); }

    public void rebuildRouterInfo(boolean blockingRebuild) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Rebuilding new routerInfo");
        
        RouterInfo ri = null;
        if (_routerInfo != null)
            ri = new RouterInfo(_routerInfo);
        else
            ri = new RouterInfo();
        
        try {
            ri.setPublished(_context.clock().now());
            Properties stats = _context.statPublisher().publishStatistics();
            stats.setProperty(RouterInfo.PROP_NETWORK_ID, NETWORK_ID+"");
            
            ri.setOptions(stats);
            ri.setAddresses(_context.commSystem().createAddresses());

            addCapabilities(ri);
            SigningPrivateKey key = _context.keyManager().getSigningPrivateKey();
            if (key == null) {
                _log.log(Log.CRIT, "Internal error - signing private key not known?  wtf");
                return;
            }
            ri.sign(key);
            setRouterInfo(ri);
            if (!ri.isValid())
                throw new DataFormatException("Our RouterInfo has a bad signature");
            Republish r = new Republish(_context);
            if (blockingRebuild)
                r.timeReached();
            else
                _context.simpleScheduler().addEvent(r, 0);
        } catch (DataFormatException dfe) {
            _log.log(Log.CRIT, "Internal error - unable to sign our own address?!", dfe);
        }
    }

    // publicize our ballpark capacity
    public static final char CAPABILITY_BW12 = 'K';
    public static final char CAPABILITY_BW32 = 'L';
    public static final char CAPABILITY_BW64 = 'M';
    public static final char CAPABILITY_BW128 = 'N';
    public static final char CAPABILITY_BW256 = 'O';
    public static final String PROP_FORCE_BWCLASS = "router.forceBandwidthClass";
    
    public static final char CAPABILITY_REACHABLE = 'R';
    public static final char CAPABILITY_UNREACHABLE = 'U';
    public static final String PROP_FORCE_UNREACHABLE = "router.forceUnreachable";

    /** @deprecated unused */
    public static final char CAPABILITY_NEW_TUNNEL = 'T';
    
    public void addCapabilities(RouterInfo ri) {
        int bwLim = Math.min(_context.bandwidthLimiter().getInboundKBytesPerSecond(),
                             _context.bandwidthLimiter().getOutboundKBytesPerSecond());
        bwLim = (int)(bwLim * getSharePercentage());
        if (_log.shouldLog(Log.INFO))
            _log.info("Adding capabilities w/ bw limit @ " + bwLim, new Exception("caps"));
        
        String force = _context.getProperty(PROP_FORCE_BWCLASS);
        if (force != null && force.length() > 0) {
            ri.addCapability(force.charAt(0));
        } else if (bwLim < 12) {
            ri.addCapability(CAPABILITY_BW12);
        } else if (bwLim <= 32) {
            ri.addCapability(CAPABILITY_BW32);
        } else if (bwLim <= 64) {
            ri.addCapability(CAPABILITY_BW64);
        } else if (bwLim <= 128) {
            ri.addCapability(CAPABILITY_BW128);
        } else { // ok, more than 128KBps... aka "lots"
            ri.addCapability(CAPABILITY_BW256);
        }
        
        // if prop set to true, don't tell people we are ff even if we are
        if (_context.netDb().floodfillEnabled() &&
            !_context.getBooleanProperty("router.hideFloodfillParticipant"))
            ri.addCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL);
        
        if(_context.getBooleanProperty(PROP_HIDDEN))
            ri.addCapability(RouterInfo.CAPABILITY_HIDDEN);
        
        if (_context.getBooleanProperty(PROP_FORCE_UNREACHABLE)) {
            ri.addCapability(CAPABILITY_UNREACHABLE);
            return;
        }
        switch (_context.commSystem().getReachabilityStatus()) {
            case CommSystemFacade.STATUS_OK:
                ri.addCapability(CAPABILITY_REACHABLE);
                break;
            case CommSystemFacade.STATUS_DIFFERENT:
            case CommSystemFacade.STATUS_REJECT_UNSOLICITED:
                ri.addCapability(CAPABILITY_UNREACHABLE);
                break;
            case CommSystemFacade.STATUS_DISCONNECTED:
            case CommSystemFacade.STATUS_HOSED:
            case CommSystemFacade.STATUS_UNKNOWN:
                // no explicit capability
                break;
        }
    }
    
    public boolean isHidden() {
        RouterInfo ri = _routerInfo;
        if ( (ri != null) && (ri.isHidden()) )
            return true;
        String h = _context.getProperty(PROP_HIDDEN_HIDDEN);
        if (h != null)
            return Boolean.parseBoolean(h);
        return _context.commSystem().isInBadCountry();
    }

    /**
     *  Only called at startup via LoadRouterInfoJob and RebuildRouterInfoJob.
     *  Not called by periodic RepublishLocalRouterInfoJob.
     *  We don't want to change the cert on the fly as it changes the router hash.
     *  RouterInfo.isHidden() checks the capability, but RouterIdentity.isHidden() checks the cert.
     *  There's no reason to ever add a hidden cert?
     *  @return the certificate for a new RouterInfo - probably a null cert.
     */
    public Certificate createCertificate() {
        if (_context.getBooleanProperty(PROP_HIDDEN))
            return new Certificate(Certificate.CERTIFICATE_TYPE_HIDDEN, null);
        return Certificate.NULL_CERT;
    }
    
    /**
     *  @since 0.9.3
     */
    public EventLog eventLog() {
        return _eventLog;
    }
    
    /**
     * Ugly list of files that we need to kill if we are building a new identity
     *
     */
    private static final String _rebuildFiles[] = new String[] { "router.info", 
                                                                 "router.keys",
                                                                 "netDb/my.info",      // no longer used
                                                                 "connectionTag.keys", // never used?
                                                                 "keyBackup/privateEncryption.key",
                                                                 "keyBackup/privateSigning.key",
                                                                 "keyBackup/publicEncryption.key",
                                                                 "keyBackup/publicSigning.key",
                                                                 "sessionKeys.dat"     // no longer used
                                                               };

    public void killKeys() {
        //new Exception("Clearing identity files").printStackTrace();
        for (int i = 0; i < _rebuildFiles.length; i++) {
            File f = new File(_context.getRouterDir(),_rebuildFiles[i]);
            if (f.exists()) {
                boolean removed = f.delete();
                if (removed) {
                    System.out.println("INFO:  Removing old identity file: " + _rebuildFiles[i]);
                } else {
                    System.out.println("ERROR: Could not remove old identity file: " + _rebuildFiles[i]);
                }
            }
        }

        // now that we have random ports, keeping the same port would be bad
        synchronized(this) {
            removeConfigSetting(UDPTransport.PROP_INTERNAL_PORT);
            removeConfigSetting(UDPTransport.PROP_EXTERNAL_PORT);
            saveConfig();
        }
    }

    /**
     * Rebuild a new identity the hard way - delete all of our old identity 
     * files, then reboot the router.
     *
     */
    public synchronized void rebuildNewIdentity() {
        if (_shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(_shutdownHook);
            } catch (IllegalStateException ise) {}
        }
        killKeys();
        for (Runnable task : _context.getShutdownTasks()) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Running shutdown task " + task.getClass());
            try {
                task.run();
            } catch (Throwable t) {
                _log.log(Log.CRIT, "Error running shutdown task", t);
            }
        }
        _context.removeShutdownTasks();
        // hard and ugly
        if (_context.hasWrapper())
            _log.log(Log.CRIT, "Restarting with new router identity");
        else
            _log.log(Log.CRIT, "Shutting down because old router identity was invalid - restart I2P");
        finalShutdown(EXIT_HARD_RESTART);
    }
    
    private void warmupCrypto() {
        _context.random().nextBoolean();
        // Instantiate to fire up the YK refiller thread
        _context.elGamalEngine();
    }
    
    private void startupQueue() {
        _context.jobQueue().runQueue(1);
    }
    
    private void setupHandlers() {
        _context.inNetMessagePool().registerHandlerJobBuilder(GarlicMessage.MESSAGE_TYPE, new GarlicMessageHandler(_context));
        //_context.inNetMessagePool().registerHandlerJobBuilder(TunnelMessage.MESSAGE_TYPE, new TunnelMessageHandler(_context));
    }
    
    public static final int EXIT_GRACEFUL = 2;
    public static final int EXIT_HARD = 3;
    public static final int EXIT_OOM = 10;
    public static final int EXIT_HARD_RESTART = 4;
    public static final int EXIT_GRACEFUL_RESTART = 5;
    
    /**
     *  Shutdown with no chance of cancellation
     */
    public synchronized void shutdown(int exitCode) {
        if (_shutdownInProgress)
            return;
        _shutdownInProgress = true;
        _context.throttle().setShutdownStatus();
        if (_shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(_shutdownHook);
            } catch (IllegalStateException ise) {}
        }
        shutdown2(exitCode);
    }

    /**
     *  Cancel the JVM runtime hook before calling this.
     *  Called by the ShutdownHook.
     *  NOT to be called by others, use shutdown().
     */
    public synchronized void shutdown2(int exitCode) {
        // help us shut down esp. after OOM
        int priority = (exitCode == EXIT_OOM) ? Thread.MAX_PRIORITY - 1 : Thread.NORM_PRIORITY + 2;
        Thread.currentThread().setPriority(priority);
        _shutdownInProgress = true;
        _log.log(Log.CRIT, "Starting final shutdown(" + exitCode + ')');
        // So we can get all the way to the end
        // No, you can't do Thread.currentThread.setDaemon(false)
        if (_killVMOnEnd) {
            try {
                (new Spinner()).start();
            } catch (Throwable t) {}
        }
        ((RouterClock) _context.clock()).removeShiftListener(this);
        _isAlive = false;
        _context.random().saveSeed();
        I2PThread.removeOOMEventListener(_oomListener);
        // Run the shutdown hooks first in case they want to send some goodbye messages
        // Maybe we need a delay after this too?
        for (Runnable task : _context.getShutdownTasks()) {
            //System.err.println("Running shutdown task " + task.getClass());
            if (_log.shouldLog(Log.WARN))
                _log.warn("Running shutdown task " + task.getClass());
            try {
                //task.run();
                Thread t = new Thread(task, "Shutdown task " + task.getClass().getName());
                t.setDaemon(true);
                t.start();
                try {
                    t.join(10*1000);
                } catch (InterruptedException ie) {}
                if (t.isAlive())
                    _log.logAlways(Log.WARN, "Shutdown task took more than 10 seconds to run: " + task.getClass());
            } catch (Throwable t) {
                _log.log(Log.CRIT, "Error running shutdown task", t);
            }
        }

        // Set the last version to the current version, since 0.8.13
        if (!RouterVersion.VERSION.equals(_config.get("router.previousVersion"))) {
            saveConfig("router.previousVersion", RouterVersion.VERSION);
        }

        _context.removeShutdownTasks();
        try { _context.clientManager().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the client manager", t); }
        try { _context.namingService().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the naming service", t); }
        try { _context.jobQueue().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the job queue", t); }
        try { _context.statPublisher().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the stats publisher", t); }
        try { _context.tunnelManager().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the tunnel manager", t); }
        try { _context.tunnelDispatcher().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the tunnel dispatcher", t); }
        try { _context.netDb().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the networkDb", t); }
        try { _context.commSystem().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the comm system", t); }
        try { _context.bandwidthLimiter().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the comm system", t); }
        try { _context.peerManager().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the peer manager", t); }
        try { _context.messageRegistry().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the message registry", t); }
        try { _context.messageValidator().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the message validator", t); }
        try { _context.inNetMessagePool().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the inbound net pool", t); }
        try { _context.clientMessagePool().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the client msg pool", t); }
        try { _context.sessionKeyManager().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the session key manager", t); }
        try { _context.messageHistory().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the message history logger", t); }
        // do stat manager last to reduce chance of NPEs in other threads
        try { _context.statManager().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the stats manager", t); }
        _context.deleteTempDir();
        List<RouterContext> contexts = RouterContext.getContexts();
        contexts.remove(_context);

        // shut down I2PAppContext tasks here

        try {
            _context.elGamalEngine().shutdown();
        } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting elGamal", t); }

        if (contexts.isEmpty()) {
            // any thing else to shut down?
        } else {
            _log.logAlways(Log.WARN, "Warning - " + contexts.size() + " routers remaining in this JVM, not releasing all resources");
        }
        try {
            ((FortunaRandomSource)_context.random()).shutdown();
        } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting random()", t); }

        // logManager shut down in finalShutdown()
        _watchdog.shutdown();
        _watchdogThread.interrupt();
        _eventLog.addEvent(EventLog.STOPPED, Integer.toString(exitCode));
        finalShutdown(exitCode);
    }

    /**
     * disable dynamic key functionality for the moment, as it may be harmful and doesn't
     * add meaningful anonymity
     */
    private static final boolean ALLOW_DYNAMIC_KEYS = false;

    /**
     *  Cancel the JVM runtime hook before calling this.
     */
    private synchronized void finalShutdown(int exitCode) {
        clearCaches();
        _log.log(Log.CRIT, "Shutdown(" + exitCode + ") complete"  /* , new Exception("Shutdown") */ );
        try { _context.logManager().shutdown(); } catch (Throwable t) { }
        if (ALLOW_DYNAMIC_KEYS) {
            if (_context.getBooleanProperty(PROP_DYNAMIC_KEYS))
                killKeys();
        }

        File f = getPingFile();
        f.delete();
        if (RouterContext.getContexts().isEmpty())
            RouterContext.killGlobalContext();

        // Since 0.8.8, for Android and the wrapper
        for (Runnable task : _context.getFinalShutdownTasks()) {
            //System.err.println("Running final shutdown task " + task.getClass());
            try {
                task.run();
            } catch (Throwable t) {
                System.err.println("Running final shutdown task " + t);
            }
        }
        _context.getFinalShutdownTasks().clear();

        if (_killVMOnEnd) {
            try { Thread.sleep(1000); } catch (InterruptedException ie) {}
            //Runtime.getRuntime().halt(exitCode);
            // allow the Runtime shutdown hooks to execute
            Runtime.getRuntime().exit(exitCode);
        } else if (SystemVersion.isAndroid()) {
            Runtime.getRuntime().gc();
        }
    }
    
    /**
     * Call this if we want the router to kill itself as soon as we aren't 
     * participating in any more tunnels (etc).  This will not block and doesn't
     * guarantee any particular time frame for shutting down.  To shut the 
     * router down immediately, use {@link #shutdown}.  If you want to cancel
     * the graceful shutdown (prior to actual shutdown ;), call 
     * {@link #cancelGracefulShutdown}.
     *
     */
    public void shutdownGracefully() {
        shutdownGracefully(EXIT_GRACEFUL);
    }
    /**
     * Call this with EXIT_HARD or EXIT_HARD_RESTART for a non-blocking,
     * hard, non-graceful shutdown with a brief delay to allow a UI response
     */
    public void shutdownGracefully(int exitCode) {
        _gracefulExitCode = exitCode;
        _config.put(PROP_SHUTDOWN_IN_PROGRESS, "true");
        _context.throttle().setShutdownStatus();
        synchronized (_gracefulShutdownDetector) {
            _gracefulShutdownDetector.notifyAll();
        }
    }
    
    /**
     * Cancel any prior request to shut the router down gracefully.
     *
     */
    public void cancelGracefulShutdown() {
        _gracefulExitCode = -1;
        _config.remove(PROP_SHUTDOWN_IN_PROGRESS);
        _context.throttle().cancelShutdownStatus();
        synchronized (_gracefulShutdownDetector) {
            _gracefulShutdownDetector.notifyAll();
        }        
    }

    /**
     * What exit code do we plan on using when we shut down (or -1, if there isn't a graceful shutdown planned)
     */
    public int scheduledGracefulExitCode() { return _gracefulExitCode; }

    /**
     * Is a graceful shutdown in progress? This may be cancelled.
     */
    public boolean gracefulShutdownInProgress() {
        return (null != _config.get(PROP_SHUTDOWN_IN_PROGRESS));
    }

    /**
     * Is a final shutdown in progress? This may not be cancelled.
     * @since 0.8.12
     */
    public boolean isFinalShutdownInProgress() {
        return _shutdownInProgress;
    }

    /** How long until the graceful shutdown will kill us?  */
    public long getShutdownTimeRemaining() {
        if (_gracefulExitCode <= 0) return -1; // maybe Long.MAX_VALUE would be better?
        if (_gracefulExitCode == EXIT_HARD || _gracefulExitCode == EXIT_HARD_RESTART)
            return 0;
        long exp = _context.tunnelManager().getLastParticipatingExpiration();
        if (exp < 0)
            return -1;
        else
            return exp + 2*CLOCK_FUDGE_FACTOR - _context.clock().now();
    }
    
    /**
     * Save the current config options (returning true if save was 
     * successful, false otherwise)
     *
     * Synchronized with file read in getConfig()
     */
    public boolean saveConfig() {
        try {
            Properties ordered = new OrderedProperties();
            synchronized(_configFileLock) {
                ordered.putAll(_config);
                DataHelper.storeProps(ordered, new File(_configFilename));
            }
        } catch (Exception ioe) {
                // warning, _log will be null when called from constructor
                if (_log != null)
                    _log.error("Error saving the config to " + _configFilename, ioe);
                else
                    System.err.println("Error saving the config to " + _configFilename + ": " + ioe);
                return false;
        }
        return true;
    }
    
    /**
     * Updates the current config and then saves it.
     * Prevents a race in the interval between setConfigSetting() / removeConfigSetting() and saveConfig(),
     * Synchronized with getConfig() / saveConfig()
     *
     * @param name setting to add/change/remove before saving
     * @param value if non-null, updated value; if null, setting will be removed
     * @return success
     * @since 0.8.13
     */
    public boolean saveConfig(String name, String value) {
        synchronized(_configFileLock) {
            if (value != null)
                _config.put(name, value);
            else
                removeConfigSetting(name);
            return saveConfig();
        }
    }

    /**
     * Updates the current config and then saves it.
     * Prevents a race in the interval between setConfigSetting() / removeConfigSetting() and saveConfig(),
     * Synchronized with getConfig() / saveConfig()
     *
     * @param toAdd settings to add/change before saving, may be null or empty
     * @param toRemove settings to remove before saving, may be null or empty
     * @return success
     * @since 0.8.13
     */
    public boolean saveConfig(Map toAdd, Collection<String> toRemove) {
        synchronized(_configFileLock) {
            if (toAdd != null)
                _config.putAll(toAdd);
            if (toRemove != null) {
                for (String s : toRemove) {
                    removeConfigSetting(s);
                }
            }
            return saveConfig();
        }
    }

    /**
     *  The clock shift listener.
     *  Restart the router if we should.
     *
     *  @since 0.8.8
     */
    public void clockShift(long delta) {
        if (gracefulShutdownInProgress() || !_isAlive)
            return;
        if (delta > -60*1000 && delta < 60*1000)
            return;
        _eventLog.addEvent(EventLog.CLOCK_SHIFT, Long.toString(delta));
        // update the routing key modifier
        _context.routingKeyGenerator().generateDateBasedModData();
        if (_context.commSystem().countActivePeers() <= 0)
            return;
        if (delta > 0)
            _log.error("Restarting after large clock shift forward by " + DataHelper.formatDuration(delta));
        else
            _log.error("Restarting after large clock shift backward by " + DataHelper.formatDuration(0 - delta));
        restart();
    }

    /**
     *  A "soft" restart, primarily of the comm system, after
     *  a port change or large step-change in system time.
     *  Does not stop the whole JVM, so it is safe even in the absence
     *  of the wrapper.
     *  This is not a graceful restart - all peer connections are dropped immediately.
     *
     *  As of 0.8.8, this returns immediately and does the actual restart in a separate thread.
     *  Poll isAlive() if you need to know when the restart is complete.
     */
    public synchronized void restart() {
        if (gracefulShutdownInProgress() || !_isAlive)
            return;
        ((RouterClock) _context.clock()).removeShiftListener(this);
        _isAlive = false;
        _started = _context.clock().now();
        Thread t = new Thread(new Restarter(_context), "Router Restart");
        t.setPriority(Thread.NORM_PRIORITY + 1);
        t.start();
    }    

    /**
     *  Only for Restarter
     *  @since 0.8.12
     */
    public void setIsAlive() {
        _isAlive = true;
    }

    /**
     *  Usage: Router [rebuild]
     *  No other options allowed, for now
     *
     *  @param args null ok
     *  @throws IllegalArgumentException
     */
    public static void main(String args[]) {
        boolean rebuild = false;
        if (args != null) {
            boolean error = false;
            Getopt g = new Getopt("router", args, "");
            int c;
            while ((c = g.getopt()) != -1) {
                switch (c) {
                    default:
                        error = true;
                }
            }
            int remaining = args.length - g.getOptind();
            if (remaining > 1) {
                error = true;
            } else if (remaining == 1) {
                rebuild = args[g.getOptind()].equals("rebuild");;
                if (!rebuild)
                    error = true;
            }
            if (error)
                throw new IllegalArgumentException();
        }

        System.out.println("Starting I2P " + RouterVersion.FULL_VERSION);
        //verifyWrapperConfig();
        Router r = new Router();
        if (rebuild) {
            r.rebuildNewIdentity();
        } else {
            // This is here so that we can get the directory location from the context
            // for the zip file and the base location to unzip to.
            // If it does an update, it never returns.
            // I guess it's better to have the other-router check above this, we don't want to
            // overwrite an existing running router's jar files. Other than ours.
            r.installUpdates();
            // *********  Start no threads before here ********* //
            r.runRouter();
        }
    }
    
    public static final String UPDATE_FILE = "i2pupdate.zip";
    private static final String DELETE_FILE = "deletelist.txt";
    
    /**
     * Context must be available.
     * Unzip update file found in the router dir OR base dir, to the base dir
     *
     * If we can't write to the base dir, complain.
     * Note: _log not available here.
     */
    private void installUpdates() {
        File updateFile = new File(_context.getRouterDir(), UPDATE_FILE);
        boolean exists = updateFile.exists();
        if (!exists) {
            updateFile = new File(_context.getBaseDir(), UPDATE_FILE);
            exists = updateFile.exists();
        }
        if (exists) {
            // do a simple permissions test, if it fails leave the file in place and don't restart
            File test = new File(_context.getBaseDir(), "history.txt");
            if ((test.exists() && !test.canWrite()) || (!_context.getBaseDir().canWrite())) {
                System.out.println("ERROR: No write permissions on " + _context.getBaseDir() +
                                   " to extract software update file");
                // carry on
                return;
            }
            System.out.println("INFO: Update file exists [" + UPDATE_FILE + "] - installing");
            // verify the whole thing first
            // we could remember this fails, and not bother restarting, but who cares...
            boolean ok = FileUtil.verifyZip(updateFile);
            if (ok) {
                // This may be useful someday. First added in 0.8.2
                // Moved above the extract so we don't NCDFE
                _config.put("router.updateLastInstalled", "" + System.currentTimeMillis());
                // Set the last version to the current version, since 0.8.13
                _config.put("router.previousVersion", RouterVersion.VERSION);
                _config.put("router.previousFullVersion", RouterVersion.FULL_VERSION);
                saveConfig();
                ok = FileUtil.extractZip(updateFile, _context.getBaseDir());
            }

            // Very important - we have now trashed our jars.
            // After this point, do not use any new I2P classes, or they will fail to load
            // and we will die with NCDFE.
            // Ideally, do not use I2P classes at all, new or not.
            try {
                if (ok) {
                    // We do this here so we may delete old jars before we restart
                    deleteListedFiles();
                    System.out.println("INFO: Update installed");
                } else {
                    System.out.println("ERROR: Update failed!");
                }
                if (!ok) {
                    // we can't leave the file in place or we'll continually restart, so rename it
                    File bad = new File(_context.getRouterDir(), "BAD-" + UPDATE_FILE);
                    boolean renamed = updateFile.renameTo(bad);
                    if (renamed) {
                        System.out.println("Moved update file to " + bad.getAbsolutePath());
                    } else {
                        System.out.println("Deleting file " + updateFile.getAbsolutePath());
                        ok = true;  // so it will be deleted
                    }
                }
                if (ok) {
                    boolean deleted = updateFile.delete();
                    if (!deleted) {
                        System.out.println("ERROR: Unable to delete the update file!");
                        updateFile.deleteOnExit();
                    }
                }
                // exit whether ok or not
                if (_context.hasWrapper())
                    System.out.println("INFO: Restarting after update");
                else
                    System.out.println("WARNING: Exiting after update, restart I2P");
            } catch (Throwable t) {
                // hide the NCDFE
                // hopefully the update file got deleted or we will loop
            }
            System.exit(EXIT_HARD_RESTART);
        } else {
            deleteJbigiFiles();
            // It was here starting in 0.8.12 so it could be used the very first time
            // Now moved up so it is usually run only after an update
            // But the first time before jetty 6 it will run here...
            // Here we can't remove jars
            deleteListedFiles();
        }
    }

    /**
     *  Remove extracted libjbigi.so and libjcpuid.so files if we have a newer jbigi.jar,
     *  so the new ones will be extracted.
     *  We do this after the restart, not after the extract, because it's safer, and
     *  because people may upgrade their jbigi.jar file manually.
     *
     *  Copied from NativeBigInteger, which we can't access here or the
     *  libs will get loaded.
     */
    private void deleteJbigiFiles() {
            String osArch = System.getProperty("os.arch");
            boolean isX86 = osArch.contains("86") || osArch.equals("amd64");
            String osName = System.getProperty("os.name").toLowerCase(Locale.US);
            boolean isWin = SystemVersion.isWindows();
            boolean isMac = SystemVersion.isMac();
            // only do this on these OSes
            boolean goodOS = isWin || isMac ||
                             osName.contains("linux") || osName.contains("freebsd");

            // only do this on these x86
            File jbigiJar = new File(_context.getBaseDir(), "lib/jbigi.jar");
            if (isX86 && goodOS && jbigiJar.exists()) {
                String libPrefix = (isWin ? "" : "lib");
                String libSuffix = (isWin ? ".dll" : isMac ? ".jnilib" : ".so");

                File jcpuidLib = new File(_context.getBaseDir(), libPrefix + "jcpuid" + libSuffix);
                if (jcpuidLib.canWrite() && jbigiJar.lastModified() > jcpuidLib.lastModified()) {
                    String path = jcpuidLib.getAbsolutePath();
                    boolean success = FileUtil.copy(path, path + ".bak", true, true);
                    if (success) {
                        boolean success2 = jcpuidLib.delete();
                        if (success2) {
                            System.out.println("New jbigi.jar detected, moved jcpuid library to " +
                                               path + ".bak");
                            System.out.println("Check logs for successful installation of new library");
                        }
                    }
                }

                File jbigiLib = new File(_context.getBaseDir(), libPrefix + "jbigi" + libSuffix);
                if (jbigiLib.canWrite() && jbigiJar.lastModified() > jbigiLib.lastModified()) {
                    String path = jbigiLib.getAbsolutePath();
                    boolean success = FileUtil.copy(path, path + ".bak", true, true);
                    if (success) {
                        boolean success2 = jbigiLib.delete();
                        if (success2) {
                            System.out.println("New jbigi.jar detected, moved jbigi library to " +
                                               path + ".bak");
                            System.out.println("Check logs for successful installation of new library");
                        }
                    }
                }
            }
    }
    
    /**
     *  Delete all files listed in the delete file.
     *  Format: One file name per line, comment lines start with '#'.
     *  All file names must be relative to $I2P, absolute file names not allowed.
     *  We probably can't remove old jars this way.
     *  Fails silently.
     *  Use no new I2P classes here so it may be called after zip extraction.
     *  @since 0.8.12
     */
    private void deleteListedFiles() {
        File deleteFile = new File(_context.getBaseDir(), DELETE_FILE);
        if (!deleteFile.exists())
            return;
        // this is similar to FileUtil.readTextFile() but we can't use any I2P classes here
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(deleteFile);
            BufferedReader in = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
            String line;
            while ( (line = in.readLine()) != null) {
                String fl = line.trim();
                if (fl.contains("..") || fl.startsWith("#") || fl.length() == 0)
                    continue;
                File df = new File(fl);
                if (df.isAbsolute())
                    continue;
                df = new File(_context.getBaseDir(), fl);
                if (df.exists() && !df.isDirectory()) {
                    if (df.delete())
                        System.out.println("INFO: File [" + fl + "] deleted");
                }
            }
        } catch (IOException ioe) {
        } finally {
            if (fis != null) try { fis.close(); } catch (IOException ioe) {}
            if (deleteFile.delete())
                System.out.println("INFO: File [" + DELETE_FILE + "] deleted");
        }
    }

/*******
    private static void verifyWrapperConfig() {
        File cfgUpdated = new File("wrapper.config.updated");
        if (cfgUpdated.exists()) {
            cfgUpdated.delete();
            System.out.println("INFO: Wrapper config updated, but the service wrapper requires you to manually restart");
            System.out.println("INFO: Shutting down the router - please rerun it!");
            System.exit(EXIT_HARD);
        }
    }
*******/
    
/*
    private static String getPingFile(Properties envProps) {
        if (envProps != null) 
            return envProps.getProperty("router.pingFile", "router.ping");
        else
            return "router.ping";
    }
*/
    private File getPingFile() {
        String s = _context.getProperty("router.pingFile", "router.ping");
        File f = new File(s);
        if (!f.isAbsolute())
            f = new File(_context.getPIDDir(), s);
        return f;
    }
    
    static final long LIVELINESS_DELAY = 60*1000;
    
    /** 
     * Check the file "router.ping", but if 
     * that file already exists and was recently written to, return false as there is
     * another instance running.
     * 
     * @return true if the router is the only one running 
     * @since 0.8.2
     */
    private boolean isOnlyRouterRunning() {
        File f = getPingFile();
        if (f.exists()) {
            long lastWritten = f.lastModified();
            if (System.currentTimeMillis()-lastWritten > LIVELINESS_DELAY) {
                System.err.println("WARN: Old router was not shut down gracefully, deleting router.ping");
                f.delete();
            } else {
                return false;
            }
        }
        return true;
    }

    /** 
     * Start a thread that will periodically update the file "router.ping".
     * isOnlyRouterRunning() MUST have been called previously.
     */
    private void beginMarkingLiveliness() {
        File f = getPingFile();
        _context.simpleScheduler().addPeriodicEvent(new MarkLiveliness(this, f), 0, LIVELINESS_DELAY - (5*1000));
    }
    
    public static final String PROP_BANDWIDTH_SHARE_PERCENTAGE = "router.sharePercentage";
    public static final int DEFAULT_SHARE_PERCENTAGE = 80;
    
    /** 
     * What fraction of the bandwidth specified in our bandwidth limits should
     * we allow to be consumed by participating tunnels?
     * @return a number less than one, not a percentage!
     *
     */
    public double getSharePercentage() {
        String pct = _context.getProperty(PROP_BANDWIDTH_SHARE_PERCENTAGE);
        if (pct != null) {
            try {
                double d = Double.parseDouble(pct);
                if (d > 1)
                    return d/100d; // *cough* sometimes its 80 instead of .8 (!stab jrandom)
                else
                    return d;
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Unable to get the share percentage");
            }
        }
        return DEFAULT_SHARE_PERCENTAGE / 100.0d;
    }

    public int get1sRate() { return get1sRate(false); }

    public int get1sRate(boolean outboundOnly) {
            FIFOBandwidthLimiter bw = _context.bandwidthLimiter();
                int out = (int)bw.getSendBps();
                if (outboundOnly)
                    return out;
                return (int)Math.max(out, bw.getReceiveBps());
    }

    public int get1sRateIn() {
            FIFOBandwidthLimiter bw = _context.bandwidthLimiter();
                return (int) bw.getReceiveBps();
    }

    public int get15sRate() { return get15sRate(false); }

    public int get15sRate(boolean outboundOnly) {
            FIFOBandwidthLimiter bw = _context.bandwidthLimiter();
                int out = (int)bw.getSendBps15s();
                if (outboundOnly)
                    return out;
                return (int)Math.max(out, bw.getReceiveBps15s());
    }

    public int get15sRateIn() {
            FIFOBandwidthLimiter bw = _context.bandwidthLimiter();
                return (int) bw.getReceiveBps15s();
    }

    public int get1mRate() { return get1mRate(false); }

    public int get1mRate(boolean outboundOnly) {
        int send = 0;
        StatManager mgr = _context.statManager();
        RateStat rs = mgr.getRate("bw.sendRate");
        if (rs != null)
            send = (int)rs.getRate(1*60*1000).getAverageValue();
        if (outboundOnly)
            return send;
        int recv = 0;
        rs = mgr.getRate("bw.recvRate");
        if (rs != null)
            recv = (int)rs.getRate(1*60*1000).getAverageValue();
        return Math.max(send, recv);
    }

    public int get1mRateIn() {
        StatManager mgr = _context.statManager();
        RateStat rs = mgr.getRate("bw.recvRate");
        int recv = 0;
        if (rs != null)
            recv = (int)rs.getRate(1*60*1000).getAverageValue();
        return recv;
    }

    public int get5mRate() { return get5mRate(false); }

    public int get5mRate(boolean outboundOnly) {
        int send = 0;
        RateStat rs = _context.statManager().getRate("bw.sendRate");
        if (rs != null)
            send = (int)rs.getRate(5*60*1000).getAverageValue();
        if (outboundOnly)
            return send;
        int recv = 0;
        rs = _context.statManager().getRate("bw.recvRate");
        if (rs != null)
            recv = (int)rs.getRate(5*60*1000).getAverageValue();
        return Math.max(send, recv);
    }
}
