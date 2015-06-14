package net.i2p.router.update;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import net.i2p.router.RouterContext;
import net.i2p.router.web.ConfigUpdateHelper;
import net.i2p.update.*;
import static net.i2p.update.UpdateType.*;
import static net.i2p.update.UpdateMethod.*;

/**
 * Task to periodically look for updates to the news.xml, and to keep
 * track of whether that has an announcement for a new version.
 *
 * Overrides UpdateRunner for convenience, this is not an Updater
 *
 * @since 0.9.4 moved from NewsFetcher
 */
class NewsHandler extends UpdateHandler implements Checker {
    
    /**
     *  Changed in 0.9.11 to the b32 for psi.i2p, run by psi.
     *  We may be able to change it to psi.i2p in a future release after
     *  the hostname propagates.
     *
     *  @since 0.7.14 not configurable
     */
    private static final String BACKUP_NEWS_URL = "http://avviiexdngd32ccoy4kuckvc3mkf53ycvzbz6vz75vzhv4tbpk5a.b32.i2p/news.xml";
    private static final String BACKUP_NEWS_URL_SU3 = "http://avviiexdngd32ccoy4kuckvc3mkf53ycvzbz6vz75vzhv4tbpk5a.b32.i2p/news.su3";

    public NewsHandler(RouterContext ctx, ConsoleUpdateManager mgr) {
        super(ctx, mgr);
    }

    /**
     *  This will check for news or router updates (it does the same thing).
     *  Should not block.
     *  @param currentVersion ignored, stored locally
     */
    public UpdateTask check(UpdateType type, UpdateMethod method,
                            String id, String currentVersion, long maxTime) {
        if ((type != ROUTER_SIGNED && type != NEWS && type != NEWS_SU3) ||
            method != HTTP)
            return null;
        List<URI> updateSources = new ArrayList<URI>(2);
        try {
            // This may be su3 or xml
            updateSources.add(new URI(ConfigUpdateHelper.getNewsURL(_context)));
        } catch (URISyntaxException use) {}
        try {
            //updateSources.add(new URI(BACKUP_NEWS_URL));
            updateSources.add(new URI(BACKUP_NEWS_URL_SU3));
        } catch (URISyntaxException use) {}
        UpdateRunner update = new NewsFetcher(_context, _mgr, updateSources);
        return update;
    }
}
