package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Set;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 *  Stores in-memory only. See extension.
 */
class TransientDataStore implements DataStore {
    protected final Log _log;
    private final ConcurrentHashMap<Hash, DatabaseEntry> _data;
    protected final RouterContext _context;
    
    public TransientDataStore(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _data = new ConcurrentHashMap<Hash, DatabaseEntry>(1024);
        if (_log.shouldLog(Log.INFO))
            _log.info("Data Store initialized");
    }
    
    public boolean isInitialized() { return true; }

    public void stop() {
        _data.clear();
    }
    
    public void restart() {
        stop();
    }
    
    public void rescan() {}

    /**
     *  @return total size (RI and LS)
     *  @since 0.8.8
     */
    public int size() {
        return _data.size();
    }

    /**
     *  @return Unmodifiable view, not a copy
     */
    public Set<Hash> getKeys() {
        return Collections.unmodifiableSet(_data.keySet());
    }
    
    /**
     *  @return Unmodifiable view, not a copy
     *  @since 0.8.3
     */
    public Collection<DatabaseEntry> getEntries() {
        return Collections.unmodifiableCollection(_data.values());
    }

    /**
     *  @return Unmodifiable view, not a copy
     *  @since 0.8.3
     */
    public Set<Map.Entry<Hash, DatabaseEntry>> getMapEntries() {
        return Collections.unmodifiableSet(_data.entrySet());
    }

    /** for PersistentDataStore only - don't use here
      * @throws UnsupportedOperationException always
      */
    public DatabaseEntry get(Hash key, boolean persist) {
        throw new UnsupportedOperationException();
    }

    public DatabaseEntry get(Hash key) {
        return _data.get(key);
    }
    
    public boolean isKnown(Hash key) {
        return _data.containsKey(key);
    }

    public int countLeaseSets() {
        int count = 0;
        for (DatabaseEntry d : _data.values()) {
            if (d.getType() == DatabaseEntry.KEY_TYPE_LEASESET)
                count++;
        }
        return count;
    }
    
    /** for PersistentDataStore only - don't use here
      * @throws UnsupportedOperationException always
      */
    public boolean put(Hash key, DatabaseEntry data, boolean persist) {
        throw new UnsupportedOperationException();
    }

    /**
     *  @param data must be validated before here
     *  @return success
     */
    public boolean put(Hash key, DatabaseEntry data) {
        if (data == null) return false;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Storing key " + key);
        DatabaseEntry old = _data.putIfAbsent(key, data);
        boolean rv = false;
        if (data.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
            // Don't do this here so we don't reset it at router startup;
            // the StoreMessageJob calls this
            //_context.profileManager().heardAbout(key);
            RouterInfo ri = (RouterInfo)data;
            if (old != null) {
                RouterInfo ori = (RouterInfo)old;
                if (ri.getPublished() < ori.getPublished()) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Almost clobbered an old router! " + key + ": [old published on " + new Date(ori.getPublished()) +
                                  " new on " + new Date(ri.getPublished()) + ']');
                } else if (ri.getPublished() == ori.getPublished()) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Duplicate " + key);
                } else {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Updated the old router for " + key + ": [old published on " + new Date(ori.getPublished()) +
                                  " new on " + new Date(ri.getPublished()) + ']');
                    _data.put(key, data);
                    rv = true;
                }
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("New router for " + key + ": published on " + new Date(ri.getPublished()));
                rv = true;
            }
        } else if (data.getType() == DatabaseEntry.KEY_TYPE_LEASESET) {
            LeaseSet ls = (LeaseSet)data;
            if (old != null) {
                LeaseSet ols = (LeaseSet)old;
                if (ls.getEarliestLeaseDate() < ols.getEarliestLeaseDate()) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Almost clobbered an old leaseSet! " + key + ": [old expires " + new Date(ols.getEarliestLeaseDate()) +
                                  " new on " + new Date(ls.getEarliestLeaseDate()) + ']');
                } else if (ls.getEarliestLeaseDate() == ols.getEarliestLeaseDate()) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Duplicate " + key);
                } else {
                    if (_log.shouldLog(Log.INFO)) {
                        _log.info("Updated old leaseSet " + key + ": [old expires " + new Date(ols.getEarliestLeaseDate()) +
                                  " new on " + new Date(ls.getEarliestLeaseDate()) + ']');
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("RAP? " + ls.getReceivedAsPublished() + " RAR? " + ls.getReceivedAsReply());
                    }
                    _data.put(key, data);
                    rv = true;
                }
            } else {
                if (_log.shouldLog(Log.INFO)) {
                    _log.info("New leaseset for " + key + ": expires " + new Date(ls.getEarliestLeaseDate()));
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("RAP? " + ls.getReceivedAsPublished() + " RAR? " + ls.getReceivedAsReply());
                }
                rv = true;
            }
        }
        return rv;
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("Transient DataStore: ").append(_data.size()).append("\nKeys: ");
        for (Map.Entry<Hash, DatabaseEntry> e : _data.entrySet()) {
            Hash key = e.getKey();
            DatabaseEntry dp = e.getValue();
            buf.append("\n\t*Key:   ").append(key.toString()).append("\n\tContent: ").append(dp.toString());
        }
        buf.append("\n");
        return buf.toString();
    }
    
    /** for PersistentDataStore only - don't use here
      * @throws UnsupportedOperationException always
      */
    public DatabaseEntry remove(Hash key, boolean persist) {
        throw new UnsupportedOperationException();
    }

    public DatabaseEntry remove(Hash key) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Removing key " + key);
        return _data.remove(key);
    }
}
