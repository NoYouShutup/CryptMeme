package net.i2p.router.web;

import net.i2p.data.DataHelper;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.RandomSource;

/**
 * simple helper to control restarts/shutdowns in the left hand nav
 *
 */
public class ConfigRestartBean {
    /** all these are tagged below so no need to _x them here.
     *  order is: form value, form class, display text.
     */
    private static final String[] SET1 = {"shutdownImmediate", "stop", "Shutdown immediately", "cancelShutdown", "cancel", "Cancel shutdown"};
    private static final String[] SET2 = {"restartImmediate", "reload", "Restart immediately", "cancelShutdown", "cancel", "Cancel restart"};
    private static final String[] SET3 = {"restart", "reload", "Restart", "shutdown", "stop", "Shutdown"};
    private static final String[] SET4 = {"shutdown", "stop", "Shutdown"};

    private static final String _systemNonce = Long.toString(RandomSource.getInstance().nextLong());

    /** formerly System.getProperty("console.nonce") */
    public static String getNonce() { 
        return _systemNonce;
    }

    /** this also initiates the restart/shutdown based on action */
    public static String renderStatus(String urlBase, String action, String nonce) {
        RouterContext ctx = ContextHelper.getContext(null);
        String systemNonce = getNonce();
        if ( (nonce != null) && (systemNonce.equals(nonce)) && (action != null) ) {
            // Normal browsers send value, IE sends button label
            if ("shutdownImmediate".equals(action) || _("Shutdown immediately", ctx).equals(action)) {
                if (ctx.hasWrapper())
                    ConfigServiceHandler.registerWrapperNotifier(ctx, Router.EXIT_HARD, false);
                //ctx.router().shutdown(Router.EXIT_HARD); // never returns
                ctx.router().shutdownGracefully(Router.EXIT_HARD); // give the UI time to respond
            } else if ("cancelShutdown".equals(action) || _("Cancel shutdown", ctx).equals(action) ||
                       _("Cancel restart", ctx).equals(action)) {
                ctx.router().cancelGracefulShutdown();
            } else if ("restartImmediate".equals(action) || _("Restart immediately", ctx).equals(action)) {
                if (ctx.hasWrapper())
                    ConfigServiceHandler.registerWrapperNotifier(ctx, Router.EXIT_HARD_RESTART, false);
                //ctx.router().shutdown(Router.EXIT_HARD_RESTART); // never returns
                ctx.router().shutdownGracefully(Router.EXIT_HARD_RESTART); // give the UI time to respond
            } else if ("restart".equals(action) || _("Restart", ctx).equals(action)) {
                if (ctx.hasWrapper())
                    ConfigServiceHandler.registerWrapperNotifier(ctx, Router.EXIT_GRACEFUL_RESTART, false);
                ctx.router().shutdownGracefully(Router.EXIT_GRACEFUL_RESTART);
            } else if ("shutdown".equals(action) || _("Shutdown", ctx).equals(action)) {
                if (ctx.hasWrapper())
                    ConfigServiceHandler.registerWrapperNotifier(ctx, Router.EXIT_GRACEFUL, false);
                ctx.router().shutdownGracefully();
            }
        }
        
        boolean shuttingDown = isShuttingDown(ctx);
        boolean restarting = isRestarting(ctx);
        long timeRemaining = ctx.router().getShutdownTimeRemaining();
        StringBuilder buf = new StringBuilder(128);
        if ((shuttingDown || restarting) && timeRemaining <= 5*1000) {
            buf.append("<h4>");
            if (restarting)
                buf.append(_("Restart imminent", ctx));
            else
                buf.append(_("Shutdown imminent", ctx));
            buf.append("</h4>");
        } else if (shuttingDown) {
            buf.append("<h4>");
            buf.append(_("Shutdown in {0}", DataHelper.formatDuration2(timeRemaining), ctx));
            int tuns = ctx.tunnelManager().getParticipatingCount();
            if (tuns > 0) {
                buf.append("<br>").append(ngettext("Please wait for routing commitment to expire for {0} tunnel",
                                                "Please wait for routing commitments to expire for {0} tunnels",
                                                tuns, ctx));
            }
            buf.append("</h4><hr>");
            buttons(ctx, buf, urlBase, systemNonce, SET1);
        } else if (restarting) {
            buf.append("<h4>");
            buf.append(_("Restart in {0}", DataHelper.formatDuration2(timeRemaining), ctx));
            int tuns = ctx.tunnelManager().getParticipatingCount();
            if (tuns > 0) {
                buf.append("<br>").append(ngettext("Please wait for routing commitment to expire for {0} tunnel",
                                                "Please wait for routing commitments to expire for {0} tunnels",
                                                tuns, ctx));
            }
            buf.append("</h4><hr>");
            buttons(ctx, buf, urlBase, systemNonce, SET2);
        } else {
            if (ctx.hasWrapper())
                buttons(ctx, buf, urlBase, systemNonce, SET3);
            else
                buttons(ctx, buf, urlBase, systemNonce, SET4);
        }
        return buf.toString();
    }
    
    /** @param s value,class,label,... triplets */
    private static void buttons(RouterContext ctx, StringBuilder buf, String url, String nonce, String[] s) {
        buf.append("<form action=\"").append(url).append("\" method=\"POST\">\n");
        buf.append("<input type=\"hidden\" name=\"consoleNonce\" value=\"").append(nonce).append("\" >\n");
        for (int i = 0; i < s.length; i+= 3) {
            buf.append("<button type=\"submit\" name=\"action\" value=\"")
               .append(s[i]).append("\" class=\"")
               .append(s[i+1]).append("\" >")
               .append(_(s[i+2], ctx)).append("</button>\n");
        }
        buf.append("</form>\n");
    }

    private static boolean isShuttingDown(RouterContext ctx) {
        return Router.EXIT_GRACEFUL == ctx.router().scheduledGracefulExitCode() ||
               Router.EXIT_HARD == ctx.router().scheduledGracefulExitCode();
    }
    private static boolean isRestarting(RouterContext ctx) {
        return Router.EXIT_GRACEFUL_RESTART == ctx.router().scheduledGracefulExitCode() ||
               Router.EXIT_HARD_RESTART == ctx.router().scheduledGracefulExitCode();
    }
    /** this is for summaryframe.jsp */
    public static long getRestartTimeRemaining() {
        RouterContext ctx = ContextHelper.getContext(null);
        if (ctx.router().gracefulShutdownInProgress())
            return ctx.router().getShutdownTimeRemaining();
        return Long.MAX_VALUE/2;  // summaryframe.jsp adds a safety factor so we don't want to overflow...
    }

    private static String _(String s, RouterContext ctx) {
        return Messages.getString(s, ctx);
    }

    private static String _(String s, Object o, RouterContext ctx) {
        return Messages.getString(s, o, ctx);
    }

    /** translate (ngettext) @since 0.9.10 */
    private static String ngettext(String s, String p, int n, RouterContext ctx) {
        return Messages.getString(n, s, p, ctx);
    }
}

