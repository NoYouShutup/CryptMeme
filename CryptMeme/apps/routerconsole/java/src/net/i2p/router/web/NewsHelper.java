package net.i2p.router.web;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.Reader;
import java.text.SimpleDateFormat;
import java.util.Date;

import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.router.update.ConsoleUpdateManager;
import static net.i2p.update.UpdateType.*;
import net.i2p.util.TranslateReader;

/**
 *  If news file does not exist, use file from the initialNews directory
 *  in $I2P
 *
 *  @since 0.8.2
 */
public class NewsHelper extends ContentHelper {
    
    public static final String PROP_LAST_UPDATE_TIME = "router.updateLastDownloaded";
    /** @since 0.8.12 */
    private static final String PROP_LAST_HIDDEN = "routerconsole.newsLastHidden";
    /** @since 0.9.4 */
    public static final String PROP_LAST_CHECKED = "routerconsole.newsLastChecked";
    /** @since 0.9.4 */
    public static final String PROP_LAST_UPDATED = "routerconsole.newsLastUpdated";
    public static final String NEWS_FILE = "docs/news.xml";

    /**
     *  If ANY update is in progress.
     *  @since 0.9.4 was stored in system properties
     */
    public static boolean isAnyUpdateInProgress() {
        ConsoleUpdateManager mgr = ConsoleUpdateManager.getInstance();
        if (mgr == null) return false;
        return mgr.isUpdateInProgress();
    }

    /**
     *  If a signed or unsigned router update is in progress.
     *  Does NOT cover plugins, news, etc.
     *  @since 0.9.4 was stored in system properties
     */
    public static boolean isUpdateInProgress() {
        ConsoleUpdateManager mgr = ConsoleUpdateManager.getInstance();
        if (mgr == null) return false;
        return mgr.isUpdateInProgress(ROUTER_SIGNED) ||
               mgr.isUpdateInProgress(ROUTER_SIGNED_SU3) ||
               mgr.isUpdateInProgress(ROUTER_UNSIGNED) ||
               mgr.isUpdateInProgress(ROUTER_DEV_SU3) ||
               mgr.isUpdateInProgress(TYPE_DUMMY);
    }

    /**
     *  Release update only.
     *  Will be false if already downloaded.
     *  @since 0.9.4 moved from NewsFetcher
     */
    public static boolean isUpdateAvailable() {
        ConsoleUpdateManager mgr = ConsoleUpdateManager.getInstance();
        if (mgr == null) return false;
        return mgr.getUpdateAvailable(ROUTER_SIGNED) != null ||
               mgr.getUpdateAvailable(ROUTER_SIGNED_SU3) != null;
    }

    /**
     *  Release update only.
     *  Available version, will be null if already downloaded.
     *  @return null if none
     *  @since 0.9.4 moved from NewsFetcher
     */
    public static String updateVersion() {
        ConsoleUpdateManager mgr = ConsoleUpdateManager.getInstance();
        if (mgr == null) return null;
        String rv = mgr.getUpdateAvailable(ROUTER_SIGNED_SU3);
        if (rv != null)
            return rv;
        return mgr.getUpdateAvailable(ROUTER_SIGNED);
    }

    /**
     *  Release update only.
     *  Translated message about new version available but constrained
     *  @return null if none
     *  @since 0.9.9
     */
    public static String updateConstraint() {
        ConsoleUpdateManager mgr = ConsoleUpdateManager.getInstance();
        if (mgr == null) return null;
        return mgr.getUpdateConstraint(ROUTER_SIGNED, "");
    }

    /**
     *  Release update only.
     *  Already downloaded but not installed version.
     *  @return null if none
     *  @since 0.9.4
     */
    public static String updateVersionDownloaded() {
        ConsoleUpdateManager mgr = ConsoleUpdateManager.getInstance();
        if (mgr == null) return null;
        String rv = mgr.getUpdateDownloaded(ROUTER_SIGNED_SU3);
        if (rv != null)
            return rv;
        return mgr.getUpdateDownloaded(ROUTER_SIGNED);
    }

    /**
     *  Will be false if already downloaded or if dev update disabled.
     *  @since 0.9.4 moved from NewsFetcher
     */
    public static boolean isUnsignedUpdateAvailable(RouterContext ctx) {
        ConsoleUpdateManager mgr = ConsoleUpdateManager.getInstance();
        if (mgr == null) return false;
        return mgr.getUpdateAvailable(ROUTER_UNSIGNED) != null &&
               ctx.getBooleanProperty(ConfigUpdateHandler.PROP_UPDATE_UNSIGNED);
    }

    /**
     *  @return null if none
     *  @since 0.9.4 moved from NewsFetcher
     */
    public static String unsignedUpdateVersion() {
        ConsoleUpdateManager mgr = ConsoleUpdateManager.getInstance();
        if (mgr == null) return null;
        return formatUnsignedVersion(mgr.getUpdateAvailable(ROUTER_UNSIGNED));
    }

    /**
     *  Already downloaded but not installed version
     *  @return null if none
     *  @since 0.9.4
     */
    public static String unsignedVersionDownloaded() {
        ConsoleUpdateManager mgr = ConsoleUpdateManager.getInstance();
        if (mgr == null) return null;
        return formatUnsignedVersion(mgr.getUpdateDownloaded(ROUTER_UNSIGNED));
    }

    /**
     *  Will be false if already downloaded or if dev update disabled.
     *  @since 0.9.20
     */
    public static boolean isDevSU3UpdateAvailable(RouterContext ctx) {
        ConsoleUpdateManager mgr = ConsoleUpdateManager.getInstance();
        if (mgr == null) return false;
        return mgr.getUpdateAvailable(ROUTER_DEV_SU3) != null &&
               ctx.getBooleanProperty(ConfigUpdateHandler.PROP_UPDATE_DEV_SU3);
    }

    /**
     *  @return null if none
     *  @since 0.9.20
     */
    public static String devSU3UpdateVersion() {
        ConsoleUpdateManager mgr = ConsoleUpdateManager.getInstance();
        if (mgr == null) return null;
        return mgr.getUpdateAvailable(ROUTER_DEV_SU3);
    }

    /**
     *  Already downloaded but not installed version
     *  @return null if none
     *  @since 0.9.20
     */
    public static String devSU3VersionDownloaded() {
        ConsoleUpdateManager mgr = ConsoleUpdateManager.getInstance();
        if (mgr == null) return null;
        return mgr.getUpdateDownloaded(ROUTER_DEV_SU3);
    }

    /**
     *  Convert long date stamp to
     *  '07-Jul 21:09 UTC' with month name in the system locale
     *  @return null if ver = null
     *  @since 0.9.4 moved from NewsFetcher
     */
    private static String formatUnsignedVersion(String ver) {
        if (ver != null) {
            try {
                long modtime = Long.parseLong(ver);
                return (new SimpleDateFormat("dd-MMM HH:mm")).format(new Date(modtime)) + " UTC";
            } catch (NumberFormatException nfe) {}
        }
        return null;
    }

    /**
     *  @return "" if none
     *  @since 0.9.4 moved from UpdateHelper
     */
    public static String getUpdateStatus() {
        ConsoleUpdateManager mgr = ConsoleUpdateManager.getInstance();
        if (mgr == null) return "";
        return mgr.getStatus();
    }

    private static final String BUNDLE_NAME = "net.i2p.router.news.messages";

    /**
     *  If we haven't downloaded news yet, use the translated initial news file
     */
    @Override
    public String getContent() {
        File news = new File(_page);
        if (!news.exists()) {
            _page = (new File(_context.getBaseDir(), "docs/initialNews/initialNews.xml")).getAbsolutePath();
            // don't use super, translate on-the-fly
            Reader reader = null;
            try {
                char[] buf = new char[512];
                StringBuilder out = new StringBuilder(2048);
                reader = new TranslateReader(_context, BUNDLE_NAME, new FileInputStream(_page));
                int len;
                while((len = reader.read(buf)) > 0) {
                    out.append(buf, 0, len);
                }
                return out.toString();
            } catch (IOException ioe) {
                return "";
            } finally {
                try {
                    if (reader != null)
                        reader.close();
                } catch (IOException foo) {}
            }
        }
        return super.getContent();
    }

    /**
     *  Is the news newer than the last time it was hidden?
     *  @since 0.8.12
     */
    public boolean shouldShowNews() {
        return shouldShowNews(_context);
    }

    /**
     *  @since 0.9.4
     */
    public static boolean shouldShowNews(RouterContext ctx) {
        long lastUpdated = lastUpdated(ctx);
        if (lastUpdated <= 0)
            return true;
        long last = ctx.getProperty(PROP_LAST_HIDDEN, 0L);
        return lastUpdated > last;
    }

    /**
     *  Save config with the timestamp of the current news to hide, or 0 to show
     *  @since 0.8.12
     */
    public void showNews(boolean yes) {
        showNews(_context, yes);
    }

    /**
     *  Save config with the timestamp of the current news to hide, or 0 to show
     *  @since 0.9.4
     */
    public static void showNews(RouterContext ctx, boolean yes) {
        long stamp = yes ? 0 : lastUpdated(ctx);
        ctx.router().saveConfig(PROP_LAST_HIDDEN, Long.toString(stamp));
    }

    /**
     *  @return HTML
     *  @since 0.9.4 moved from NewsFetcher
     */
    public String status() {
        return status(_context);
    }

    /**
     *  @return HTML
     *  @since 0.9.4 moved from NewsFetcher
     */
    public static String status(RouterContext ctx) {
         StringBuilder buf = new StringBuilder(128);
         long now = ctx.clock().now();
         buf.append("<i>");
         long lastUpdated = lastUpdated(ctx);
         long lastFetch = lastChecked(ctx);
         if (lastUpdated > 0) {
             buf.append(Messages.getString("News last updated {0} ago.",
                                           DataHelper.formatDuration2(now - lastUpdated),
                                           ctx))
                .append('\n');
         }
         if (lastFetch > lastUpdated) {
             buf.append(Messages.getString("News last checked {0} ago.",
                                           DataHelper.formatDuration2(now - lastFetch),
                                           ctx));
         }
         buf.append("</i>");
         String consoleNonce = CSSHelper.getNonce();
         if (lastUpdated > 0 && consoleNonce != null) {
             if (shouldShowNews(ctx)) {
                 buf.append(" <a href=\"/?news=0&amp;consoleNonce=").append(consoleNonce).append("\">")
                    .append(Messages.getString("Hide news", ctx));
             } else {
                 buf.append(" <a href=\"/?news=1&amp;consoleNonce=").append(consoleNonce).append("\">")
                    .append(Messages.getString("Show news", ctx));
             }
             buf.append("</a>");
         }
         return buf.toString();
    }
    
    /**
     *  @since 0.9.4 moved from NewsFetcher
     */
    public static boolean dontInstall(RouterContext ctx) {
        return isUpdateDisabled(ctx) || isBaseReadonly(ctx);
    }
    
    /**
     *  @since 0.9.9
     */
    public static boolean isUpdateDisabled(RouterContext ctx) {
        return ctx.getBooleanProperty(ConfigUpdateHandler.PROP_UPDATE_DISABLED);
    }
    
    /**
     *  @since 0.9.9
     */
    public static boolean isBaseReadonly(RouterContext ctx) {
        File test = new File(ctx.getBaseDir(), "history.txt");
        boolean readonly = ((test.exists() && !test.canWrite()) || (!ctx.getBaseDir().canWrite()));
        return readonly;
    }

    /**
     *  @since 0.9.4
     */
    public static long lastChecked(RouterContext ctx) {
        return ctx.getProperty(PROP_LAST_CHECKED, 0L);
    }

    /**
     *  When the news was last downloaded
     *  @since 0.9.4
     */
    public static long lastUpdated(RouterContext ctx) {
        long rv = ctx.getProperty(PROP_LAST_UPDATED, 0L);
        if (rv > 0)
            return rv;
        File newsFile = new File(ctx.getRouterDir(), NEWS_FILE);
        rv = newsFile.lastModified();
        ctx.router().saveConfig(PROP_LAST_UPDATED, Long.toString(rv));
        return rv;
    }
}
