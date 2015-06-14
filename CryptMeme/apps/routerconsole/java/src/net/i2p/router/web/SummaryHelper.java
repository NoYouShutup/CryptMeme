package net.i2p.router.web;

import java.io.IOException;
import java.text.Collator;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterVersion;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.transport.TransportUtil;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.PortMapper;

/**
 * Simple helper to query the appropriate router for data necessary to render
 * the summary sections on the router console.  
 *
 * For the full summary bar use renderSummaryBar()
 */
public class SummaryHelper extends HelperBase {

    // Opera 10.63 doesn't have the char, TODO check UA
    //static final String THINSP = "&thinsp;/&thinsp;";
    static final String THINSP = " / ";
    private static final char S = ',';
    static final String PROP_SUMMARYBAR = "routerconsole.summaryBar.";

    static final String DEFAULT_FULL =
        "HelpAndFAQ" + S +
        "I2PServices" + S +
        "I2PInternals" + S +
        "General" + S +
        "NetworkReachability" + S +
        "UpdateStatus" + S +
        "RestartStatus" + S +
        "Peers" + S +
        "FirewallAndReseedStatus" + S +
        "Bandwidth" + S +
        "Tunnels" + S +
        "Congestion" + S +
        "TunnelStatus" + S +
        "Destinations" + S +
        "";

    static final String DEFAULT_MINIMAL =
        "ShortGeneral" + S +
        "NewsHeadings" + S +
        "UpdateStatus" + S +
        "NetworkReachability" + S +
        "RestartStatus" + S +
        "FirewallAndReseedStatus" + S +
        "Destinations" + S +
        "";

    /**
     * Retrieve the shortened 4 character ident for the router located within
     * the current JVM at the given context.
     *
     */
    public String getIdent() { 
        if (_context == null) return "[no router]";
        
        if (_context.routerHash() != null)
            return _context.routerHash().toBase64().substring(0, 4);
        else
            return "[unknown]";
    }
    /**
     * Retrieve the version number of the router.
     *
     */
    public String getVersion() { 
        return RouterVersion.FULL_VERSION;
    }
    /**
     * Retrieve a pretty printed uptime count (ala 4d or 7h or 39m)
     *
     */
    public String getUptime() { 
        if (_context == null) return "[no router]";
        
        Router router = _context.router();
        if (router == null) 
            return "[not up]";
        else
            return DataHelper.formatDuration2(router.getUptime());
    }
    
/**
    this displayed offset, not skew - now handled in reachability()

    private String timeSkew() {
        if (_context == null) return "";
        //if (!_context.clock().getUpdatedSuccessfully())
        //    return " (Unknown skew)";
        long ms = _context.clock().getOffset();
        long diff = Math.abs(ms);
        if (diff < 3000)
            return "";
        return " (" + DataHelper.formatDuration2(diff) + " " + _("skew") + ")";
    }
**/
    
    public boolean allowReseed() {
        return _context.netDb().isInitialized() &&
               (_context.netDb().getKnownRouters() < 30) ||
                _context.getBooleanProperty("i2p.alwaysAllowReseed");
    }
    
    /** subtract one for ourselves, so if we know no other peers it displays zero */
    public int getAllPeers() { return Math.max(_context.netDb().getKnownRouters() - 1, 0); }
    
    public String getReachability() {
        return reachability(); // + timeSkew();
        // testing
        //return reachability() +
        //       " Offset: " + DataHelper.formatDuration(_context.clock().getOffset()) +
        //       " Slew: " + DataHelper.formatDuration(((RouterClock)_context.clock()).getDeltaOffset());
    }

    private String reachability() {
        if (_context.commSystem().isDummy())
            return "VM Comm System";
        if (_context.router().getUptime() > 60*1000 && (!_context.router().gracefulShutdownInProgress()) &&
            !_context.clientManager().isAlive())
            return _("ERR-Client Manager I2CP Error - check logs");  // not a router problem but the user should know
        // Warn based on actual skew from peers, not update status, so if we successfully offset
        // the clock, we don't complain.
        //if (!_context.clock().getUpdatedSuccessfully())
        long skew = _context.commSystem().getFramedAveragePeerClockSkew(33);
        // Display the actual skew, not the offset
        if (Math.abs(skew) > 30*1000)
            return _("ERR-Clock Skew of {0}", DataHelper.formatDuration2(Math.abs(skew)));
        if (_context.router().isHidden())
            return _("Hidden");
        RouterInfo routerInfo = _context.router().getRouterInfo();
        if (routerInfo == null)
            return _("Testing");

        Status status = _context.commSystem().getStatus();
        switch (status) {
            case OK:
            case IPV4_OK_IPV6_UNKNOWN:
            case IPV4_OK_IPV6_FIREWALLED:
            case IPV4_UNKNOWN_IPV6_OK:
            case IPV4_DISABLED_IPV6_OK:
            case IPV4_SNAT_IPV6_OK:
                RouterAddress ra = routerInfo.getTargetAddress("NTCP");
                if (ra == null)
                    return _(status.toStatusString());
                byte[] ip = ra.getIP();
                if (ip == null)
                    return _("ERR-Unresolved TCP Address");
                // TODO set IPv6 arg based on configuration?
                if (TransportUtil.isPubliclyRoutable(ip, true))
                    return _(status.toStatusString());
                return _("ERR-Private TCP Address");

            case IPV4_SNAT_IPV6_UNKNOWN:
            case DIFFERENT:
                return _("ERR-SymmetricNAT");

            case REJECT_UNSOLICITED:
            case IPV4_DISABLED_IPV6_FIREWALLED:
                if (routerInfo.getTargetAddress("NTCP") != null)
                    return _("WARN-Firewalled with Inbound TCP Enabled");
                // fall through...
            case IPV4_FIREWALLED_IPV6_OK:
            case IPV4_FIREWALLED_IPV6_UNKNOWN:
                if (((FloodfillNetworkDatabaseFacade)_context.netDb()).floodfillEnabled())
                    return _("WARN-Firewalled and Floodfill");
                //if (_context.router().getRouterInfo().getCapabilities().indexOf('O') >= 0)
                //    return _("WARN-Firewalled and Fast");
                return _(status.toStatusString());

            case DISCONNECTED:
                return _("Disconnected - check network cable");

            case HOSED:
                return _("ERR-UDP Port In Use - Set i2np.udp.internalPort=xxxx in advanced config and restart");

            case UNKNOWN:
            case IPV4_UNKNOWN_IPV6_FIREWALLED:
            case IPV4_DISABLED_IPV6_UNKNOWN:
            default:
                ra = routerInfo.getTargetAddress("SSU");
                if (ra == null && _context.router().getUptime() > 5*60*1000) {
                    if (getActivePeers() <= 0)
                        return _("ERR-No Active Peers, Check Network Connection and Firewall");
                    else if (_context.getProperty(ConfigNetHelper.PROP_I2NP_NTCP_HOSTNAME) == null ||
                        _context.getProperty(ConfigNetHelper.PROP_I2NP_NTCP_PORT) == null)
                        return _("ERR-UDP Disabled and Inbound TCP host/port not set");
                    else
                        return _("WARN-Firewalled with UDP Disabled");
                }
                return _(status.toStatusString());
        }
    }
    
    /**
     * Retrieve amount of used memory.
     *
     */
/********
    public String getMemory() {
        DecimalFormat integerFormatter = new DecimalFormat("###,###,##0");
        long used = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())/1024;
        long usedPc = 100 - ((Runtime.getRuntime().freeMemory() * 100) / Runtime.getRuntime().totalMemory());
        return integerFormatter.format(used) + "KB (" + usedPc + "%)"; 
    }
********/
    
    /**
     * How many peers we are talking to now
     *
     */
    public int getActivePeers() { 
        if (_context == null) 
            return 0;
        else
            return _context.commSystem().countActivePeers();
    }

    /**
     * Should we warn about a possible firewall problem?
     */
    public boolean showFirewallWarning() {
        return _context != null && 
               _context.netDb().isInitialized() &&
               _context.router().getUptime() > 2*60*1000 &&
               (!_context.commSystem().isDummy()) &&
               _context.commSystem().countActivePeers() <= 0 &&
               _context.netDb().getKnownRouters() > 5;
    }

    /**
     * How many active identities have we spoken with recently
     *
     */
    public int getActiveProfiles() { 
        if (_context == null) 
            return 0;
        else
            return _context.profileOrganizer().countActivePeers();
    }
    /**
     * How many active peers the router ranks as fast.
     *
     */
    public int getFastPeers() { 
        if (_context == null) 
            return 0;
        else
            return _context.profileOrganizer().countFastPeers();
    }
    /**
     * How many active peers the router ranks as having a high capacity.
     *
     */
    public int getHighCapacityPeers() { 
        if (_context == null) 
            return 0;
        else
            return _context.profileOrganizer().countHighCapacityPeers();
    }
    /**
     * How many active peers the router ranks as well integrated.
     *
     */
    public int getWellIntegratedPeers() { 
        if (_context == null) 
            return 0;
        //return _context.profileOrganizer().countWellIntegratedPeers();
        return _context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL).size();
    }
    /**
     * How many peers the router ranks as failing.
     *
     */
/********
    public int getFailingPeers() { 
        if (_context == null) 
            return 0;
        else
            return _context.profileOrganizer().countFailingPeers();
    }
********/
    /**
     * How many peers totally suck.
     *
     */
/********
    public int getBanlistedPeers() { 
        if (_context == null) 
            return 0;
        else
            return _context.banlist().getRouterCount();
    }
********/
 
    /**
     *    @return "x.xx / y.yy {K|M}"
     */
    public String getSecondKBps() { 
        if (_context == null) 
            return "0 / 0";
        return formatPair(_context.bandwidthLimiter().getReceiveBps(), 
                          _context.bandwidthLimiter().getSendBps());
    }
    
    /**
     *    @return "x.xx / y.yy {K|M}"
     */
    public String getFiveMinuteKBps() {
        if (_context == null) 
            return "0 / 0";
        
        RateStat receiveRate = _context.statManager().getRate("bw.recvRate");
        double in = 0;
        if (receiveRate != null) {
            Rate r = receiveRate.getRate(5*60*1000);
            if (r != null)
                in = r.getAverageValue();
        }
        RateStat sendRate = _context.statManager().getRate("bw.sendRate");
        double out = 0;
        if (sendRate != null) {
            Rate r = sendRate.getRate(5*60*1000);
            if (r != null)
                out = r.getAverageValue();
        }
        return formatPair(in, out);
    }
    
    /**
     *    @return "x.xx / y.yy {K|M}"
     */
    public String getLifetimeKBps() { 
        if (_context == null) 
            return "0 / 0";
        
        RateStat receiveRate = _context.statManager().getRate("bw.recvRate");
        double in;
        if (receiveRate == null)
            in = 0;
        else
            in = receiveRate.getLifetimeAverageValue();
        RateStat sendRate = _context.statManager().getRate("bw.sendRate");
        double out;
        if (sendRate == null)
            out = 0;
        else
            out = sendRate.getLifetimeAverageValue();
        return formatPair(in, out);
    }
    
    /**
     *    @return "x.xx / y.yy {K|M}"
     */
    private static String formatPair(double in, double out) {
        boolean mega = in >= 1024*1024 || out >= 1024*1024;
        // scale both the same
        if (mega) {
            in /= 1024*1024;
            out /= 1024*1024;
        } else {
            in /= 1024;
            out /= 1024;
        }
        // control total width
        DecimalFormat fmt;
        if (in >= 1000 || out >= 1000)
            fmt = new DecimalFormat("#0");
        else if (in >= 100 || out >= 100)
            fmt = new DecimalFormat("#0.0");
        else
            fmt = new DecimalFormat("#0.00");
        return fmt.format(in) + THINSP + fmt.format(out) + "&nbsp;" +
               (mega ? 'M' : 'K');
    }

    /**
     * How much data have we received since the router started (pretty printed
     * string with 2 decimal places and the appropriate units - GB/MB/KB/bytes)
     *
     */
    public String getInboundTransferred() { 
        if (_context == null) 
            return "0";
        
        long received = _context.bandwidthLimiter().getTotalAllocatedInboundBytes();

        return DataHelper.formatSize2(received) + 'B';
    }
    
    /**
     * How much data have we sent since the router started (pretty printed
     * string with 2 decimal places and the appropriate units - GB/MB/KB/bytes)
     *
     */
    public String getOutboundTransferred() { 
        if (_context == null) 
            return "0";
        
        long sent = _context.bandwidthLimiter().getTotalAllocatedOutboundBytes();
        return DataHelper.formatSize2(sent) + 'B';
    }
    
    /**
     * Client destinations connected locally.
     *
     * @return html section summary
     */
    public String getDestinations() {
        // convert the set to a list so we can sort by name and not lose duplicates
        List<Destination> clients = new ArrayList<Destination>(_context.clientManager().listClients());
        
        StringBuilder buf = new StringBuilder(512);
        buf.append("<h3><a href=\"/i2ptunnelmgr\" target=\"_top\" title=\"")
           .append(_("Add/remove/edit &amp; control your client and server tunnels"))
           .append("\">").append(_("Local Tunnels"))
           .append("</a></h3><hr class=\"b\"><div class=\"tunnels\">");
        if (!clients.isEmpty()) {
            Collections.sort(clients, new AlphaComparator());
            buf.append("<table>");
            
            for (Destination client : clients) {
                String name = getName(client);
                Hash h = client.calculateHash();
                
                buf.append("<tr><td align=\"right\"><img src=\"/themes/console/images/");
                if (_context.clientManager().shouldPublishLeaseSet(h))
                    buf.append("server.png\" alt=\"Server\" title=\"").append(_("Hidden Service")).append("\">");
                else
                    buf.append("client.png\" alt=\"Client\" title=\"").append(_("Client")).append("\">");
                buf.append("</td><td align=\"left\"><b><a href=\"tunnels#").append(h.toBase64().substring(0,4));
                buf.append("\" target=\"_top\" title=\"").append(_("Show tunnels")).append("\">");
                if (name.length() < 18)
                    buf.append(DataHelper.escapeHTML(name));
                else
                    buf.append(DataHelper.escapeHTML(name.substring(0,15))).append("&hellip;");
                buf.append("</a></b></td>\n");
                LeaseSet ls = _context.netDb().lookupLeaseSetLocally(h);
                if (ls != null && _context.tunnelManager().getOutboundClientTunnelCount(h) > 0) {
                    long timeToExpire = ls.getEarliestLeaseDate() - _context.clock().now();
                    if (timeToExpire < 0) {
                        // red or yellow light                 
                        buf.append("<td><img src=\"/themes/console/images/local_inprogress.png\" alt=\"").append(_("Rebuilding")).append("&hellip;\" title=\"").append(_("Leases expired")).append(" ").append(DataHelper.formatDuration2(0-timeToExpire));
                        buf.append(" ").append(_("ago")).append(". ").append(_("Rebuilding")).append("&hellip;\"></td></tr>\n");                    
                    } else {
                        // green light 
                        buf.append("<td><img src=\"/themes/console/images/local_up.png\" alt=\"Ready\" title=\"").append(_("Ready")).append("\"></td></tr>\n");
                    }
                } else {
                    // yellow light
                    buf.append("<td><img src=\"/themes/console/images/local_inprogress.png\" alt=\"").append(_("Building")).append("&hellip;\" title=\"").append(_("Building tunnels")).append("&hellip;\"></td></tr>\n");
                }
            }
            buf.append("</table>");
        } else {
            buf.append("<center><i>").append(_("none")).append("</i></center>");
        }
        buf.append("</div>\n");
        return buf.toString();
    }
    
    /**
     *  Compare translated nicknames - put "shared clients" first in the sort
     *  Inner class, can't be Serializable
     */
    private class AlphaComparator implements Comparator<Destination> {
        private final String xsc = _("shared clients");

        public int compare(Destination lhs, Destination rhs) {
            String lname = getName(lhs);
            String rname = getName(rhs);
            if (lname.equals(xsc))
                return -1;
            if (rname.equals(xsc))
                return 1;
            return Collator.getInstance().compare(lname, rname);
        }
    }

    /** translate here so collation works above */
    private String getName(Destination d) {
        TunnelPoolSettings in = _context.tunnelManager().getInboundSettings(d.calculateHash());
        String name = (in != null ? in.getDestinationNickname() : null);
        if (name == null) {
            TunnelPoolSettings out = _context.tunnelManager().getOutboundSettings(d.calculateHash());
            name = (out != null ? out.getDestinationNickname() : null);
            if (name == null)
                name = d.calculateHash().toBase64().substring(0,6);
            else
                name = _(name);
        } else {
            name = _(name);
        }
        return name;
    }

    /**
     * How many free inbound tunnels we have.
     *
     */
    public int getInboundTunnels() { 
        if (_context == null) 
            return 0;
        else
            return _context.tunnelManager().getFreeTunnelCount();
    }
    
    /**
     * How many active outbound tunnels we have.
     *
     */
    public int getOutboundTunnels() { 
        if (_context == null) 
            return 0;
        else
            return _context.tunnelManager().getOutboundTunnelCount();
    }
    
    /**
     * How many inbound client tunnels we have.
     *
     */
    public int getInboundClientTunnels() { 
        if (_context == null) 
            return 0;
        else
            return _context.tunnelManager().getInboundClientTunnelCount();
    }
    
    /**
     * How many active outbound client tunnels we have.
     *
     */
    public int getOutboundClientTunnels() { 
        if (_context == null) 
            return 0;
        else
            return _context.tunnelManager().getOutboundClientTunnelCount();
    }
    
    /**
     * How many tunnels we are participating in.
     *
     */
    public int getParticipatingTunnels() { 
        if (_context == null) 
            return 0;
        else
            return _context.tunnelManager().getParticipatingCount();
    }
 
    /** @since 0.7.10 */
    public String getShareRatio() { 
        if (_context == null) 
            return "0";
        double sr = _context.tunnelManager().getShareRatio();
        DecimalFormat fmt = new DecimalFormat("##0.00");
        return fmt.format(sr);
    }

    /**
     * How lagged our job queue is over the last minute (pretty printed with
     * the units attached)
     *
     */
    public String getJobLag() { 
        if (_context == null) 
            return "0";
        
        RateStat rs = _context.statManager().getRate("jobQueue.jobLag");
        if (rs == null)
            return "0";
        Rate lagRate = rs.getRate(60*1000);
        return DataHelper.formatDuration2((long)lagRate.getAverageValue());
    }
 
    /**
     * How long it takes us to pump out a message, averaged over the last minute 
     * (pretty printed with the units attached)
     *
     */   
    public String getMessageDelay() { 
        if (_context == null) 
            return "0";
        
        return DataHelper.formatDuration2(_context.throttle().getMessageDelay());
    }
    
    /**
     * How long it takes us to test our tunnels, averaged over the last 10 minutes
     * (pretty printed with the units attached)
     *
     */
    public String getTunnelLag() { 
        if (_context == null) 
            return "0";
        
        return DataHelper.formatDuration2(_context.throttle().getTunnelLag());
    }
    
    public String getTunnelStatus() { 
        if (_context == null) 
            return "";
        return _context.throttle().getTunnelStatus();
    }
    
    public String getInboundBacklog() {
        if (_context == null)
            return "0";
        
        return String.valueOf(_context.tunnelManager().getInboundBuildQueueSize());
    }
    
/*******
    public String getPRNGStatus() {
        Rate r = _context.statManager().getRate("prng.bufferWaitTime").getRate(60*1000);
        int use = (int) r.getLastEventCount();
        int i = (int) (r.getAverageValue() + 0.5);
        if (i <= 0) {
            r = _context.statManager().getRate("prng.bufferWaitTime").getRate(10*60*1000);
            i = (int) (r.getAverageValue() + 0.5);
        }
        String rv = i + "/";
        r = _context.statManager().getRate("prng.bufferFillTime").getRate(60*1000);
        i = (int) (r.getAverageValue() + 0.5);
        if (i <= 0) {
            r = _context.statManager().getRate("prng.bufferFillTime").getRate(10*60*1000);
            i = (int) (r.getAverageValue() + 0.5);
        }
        rv = rv + i + "ms";
        // margin == fill time / use time
        if (use > 0 && i > 0)
            rv = rv + ' ' + (60*1000 / (use * i)) + 'x';
        return rv;
    }
********/

    private static boolean updateAvailable() { 
        return NewsHelper.isUpdateAvailable();
    }

    private boolean unsignedUpdateAvailable() { 
        return NewsHelper.isUnsignedUpdateAvailable(_context);
    }

    /** @since 0.9.20 */
    private boolean devSU3UpdateAvailable() { 
        return NewsHelper.isDevSU3UpdateAvailable(_context);
    }

    private static String getUpdateVersion() { 
        return DataHelper.escapeHTML(NewsHelper.updateVersion());
    }

    private static String getUnsignedUpdateVersion() { 
        // value is a formatted date, does not need escaping
        return NewsHelper.unsignedUpdateVersion();
    }

    /** @since 0.9.20 */
    private static String getDevSU3UpdateVersion() { 
        return DataHelper.escapeHTML(NewsHelper.devSU3UpdateVersion());
    }

    /**
     *  The update status and buttons
     *  @since 0.8.13 moved from SummaryBarRenderer
     */
    public String getUpdateStatus() {
        StringBuilder buf = new StringBuilder(512);
        // display all the time so we display the final failure message, and plugin update messages too
        String status = NewsHelper.getUpdateStatus();
        boolean needSpace = false;
        if (status.length() > 0) {
            buf.append("<h4>").append(status).append("</h4>\n");
            needSpace = true;
        }
        String dver = NewsHelper.updateVersionDownloaded();
        if (dver == null) {
            dver = NewsHelper.devSU3VersionDownloaded();
            if (dver == null)
                dver = NewsHelper.unsignedVersionDownloaded();
        }
        if (dver != null &&
            !NewsHelper.isUpdateInProgress() &&
            !_context.router().gracefulShutdownInProgress()) {
            if (needSpace)
                buf.append("<hr>");
            else
                needSpace = true;
            buf.append("<h4><b>").append(_("Update downloaded")).append("<br>");
            if (_context.hasWrapper())
                buf.append(_("Click Restart to install"));
            else
                buf.append(_("Click Shutdown and restart to install"));
            buf.append(' ').append(_("Version {0}", DataHelper.escapeHTML(dver)));
            buf.append("</b></h4>");
        }
        boolean avail = updateAvailable();
        boolean unsignedAvail = unsignedUpdateAvailable();
        boolean devSU3Avail = devSU3UpdateAvailable();
        String constraint = avail ? NewsHelper.updateConstraint() : null;
        if (avail && constraint != null &&
            !NewsHelper.isUpdateInProgress() &&
            !_context.router().gracefulShutdownInProgress()) {
            if (needSpace)
                buf.append("<hr>");
            else
                needSpace = true;
            buf.append("<h4><b>").append(_("Update available")).append(":<br>");
            buf.append(_("Version {0}", getUpdateVersion())).append("<br>");
            buf.append(constraint).append("</b></h4>");
            avail = false;
        }
        if ((avail || unsignedAvail || devSU3Avail) &&
            !NewsHelper.isUpdateInProgress() &&
            !_context.router().gracefulShutdownInProgress() &&
            _context.portMapper().getPort(PortMapper.SVC_HTTP_PROXY) > 0 &&  // assume using proxy for now
            getAction() == null &&
            getUpdateNonce() == null) {
                if (needSpace)
                    buf.append("<hr>");
                long nonce = _context.random().nextLong();
                String prev = System.getProperty("net.i2p.router.web.UpdateHandler.nonce");
                if (prev != null)
                    System.setProperty("net.i2p.router.web.UpdateHandler.noncePrev", prev);
                System.setProperty("net.i2p.router.web.UpdateHandler.nonce", nonce+"");
                String uri = getRequestURI();
                buf.append("<form action=\"").append(uri).append("\" method=\"POST\">\n");
                buf.append("<input type=\"hidden\" name=\"updateNonce\" value=\"").append(nonce).append("\" >\n");
                if (avail) {
                    buf.append("<button type=\"submit\" class=\"download\" name=\"updateAction\" value=\"signed\" >")
                       // Note to translators: parameter is a version, e.g. "0.8.4"
                       .append(_("Download {0} Update", getUpdateVersion()))
                       .append("</button><br>\n");
                }
                if (devSU3Avail) {
                    buf.append("<button type=\"submit\" class=\"download\" name=\"updateAction\" value=\"DevSU3\" >")
                       // Note to translators: parameter is a router version, e.g. "0.9.19-16"
                       // <br> is optional, to help the browser make the lines even in the button
                       // If the translation is shorter than the English, you should probably not include <br>
                       .append(_("Download Signed<br>Development Update<br>{0}", getDevSU3UpdateVersion()))
                       .append("</button><br>\n");
                }
                if (unsignedAvail) {
                    buf.append("<button type=\"submit\" class=\"download\" name=\"updateAction\" value=\"Unsigned\" >")
                       // Note to translators: parameter is a date and time, e.g. "02-Mar 20:34 UTC"
                       // <br> is optional, to help the browser make the lines even in the button
                       // If the translation is shorter than the English, you should probably not include <br>
                       .append(_("Download Unsigned<br>Update {0}", getUnsignedUpdateVersion()))
                       .append("</button><br>\n");
                }
                buf.append("</form>\n");
        }
        return buf.toString();
    }

    /**
     *  The restart status and buttons
     *  @since 0.8.13 moved from SummaryBarRenderer
     */
    public String getRestartStatus() {
        return ConfigRestartBean.renderStatus(getRequestURI(), getAction(), getConsoleNonce());
    }

    /**
     *  The firewall status and reseed status/buttons
     *  @since 0.9 moved from SummaryBarRenderer
     */
    public String getFirewallAndReseedStatus() {
        StringBuilder buf = new StringBuilder(256);
        if (showFirewallWarning()) {
            buf.append("<h4><a href=\"/confignet\" target=\"_top\" title=\"")
               .append(_("Help with firewall configuration"))
               .append("\">")
               .append(_("Check network connection and NAT/firewall"))
               .append("</a></h4>");
        }

        boolean reseedInProgress = _context.netDb().reseedChecker().inProgress();
        // If showing the reseed link is allowed
        if (allowReseed()) {
            if (reseedInProgress) {
                // While reseed occurring, show status message instead
                buf.append("<i>").append(_context.netDb().reseedChecker().getStatus()).append("</i><br>");
            } else {
                // While no reseed occurring, show reseed link
                long nonce = _context.random().nextLong();
                String prev = System.getProperty("net.i2p.router.web.ReseedHandler.nonce");
                if (prev != null) System.setProperty("net.i2p.router.web.ReseedHandler.noncePrev", prev);
                System.setProperty("net.i2p.router.web.ReseedHandler.nonce", nonce+"");
                String uri = getRequestURI();
                buf.append("<p><form action=\"").append(uri).append("\" method=\"POST\">\n");
                buf.append("<input type=\"hidden\" name=\"reseedNonce\" value=\"").append(nonce).append("\" >\n");
                buf.append("<button type=\"submit\" class=\"reload\" value=\"Reseed\" >").append(_("Reseed")).append("</button></form></p>\n");
            }
        }
        // If a new reseed ain't running, and the last reseed had errors, show error message
        if (!reseedInProgress) {
            String reseedErrorMessage = _context.netDb().reseedChecker().getError();
            if (reseedErrorMessage.length() > 0) {
                buf.append("<i>").append(reseedErrorMessage).append("</i><br>");
            }
        }
        if (buf.length() <= 0)
            return "";
        return buf.toString();
    }

    private NewsHelper _newshelper;
    public void storeNewsHelper(NewsHelper n) { _newshelper = n; }
    public NewsHelper getNewsHelper() { return _newshelper; }

    public List<String> getSummaryBarSections(String page) {
        String config = "";
        if ("home".equals(page)) {
            config = _context.getProperty(PROP_SUMMARYBAR + page, DEFAULT_MINIMAL);
        } else {
            config = _context.getProperty(PROP_SUMMARYBAR + page);
            if (config == null)
                config = _context.getProperty(PROP_SUMMARYBAR + "default", DEFAULT_FULL);
        }
        return Arrays.asList(config.split("" + S));
    }

    static void saveSummaryBarSections(RouterContext ctx, String page, Map<Integer, String> sections) {
        StringBuilder buf = new StringBuilder(512);
        for(String section : sections.values())
            buf.append(section).append(S);
        ctx.router().saveConfig(PROP_SUMMARYBAR + page, buf.toString());
    }

    /** output the summary bar to _out */
    public void renderSummaryBar() throws IOException {
        SummaryBarRenderer renderer = new SummaryBarRenderer(_context, this);
        renderer.renderSummaryHTML(_out);
    }

    /* below here is stuff we need to get from summarynoframe.jsp to SummaryBarRenderer */

    private String _action;
    public void setAction(String s) { _action = s == null ? null : DataHelper.stripHTML(s); }
    public String getAction() { return _action; }

    private String _consoleNonce;
    public void setConsoleNonce(String s) { _consoleNonce = s == null ? null : DataHelper.stripHTML(s); }
    public String getConsoleNonce() { return _consoleNonce; }

    private String _updateNonce;
    public void setUpdateNonce(String s) { _updateNonce = s == null ? null : DataHelper.stripHTML(s); }
    public String getUpdateNonce() { return _updateNonce; }

    private String _requestURI;
    public void setRequestURI(String s) { _requestURI = s == null ? null : DataHelper.stripHTML(s); }

    /**
     * @return non-null; "/home" if (strangely) not set by jsp
     */
    public String getRequestURI() {
        return _requestURI != null ? _requestURI : "/home";
    }

    public String getConfigTable() {
        String[] allSections = SummaryBarRenderer.ALL_SECTIONS;
        Map<String, String> sectionNames = SummaryBarRenderer.SECTION_NAMES;
        List<String> sections = getSummaryBarSections("default");
        TreeSet<String> sortedSections = new TreeSet<String>();

        for (int i = 0; i < allSections.length; i++) {
            String section = allSections[i];
            if (!sections.contains(section))
                sortedSections.add(section);
        }

        String theme = _context.getProperty(CSSHelper.PROP_THEME_NAME, CSSHelper.DEFAULT_THEME);
        String imgPath = CSSHelper.BASE_THEME_PATH + theme + "/images/";

        StringBuilder buf = new StringBuilder(2048);
        buf.append("<table class=\"sidebarconf\"><tr><th>")
           .append(_("Remove"))
           .append("</th><th>")
           .append(_("Name"))
           .append("</th><th colspan=\"2\">")
           .append(_("Order"))
           .append("</th></tr>\n");
        for (String section : sections) {
            int i = sections.indexOf(section);
            buf.append("<tr><td align=\"center\"><input type=\"checkbox\" class=\"optbox\" name=\"delete_")
               .append(i)
               .append("\"></td><td align=\"left\">")
               .append(_(sectionNames.get(section)))
               .append("</td><td align=\"right\"><input type=\"hidden\" name=\"order_")
               .append(i).append('_').append(section)
               .append("\" value=\"")
               .append(i)
               .append("\">");
            if (i > 0) {
                buf.append("<button type=\"submit\" class=\"buttonTop\" name=\"action\" value=\"move_")
                   .append(i)
                   .append("_top\"><img alt=\"")
                   .append(_("Top"))
                   .append("\" src=\"" + imgPath + "move_top.png\" /></button>");
                buf.append("<button type=\"submit\" class=\"buttonUp\" name=\"action\" value=\"move_")
                   .append(i)
                   .append("_up\"><img alt=\"")
                   .append(_("Up"))
                   .append("\" src=\"" + imgPath + "move_up.png\" /></button>");
            }
            buf.append("</td><td align=\"left\">");
            if (i < sections.size() - 1) {
                buf.append("<button type=\"submit\" class=\"buttonDown\" name=\"action\" value=\"move_")
                   .append(i)
                   .append("_down\"><img alt=\"")
                   .append(_("Down"))
                   .append("\" src=\"" + imgPath + "move_down.png\" /></button>");
                buf.append("<button type=\"submit\" class=\"buttonBottom\" name=\"action\" value=\"move_")
                   .append(i)
                   .append("_bottom\"><img alt=\"")
                   .append(_("Bottom"))
                   .append("\" src=\"" + imgPath + "move_bottom.png\" /></button>");
            }
            buf.append("</td></tr>\n");
        }
        buf.append("<tr><td align=\"center\">" +
                   "<input type=\"submit\" name=\"action\" class=\"delete\" value=\"")
           .append(_("Delete selected"))
           .append("\"></td><td align=\"left\"><b>")
           .append(_("Add")).append(":</b> " +
                   "<select name=\"name\">\n" +
                   "<option value=\"\" selected=\"selected\">")
           .append(_("Select a section to add"))
           .append("</option>\n");

        for (String s : sortedSections) {
            buf.append("<option value=\"").append(s).append("\">")
               .append(sectionNames.get(s)).append("</option>\n");
        }

        buf.append("</select>\n" +
                   "<input type=\"hidden\" name=\"order\" value=\"")
           .append(sections.size())
           .append("\"></td>" +
                   "<td align=\"center\" colspan=\"2\">" +
                   "<input type=\"submit\" name=\"action\" class=\"add\" value=\"")
           .append(_("Add item"))
           .append("\"></td></tr>")
           .append("</table>\n");
        return buf.toString();
    }
}
