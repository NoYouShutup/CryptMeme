package net.i2p.router.time;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringTokenizer;
import java.util.concurrent.CopyOnWriteArrayList;

import net.i2p.I2PAppContext;
import net.i2p.time.Timestamper;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Periodically query a series of NTP servers and update any associated
 * listeners.  It tries the NTP servers in order, contacting them using 
 * SNTP (UDP port 123).
 *
 * @since 0.9.1 moved from net.i2p.time
 */
public class RouterTimestamper extends Timestamper {
    private final I2PAppContext _context;
    private Log _log;
    private final List<String> _servers;
    private List<String> _priorityServers;
    private final List<UpdateListener> _listeners;
    private int _queryFrequency;
    private int _concurringServers;
    private int _consecutiveFails;
    private volatile boolean _disabled;
    private final boolean _daemon;
    private boolean _initialized;
    private boolean _wellSynced;
    private volatile boolean _isRunning;
    private Thread _timestamperThread;
    
    private static final int MIN_QUERY_FREQUENCY = 5*60*1000;
    private static final int DEFAULT_QUERY_FREQUENCY = 11*60*1000;
    private static final String DEFAULT_SERVER_LIST = "0.pool.ntp.org,1.pool.ntp.org,2.pool.ntp.org";
    private static final String DEFAULT_DISABLED = "true";
    /** how many times do we have to query if we are changing the clock? */
    private static final int DEFAULT_CONCURRING_SERVERS = 3;
    private static final int MAX_CONSECUTIVE_FAILS = 10;
    
    public static final String PROP_QUERY_FREQUENCY = "time.queryFrequencyMs";
    public static final String PROP_SERVER_LIST = "time.sntpServerList";
    public static final String PROP_DISABLED = "time.disabled";
    public static final String PROP_CONCURRING_SERVERS = "time.concurringServers";
    public static final String PROP_IP_COUNTRY = "i2np.lastCountry";
    
    /** if different SNTP servers differ by more than 10s, someone is b0rked */
    private static final int MAX_VARIANCE = 10*1000;
        
    public RouterTimestamper(I2PAppContext ctx) {
        this(ctx, null, true);
    }
    
    public RouterTimestamper(I2PAppContext ctx, UpdateListener lsnr) {
        this(ctx, lsnr, true);
    }
    public RouterTimestamper(I2PAppContext ctx, UpdateListener lsnr, boolean daemon) {
        super();
        // moved here to prevent problems with synchronized statements.
        _servers = new ArrayList<String>(3);
        _listeners = new CopyOnWriteArrayList<UpdateListener>();
        _context = ctx;
        _daemon = daemon;
        // DO NOT initialize _log here, stack overflow via LogManager init loop

        // Don't bother starting a thread if we are disabled.
        // This means we no longer check every 5 minutes to see if we got enabled,
        // so the property must be set at startup.
        // We still need to be instantiated since the router calls clock().getTimestamper().waitForInitialization()
        String disabled = ctx.getProperty(PROP_DISABLED, DEFAULT_DISABLED);
        if (Boolean.parseBoolean(disabled)) {
            _initialized = true;
            return;
        }
        if (lsnr != null)
            _listeners.add(lsnr);
        updateConfig();
        startTimestamper();
    }
    
    public int getServerCount() { 
        synchronized (_servers) {
            return _servers.size(); 
        }
    }
    public String getServer(int index) { 
        synchronized (_servers) {
            return _servers.get(index); 
        }
    }
    
    public int getQueryFrequencyMs() { return _queryFrequency; }
    
    public boolean getIsDisabled() { return _disabled; }
    
    public void addListener(UpdateListener lsnr) {
            _listeners.add(lsnr);
    }
    public void removeListener(UpdateListener lsnr) {
            _listeners.remove(lsnr);
    }
    public int getListenerCount() {
            return _listeners.size();
    }
    public UpdateListener getListener(int index) {
            return _listeners.get(index);
    }
    
    private void startTimestamper() {
        _timestamperThread = new I2PThread(this, "Timestamper", _daemon);
        _timestamperThread.setPriority(I2PThread.MIN_PRIORITY);
        _isRunning = true;
        _timestamperThread.start();
        _context.addShutdownTask(new Shutdown());
    }
    
    @Override
    public void waitForInitialization() {
        try { 
            synchronized (this) {
                if (!_initialized)
                    wait();
            }
        } catch (InterruptedException ie) {}
    }
    
    /**
     *  Update the time immediately.
     *  @since 0.8.8
     */
    @Override
    public void timestampNow() {
         if (_initialized && _isRunning && (!_disabled) && _timestamperThread != null)
             _timestamperThread.interrupt();
    }
    
    /** @since 0.8.8 */
    private class Shutdown implements Runnable {
        public void run() {
             _isRunning = false;
             if (_timestamperThread != null)
                 _timestamperThread.interrupt();
        }
    }
    
    @Override
    public void run() {
        try { Thread.sleep(1000); } catch (InterruptedException ie) {}
        _log = _context.logManager().getLog(Timestamper.class);
        if (_log.shouldLog(Log.INFO))
            _log.info("Starting timestamper");
        boolean lastFailed = false;
        try {
            while (_isRunning) {
                updateConfig();
                if (!_disabled) {
                    // first the servers for our country, if we know what country we're in...
                    if (_priorityServers != null) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Querying servers " + _priorityServers);
                        try {
                            lastFailed = !queryTime(_priorityServers.toArray(new String[_priorityServers.size()]));
                        } catch (IllegalArgumentException iae) {
                            if ( (!lastFailed) && (_log.shouldLog(Log.WARN)) )
                                _log.warn("Unable to reach country-specific NTP servers");
                            lastFailed = true;
                        }
                    }
                    // ... and then the global list, if that failed
                    if (_priorityServers == null || lastFailed) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Querying servers " + _servers);
                        try {
                            lastFailed = !queryTime(_servers.toArray(new String[_servers.size()]));
                        } catch (IllegalArgumentException iae) {
                            if ( (!_initialized) && (_log.shouldLog(Log.ERROR)) ) {
                                List<String> all = new ArrayList<String>();
                                if (_priorityServers != null)
                                    all.addAll(_priorityServers);
                                all.addAll(_servers);
                                _log.error("Unable to reach any of the NTP servers " + all + " - network disconnected? Or set time.sntpServerList=myserver1.com,myserver2.com in advanced configuration.");
                            }
                            lastFailed = true;
                        }
                    }
                }
                
                _initialized = true;
                synchronized (this) { notifyAll(); }
                long sleepTime;
                if (lastFailed) {
                    if (++_consecutiveFails >= MAX_CONSECUTIVE_FAILS)
                        sleepTime = 30*60*1000;
                    else
                        sleepTime = 30*1000;
                } else {
                    _consecutiveFails = 0;
                    sleepTime = _context.random().nextInt(_queryFrequency / 2) + _queryFrequency;
                    if (_wellSynced)
                        sleepTime *= 3;
                }
                try { Thread.sleep(sleepTime); } catch (InterruptedException ie) {}
            }
        } catch (Throwable t) {
            _log.log(Log.CRIT, "Timestamper died!", t);
            synchronized (this) { notifyAll(); }
        }
    }
    
    /**
     * True if the time was queried successfully, false if it couldn't be
     */
    private boolean queryTime(String serverList[]) throws IllegalArgumentException {
        long found[] = new long[_concurringServers];
        long now = -1;
        int stratum = -1;
        long expectedDelta = 0;
        _wellSynced = false;
        for (int i = 0; i < _concurringServers; i++) {
            if (i > 0) {
                // this delays startup when net is disconnected or the timeserver list is bad, don't make it too long
                try { Thread.sleep(2*1000); } catch (InterruptedException ie) {}
            }
            long[] timeAndStratum = NtpClient.currentTimeAndStratum(serverList);
            now = timeAndStratum[0];
            stratum = (int) timeAndStratum[1];
            long delta = now - _context.clock().now();
            found[i] = delta;
            if (i == 0) {
                if (Math.abs(delta) < MAX_VARIANCE) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("a single SNTP query was within the tolerance (" + delta + "ms)");
                    // If less than a half second on the first try, we're in good shape
                    _wellSynced = Math.abs(delta) < 500;
                    break;
                } else {
                    // outside the tolerance, lets iterate across the concurring queries
                    expectedDelta = delta;
                }
            } else {
                if (Math.abs(delta - expectedDelta) > MAX_VARIANCE) {
                    if (_log.shouldLog(Log.ERROR)) {
                        StringBuilder err = new StringBuilder(96);
                        err.append("SNTP client variance exceeded at query ").append(i);
                        err.append(".  expected = ");
                        err.append(expectedDelta);
                        err.append(", found = ");
                        err.append(delta);
                        err.append(" all deltas: ");
                        for (int j = 0; j < found.length; j++)
                            err.append(found[j]).append(' ');
                        _log.error(err.toString());
                    }
                    return false;
                }
            }
        }
        stampTime(now, stratum);
        if (_log.shouldLog(Log.DEBUG)) {
            StringBuilder buf = new StringBuilder(64);
            buf.append("Deltas: ");
            for (int i = 0; i < found.length; i++)
                buf.append(found[i]).append(' ');
            _log.debug(buf.toString());
        }
        return true;
    }
    
    /**
     * Notify the listeners
     *
     * @since stratum param added in 0.7.12
     */
    private void stampTime(long now, int stratum) {
        long before = _context.clock().now();
        for (UpdateListener lsnr : _listeners) {
             lsnr.setNow(now, stratum);
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Stamped the time as " + now + " (delta=" + (now-before) + ")");
    }
 
    /**
     * Reload all the config elements from the appContext
     *
     */
    private void updateConfig() {
        String serverList = _context.getProperty(PROP_SERVER_LIST);
        if ( (serverList == null) || (serverList.trim().length() <= 0) ) {
            serverList = DEFAULT_SERVER_LIST;
            String country = _context.getProperty(PROP_IP_COUNTRY);
            if (country == null) {
                country = Locale.getDefault().getCountry();
                if (country != null)
                    country = country.toLowerCase(Locale.US);
            }
            if (country != null &&  country.length() > 0) {
                _priorityServers = new ArrayList<String>(3);
                for (int i = 0; i < 3; i++)
                     _priorityServers.add(i + "." + country + ".pool.ntp.org");
            } else {
                _priorityServers = null;
            }
        } else {
            _priorityServers = null;
        }
        _servers.clear();
        StringTokenizer tok = new StringTokenizer(serverList, ", ");
        while (tok.hasMoreTokens()) {
            String val = tok.nextToken();
            val = val.trim();
            if (val.length() > 0)
                _servers.add(val);
        }
        
        _queryFrequency = Math.max(MIN_QUERY_FREQUENCY,
                                   _context.getProperty(PROP_QUERY_FREQUENCY, DEFAULT_QUERY_FREQUENCY));
        
        String disabled = _context.getProperty(PROP_DISABLED, DEFAULT_DISABLED);
        _disabled = Boolean.parseBoolean(disabled);
        
        _concurringServers = Math.min(4, Math.max(1,
                              _context.getProperty(PROP_CONCURRING_SERVERS, DEFAULT_CONCURRING_SERVERS)));
    }
    
/****
    public static void main(String args[]) {
        System.setProperty(PROP_DISABLED, "false");
        System.setProperty(PROP_QUERY_FREQUENCY, "30000");
        I2PAppContext.getGlobalContext();
        for (int i = 0; i < 5*60*1000; i += 61*1000) {
            try { Thread.sleep(61*1000); } catch (InterruptedException ie) {}
        }
    }
****/
}
