package net.i2p.router.web;

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.text.DecimalFormat;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.DBHistory;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.stat.Rate;
import net.i2p.stat.RateAverages;
import net.i2p.stat.RateStat;

/**
 * Helper class to refactor the HTML rendering from out of the ProfileOrganizer
 *
 */
class ProfileOrganizerRenderer {
    private final RouterContext _context;
    private final ProfileOrganizer _organizer;
    
    public ProfileOrganizerRenderer(ProfileOrganizer organizer, RouterContext context) {
        _context = context;
        _organizer = organizer;
    }

    /**
     *  @param mode 0 = high cap; 1 = all; 2 = floodfill
     */
    public void renderStatusHTML(Writer out, int mode) throws IOException {
        boolean full = mode == 1;
        Set<Hash> peers = _organizer.selectAllPeers();
        
        long now = _context.clock().now();
        long hideBefore = now - 90*60*1000;
        
        Set<PeerProfile> order = new TreeSet<PeerProfile>(mode == 2 ? new HashComparator() : new ProfileComparator());
        int older = 0;
        int standard = 0;
        for (Hash peer : peers) {
            if (_organizer.getUs().equals(peer)) continue;
            PeerProfile prof = _organizer.getProfile(peer);
            if (mode == 2) {
                RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer);
                if (info != null && info.getCapabilities().indexOf("f") >= 0)
                    order.add(prof);
                continue;
            }
            if (prof.getLastSendSuccessful() <= hideBefore) {
                older++;
                continue;
            }
            if ((!full) && !_organizer.isHighCapacity(peer)) {
                standard++;
                continue;
            }
            order.add(prof);
        }
        
        int fast = 0;
        int reliable = 0;
        int integrated = 0;
        StringBuilder buf = new StringBuilder(16*1024);

      ////
      //// don't bother reindenting
      ////
      if (mode < 2) {

        //buf.append("<h2>").append(_("Peer Profiles")).append("</h2>\n<p>");
        buf.append(ngettext("Showing 1 recent profile.", "Showing {0} recent profiles.", order.size())).append('\n');
        if (older > 0)
            buf.append(ngettext("Hiding 1 older profile.", "Hiding {0} older profiles.", older)).append('\n');
        if (standard > 0)
            buf.append("<a href=\"/profiles?f=1\">").append(ngettext("Hiding 1 standard profile.", "Hiding {0} standard profiles.", standard)).append("</a>\n");
        buf.append("</p>");
                   buf.append("<table>");
                   buf.append("<tr>");
                   buf.append("<th>").append(_("Peer")).append("</th>");
                   buf.append("<th>").append(_("Groups (Caps)")).append("</th>");
                   buf.append("<th>").append(_("Speed")).append("</th>");
                   buf.append("<th>").append(_("Capacity")).append("</th>");
                   buf.append("<th>").append(_("Integration")).append("</th>");
                   buf.append("<th>").append(_("Status")).append("</th>");
                   buf.append("<th>&nbsp;</th>");
                   buf.append("</tr>");
        int prevTier = 1;
        for (PeerProfile prof : order) {
            Hash peer = prof.getPeer();
            
            int tier = 0;
            boolean isIntegrated = false;
            if (_organizer.isFast(peer)) {
                tier = 1;
                fast++;
                reliable++;
            } else if (_organizer.isHighCapacity(peer)) {
                tier = 2;
                reliable++;
            } else if (_organizer.isFailing(peer)) {
            } else {
                tier = 3;
            }
            
            if (_organizer.isWellIntegrated(peer)) {
                isIntegrated = true;
                integrated++;
            }
            
            if (tier != prevTier)
                buf.append("<tr><td colspan=\"7\"><hr></td></tr>\n");
            prevTier = tier;
            
            buf.append("<tr><td align=\"center\" nowrap>");
            buf.append(_context.commSystem().renderPeerHTML(peer));
            // debug
            //if(prof.getIsExpandedDB())
            //   buf.append(" ** ");
            buf.append("</td><td align=\"center\">");
            
            switch (tier) {
                case 1: buf.append(_("Fast, High Capacity")); break;
                case 2: buf.append(_("High Capacity")); break;
                case 3: buf.append(_("Standard")); break;
                default: buf.append(_("Failing")); break;
            }
            if (isIntegrated) buf.append(", ").append(_("Integrated"));
            RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer);
            if (info != null) {
                // prevent HTML injection in the caps and version
                buf.append(" (").append(DataHelper.stripHTML(info.getCapabilities()));
                String v = info.getOption("router.version");
                if (v != null)
                    buf.append(' ').append(DataHelper.stripHTML(v));
                buf.append(')');
            }
            
            buf.append("<td align=\"right\">").append(num(prof.getSpeedValue()));
            long bonus = prof.getSpeedBonus();
            if (bonus != 0) {
                if (bonus > 0)
                    buf.append(" (+");
                else
                    buf.append(" (");
                buf.append(bonus).append(')');
            }
            buf.append("</td><td align=\"right\">").append(num(prof.getCapacityValue()));
            bonus = prof.getCapacityBonus();
            if (bonus != 0) {
                if (bonus > 0)
                    buf.append(" (+");
                else
                    buf.append(" (");
                buf.append(bonus).append(')');
            }
            buf.append("</td><td align=\"right\">").append(num(prof.getIntegrationValue()));
            buf.append("</td><td align=\"center\">");
            if (_context.banlist().isBanlisted(peer)) buf.append(_("Banned"));
            if (prof.getIsFailing()) buf.append(' ').append(_("Failing"));
            if (_context.commSystem().wasUnreachable(peer)) buf.append(' ').append(_("Unreachable"));
            RateAverages ra = RateAverages.getTemp();
            Rate failed = prof.getTunnelHistory().getFailedRate().getRate(30*60*1000);
            long fails = failed.computeAverages(ra, false).getTotalEventCount();
            if (fails > 0) {
                Rate accepted = prof.getTunnelCreateResponseTime().getRate(30*60*1000);
                long total = fails + accepted.computeAverages(ra, false).getTotalEventCount();
                if (total / fails <= 10)   // hide if < 10%
                    buf.append(' ').append(fails).append('/').append(total).append(' ').append(_("Test Fails"));
            }
            buf.append("&nbsp;</td>");
            //buf.append("<td nowrap align=\"center\"><a target=\"_blank\" href=\"dumpprofile.jsp?peer=")
            //   .append(peer.toBase64().substring(0,6)).append("\">").append(_("profile")).append("</a>");
            buf.append("<td nowrap align=\"center\"><a href=\"viewprofile?peer=")
               .append(peer.toBase64()).append("\">").append(_("profile")).append("</a>");
            buf.append("&nbsp;<a href=\"configpeer?peer=").append(peer.toBase64()).append("\">+-</a></td>\n");
            buf.append("</tr>");
            // let's not build the whole page in memory (~500 bytes per peer)
            out.write(buf.toString());
            buf.setLength(0);
        }
        buf.append("</table>");

      ////
      //// don't bother reindenting
      ////
      } else {

        //buf.append("<h2><a name=\"flood\"></a>").append(_("Floodfill and Integrated Peers"))
        //   .append(" (").append(integratedPeers.size()).append(")</h2>\n");
        buf.append("<table>");
        buf.append("<tr>");
        buf.append("<th class=\"smallhead\">").append(_("Peer")).append("</th>");
        buf.append("<th class=\"smallhead\">").append(_("Caps")).append("</th>");
        buf.append("<th class=\"smallhead\">").append(_("Integ. Value")).append("</th>");
        buf.append("<th class=\"smallhead\">").append(_("Last Heard About")).append("</th>");
        buf.append("<th class=\"smallhead\">").append(_("Last Heard From")).append("</th>");
        buf.append("<th class=\"smallhead\">").append(_("Last Good Send")).append("</th>");        
        buf.append("<th class=\"smallhead\">").append(_("Last Bad Send")).append("</th>");
        buf.append("<th class=\"smallhead\">").append(_("10m Resp. Time")).append("</th>");
        buf.append("<th class=\"smallhead\">").append(_("1h Resp. Time")).append("</th>");
        buf.append("<th class=\"smallhead\">").append(_("1d Resp. Time")).append("</th>");
        buf.append("<th class=\"smallhead\">").append(_("Last Good Lookup")).append("</th>"); 
        buf.append("<th class=\"smallhead\">").append(_("Last Bad Lookup")).append("</th>");        
        buf.append("<th class=\"smallhead\">").append(_("Last Good Store")).append("</th>");
        buf.append("<th class=\"smallhead\">").append(_("Last Bad Store")).append("</th>");
        buf.append("<th class=\"smallhead\">").append(_("1h Fail Rate")).append("</th>");
        buf.append("<th class=\"smallhead\">").append(_("1d Fail Rate")).append("</th>");
        buf.append("</tr>");
        RateAverages ra = RateAverages.getTemp();
        for (PeerProfile prof : order) {
            Hash peer = prof.getPeer();

            buf.append("<tr><td align=\"center\" nowrap>");
            buf.append(_context.commSystem().renderPeerHTML(peer));
            buf.append("</td>");
            RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer);
            if (info != null)
                buf.append("<td align=\"center\">").append(DataHelper.stripHTML(info.getCapabilities())).append("</td>");
            else
                buf.append("<td>&nbsp;</td>");
            buf.append("<td align=\"right\">").append(num(prof.getIntegrationValue())).append("</td>");
            long time;
            time = now - prof.getLastHeardAbout();
            buf.append("<td align=\"right\">").append(DataHelper.formatDuration2(time)).append("</td>");
            time = now - prof.getLastHeardFrom();
            buf.append("<td align=\"right\">").append(DataHelper.formatDuration2(time)).append("</td>");
            time = now - prof.getLastSendSuccessful();
            buf.append("<td align=\"right\">").append(DataHelper.formatDuration2(time)).append("</td>");
            time = now - prof.getLastSendFailed();
            buf.append("<td align=\"right\">").append(DataHelper.formatDuration2(time)).append("</td>");
            buf.append("<td align=\"right\">").append(avg(prof, 10*60*1000l, ra)).append("</td>");
            buf.append("<td align=\"right\">").append(avg(prof, 60*60*1000l, ra)).append("</td>");
            buf.append("<td align=\"right\">").append(avg(prof, 24*60*60*1000l, ra)).append("</td>");
            DBHistory dbh = prof.getDBHistory();
            if (dbh != null) {
                time = now - dbh.getLastLookupSuccessful();
                buf.append("<td align=\"right\">").append(DataHelper.formatDuration2(time)).append("</td>");
                time = now - dbh.getLastLookupFailed();
                buf.append("<td align=\"right\">").append(DataHelper.formatDuration2(time)).append("</td>");
                time = now - dbh.getLastStoreSuccessful();
                buf.append("<td align=\"right\">").append(DataHelper.formatDuration2(time)).append("</td>");
                time = now - dbh.getLastStoreFailed();
                buf.append("<td align=\"right\">").append(DataHelper.formatDuration2(time)).append("</td>");
                buf.append("<td align=\"right\">").append(davg(dbh, 60*60*1000l, ra)).append("</td>");
                buf.append("<td align=\"right\">").append(davg(dbh, 24*60*60*1000l, ra)).append("</td>");
            } else {
                for (int i = 0; i < 6; i++)
                    buf.append("<td align=\"right\">").append(_(NA));
            }
            buf.append("</tr>\n");
        }
        buf.append("</table>");

      ////
      //// don't bother reindenting
      ////
      }
      if (mode < 2) {

        buf.append("<h3>").append(_("Thresholds")).append("</h3>");
        buf.append("<p><b>").append(_("Speed")).append(":</b> ").append(num(_organizer.getSpeedThreshold()))
           .append(" (").append(fast).append(' ').append(_("fast peers")).append(")<br>");
        buf.append("<b>").append(_("Capacity")).append(":</b> ").append(num(_organizer.getCapacityThreshold()))
           .append(" (").append(reliable).append(' ').append(_("high capacity peers")).append(")<br>");
        buf.append("<b>").append(_("Integration")).append(":</b> ").append(num(_organizer.getIntegrationThreshold()))
           .append(" (").append(integrated).append(' ').append(_(" well integrated peers")).append(")</p>");
        buf.append("<h3>").append(_("Definitions")).append("</h3><ul>");
        buf.append("<li><b>").append(_("groups")).append("</b>: ").append(_("as determined by the profile organizer")).append("</li>");
        buf.append("<li><b>").append(_("caps")).append("</b>: ").append(_("capabilities in the netDb, not used to determine profiles")).append("</li>");
        buf.append("<li><b>").append(_("speed")).append("</b>: ").append(_("peak throughput (bytes per second) over a 1 minute period that the peer has sustained in a single tunnel")).append("</li>");
        buf.append("<li><b>").append(_("capacity")).append("</b>: ").append(_("how many tunnels can we ask them to join in an hour?")).append("</li>");
        buf.append("<li><b>").append(_("integration")).append("</b>: ").append(_("how many new peers have they told us about lately?")).append("</li>");
        buf.append("<li><b>").append(_("status")).append("</b>: ").append(_("is the peer banned, or unreachable, or failing tunnel tests?")).append("</li>");
        buf.append("</ul>");

      ////
      //// don't bother reindenting
      ////
      }  // mode < 2

        out.write(buf.toString());
        out.flush();
    }
    
    private class ProfileComparator extends HashComparator {
        public int compare(PeerProfile left, PeerProfile right) {
            if (_context.profileOrganizer().isFast(left.getPeer())) {
                if (_context.profileOrganizer().isFast(right.getPeer())) {
                    return super.compare(left, right);
                } else {
                    return -1; // fast comes first
                }
            } else if (_context.profileOrganizer().isHighCapacity(left.getPeer())) {
                if (_context.profileOrganizer().isFast(right.getPeer())) {
                    return 1; 
                } else if (_context.profileOrganizer().isHighCapacity(right.getPeer())) {
                    return super.compare(left, right);
                } else {
                    return -1;
                }
            } else if (_context.profileOrganizer().isFailing(left.getPeer())) {
                if (_context.profileOrganizer().isFailing(right.getPeer())) {
                    return super.compare(left, right);
                } else {
                    return 1;
                }
            } else {
                // left is not failing
                if (_context.profileOrganizer().isFast(right.getPeer())) {
                    return 1;
                } else if (_context.profileOrganizer().isHighCapacity(right.getPeer())) {
                    return 1;
                } else if (_context.profileOrganizer().isFailing(right.getPeer())) {
                    return -1;
                } else {
                    return super.compare(left, right);
                }
            }
        }
    }
        
    /**
     *  Used for floodfill-only page
     *  @since 0.9.8
     */
    private static class HashComparator implements Comparator<PeerProfile>, Serializable {
        public int compare(PeerProfile left, PeerProfile right) {
            return left.getPeer().toBase64().compareTo(right.getPeer().toBase64());
        }
        
    }
    
    private final static DecimalFormat _fmt = new DecimalFormat("###,##0.00");
    private final static String num(double num) { synchronized (_fmt) { return _fmt.format(num); } }
    private final static String NA = HelperBase._x("n/a");

    private String avg (PeerProfile prof, long rate, RateAverages ra) {
            RateStat rs = prof.getDbResponseTime();
            if (rs == null)
                return _(NA);
            Rate r = rs.getRate(rate);
            if (r == null)
                return _(NA);
            r.computeAverages(ra, false);
            if (ra.getTotalEventCount() == 0)
                return _(NA);
            return DataHelper.formatDuration2(Math.round(ra.getAverage()));
    }

    private String davg (DBHistory dbh, long rate, RateAverages ra) {
            RateStat rs = dbh.getFailedLookupRate();
            if (rs == null)
                return "0%";
            Rate r = rs.getRate(rate);
            if (r == null)
                return "0%";
            r.computeAverages(ra, false);
            if (ra.getTotalEventCount() <= 0)
                return "0%";
            double avg = 0.5 + 100 * ra.getAverage();
            return ((int) avg) + "%";
    }

    /** translate a string */
    private String _(String s) {
        return Messages.getString(s, _context);
    }

    /** translate (ngettext) @since 0.8.5 */
    public String ngettext(String s, String p, int n) {
        return Messages.getString(n, s, p, _context);
    }

}
