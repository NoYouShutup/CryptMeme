package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterInfo;
import net.i2p.kademlia.KBucketSet;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Search for a particular key iteratively until we either find a value, we run
 * out of peers, or the bucket the key belongs in has sufficient values in it.
 * Well, we're skipping the 'bucket gets filled up' test for now, since it'll never
 * get used (at least for a while).
 *
 */
class ExploreJob extends SearchJob {
    private FloodfillPeerSelector _peerSelector;
    
    /** how long each exploration should run for
     *  The exploration won't "succeed" so we make it long so we query several peers */
    private static final long MAX_EXPLORE_TIME = 15*1000;
    
    /** how many peers to explore through concurrently */
    private static final int EXPLORE_BREDTH = 1;

    /** only send the closest "dont tell me about" refs...
     *  Override to make this bigger because we want to include both the
     *  floodfills and the previously-queried peers */
    static final int MAX_CLOSEST = 20; // LINT -- field hides another field, this isn't an override.
    
    /** Override to make this shorter, since we don't sort out the
     *  unresponsive ff peers like we do in FloodOnlySearchJob */
    static final int PER_FLOODFILL_PEER_TIMEOUT = 5*1000; // LINT -- field hides another field, this isn't an override.

    /**
     * Create a new search for the routingKey specified
     *
     */
    public ExploreJob(RouterContext context, KademliaNetworkDatabaseFacade facade, Hash key) {
        // note that we're treating the last param (isLease) as *false* since we're just exploring.
        // if this collides with an actual leaseSet's key, neat, but that wouldn't imply we're actually
        // attempting to send that lease a message!
        super(context, facade, key, null, null, MAX_EXPLORE_TIME, false, false);
        _peerSelector = (FloodfillPeerSelector) (_facade.getPeerSelector());
    }
    
    /**
     * Build the database search message, but unlike the normal searches, we're more explicit in
     * what we /dont/ want.  We don't just ask them to ignore the peers we've already searched
     * on, but to ignore a number of the peers we already know about (in the target key's bucket) as well.
     *
     * Perhaps we may want to ignore other keys too, such as the ones in nearby
     * buckets, but we probably don't want the dontIncludePeers set to get too
     * massive (aka sending the entire routing table as 'dont tell me about these
     * guys').  but maybe we do.  dunno.  lots of implications.
     *
     * FloodfillPeerSelector would add only the floodfill peers,
     * and PeerSelector doesn't include the floodfill peers,
     * so we add the ff peers ourselves and then use the regular PeerSelector.
     *
     * @param replyTunnelId tunnel to receive replies through, or our router hash if replyGateway is null
     * @param replyGateway gateway for the reply tunnel, if null, we are sending direct, do not encrypt
     * @param expiration when the search should stop
     * @param peer the peer to send it to
     *
     * @return a DatabaseLookupMessage or GarlicMessage
     */
    @Override
    protected I2NPMessage buildMessage(TunnelId replyTunnelId, Hash replyGateway, long expiration, RouterInfo peer) {
        DatabaseLookupMessage msg = new DatabaseLookupMessage(getContext(), true);
        msg.setSearchKey(getState().getTarget());
        msg.setFrom(replyGateway);
        // Moved below now that DLM makes a copy
        //msg.setDontIncludePeers(getState().getClosestAttempted(MAX_CLOSEST));
        Set<Hash> dontIncludePeers = getState().getClosestAttempted(MAX_CLOSEST);
        msg.setMessageExpiration(expiration);
        if (replyTunnelId != null)
            msg.setReplyTunnel(replyTunnelId);
        
        int available = MAX_CLOSEST - dontIncludePeers.size();
        if (available > 0) {
            // Add a flag to say this is an exploration and we don't want floodfills in the responses.
            // Doing it this way is of course backwards-compatible.
            // Supported as of 0.7.9
            if (dontIncludePeers.add(Hash.FAKE_HASH))
                available--;
        }
        // supported as of 0.9.16. TODO remove fake hash above
        msg.setSearchType(DatabaseLookupMessage.Type.EXPL);

        KBucketSet<Hash> ks = _facade.getKBuckets();
        Hash rkey = getContext().routingKeyGenerator().getRoutingKey(getState().getTarget());
        // in a few releases, we can (and should) remove this,
        // as routers will honor the above flag, and we want the table to include
        // only non-floodfills.
        // Removed in 0.8.8, good thing, as we had well over MAX_CLOSEST floodfills.
        //if (available > 0 && ks != null) {
        //    List peers = _peerSelector.selectFloodfillParticipants(rkey, available, ks);
        //    int len = peers.size();
        //    if (len > 0)
        //        msg.getDontIncludePeers().addAll(peers);
        //}
        
        available = MAX_CLOSEST - dontIncludePeers.size();
        if (available > 0) {
            // selectNearestExplicit adds our hash to the dontInclude set (3rd param) ...
            // And we end up with MAX_CLOSEST+1 entries.
            // We don't want our hash in the message's don't-include list though.
            // We're just exploring, but this could give things away, and tie our exploratory tunnels to our router,
            // so let's not put our hash in there.
            Set<Hash> dontInclude = new HashSet<Hash>(dontIncludePeers);
            List<Hash> peers = _peerSelector.selectNearestExplicit(rkey, available, dontInclude, ks);
            dontIncludePeers.addAll(peers);
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Peers we don't want to hear about: " + dontIncludePeers);
        
        msg.setDontIncludePeers(dontIncludePeers);

        // Now encrypt if we can
        I2NPMessage outMsg;
        if (replyTunnelId != null &&
            getContext().getProperty(IterativeSearchJob.PROP_ENCRYPT_RI, IterativeSearchJob.DEFAULT_ENCRYPT_RI)) {
            // request encrypted reply?
            if (DatabaseLookupMessage.supportsEncryptedReplies(peer)) {
                MessageWrapper.OneTimeSession sess;
                sess = MessageWrapper.generateSession(getContext());
                if (_log.shouldLog(Log.INFO))
                    _log.info(getJobId() + ": Requesting encrypted reply from " + peer.getIdentity().calculateHash() +
                              ' ' + sess.key + ' ' + sess.tag);
                msg.setReplySession(sess.key, sess.tag);
            }
            outMsg = MessageWrapper.wrap(getContext(), msg, peer);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getJobId() + ": Encrypted exploratory DLM for " + getState().getTarget() + " to " +
                           peer.getIdentity().calculateHash());
        } else {
            outMsg = msg;
        }
        return outMsg;
    }
    
    /** max # of concurrent searches */
    @Override
    protected int getBredth() { return EXPLORE_BREDTH; }
    

    /**
     * We've gotten a search reply that contained the specified
     * number of peers that we didn't know about before.
     *
     */
    @Override
    protected void newPeersFound(int numNewPeers) {
        // who cares about how many new peers.  well, maybe we do.  but for now,
        // we'll do the simplest thing that could possibly work.
        _facade.setLastExploreNewDate(getContext().clock().now());
    }
    
    /*
     * We could override searchNext to see if we actually fill up a kbucket before
     * the search expires, but, c'mon, the keyspace is just too bloody massive, and
     * buckets wont be filling anytime soon, so might as well just use the SearchJob's
     * searchNext
     *
     */
    
    @Override
    public String getName() { return "Kademlia NetDb Explore"; }
}
