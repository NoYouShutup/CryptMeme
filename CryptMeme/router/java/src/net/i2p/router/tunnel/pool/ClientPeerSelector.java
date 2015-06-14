package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import static net.i2p.router.peermanager.ProfileOrganizer.Slice.*;

/**
 * Pick peers randomly out of the fast pool, and put them into tunnels
 * ordered by XOR distance from a random key.
 *
 */
class ClientPeerSelector extends TunnelPeerSelector {

    public ClientPeerSelector(RouterContext context) {
        super(context);
    }

    public List<Hash> selectPeers(TunnelPoolSettings settings) {
        int length = getLength(settings);
        if (length < 0)
            return null;
        if ( (length == 0) && (settings.getLength()+settings.getLengthVariance() > 0) )
            return null;

        List<Hash> rv;
    
        if (length > 0) {
            if (shouldSelectExplicit(settings))
                return selectExplicit(settings, length);
        
            Set<Hash> exclude = getExclude(settings.isInbound(), false);
            Set<Hash> matches = new HashSet<Hash>(length);
            if (length == 1) {
                // closest-hop restrictions
                Set<Hash> moreExclude = getClosestHopExclude(settings.isInbound());
                if (moreExclude != null)
                    exclude.addAll(moreExclude);
                ctx.profileOrganizer().selectFastPeers(length, exclude, matches, 0);
                matches.remove(ctx.routerHash());
                rv = new ArrayList<Hash>(matches);
            } else {
                // build a tunnel using 4 subtiers.
                // For a 2-hop tunnel, the first hop comes from subtiers 0-1 and the last from subtiers 2-3.
                // For a longer tunnels, the first hop comes from subtier 0, the middle from subtiers 2-3, and the last from subtier 1.
                rv = new ArrayList<Hash>(length + 1);
                // OBEP or IB last hop
                // group 0 or 1 if two hops, otherwise group 0
                Set<Hash> firstHopExclude;
                if (!settings.isInbound()) {
                    // exclude existing OBEPs to get some diversity ?

                    // closest-hop restrictions
                    Set<Hash> moreExclude = getClosestHopExclude(false);
                    if (moreExclude != null) {
                        moreExclude.addAll(exclude);
                        firstHopExclude = moreExclude;
                    } else {
                        firstHopExclude = exclude;
                    }
                } else {
                    firstHopExclude = exclude;
                }
                ctx.profileOrganizer().selectFastPeers(1, firstHopExclude, matches, settings.getRandomKey(), length == 2 ? SLICE_0_1 : SLICE_0);
                matches.remove(ctx.routerHash());
                exclude.addAll(matches);
                rv.addAll(matches);
                matches.clear();
                if (length > 2) {
                    // middle hop(s)
                    // group 2 or 3
                    ctx.profileOrganizer().selectFastPeers(length - 2, exclude, matches, settings.getRandomKey(), SLICE_2_3);
                    matches.remove(ctx.routerHash());
                    if (matches.size() > 1) {
                        // order the middle peers for tunnels >= 4 hops
                        List<Hash> ordered = new ArrayList<Hash>(matches);
                        orderPeers(ordered, settings.getRandomKey());
                        rv.addAll(ordered);
                    } else {
                        rv.addAll(matches);
                    }
                    exclude.addAll(matches);
                    matches.clear();
                }
                // IBGW or OB first hop
                // group 2 or 3 if two hops, otherwise group 1
                if (settings.isInbound()) {
                    // exclude existing IBGWs to get some diversity ?

                    // closest-hop restrictions
                    Set<Hash> moreExclude = getClosestHopExclude(true);
                    if (moreExclude != null)
                        exclude.addAll(moreExclude);
                }
                ctx.profileOrganizer().selectFastPeers(1, exclude, matches, settings.getRandomKey(), length == 2 ? SLICE_2_3 : SLICE_1);
                matches.remove(ctx.routerHash());
                rv.addAll(matches);
            }
        } else {
            rv = new ArrayList<Hash>(1);
        }
        
        if (settings.isInbound())
            rv.add(0, ctx.routerHash());
        else
            rv.add(ctx.routerHash());
        return rv;
    }
}
