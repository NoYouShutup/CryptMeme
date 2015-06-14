package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Set;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Go through the routing table pick routers that are
 * is out of date, but don't expire routers we're actively connected to.
 *
 * We could in the future use profile data, netdb total size, a Kademlia XOR distance,
 * or other criteria to minimize netdb size, but for now we just use _facade's
 * validate(), which is a sliding expriation based on netdb size.
 *
 */
class ExpireRoutersJob extends JobImpl {
    private final Log _log;
    private final KademliaNetworkDatabaseFacade _facade;
    
    /** rerun fairly often, so the fails don't queue up too many netdb searches at once */
    private final static long RERUN_DELAY_MS = 5*60*1000;
    
    public ExpireRoutersJob(RouterContext ctx, KademliaNetworkDatabaseFacade facade) {
        super(ctx);
        _log = ctx.logManager().getLog(ExpireRoutersJob.class);
        _facade = facade;
    }
    
    public String getName() { return "Expire Routers Job"; }

    public void runJob() {
        if (getContext().commSystem().getReachabilityStatus() != CommSystemFacade.STATUS_DISCONNECTED) {
            int removed = expireKeys();
            if (_log.shouldLog(Log.INFO))
                _log.info("Routers expired: " + removed);
        }
        requeue(RERUN_DELAY_MS);
    }
    
    
    /**
     * Run through all of the known peers and pick ones that have really old
     * routerInfo publish dates, excluding ones that we are connected to,
     * so that they can be failed
     *
     * @return number removed
     */
    private int expireKeys() {
        Set<Hash> keys = _facade.getAllRouters();
        keys.remove(getContext().routerHash());
        if (keys.size() < 150)
            return 0;
        int removed = 0;
        for (Hash key : keys) {
            // Don't expire anybody we are connected to
            if (!getContext().commSystem().isEstablished(key)) {
                DatabaseEntry e = _facade.lookupLocallyWithoutValidation(key);
                if (e != null &&
                    e.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
                    try {
                        if (_facade.validate((RouterInfo) e) != null) {
                            _facade.dropAfterLookupFailed(key);
                            removed++;
                        }
                    } catch (IllegalArgumentException iae) {
                        _facade.dropAfterLookupFailed(key);
                        removed++;
                    }
                }
            }
        }
        return removed;
    }
}
