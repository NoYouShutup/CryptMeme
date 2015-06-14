package org.klomp.snark;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientAppManager;
import net.i2p.crypto.SHA1Hash;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.update.*;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SimpleTimer2;

import org.klomp.snark.dht.DHT;
import org.klomp.snark.dht.KRPC;

/**
 * Manage multiple snarks
 */
public class SnarkManager implements CompleteListener {
    
    /**
     *  Map of (canonical) filename of the .torrent file to Snark instance.
     *  This is a CHM so listTorrentFiles() need not be synced, but
     *  all adds, deletes, and the DirMonitor should sync on it.
     */
    private final Map<String, Snark> _snarks;
    /** used to prevent DirMonitor from deleting torrents that don't have a torrent file yet */
    private final Set<String> _magnets;
    private final Object _addSnarkLock;
    private File _configFile;
    private File _configDir;
    /** one lock for all config, files for simplicity */
    private final Object _configLock = new Object();
    private Properties _config;
    private final I2PAppContext _context;
    private final String _contextPath;
    private final String _contextName;
    private final Log _log;
    private final Queue<String> _messages;
    private final I2PSnarkUtil _util;
    private PeerCoordinatorSet _peerCoordinatorSet;
    private ConnectionAcceptor _connectionAcceptor;
    private Thread _monitor;
    private volatile boolean _running;
    private volatile boolean _stopping;
    private final Map<String, Tracker> _trackerMap;
    private UpdateManager _umgr;
    private UpdateHandler _uhandler;
    private SimpleTimer2.TimedEvent _idleChecker;
    
    public static final String PROP_I2CP_HOST = "i2psnark.i2cpHost";
    public static final String PROP_I2CP_PORT = "i2psnark.i2cpPort";
    public static final String PROP_I2CP_OPTS = "i2psnark.i2cpOptions";
    //public static final String PROP_EEP_HOST = "i2psnark.eepHost";
    //public static final String PROP_EEP_PORT = "i2psnark.eepPort";
    public static final String PROP_UPLOADERS_TOTAL = "i2psnark.uploaders.total";
    public static final String PROP_UPBW_MAX = "i2psnark.upbw.max";
    public static final String PROP_DIR = "i2psnark.dir";
    private static final String PROP_META_PREFIX = "i2psnark.zmeta.";
    private static final String PROP_META_STAMP = "stamp";
    private static final String PROP_META_BASE = "base";
    private static final String PROP_META_BITFIELD = "bitfield";
    private static final String PROP_META_PRIORITY = "priority";
    private static final String PROP_META_PRESERVE_NAMES = "preserveFileNames";
    private static final String PROP_META_UPLOADED = "uploaded";
    //private static final String PROP_META_BITFIELD_SUFFIX = ".bitfield";
    //private static final String PROP_META_PRIORITY_SUFFIX = ".priority";
    private static final String PROP_META_MAGNET_PREFIX = "i2psnark.magnet.";

    private static final String CONFIG_FILE_SUFFIX = ".config";
    private static final String CONFIG_FILE = "i2psnark" + CONFIG_FILE_SUFFIX;
    public static final String PROP_FILES_PUBLIC = "i2psnark.filesPublic";
    public static final String PROP_OLD_AUTO_START = "i2snark.autoStart";   // oops
    public static final String PROP_AUTO_START = "i2psnark.autoStart";      // convert in migration to new config file
    public static final String DEFAULT_AUTO_START = "false";
    //public static final String PROP_LINK_PREFIX = "i2psnark.linkPrefix";
    //public static final String DEFAULT_LINK_PREFIX = "file:///";
    public static final String PROP_STARTUP_DELAY = "i2psnark.startupDelay";
    public static final String PROP_REFRESH_DELAY = "i2psnark.refreshSeconds";
    public static final String PROP_PAGE_SIZE = "i2psnark.pageSize";
    public static final String RC_PROP_THEME = "routerconsole.theme";
    public static final String RC_PROP_UNIVERSAL_THEMING = "routerconsole.universal.theme";
    public static final String PROP_THEME = "i2psnark.theme";
    public static final String DEFAULT_THEME = "ubergine";
    private static final String PROP_USE_OPENTRACKERS = "i2psnark.useOpentrackers";
    public static final String PROP_OPENTRACKERS = "i2psnark.opentrackers";
    public static final String PROP_PRIVATETRACKERS = "i2psnark.privatetrackers";
    private static final String PROP_USE_DHT = "i2psnark.enableDHT";

    public static final int MIN_UP_BW = 10;
    public static final int DEFAULT_MAX_UP_BW = 25;
    public static final int DEFAULT_STARTUP_DELAY = 3; 
    public static final int DEFAULT_REFRESH_DELAY_SECS = 60;
    private static final int DEFAULT_PAGE_SIZE = 50;
    public static final String CONFIG_DIR_SUFFIX = ".d";
    private static final String SUBDIR_PREFIX = "s";
    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-~";

    /**
     *  "name", "announceURL=websiteURL" pairs
     *  '=' in announceURL must be escaped as &#44;
     *
     *  Please use host name, not b32 or full dest, in announce URL. Ensure in default hosts.txt.
     *  Please use host name, not b32 or full dest, in website URL. Ensure in default hosts.txt.
     */
    private static final String DEFAULT_TRACKERS[] = { 
//       "Postman", "http://YRgrgTLGnbTq2aZOZDJQ~o6Uk5k6TK-OZtx0St9pb0G-5EGYURZioxqYG8AQt~LgyyI~NCj6aYWpPO-150RcEvsfgXLR~CxkkZcVpgt6pns8SRc3Bi-QSAkXpJtloapRGcQfzTtwllokbdC-aMGpeDOjYLd8b5V9Im8wdCHYy7LRFxhEtGb~RL55DA8aYOgEXcTpr6RPPywbV~Qf3q5UK55el6Kex-6VCxreUnPEe4hmTAbqZNR7Fm0hpCiHKGoToRcygafpFqDw5frLXToYiqs9d4liyVB-BcOb0ihORbo0nS3CLmAwZGvdAP8BZ7cIYE3Z9IU9D1G8JCMxWarfKX1pix~6pIA-sp1gKlL1HhYhPMxwyxvuSqx34o3BqU7vdTYwWiLpGM~zU1~j9rHL7x60pVuYaXcFQDR4-QVy26b6Pt6BlAZoFmHhPcAuWfu-SFhjyZYsqzmEmHeYdAwa~HojSbofg0TMUgESRXMw6YThK1KXWeeJVeztGTz25sL8AAAA.i2p/announce.php=http://tracker.postman.i2p/"
//       , "eBook", "http://E71FRom6PZNEqTN2Lr8P-sr23b7HJVC32KoGnVQjaX6zJiXwhJy2HsXob36Qmj81TYFZdewFZa9mSJ533UZgGyQkXo2ahctg82JKYZfDe5uDxAn1E9YPjxZCWJaFJh0S~UwSs~9AZ7UcauSJIoNtpxrtbmRNVFLqnkEDdLZi26TeucfOmiFmIWnVblLniWv3tG1boE9Abd-6j3FmYVrRucYuepAILYt6katmVNOk6sXmno1Eynrp~~MBuFq0Ko6~jsc2E2CRVYXDhGHEMdt-j6JUz5D7S2RIVzDRqQyAZLKJ7OdQDmI31przzmne1vOqqqLC~1xUumZVIvF~yOeJUGNjJ1Vx0J8i2BQIusn1pQJ6UCB~ZtZZLQtEb8EPVCfpeRi2ri1M5CyOuxN0V5ekmPHrYIBNevuTCRC26NP7ZS5VDgx1~NaC3A-CzJAE6f1QXi0wMI9aywNG5KGzOPifcsih8eyGyytvgLtrZtV7ykzYpPCS-rDfITncpn5hliPUAAAA.i2p/pub/bt/announce.php=http://de-ebook-archiv.i2p/pub/bt/"
//       , "Gaytorrents", "http://uxPWHbK1OIj9HxquaXuhMiIvi21iK0~ZiG9d8G0840ZXIg0r6CbiV71xlsqmdnU6wm0T2LySriM0doW2gUigo-5BNkUquHwOjLROiETnB3ZR0Ml4IGa6QBPn1aAq2d9~g1r1nVjLE~pcFnXB~cNNS7kIhX1d6nLgYVZf0C2cZopEow2iWVUggGGnAA9mHjE86zLEnTvAyhbAMTqDQJhEuLa0ZYSORqzJDMkQt90MV4YMjX1ICY6RfUSFmxEqu0yWTrkHsTtRw48l~dz9wpIgc0a0T9C~eeWvmBFTqlJPtQZwntpNeH~jF7nlYzB58olgV2HHFYpVYD87DYNzTnmNWxCJ5AfDorm6AIUCV2qaE7tZtI1h6fbmGpGlPyW~Kw5GXrRfJwNvr6ajwAVi~bPVnrBwDZezHkfW4slOO8FACPR28EQvaTu9nwhAbqESxV2hCTq6vQSGjuxHeOuzBOEvRWkLKOHWTC09t2DbJ94FSqETmZopTB1ukEmaxRWbKSIaAAAA.i2p/announce.php=http://gaytorrents.i2p/"
//       , "NickyB", "http://9On6d3cZ27JjwYCtyJJbowe054d5tFnfMjv4PHsYs-EQn4Y4mk2zRixatvuAyXz2MmRfXG-NAUfhKr0KCxRNZbvHmlckYfT-WBzwwpiMAl0wDFY~Pl8cqXuhfikSG5WrqdPfDNNIBuuznS0dqaczf~OyVaoEOpvuP3qV6wKqbSSLpjOwwAaQPHjlRtNIW8-EtUZp-I0LT45HSoowp~6b7zYmpIyoATvIP~sT0g0MTrczWhbVTUZnEkZeLhOR0Duw1-IRXI2KHPbA24wLO9LdpKKUXed05RTz0QklW5ROgR6TYv7aXFufX8kC0-DaKvQ5JKG~h8lcoHvm1RCzNqVE-2aiZnO2xH08H-iCWoLNJE-Td2kT-Tsc~3QdQcnEUcL5BF-VT~QYRld2--9r0gfGl-yDrJZrlrihHGr5J7ImahelNn9PpkVp6eIyABRmJHf2iicrk3CtjeG1j9OgTSwaNmEpUpn4aN7Kx0zNLdH7z6uTgCGD9Kmh1MFYrsoNlTp4AAAA.i2p/bittorrent/announce.php=http://nickyb.i2p/bittorrent/"
//       , "Orion", "http://gKik1lMlRmuroXVGTZ~7v4Vez3L3ZSpddrGZBrxVriosCQf7iHu6CIk8t15BKsj~P0JJpxrofeuxtm7SCUAJEr0AIYSYw8XOmp35UfcRPQWyb1LsxUkMT4WqxAT3s1ClIICWlBu5An~q-Mm0VFlrYLIPBWlUFnfPR7jZ9uP5ZMSzTKSMYUWao3ejiykr~mtEmyls6g-ZbgKZawa9II4zjOy-hdxHgP-eXMDseFsrym4Gpxvy~3Fv9TuiSqhpgm~UeTo5YBfxn6~TahKtE~~sdCiSydqmKBhxAQ7uT9lda7xt96SS09OYMsIWxLeQUWhns-C~FjJPp1D~IuTrUpAFcVEGVL-BRMmdWbfOJEcWPZ~CBCQSO~VkuN1ebvIOr9JBerFMZSxZtFl8JwcrjCIBxeKPBmfh~xYh16BJm1BBBmN1fp2DKmZ2jBNkAmnUbjQOqWvUcehrykWk5lZbE7bjJMDFH48v3SXwRuDBiHZmSbsTY6zhGY~GkMQHNGxPMMSIAAAA.i2p/bt/announce.php=http://orion.i2p/bt/"
//       , "anonymity", "http://8EoJZIKrWgGuDrxA3nRJs1jsPfiGwmFWL91hBrf0HA7oKhEvAna4Ocx47VLUR9retVEYBAyWFK-eZTPcvhnz9XffBEiJQQ~kFSCqb1fV6IfPiV3HySqi9U5Caf6~hC46fRd~vYnxmaBLICT3N160cxBETqH3v2rdxdJpvYt8q4nMk9LUeVXq7zqCTFLLG5ig1uKgNzBGe58iNcsvTEYlnbYcE930ABmrzj8G1qQSgSwJ6wx3tUQNl1z~4wSOUMan~raZQD60lRK70GISjoX0-D0Po9WmPveN3ES3g72TIET3zc3WPdK2~lgmKGIs8GgNLES1cXTolvbPhdZK1gxddRMbJl6Y6IPFyQ9o4-6Rt3Lp-RMRWZ2TG7j2OMcNSiOmATUhKEFBDfv-~SODDyopGBmfeLw16F4NnYednvn4qP10dyMHcUASU6Zag4mfc2-WivrOqeWhD16fVAh8MoDpIIT~0r9XmwdaVFyLcjbXObabJczxCAW3fodQUnvuSkwzAAAA.i2p/anonymityTracker/announce.php=http://anonymityweb.i2p/anonymityTracker/"
//       , "The freak's tracker", "http://mHKva9x24E5Ygfey2llR1KyQHv5f8hhMpDMwJDg1U-hABpJ2NrQJd6azirdfaR0OKt4jDlmP2o4Qx0H598~AteyD~RJU~xcWYdcOE0dmJ2e9Y8-HY51ie0B1yD9FtIV72ZI-V3TzFDcs6nkdX9b81DwrAwwFzx0EfNvK1GLVWl59Ow85muoRTBA1q8SsZImxdyZ-TApTVlMYIQbdI4iQRwU9OmmtefrCe~ZOf4UBS9-KvNIqUL0XeBSqm0OU1jq-D10Ykg6KfqvuPnBYT1BYHFDQJXW5DdPKwcaQE4MtAdSGmj1epDoaEBUa9btQlFsM2l9Cyn1hzxqNWXELmx8dRlomQLlV4b586dRzW~fLlOPIGC13ntPXogvYvHVyEyptXkv890jC7DZNHyxZd5cyrKC36r9huKvhQAmNABT2Y~pOGwVrb~RpPwT0tBuPZ3lHYhBFYmD8y~AOhhNHKMLzea1rfwTvovBMByDdFps54gMN1mX4MbCGT4w70vIopS9yAAAA.i2p/bytemonsoon/announce.php"
//       , "mastertracker", "http://VzXD~stRKbL3MOmeTn1iaCQ0CFyTmuFHiKYyo0Rd~dFPZFCYH-22rT8JD7i-C2xzYFa4jT5U2aqHzHI-Jre4HL3Ri5hFtZrLk2ax3ji7Qfb6qPnuYkuiF2E2UDmKUOppI8d9Ye7tjdhQVCy0izn55tBaB-U7UWdcvSK2i85sauyw3G0Gfads1Rvy5-CAe2paqyYATcDmGjpUNLoxbfv9KH1KmwRTNH6k1v4PyWYYnhbT39WfKMbBjSxVQRdi19cyJrULSWhjxaQfJHeWx5Z8Ev4bSPByBeQBFl2~4vqy0S5RypINsRSa3MZdbiAAyn5tr5slWR6QdoqY3qBQgBJFZppy-3iWkFqqKgSxCPundF8gdDLC5ddizl~KYcYKl42y9SGFHIukH-TZs8~em0~iahzsqWVRks3zRG~tlBcX2U3M2~OJs~C33-NKhyfZT7-XFBREvb8Szmd~p66jDxrwOnKaku-G6DyoQipJqIz4VHmY9-y5T8RrUcJcM-5lVoMpAAAA.i2p/announce.php=http://tracker.mastertracker.i2p/"
//       , "Galen", "http://5jpwQMI5FT303YwKa5Rd38PYSX04pbIKgTaKQsWbqoWjIfoancFdWCShXHLI5G5ofOb0Xu11vl2VEMyPsg1jUFYSVnu4-VfMe3y4TKTR6DTpetWrnmEK6m2UXh91J5DZJAKlgmO7UdsFlBkQfR2rY853-DfbJtQIFl91tbsmjcA5CGQi4VxMFyIkBzv-pCsuLQiZqOwWasTlnzey8GcDAPG1LDcvfflGV~6F5no9mnuisZPteZKlrv~~TDoXTj74QjByWc4EOYlwqK8sbU9aOvz~s31XzErbPTfwiawiaZ0RUI-IDrKgyvmj0neuFTWgjRGVTH8bz7cBZIc3viy6ioD-eMQOrXaQL0TCWZUelRwHRvgdPiQrxdYQs7ixkajeHzxi-Pq0EMm5Vbh3j3Q9kfUFW3JjFDA-MLB4g6XnjCbM5J1rC0oOBDCIEfhQkszru5cyLjHiZ5yeA0VThgu~c7xKHybv~OMXION7V8pBKOgET7ZgAkw1xgYe3Kkyq5syAAAA.i2p/tr/announce.php=http://galen.i2p/tr/"
       "Postman", "http://tracker2.postman.i2p/announce.php=http://tracker2.postman.i2p/"
//       ,"Welterde", "http://tracker.welterde.i2p/a=http://tracker.welterde.i2p/stats?mode=top5"
       ,"Diftracker", "http://diftracker.i2p/announce.php=http://diftracker.i2p/"
//       , "CRSTRACK", "http://b4G9sCdtfvccMAXh~SaZrPqVQNyGQbhbYMbw6supq2XGzbjU4NcOmjFI0vxQ8w1L05twmkOvg5QERcX6Mi8NQrWnR0stLExu2LucUXg1aYjnggxIR8TIOGygZVIMV3STKH4UQXD--wz0BUrqaLxPhrm2Eh9Hwc8TdB6Na4ShQUq5Xm8D4elzNUVdpM~RtChEyJWuQvoGAHY3ppX-EJJLkiSr1t77neS4Lc-KofMVmgI9a2tSSpNAagBiNI6Ak9L1T0F9uxeDfEG9bBSQPNMOSUbAoEcNxtt7xOW~cNOAyMyGydwPMnrQ5kIYPY8Pd3XudEko970vE0D6gO19yoBMJpKx6Dh50DGgybLQ9CpRaynh2zPULTHxm8rneOGRcQo8D3mE7FQ92m54~SvfjXjD2TwAVGI~ae~n9HDxt8uxOecAAvjjJ3TD4XM63Q9TmB38RmGNzNLDBQMEmJFpqQU8YeuhnS54IVdUoVQFqui5SfDeLXlSkh4vYoMU66pvBfWbAAAA.i2p/tracker/announce.php=http://crstrack.i2p/tracker/"
//       ,"Exotrack", "http://blbgywsjubw3d2zih2giokakhe3o2cko7jtte4risb3hohbcoyva.b32.i2p/announce.php=http://exotrack.i2p/"
       ,"DgTrack", "http://w7tpbzncbcocrqtwwm3nezhnnsw4ozadvi2hmvzdhrqzfxfum7wa.b32.i2p/a=http://opentracker.dg2.i2p/"
       // The following is ECDSA_SHA256_P256
       ,"TheBland", "http://s5ikrdyjwbcgxmqetxb3nyheizftms7euacuub2hic7defkh3xhq.b32.i2p/a=http://tracker.thebland.i2p/stats?mode=peer"
    };
    
    /** URL. This is our equivalent to router.utorrent.com for bootstrap */
    public static final String DEFAULT_BACKUP_TRACKER = "http://opentracker.dg2.i2p/a";

    /** URLs, comma-separated. Used for "announce to open trackers also" */
    private static final String DEFAULT_OPENTRACKERS = DEFAULT_BACKUP_TRACKER +
                                                       (SigType.ECDSA_SHA256_P256.isAvailable() ? ",http://tracker.thebland.i2p/a" : "");

    public static final Set<String> DEFAULT_TRACKER_ANNOUNCES;

    /** host names for config form */
    public static final Set<String> KNOWN_OPENTRACKERS = new HashSet<String>(Arrays.asList(new String[] {
        "tracker.welterde.i2p", "cfmqlafjfmgkzbt4r3jsfyhgsr5abgxryl6fnz3d3y5a365di5aa.b32.i2p",
        "opentracker.dg2.i2p", "w7tpbzncbcocrqtwwm3nezhnnsw4ozadvi2hmvzdhrqzfxfum7wa.b32.i2p",
        "tracker.thebland.i2p", "s5ikrdyjwbcgxmqetxb3nyheizftms7euacuub2hic7defkh3xhq.b32.i2p",
        "psi.i2p", "avviiexdngd32ccoy4kuckvc3mkf53ycvzbz6vz75vzhv4tbpk5a.b32.i2p",
        "opentracker.psi.i2p", "vmow3h54yljn7zvzbqepdddt5fmygijujycod2q6yznpy2rrzuwa.b32.i2p",
        "tracker.killyourtv.i2p", "5mpvzxfbd4rtped3c7ln4ddw52e7i7t56s36ztky4ustxtxrjdpa.b32.i2p",
        "opendiftracker.i2p", "bikpeyxci4zuyy36eau5ycw665dplun4yxamn7vmsastejdqtfoq.b32.i2p"
    }));

    static {
        Set<String> ann = new HashSet<String>(8);
        for (int i = 1; i < DEFAULT_TRACKERS.length; i += 2) {
            if (DEFAULT_TRACKERS[i-1].equals("TheBland") && !SigType.ECDSA_SHA256_P256.isAvailable())
                continue;
            String urls[] = DEFAULT_TRACKERS[i].split("=", 2);
            ann.add(urls[0]);
        }
        DEFAULT_TRACKER_ANNOUNCES = Collections.unmodifiableSet(ann);
    }

    /** comma delimited list of name=announceURL=baseURL for the trackers to be displayed */
    public static final String PROP_TRACKERS = "i2psnark.trackers";

    /**
     *  For embedded.
     */
    public SnarkManager(I2PAppContext ctx) {
        this(ctx, "/i2psnark", "i2psnark");
    }

    /**
     *  For webapp.
     *  @param ctxPath generally "/i2psnark"
     *  @param ctxName generally "i2psnark"
     *  @since 0.9.6
     */
    public SnarkManager(I2PAppContext ctx, String ctxPath, String ctxName) {
        _snarks = new ConcurrentHashMap<String, Snark>();
        _magnets = new ConcurrentHashSet<String>();
        _addSnarkLock = new Object();
        _context = ctx;
        _contextPath = ctxPath;
        _contextName = ctxName;
        _log = _context.logManager().getLog(SnarkManager.class);
        _messages = new LinkedBlockingQueue<String>();
        _util = new I2PSnarkUtil(_context, ctxName);
        String cfile = ctxName + CONFIG_FILE_SUFFIX;
        File configFile = new File(cfile);
        if (!configFile.isAbsolute())
            configFile = new File(_context.getConfigDir(), cfile);
        _configDir = migrateConfig(configFile);
        _configFile = new File(_configDir, CONFIG_FILE);
        _trackerMap = new ConcurrentHashMap<String, Tracker>(4);
        loadConfig(null);
    }

    /** Caller _must_ call loadConfig(file) before this if setting new values
     *  for i2cp host/port or i2psnark.dir
     */
    public void start() {
        _running = true;
        _peerCoordinatorSet = new PeerCoordinatorSet();
        _connectionAcceptor = new ConnectionAcceptor(_util, _peerCoordinatorSet);
        _monitor = new I2PAppThread(new DirMonitor(), "Snark DirMonitor", true);
        _monitor.start();
        // only if default instance
        if ("i2psnark".equals(_contextName))
            // delay until UpdateManager is there
            _context.simpleTimer2().addEvent(new Register(), 4*60*1000);
        // Not required, Jetty has a shutdown hook
        //_context.addShutdownTask(new SnarkManagerShutdown());
        _idleChecker = new IdleChecker(this, _peerCoordinatorSet);
        _idleChecker.schedule(5*60*1000);
    }

    /** @since 0.9.4 */
    private class Register implements SimpleTimer.TimedEvent {
        public void timeReached() {
            if (!_running)
                return;
            ClientAppManager cmgr = _context.clientAppManager();
            if (cmgr != null)
                _umgr = (UpdateManager) cmgr.getRegisteredApp(UpdateManager.APP_NAME);
            if (_umgr != null) {
                _uhandler = new UpdateHandler(_context, _umgr, SnarkManager.this);
                _umgr.register(_uhandler, UpdateType.ROUTER_SIGNED, UpdateMethod.TORRENT, 10);
                _umgr.register(_uhandler, UpdateType.ROUTER_SIGNED_SU3, UpdateMethod.TORRENT, 10);
                _log.warn("Registering with update manager");
            } else {
                _log.warn("No update manager to register with");
            }
        }
    }
    
    /*
     *  Called by the webapp at Jetty shutdown.
     *  Stops all torrents. Does not close the tunnel, so the announces have a chance.
     *  Fix this so an individual webaapp stop will close the tunnel.
     *  Runs inline.
     */
    public void stop() {
        if (_umgr != null && _uhandler != null) {
            //_uhandler.shutdown();
            _umgr.unregister(_uhandler, UpdateType.ROUTER_SIGNED, UpdateMethod.TORRENT);
            _umgr.unregister(_uhandler, UpdateType.ROUTER_SIGNED_SU3, UpdateMethod.TORRENT);
        }
        _running = false;
        _monitor.interrupt();
        _connectionAcceptor.halt();
        _idleChecker.cancel();
        stopAllTorrents(true);
    }
    
    /** @since 0.9.1 */
    public boolean isStopping() { return _stopping; }

    /** hook to I2PSnarkUtil for the servlet */
    public I2PSnarkUtil util() { return _util; }

    private static final int MAX_MESSAGES = 100;

    /**
     *  Use if it does not include a link.
     *  Escapes '<' and '>' before queueing
     */
    public void addMessage(String message) {
        addMessageNoEscape(message.replace("<", "&lt;").replace(">", "&gt;"));
    }

    /**
     * Use if it includes a link.
     * Does not escape '<' and '>' before queueing
     * @since 0.9.14.1
     */
    public void addMessageNoEscape(String message) {
        _messages.offer(message);
        while (_messages.size() > MAX_MESSAGES) {
            _messages.poll();
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("MSG: " + message);
    }
    
    /** newest last */
    public List<String> getMessages() {
        if (_messages.isEmpty())
            return Collections.emptyList();
        return new ArrayList<String>(_messages);
    }
    
    /** @since 0.9 */
    public void clearMessages() {
            _messages.clear();
    }
    
    /**
     *  @return default false
     *  @since 0.8.9
     */
    public boolean areFilesPublic() {
        return Boolean.parseBoolean(_config.getProperty(PROP_FILES_PUBLIC));
    }

    public boolean shouldAutoStart() {
        return Boolean.parseBoolean(_config.getProperty(PROP_AUTO_START, DEFAULT_AUTO_START));
    }

/****
    public String linkPrefix() {
        return _config.getProperty(PROP_LINK_PREFIX, DEFAULT_LINK_PREFIX + getDataDir().getAbsolutePath() + File.separatorChar);
    }
****/

    /**
     *  @return -1 for never
     *  @since 0.8.9
     */
    public int getRefreshDelaySeconds() { 
        try {
	    return Integer.parseInt(_config.getProperty(PROP_REFRESH_DELAY));
        } catch (NumberFormatException nfe) {
            return DEFAULT_REFRESH_DELAY_SECS;
        }
    }

    /**
     *  For GUI
     *  @since 0.9.6
     */
    public int getPageSize() { 
        try {
            return Integer.parseInt(_config.getProperty(PROP_PAGE_SIZE));
        } catch (NumberFormatException nfe) {
            return DEFAULT_PAGE_SIZE;
        }
    }

    private int getStartupDelayMinutes() { 
        try {
	    return Integer.parseInt(_config.getProperty(PROP_STARTUP_DELAY));
        } catch (NumberFormatException nfe) {
            return DEFAULT_STARTUP_DELAY;
        }
    }

    public File getDataDir() { 
        String dir = _config.getProperty(PROP_DIR, _contextName);
        File f;
        if (areFilesPublic())
            f = new File(dir);
        else
            f = new SecureDirectory(dir);
        if (!f.isAbsolute()) {
            if (areFilesPublic())
                f = new File(_context.getAppDir(), dir);
            else
                f = new SecureDirectory(_context.getAppDir(), dir);
        }
        return f; 
    }

    /**
     *  Migrate the old flat config file to the new config dir
     *  containing the config file minus the per-torrent entries,
     *  the dht file, and 64 subdirs for per-torrent config files
     *  Caller must synch.
     *
     *  @return the new config directory, non-null
     *  @throws RuntimeException on creation fail
     *  @since 0.9.15
     */
    private File migrateConfig(File oldFile) {
        File dir = new SecureDirectory(oldFile + CONFIG_DIR_SUFFIX);
        if ((!dir.exists()) && (!dir.mkdirs())) {
            _log.error("Error creating I2PSnark config dir " + dir);
            throw new RuntimeException("Error creating I2PSnark config dir " + dir);
        }
        // move the DHT file as-is
        String oldName = oldFile.toString();
        if (oldName.endsWith(CONFIG_FILE_SUFFIX)) {
            String oldDHT = oldName.replace(CONFIG_FILE_SUFFIX, KRPC.DHT_FILE_SUFFIX);
            File oldDHTFile = new File(oldDHT);
            if (oldDHTFile.exists()) {
                File newDHTFile = new File(dir, "i2psnark" + KRPC.DHT_FILE_SUFFIX);
                FileUtil.rename(oldDHTFile, newDHTFile);
            }
        }
        if (!oldFile.exists())
            return dir;
        Properties oldProps = new Properties();
        try {
            DataHelper.loadProps(oldProps, oldFile);
            // a good time to fix this ancient typo
            String auto = (String) oldProps.remove(PROP_OLD_AUTO_START);
            if (auto != null)
                oldProps.setProperty(PROP_AUTO_START, auto);
        } catch (IOException ioe) {
           _log.error("Error loading I2PSnark config " + oldFile, ioe);
           return dir;
        }
        // Gather the props for each torrent, removing them from config
        // old b64 of hash as key
        Map<String, Properties> configs = new HashMap<String, Properties>(16);
        for (Iterator<Map.Entry<Object, Object>> iter = oldProps.entrySet().iterator(); iter.hasNext(); ) {
            Map.Entry<Object, Object> e = iter.next();
            String k = (String) e.getKey();
            if (k.startsWith(PROP_META_PREFIX)) {
                iter.remove();
                String v = (String) e.getValue();
                try {
                    k = k.substring(PROP_META_PREFIX.length());
                    String h = k.substring(0, 28);  // length of b64 of 160 bit infohash
                    k = k.substring(29); // skip '.'
                    Properties tprops = configs.get(h);
                    if (tprops == null) {
                        tprops = new OrderedProperties();
                        configs.put(h, tprops);
                    }
                    if (k.equals(PROP_META_BITFIELD)) {
                        // old config was timestamp,bitfield; split them
                        int comma = v.indexOf(',');
                        if (comma > 0 && v.length() > comma + 1) {
                            tprops.put(PROP_META_STAMP, v.substring(0, comma));
                            tprops.put(PROP_META_BITFIELD, v.substring(comma + 1));
                        } else {
                            // timestamp only??
                            tprops.put(PROP_META_STAMP, v);
                        }
                    } else {
                        tprops.put(k, v);
                    }
                } catch (IndexOutOfBoundsException ioobe) {
                    continue;
                }
            }
        }
        // Now make a config file for each torrent
        for (Map.Entry<String, Properties> e : configs.entrySet()) {
            String b64 = e.getKey();
            Properties props = e.getValue();
            if (props.isEmpty())
                continue;
            b64 = b64.replace('$', '=');
            byte[] ih = Base64.decode(b64);
            if (ih == null || ih.length != 20)
                continue;
            File cfg = configFile(dir, ih);
            if (!cfg.exists()) {
                File subdir = cfg.getParentFile();
                if (!subdir.exists())
                    subdir.mkdirs();
                try {
                    DataHelper.storeProps(props, cfg);
                } catch (IOException ioe) {
                    _log.error("Error storing I2PSnark config " + cfg, ioe);
                }
            }
        }
        // now store in new location, minus the zmeta entries
        File newFile = new File(dir, CONFIG_FILE);
        Properties newProps = new OrderedProperties();
        newProps.putAll(oldProps);
        try {
            DataHelper.storeProps(newProps, newFile);
        } catch (IOException ioe) {
            _log.error("Error storing I2PSnark config " + newFile, ioe);
            return dir;
        }
        oldFile.delete();
        if (_log.shouldLog(Log.WARN))
            _log.warn("Config migrated from " + oldFile + " to " + dir);
        return dir;
    }

    /**
     *  The config for a torrent
     *  @return non-null, possibly empty
     *  @since 0.9.15
     */
    private Properties getConfig(Snark snark) {
        return getConfig(snark.getInfoHash());
    }

    /**
     *  The config for a torrent
     *  @param ih 20-byte infohash
     *  @return non-null, possibly empty
     *  @since 0.9.15
     */
    private Properties getConfig(byte[] ih) {
        Properties rv = new OrderedProperties();
        File conf = configFile(_configDir, ih);
        synchronized(_configLock) {  // one lock for all
            try {
                DataHelper.loadProps(rv, conf);
            } catch (IOException ioe) {}
        }
        return rv;
    }

    /**
     *  The config file for a torrent
     *  @param confDir the config directory
     *  @param ih 20-byte infohash
     *  @since 0.9.15
     */
    private static File configFile(File confDir, byte[] ih) {
        String hex = I2PSnarkUtil.toHex(ih);
        File subdir = new SecureDirectory(confDir, SUBDIR_PREFIX + B64.charAt((ih[0] >> 2) & 0x3f));
        return new File(subdir, hex + CONFIG_FILE_SUFFIX);
    }

    /**
     *  Extract the info hash from a config file name
     *  @return null for invalid name
     *  @since 0.9.20
     */
    private static SHA1Hash configFileToInfoHash(File file) {
        String name = file.getName();
        if (name.length() != 40 + CONFIG_FILE_SUFFIX.length() || !name.endsWith(CONFIG_FILE_SUFFIX))
            return null;
        String hex = name.substring(0, 40);
        byte[] ih = new byte[20];
        try {
            for (int i = 0; i < 20; i++) {
                ih[i] = (byte) (Integer.parseInt(hex.substring(i*2, (i*2) + 2), 16) & 0xff);
            }
        } catch (NumberFormatException nfe) {
           return null;
        }
        return new SHA1Hash(ih);
    }

    /** null to set initial defaults */
    public void loadConfig(String filename) {
        synchronized(_configLock) {
            locked_loadConfig(filename);
        }
    }

    /** null to set initial defaults */
    private void locked_loadConfig(String filename) {
        if (_config == null)
            _config = new OrderedProperties();
        if (filename != null) {
            File cfg = new File(filename);
            if (!cfg.isAbsolute())
                cfg = new File(_context.getConfigDir(), filename);
            _configDir = migrateConfig(cfg);
            _configFile = new File(_configDir, CONFIG_FILE);
            if (_configFile.exists()) {
                try {
                    DataHelper.loadProps(_config, _configFile);
                } catch (IOException ioe) {
                   _log.error("Error loading I2PSnark config " + _configFile, ioe);
                }
            } 
        } 
        // now add sane defaults
        if (!_config.containsKey(PROP_I2CP_HOST))
            _config.setProperty(PROP_I2CP_HOST, "127.0.0.1");
        if (!_config.containsKey(PROP_I2CP_PORT))
            _config.setProperty(PROP_I2CP_PORT, "7654");
        if (!_config.containsKey(PROP_I2CP_OPTS))
            _config.setProperty(PROP_I2CP_OPTS, "inbound.length=3 outbound.length=3 inbound.quantity=3 outbound.quantity=3");
        //if (!_config.containsKey(PROP_EEP_HOST))
        //    _config.setProperty(PROP_EEP_HOST, "127.0.0.1");
        //if (!_config.containsKey(PROP_EEP_PORT))
        //    _config.setProperty(PROP_EEP_PORT, "4444");
        if (!_config.containsKey(PROP_UPLOADERS_TOTAL))
            _config.setProperty(PROP_UPLOADERS_TOTAL, "" + Snark.MAX_TOTAL_UPLOADERS);
        if (!_config.containsKey(PROP_DIR))
            _config.setProperty(PROP_DIR, _contextName);
        if (!_config.containsKey(PROP_AUTO_START))
            _config.setProperty(PROP_AUTO_START, DEFAULT_AUTO_START);
        if (!_config.containsKey(PROP_REFRESH_DELAY))
            _config.setProperty(PROP_REFRESH_DELAY, Integer.toString(DEFAULT_REFRESH_DELAY_SECS));
        if (!_config.containsKey(PROP_STARTUP_DELAY))
            _config.setProperty(PROP_STARTUP_DELAY, Integer.toString(DEFAULT_STARTUP_DELAY));
        if (!_config.containsKey(PROP_PAGE_SIZE))
            _config.setProperty(PROP_PAGE_SIZE, Integer.toString(DEFAULT_PAGE_SIZE));
        if (!_config.containsKey(PROP_THEME))
            _config.setProperty(PROP_THEME, DEFAULT_THEME);
        // no, so we can switch default to true later
        //if (!_config.containsKey(PROP_USE_DHT))
        //    _config.setProperty(PROP_USE_DHT, Boolean.toString(I2PSnarkUtil.DEFAULT_USE_DHT));
        updateConfig();
    }

    /**
     * Get current theme.
     * @return String -- the current theme
     */
    public String getTheme() {
        String theme = _config.getProperty(PROP_THEME);
        boolean universalTheming = _context.getBooleanProperty(RC_PROP_UNIVERSAL_THEMING);
        if (universalTheming) {
            // Fetch routerconsole theme (or use our default if it doesn't exist)
            theme = _context.getProperty(RC_PROP_THEME, DEFAULT_THEME);
            // Ensure that theme exists
            String[] themes = getThemes();
            boolean themeExists = false;
            for (int i = 0; i < themes.length; i++) {
                if (themes[i].equals(theme))
                    themeExists = true;
            }
            if (!themeExists) {
                // Since the default is not "light", explicitly check if universal theme is "classic"
                if (theme.equals("classic"))
                    theme = "light";
                else
                    theme = DEFAULT_THEME;
                _config.setProperty(PROP_THEME, DEFAULT_THEME);
            }
        }
        return theme;
    }

    /**
     * Get all themes
     * @return String[] -- Array of all the themes found, non-null, unsorted
     */
    public String[] getThemes() {
            String[] themes;
            // "docs/themes/snark/"
            File dir = new File(_context.getBaseDir(), "docs/themes/snark");
            FileFilter fileFilter = new FileFilter() { public boolean accept(File file) { return file.isDirectory(); } };
            // Walk the themes dir, collecting the theme names, and append them to the map
            File[] dirnames = dir.listFiles(fileFilter);
            if (dirnames != null) {
                themes = new String[dirnames.length];
                for(int i = 0; i < dirnames.length; i++) {
                    themes[i] = dirnames[i].getName();
                }
            } else {
                themes = new String[0];
            }
            // return the map.
            return themes;
    }


    /** call from DirMonitor since loadConfig() is called before router I2CP is up */
    private void getBWLimit() {
        if (!_config.containsKey(PROP_UPBW_MAX)) {
            int[] limits = BWLimits.getBWLimits(_util.getI2CPHost(), _util.getI2CPPort());
            if (limits != null && limits[1] > 0)
                _util.setMaxUpBW(limits[1]);
        }
    }
    
    private void updateConfig() {
        String i2cpHost = _config.getProperty(PROP_I2CP_HOST);
        int i2cpPort = getInt(PROP_I2CP_PORT, 7654);
        String opts = _config.getProperty(PROP_I2CP_OPTS);
        Map<String, String> i2cpOpts = new HashMap<String, String>();
        if (opts != null) {
            StringTokenizer tok = new StringTokenizer(opts, " ");
            while (tok.hasMoreTokens()) {
                String pair = tok.nextToken();
                int split = pair.indexOf('=');
                if (split > 0)
                    i2cpOpts.put(pair.substring(0, split), pair.substring(split+1));
            }
        }
        _util.setI2CPConfig(i2cpHost, i2cpPort, i2cpOpts);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Configuring with I2CP options " + i2cpOpts);
        //I2PSnarkUtil.instance().setI2CPConfig("66.111.51.110", 7654, new Properties());
        //String eepHost = _config.getProperty(PROP_EEP_HOST);
        //int eepPort = getInt(PROP_EEP_PORT, 4444);
        //if (eepHost != null)
        //    _util.setProxy(eepHost, eepPort);
        _util.setMaxUploaders(getInt(PROP_UPLOADERS_TOTAL, Snark.MAX_TOTAL_UPLOADERS));
        _util.setMaxUpBW(getInt(PROP_UPBW_MAX, DEFAULT_MAX_UP_BW));
        _util.setStartupDelay(getInt(PROP_STARTUP_DELAY, DEFAULT_STARTUP_DELAY));
        _util.setFilesPublic(areFilesPublic());
        _util.setOpenTrackers(getListConfig(PROP_OPENTRACKERS, DEFAULT_OPENTRACKERS));
        String useOT = _config.getProperty(PROP_USE_OPENTRACKERS);
        boolean bOT = useOT == null || Boolean.parseBoolean(useOT);
        _util.setUseOpenTrackers(bOT);
        // careful, so we can switch default to true later
        _util.setUseDHT(Boolean.parseBoolean(_config.getProperty(PROP_USE_DHT,
                                          Boolean.toString(I2PSnarkUtil.DEFAULT_USE_DHT))));
        getDataDir().mkdirs();
        initTrackerMap();
    }
    
    private int getInt(String prop, int defaultVal) {
        String p = _config.getProperty(prop);
        try {
            if ( (p != null) && (p.trim().length() > 0) )
                return  Integer.parseInt(p.trim());
        } catch (NumberFormatException nfe) {
            // ignore
        }
        return defaultVal;
    }
    
    /**
     *  all params may be null or need trimming
     */
    public void updateConfig(String dataDir, boolean filesPublic, boolean autoStart, String refreshDelay,
                             String startDelay, String pageSize, String seedPct, String eepHost, 
                             String eepPort, String i2cpHost, String i2cpPort, String i2cpOpts,
                             String upLimit, String upBW, boolean useOpenTrackers, boolean useDHT, String theme) {
        synchronized(_configLock) {
            locked_updateConfig(dataDir, filesPublic, autoStart, refreshDelay,
                                startDelay,  pageSize,  seedPct,  eepHost, 
                                eepPort,  i2cpHost,  i2cpPort,  i2cpOpts,
                                upLimit,  upBW, useOpenTrackers, useDHT,  theme);
        }
    }

    private void locked_updateConfig(String dataDir, boolean filesPublic, boolean autoStart, String refreshDelay,
                             String startDelay, String pageSize, String seedPct, String eepHost, 
                             String eepPort, String i2cpHost, String i2cpPort, String i2cpOpts,
                             String upLimit, String upBW, boolean useOpenTrackers, boolean useDHT, String theme) {
        boolean changed = false;
        boolean interruptMonitor = false;
        //if (eepHost != null) {
        //    // unused, we use socket eepget
        //    int port = _util.getEepProxyPort();
        //    try { port = Integer.parseInt(eepPort); } catch (NumberFormatException nfe) {}
        //    String host = _util.getEepProxyHost();
        //    if ( (eepHost.trim().length() > 0) && (port > 0) &&
        //         ((!host.equals(eepHost) || (port != _util.getEepProxyPort()) )) ) {
        //        _util.setProxy(eepHost, port);
        //        changed = true;
        //        _config.setProperty(PROP_EEP_HOST, eepHost);
        //        _config.setProperty(PROP_EEP_PORT, eepPort+"");
        //        addMessage("EepProxy location changed to " + eepHost + ":" + port);
        //    }
        //}
        if (upLimit != null) {
            int limit = _util.getMaxUploaders();
            try { limit = Integer.parseInt(upLimit.trim()); } catch (NumberFormatException nfe) {}
            if ( limit != _util.getMaxUploaders()) {
                if ( limit >= Snark.MIN_TOTAL_UPLOADERS ) {
                    _util.setMaxUploaders(limit);
                    changed = true;
                    _config.setProperty(PROP_UPLOADERS_TOTAL, Integer.toString(limit));
                    addMessage(_("Total uploaders limit changed to {0}", limit));
                } else {
                    addMessage(_("Minimum total uploaders limit is {0}", Snark.MIN_TOTAL_UPLOADERS));
                }
            }
        }
        if (upBW != null) {
            int limit = _util.getMaxUpBW();
            try { limit = Integer.parseInt(upBW.trim()); } catch (NumberFormatException nfe) {}
            if ( limit != _util.getMaxUpBW()) {
                if ( limit >= MIN_UP_BW ) {
                    _util.setMaxUpBW(limit);
                    changed = true;
                    _config.setProperty(PROP_UPBW_MAX, Integer.toString(limit));
                    addMessage(_("Up BW limit changed to {0}KBps", limit));
                } else {
                    addMessage(_("Minimum up bandwidth limit is {0}KBps", MIN_UP_BW));
                }
            }
        }
        
	if (startDelay != null){
		int minutes = _util.getStartupDelay();
                try { minutes = Integer.parseInt(startDelay.trim()); } catch (NumberFormatException nfe) {}
	        if ( minutes != _util.getStartupDelay()) {
                	    _util.setStartupDelay(minutes);
	                    changed = true;
        	            _config.setProperty(PROP_STARTUP_DELAY, Integer.toString(minutes));
                	    addMessage(_("Startup delay changed to {0}", DataHelper.formatDuration2(minutes * (60L * 1000))));
                }
	}

	if (refreshDelay != null) {
	    try {
                int secs = Integer.parseInt(refreshDelay.trim());
	        if (secs != getRefreshDelaySeconds()) {
	            changed = true;
	            _config.setProperty(PROP_REFRESH_DELAY, Integer.toString(secs));
                    if (secs >= 0)
	                addMessage(_("Refresh time changed to {0}", DataHelper.formatDuration2(secs * 1000)));
	            else
	                addMessage(_("Refresh disabled"));
	        }
	    } catch (NumberFormatException nfe) {}
	}

        if (pageSize != null) {
            try {
                int size = Integer.parseInt(pageSize.trim());
                if (size <= 0)
                    size = 999999;
                else if (size < 5)
                    size = 5;
                if (size != getPageSize()) {
                    changed = true;
                    pageSize = Integer.toString(size);
                    _config.setProperty(PROP_PAGE_SIZE, pageSize);
                    addMessage(_("Page size changed to {0}", pageSize));
                }
            } catch (NumberFormatException nfe) {}
        }

        if (dataDir != null && !dataDir.equals(getDataDir().getAbsolutePath())) {
            dataDir = DataHelper.stripHTML(dataDir.trim());
            File dd = new File(dataDir);
            if (!dd.isAbsolute()) {
                addMessage(_("Data directory must be an absolute path") + ": " + dataDir);
            } else if (!dd.exists()) {
                addMessage(_("Data directory does not exist") + ": " + dataDir);
            } else if (!dd.isDirectory()) {
                addMessage(_("Not a directory") + ": " + dataDir);
            } else if (!dd.canRead()) {
                addMessage(_("Unreadable") + ": " + dataDir);
            } else {
                changed = true;
                interruptMonitor = true;
                _config.setProperty(PROP_DIR, dataDir);
                addMessage(_("Data directory changed to {0}", dataDir));
            }

        }

	// Start of I2CP stuff.
	// i2cpHost will generally be null since it is hidden from the form if in router context.

            int oldI2CPPort = _util.getI2CPPort();
            String oldI2CPHost = _util.getI2CPHost();
            int port = oldI2CPPort;
            if (i2cpPort != null) {
                try { port = Integer.parseInt(i2cpPort); } catch (NumberFormatException nfe) {}
            }

            Map<String, String> opts = new HashMap<String, String>();
            i2cpOpts = DataHelper.stripHTML(i2cpOpts);
            StringTokenizer tok = new StringTokenizer(i2cpOpts, " \t\n");
            while (tok.hasMoreTokens()) {
                String pair = tok.nextToken();
                int split = pair.indexOf('=');
                if (split > 0)
                    opts.put(pair.substring(0, split), pair.substring(split+1));
            }
            Map<String, String> oldOpts = new HashMap<String, String>();
            String oldI2CPOpts = _config.getProperty(PROP_I2CP_OPTS);
            if (oldI2CPOpts == null) oldI2CPOpts = "";
            tok = new StringTokenizer(oldI2CPOpts, " \t\n");
            while (tok.hasMoreTokens()) {
                String pair = tok.nextToken();
                int split = pair.indexOf('=');
                if (split > 0)
                    oldOpts.put(pair.substring(0, split), pair.substring(split+1));
            }
            
            boolean reconnect = i2cpHost != null && i2cpHost.trim().length() > 0 && port > 0 &&
                                (port != _util.getI2CPPort() || !oldI2CPHost.equals(i2cpHost));
            if (reconnect || !oldOpts.equals(opts)) {
                boolean snarksActive = false;
                if (reconnect) {
                    for (Snark snark : _snarks.values()) {
                        if (!snark.isStopped()) {
                            snarksActive = true;
                            break;
                        }
                    }
                }
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("i2cp host [" + i2cpHost + "] i2cp port " + port + " opts [" + opts 
                               + "] oldOpts [" + oldOpts + "]");
                if (snarksActive) {
                    Properties p = new Properties();
                    p.putAll(opts);
                    _util.setI2CPConfig(i2cpHost, port, p);
                    _util.setMaxUpBW(getInt(PROP_UPBW_MAX, DEFAULT_MAX_UP_BW));
                    addMessage(_("I2CP and tunnel changes will take effect after stopping all torrents"));
                } else if (!reconnect) {
                    // The usual case, the other two are if not in router context
                    _config.setProperty(PROP_I2CP_OPTS, i2cpOpts.trim());
                    addMessage(_("I2CP options changed to {0}", i2cpOpts));
                    _util.setI2CPConfig(oldI2CPHost, oldI2CPPort, opts);
                } else {
                    // Won't happen, I2CP host/port, are hidden in the GUI if in router context
                    if (_util.connected()) {
                        _util.disconnect();
                        addMessage(_("Disconnecting old I2CP destination"));
                    }
                    addMessage(_("I2CP settings changed to {0}", i2cpHost + ':' + port + ' ' + i2cpOpts));
                    _util.setI2CPConfig(i2cpHost, port, opts);
                    _util.setMaxUpBW(getInt(PROP_UPBW_MAX, DEFAULT_MAX_UP_BW));
                    boolean ok = _util.connect();
                    if (!ok) {
                        addMessage(_("Unable to connect with the new settings, reverting to the old I2CP settings"));
                        _util.setI2CPConfig(oldI2CPHost, oldI2CPPort, oldOpts);
                        ok = _util.connect();
                        if (!ok)
                            addMessage(_("Unable to reconnect with the old settings!"));
                    } else {
                        addMessage(_("Reconnected on the new I2CP destination"));
                        _config.setProperty(PROP_I2CP_HOST, i2cpHost.trim());
                        _config.setProperty(PROP_I2CP_PORT, "" + port);
                        _config.setProperty(PROP_I2CP_OPTS, i2cpOpts.trim());
                        // no PeerAcceptors/I2PServerSockets to deal with, since all snarks are inactive
                        for (Snark snark : _snarks.values()) {
                            if (snark.restartAcceptor()) {
                                addMessage(_("I2CP listener restarted for \"{0}\"", snark.getBaseName()));
                                // this is the common ConnectionAcceptor, so we only need to do it once
                                break;
                            }
                        }
                    }
                }
                changed = true;
            }  // reconnect || changed options

        if (areFilesPublic() != filesPublic) {
            _config.setProperty(PROP_FILES_PUBLIC, Boolean.toString(filesPublic));
            _util.setFilesPublic(filesPublic);
            if (filesPublic)
                addMessage(_("New files will be publicly readable"));
            else
                addMessage(_("New files will not be publicly readable"));
            changed = true;
        }

        if (shouldAutoStart() != autoStart) {
            _config.setProperty(PROP_AUTO_START, Boolean.toString(autoStart));
            if (autoStart)
                addMessage(_("Enabled autostart"));
            else
                addMessage(_("Disabled autostart"));
            changed = true;
        }
        if (_util.shouldUseOpenTrackers() != useOpenTrackers) {
            _config.setProperty(PROP_USE_OPENTRACKERS, useOpenTrackers + "");
            if (useOpenTrackers)
                addMessage(_("Enabled open trackers - torrent restart required to take effect."));
            else
                addMessage(_("Disabled open trackers - torrent restart required to take effect."));
            _util.setUseOpenTrackers(useOpenTrackers);
            changed = true;
        }
        if (_util.shouldUseDHT() != useDHT) {
            _config.setProperty(PROP_USE_DHT, Boolean.toString(useDHT));
            if (useDHT)
                addMessage(_("Enabled DHT."));
            else
                addMessage(_("Disabled DHT."));
            if (_util.connected())
                addMessage(_("DHT change requires tunnel shutdown and reopen"));
            _util.setUseDHT(useDHT);
            changed = true;
        }
        if (theme != null) {
            if(!theme.equals(_config.getProperty(PROP_THEME))) {
                _config.setProperty(PROP_THEME, theme);
                addMessage(_("{0} theme loaded, return to main i2psnark page to view.", theme));
                changed = true;
            }
        }
        if (changed) {
            saveConfig();
            if (interruptMonitor)
                // Data dir changed. this will stop and remove all old torrents, and add the new ones
                _monitor.interrupt();
        } else {
            addMessage(_("Configuration unchanged."));
        }
    }
    
    /**
     *  Others should use the version in I2PSnarkUtil
     *  @return non-null, empty if disabled
     *  @since 0.9.1
     */
    private List<String> getOpenTrackers() {
        if (!_util.shouldUseOpenTrackers())
            return Collections.emptyList();
        return getListConfig(PROP_OPENTRACKERS, DEFAULT_OPENTRACKERS);
    }
    
    /**
     *  @return non-null, fixed size, may be empty or unmodifiable
     *  @since 0.9.1
     */
    public List<String> getPrivateTrackers() {
        return getListConfig(PROP_PRIVATETRACKERS, null);
    }

    /**
     *  @param ot null to restore default
     *  @since 0.9.1
     */
    public void saveOpenTrackers(List<String> ot) {
        setListConfig(PROP_OPENTRACKERS, ot);
        if (ot == null)
            ot = getListConfig(PROP_OPENTRACKERS, DEFAULT_OPENTRACKERS);
        _util.setOpenTrackers(ot);
        addMessage(_("Open Tracker list changed - torrent restart required to take effect."));
        saveConfig();
    }

    /**
     *  @param pt null ok, default is none
     *  @since 0.9.1
     */
    public void savePrivateTrackers(List<String> pt) {
        setListConfig(PROP_PRIVATETRACKERS, pt);
        addMessage(_("Private tracker list changed - affects newly created torrents only."));
        saveConfig();
    }

    /**
     *  @param dflt default or null
     *  @return non-null, fixed size
     *  @since 0.9.1
     */
    private List<String> getListConfig(String prop, String dflt) {
        String val = _config.getProperty(prop);
        if (val == null)
            val = dflt;
        if (val == null)
            return Collections.emptyList();
        return Arrays.asList(val.split(","));
    }

    /**
     *  Sets the config, does NOT save it
     *  @param values may be null or empty
     *  @return the comma-separated config string, non-null
     *  @since 0.9.1
     */
    private String setListConfig(String prop, List<String> values) {
        if (values == null || values.isEmpty()) {
            _config.remove(prop);
            return "";
        }
        StringBuilder buf = new StringBuilder(64);
        for (String s : values) {
             if (buf.length() > 0)
                 buf.append(',');
             buf.append(s);
        }
        String rv = buf.toString();
        _config.setProperty(prop, rv);
        return rv;
    }

    public void saveConfig() {
        try {
            synchronized (_configLock) {
                DataHelper.storeProps(_config, _configFile);
            }
        } catch (IOException ioe) {
            addMessage(_("Unable to save the config to {0}", _configFile.getAbsolutePath()));
        }
    }
    
    /** hardcoded for sanity.  perhaps this should be customizable, for people who increase their ulimit, etc. */
    public static final int MAX_FILES_PER_TORRENT = 512;
    
    /**
     *  Set of canonical .torrent filenames that we are dealing with.
     *  An unsynchronized copy.
     */
    public Set<String> listTorrentFiles() {
        return new HashSet<String>(_snarks.keySet());
    }

    /**
     * Grab the torrent given the (canonical) filename of the .torrent file
     * @return Snark or null
     */
    public Snark getTorrent(String filename) { synchronized (_snarks) { return _snarks.get(filename); } }

    /**
     *  Unmodifiable
     *  @since 0.9.4
     */
    public Collection<Snark> getTorrents() {
        return Collections.unmodifiableCollection(_snarks.values());
    }

    /**
     * Grab the torrent given the base name of the storage
     * @return Snark or null
     * @since 0.7.14
     */
    public Snark getTorrentByBaseName(String filename) {
        synchronized (_snarks) {
            for (Snark s : _snarks.values()) {
                if (s.getBaseName().equals(filename))
                    return s;
            }
        }
        return null;
    }

    /**
     * Grab the torrent given the info hash
     * @return Snark or null
     * @since 0.8.4
     */
    public Snark getTorrentByInfoHash(byte[] infohash) {
        synchronized (_snarks) {
            for (Snark s : _snarks.values()) {
                if (DataHelper.eq(infohash, s.getInfoHash()))
                    return s;
            }
        }
        return null;
    }

    /**
     *  Caller must verify this torrent is not already added.
     *
     *  @param filename the absolute path to save the metainfo to, generally ending in ".torrent"
     *  @param baseFile may be null, if so look in dataDir
     *  @throws RuntimeException via Snark.fatal()
     */
    private void addTorrent(String filename, File baseFile, boolean dontAutoStart) {
        addTorrent(filename, baseFile, dontAutoStart, null);
    }

    /**
     *  Caller must verify this torrent is not already added.
     *
     *  @param filename the absolute path to save the metainfo to, generally ending in ".torrent"
     *  @param baseFile may be null, if so look in dataDir
     *  @param dataDir must exist, or null to default to snark data directory
     *  @throws RuntimeException via Snark.fatal()
     *  @since 0.9.17
     */
    private void addTorrent(String filename, File baseFile, boolean dontAutoStart, File dataDir) {
        if ((!dontAutoStart) && !_util.connected()) {
            addMessage(_("Connecting to I2P"));
            boolean ok = _util.connect();
            if (!ok) {
                addMessage(_("Error connecting to I2P - check your I2CP settings!"));
                return;
            }
        }
        File sfile = new File(filename);
        try {
            filename = sfile.getCanonicalPath();
        } catch (IOException ioe) {
            _log.error("Unable to add the torrent " + filename, ioe);
            addMessage(_("Error: Could not add the torrent {0}", filename) + ": " + ioe);
            return;
        }
        if (dataDir == null)
            dataDir = getDataDir();
        Snark torrent = null;
        synchronized (_snarks) {
            torrent = _snarks.get(filename);
        }
        // don't hold the _snarks lock while verifying the torrent
        if (torrent == null) {
            synchronized (_addSnarkLock) {
                // double-check
                synchronized (_snarks) {
                    if(_snarks.get(filename) != null)
                        return;
                }

                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(sfile);
                } catch (IOException ioe) {
                    // catch this here so we don't try do delete it below
                    addMessage(_("Cannot open \"{0}\"", sfile.getName()) + ": " + ioe.getMessage());
                    return;
                }

                try {
                    // This is somewhat wasteful as this metainfo is thrown away,
                    // the real one is created in the Snark constructor.
                    // TODO: Make a Snark constructor where we pass the MetaInfo in as a parameter.
                    MetaInfo info = new MetaInfo(fis);
                    try {
                        fis.close();
                        fis = null;
                    } catch (IOException e) {}
                    
                    // This test may be a duplicate, but not if we were called
                    // from the DirMonitor, which only checks for dup torrent file names.
                    Snark snark = getTorrentByInfoHash(info.getInfoHash());
                    if (snark != null) {
                        // TODO - if the existing one is a magnet, delete it and add the metainfo instead?
                        addMessage(_("Torrent with this info hash is already running: {0}", snark.getBaseName()));
                        return;
                    }

                    if (!TrackerClient.isValidAnnounce(info.getAnnounce())) {
                        if (info.isPrivate()) {
                            addMessage(_("ERROR - No I2P trackers in private torrent \"{0}\"", info.getName()));
                        } else if (!_util.getOpenTrackers().isEmpty()) {
                            addMessage(_("Warning - No I2P trackers in \"{0}\", will announce to I2P open trackers and DHT only.", info.getName()));
                            //addMessage(_("Warning - No I2P trackers in \"{0}\", will announce to I2P open trackers only.", info.getName()));
                        } else if (_util.shouldUseDHT()) {
                            addMessage(_("Warning - No I2P trackers in \"{0}\", and open trackers are disabled, will announce to DHT only.", info.getName()));
                        } else {
                            addMessage(_("Warning - No I2P trackers in \"{0}\", and DHT and open trackers are disabled, you should enable open trackers or DHT before starting the torrent.", info.getName()));
                            //addMessage(_("Warning - No I2P Trackers found in \"{0}\". Make sure Open Tracker is enabled before starting this torrent.", info.getName()));
                            dontAutoStart = true;
                        }
                    }
                    String rejectMessage = validateTorrent(info);
                    if (rejectMessage != null) {
                        sfile.delete();
                        addMessage(rejectMessage);
                        return;
                    } else {
                        // TODO load saved closest DHT nodes and pass to the Snark ?
                        // This may take a LONG time
                        if (baseFile == null)
                            baseFile = getSavedBaseFile(info.getInfoHash());
                        if (_log.shouldLog(Log.INFO))
                            _log.info("New Snark, torrent: " + filename + " base: " + baseFile);
                        torrent = new Snark(_util, filename, null, -1, null, null, this,
                                            _peerCoordinatorSet, _connectionAcceptor,
                                            false, dataDir.getPath(), baseFile);
                        loadSavedFilePriorities(torrent);
                        synchronized (_snarks) {
                            _snarks.put(filename, torrent);
                        }
                    }
                } catch (IOException ioe) {
                    String err = _("Torrent in \"{0}\" is invalid", sfile.getName()) + ": " + ioe.getMessage();
                    addMessage(err);
                    _log.error(err, ioe);
                    if (sfile.exists())
                        sfile.delete();
                    return;
                } catch (OutOfMemoryError oom) {
                    addMessage(_("ERROR - Out of memory, cannot create torrent from {0}", sfile.getName()) + ": " + oom.getMessage());
                    return;
                } finally {
                    if (fis != null) try { fis.close(); } catch (IOException ioe) {}
                }
            }
        } else {
            return;
        }
        // ok, snark created, now lets start it up or configure it further
        if (!dontAutoStart && shouldAutoStart()) {
            torrent.startTorrent();
            addMessage(_("Torrent added and started: \"{0}\"", torrent.getBaseName()));
        } else {
            addMessage(_("Torrent added: \"{0}\"", torrent.getBaseName()));
        }
    }
    
    /**
     * Add a torrent with the info hash alone (magnet / maggot)
     *
     * @param name hex or b32 name from the magnet link
     * @param ih 20 byte info hash
     * @param trackerURL may be null
     * @param updateStatus should we add this magnet to the config file,
     *                     to save it across restarts, in case we don't get
     *                     the metadata before shutdown?
     * @throws RuntimeException via Snark.fatal()
     * @since 0.8.4
     */
    public void addMagnet(String name, byte[] ih, String trackerURL, boolean updateStatus) {
        // updateStatus is true from UI, false from config file bulk add
        addMagnet(name, ih, trackerURL, updateStatus, updateStatus, null, this);
    }
    
    /**
     * Add a torrent with the info hash alone (magnet / maggot)
     *
     * @param name hex or b32 name from the magnet link
     * @param ih 20 byte info hash
     * @param trackerURL may be null
     * @param updateStatus should we add this magnet to the config file,
     *                     to save it across restarts, in case we don't get
     *                     the metadata before shutdown?
     * @param dataDir must exist, or null to default to snark data directory
     * @throws RuntimeException via Snark.fatal()
     * @since 0.9.17
     */
    public void addMagnet(String name, byte[] ih, String trackerURL, boolean updateStatus, File dataDir) {
        // updateStatus is true from UI, false from config file bulk add
        addMagnet(name, ih, trackerURL, updateStatus, updateStatus, dataDir, this);
    }
    
    /**
     * Add a torrent with the info hash alone (magnet / maggot)
     * External use is for UpdateRunner.
     *
     * @param name hex or b32 name from the magnet link
     * @param ih 20 byte info hash
     * @param trackerURL may be null
     * @param updateStatus should we add this magnet to the config file,
     *                     to save it across restarts, in case we don't get
     *                     the metadata before shutdown?
     * @param dataDir must exist, or null to default to snark data directory
     * @param listener to intercept callbacks, should pass through to this
     * @return the new Snark or null on failure
     * @throws RuntimeException via Snark.fatal()
     * @since 0.9.4
     */
    public Snark addMagnet(String name, byte[] ih, String trackerURL, boolean updateStatus,
                          boolean autoStart, File dataDir, CompleteListener listener) {
        String dirPath = dataDir != null ? dataDir.getAbsolutePath() : getDataDir().getPath();
        Snark torrent = new Snark(_util, name, ih, trackerURL, listener,
                                  _peerCoordinatorSet, _connectionAcceptor,
                                  false, dirPath);

        synchronized (_snarks) {
            Snark snark = getTorrentByInfoHash(ih);
            if (snark != null) {
                addMessage(_("Torrent with this info hash is already running: {0}", snark.getBaseName()));
                return null;
            }
            // Tell the dir monitor not to delete us
            _magnets.add(name);
            if (updateStatus)
                saveMagnetStatus(ih);
            _snarks.put(name, torrent);
        }
        if (autoStart) {
            startTorrent(ih);
            addMessage(_("Fetching {0}", name));
            DHT dht = _util.getDHT();
            boolean shouldWarn = _util.connected() &&
                                 _util.getOpenTrackers().isEmpty() &&
                                 ((!_util.shouldUseDHT()) || dht == null || dht.size() <= 0);
            if (shouldWarn) {
                addMessage(_("Open trackers are disabled and we have no DHT peers. " +
                             "Fetch of {0} may not succeed until you start another torrent, enable open trackers, or enable DHT.", name));
            }
        } else {
            addMessage(_("Adding {0}", name));
        }
        return torrent;
    }

    /**
     * Stop and delete a torrent running in magnet mode
     *
     * @param snark a torrent with a fake file name ("Magnet xxxx")
     * @since 0.8.4
     */
    public void deleteMagnet(Snark snark) {
        synchronized (_snarks) {
            _snarks.remove(snark.getName());
        }
        snark.stopTorrent();
        _magnets.remove(snark.getName());
        removeMagnetStatus(snark.getInfoHash());
        removeTorrentStatus(snark);
    }
    
    /**
     * Add and start a FetchAndAdd task.
     * Remove it with deleteMagnet().
     *
     * @param torrent must be instanceof FetchAndAdd
     * @throws RuntimeException via Snark.fatal()?
     * @since 0.9.1
     */
    public void addDownloader(Snark torrent) {
        synchronized (_snarks) {
            Snark snark = getTorrentByInfoHash(torrent.getInfoHash());
            if (snark != null) {
                addMessage(_("Download already running: {0}", snark.getBaseName()));
                return;
            }
            String name = torrent.getName();
            // Tell the dir monitor not to delete us
            _magnets.add(name);
            _snarks.put(name, torrent);
        }
        torrent.startTorrent();
    }

    /**
     * Add a torrent from a MetaInfo. Save the MetaInfo data to filename.
     * Holds the snarks lock to prevent interference from the DirMonitor.
     * This verifies that a torrent with this infohash is not already added.
     * This may take a LONG time to create or check the storage.
     *
     * Called from servlet. This is only for the 'create torrent' form.
     *
     * @param metainfo the metainfo for the torrent
     * @param bitfield the current completion status of the torrent
     * @param filename the absolute path to save the metainfo to, generally ending in ".torrent", which is also the name of the torrent
     *                 Must be a filesystem-safe name.
     * @param baseFile may be null, if so look in rootDataDir
     * @throws RuntimeException via Snark.fatal()
     * @return success
     * @since 0.8.4
     */
    public boolean addTorrent(MetaInfo metainfo, BitField bitfield, String filename,
                              File baseFile, boolean dontAutoStart) throws IOException {
        // prevent interference by DirMonitor
        synchronized (_snarks) {
            Snark snark = getTorrentByInfoHash(metainfo.getInfoHash());
            if (snark != null) {
                addMessage(_("Torrent with this info hash is already running: {0}", snark.getBaseName()));
                return false;
            }
            // so addTorrent won't recheck
            saveTorrentStatus(metainfo, bitfield, null, baseFile, true, 0); // no file priorities
            try {
                locked_writeMetaInfo(metainfo, filename, areFilesPublic());
                // hold the lock for a long time
                addTorrent(filename, baseFile, dontAutoStart);
            } catch (IOException ioe) {
                addMessage(_("Failed to copy torrent file to {0}", filename));
                _log.error("Failed to write torrent file", ioe);
                return false;
            }
        }
        return true;
    }

    /**
     * Add a torrent from a file not in the torrent directory. Copy the file to filename.
     * Holds the snarks lock to prevent interference from the DirMonitor.
     * Caller must verify this torrent is not already added.
     * This may take a LONG time to create or check the storage.
     *
     * @param fromfile where the file is now, presumably in a temp directory somewhere
     * @param filename the absolute path to save the metainfo to, generally ending in ".torrent", which is also the name of the torrent
     *                 Must be a filesystem-safe name.
     * @param dataDir must exist, or null to default to snark data directory
     * @throws RuntimeException via Snark.fatal()
     * @since 0.8.4
     */
    public void copyAndAddTorrent(File fromfile, String filename, File dataDir) throws IOException {
        // prevent interference by DirMonitor
        synchronized (_snarks) {
            boolean success = FileUtil.copy(fromfile.getAbsolutePath(), filename, false);
            if (!success) {
                addMessage(_("Failed to copy torrent file to {0}", filename));
                _log.error("Failed to write torrent file to " + filename);
                return;
            }
            if (!areFilesPublic())
                SecureFileOutputStream.setPerms(new File(filename));
            // hold the lock for a long time
            addTorrent(filename, null, false, dataDir);
         }
    }

    /**
     * Write the metainfo to the file, caller must hold the snarks lock
     * to prevent interference from the DirMonitor.
     *
     * @param metainfo The metainfo for the torrent
     * @param filename The absolute path to save the metainfo to, generally ending in ".torrent".
     *                 Must be a filesystem-safe name.
     * @since 0.8.4
     */
    private static void locked_writeMetaInfo(MetaInfo metainfo, String filename, boolean areFilesPublic) throws IOException {
        File file = new File(filename);
        if (file.exists())
            throw new IOException("Cannot overwrite an existing .torrent file: " + file.getPath());
        OutputStream out = null;
        try {
            if (areFilesPublic)
                out = new FileOutputStream(filename);
            else
                out = new SecureFileOutputStream(filename);
            out.write(metainfo.getTorrentData());
        } catch (IOException ioe) {
            // remove any partial
            file.delete();
            throw ioe;
        } finally {
            try {
                if (out != null)
                    out.close();
            } catch (IOException ioe) {}
        }
    }

    /**
     * Get the timestamp for a torrent from the config file.
     * A Snark.CompleteListener method.
     */
    public long getSavedTorrentTime(Snark snark) {
        Properties config = getConfig(snark);
        String time = config.getProperty(PROP_META_STAMP);
        if (time == null)
            return 0;
        try { return Long.parseLong(time); } catch (NumberFormatException nfe) {}
        return 0;
    }
    
    /**
     * Get the saved bitfield for a torrent from the config file.
     * Convert "." to a full bitfield.
     * A Snark.CompleteListener method.
     */
    public BitField getSavedTorrentBitField(Snark snark) {
        MetaInfo metainfo = snark.getMetaInfo();
        if (metainfo == null)
            return null;
        Properties config = getConfig(snark);
        String bf = config.getProperty(PROP_META_BITFIELD);
        if (bf == null)
            return null;
        int len = metainfo.getPieces();
        if (bf.equals(".")) {
            BitField bitfield = new BitField(len);
            for (int i = 0; i < len; i++)
                 bitfield.set(i);
            return bitfield;
        }
        byte[] bitfield = Base64.decode(bf);
        if (bitfield == null)
            return null;
        if (bitfield.length * 8 < len)
            return null;
        return new BitField(bitfield, len);
    }
    
    /**
     * Get the saved priorities for a torrent from the config file.
     * @since 0.8.1
     */
    public void loadSavedFilePriorities(Snark snark) {
        MetaInfo metainfo = snark.getMetaInfo();
        Storage storage = snark.getStorage();
        if (metainfo == null || storage == null)
            return;
        if (metainfo.getFiles() == null)
            return;
        Properties config = getConfig(snark);
        String pri = config.getProperty(PROP_META_PRIORITY);
        if (pri == null)
            return;
        int filecount = metainfo.getFiles().size();
        int[] rv = new int[filecount];
        String[] arr = pri.split(",");
        for (int i = 0; i < filecount && i < arr.length; i++) {
            if (arr[i].length() > 0) {
                try {
                    rv[i] = Integer.parseInt(arr[i]);
                } catch (Throwable t) {}
            }
        }
        storage.setFilePriorities(rv);
    }

    /**
     * Get the base location for a torrent from the config file.
     * @return File or null, doesn't necessarily exist
     * @since 0.9.15
     */
    private File getSavedBaseFile(byte[] ih) {
        Properties config = getConfig(ih);
        String base = config.getProperty(PROP_META_BASE);
        if (base == null)
            return null;
        return new File(base);
    }

    /**
     * Get setting for a torrent from the config file.
     * @return setting, false if not found
     * @since 0.9.15
     */
    public boolean getSavedPreserveNamesSetting(Snark snark) {
        Properties config = getConfig(snark);
        return Boolean.parseBoolean(config.getProperty(PROP_META_PRESERVE_NAMES));
    }

    /**
     * Get setting for a torrent from the config file.
     * @return setting, 0 if not found
     * @since 0.9.15
     */
    public long getSavedUploaded(Snark snark) {
        Properties config = getConfig(snark);
        if (config != null) {
            try {
                return Long.parseLong(config.getProperty(PROP_META_UPLOADED));
            } catch (NumberFormatException nfe) {}
        }
        return 0;
    }
    
    /**
     * Save the completion status of a torrent and other data in the config file
     * for that torrent. Does nothing for magnets.
     *
     * @since 0.9.15
     */
    public void saveTorrentStatus(Snark snark) {
        MetaInfo meta = snark.getMetaInfo();
        Storage storage = snark.getStorage();
        if (meta == null || storage == null)
            return;
        saveTorrentStatus(meta, storage.getBitField(), storage.getFilePriorities(),
                          storage.getBase(), storage.getPreserveFileNames(),
                          snark.getUploaded());
    }

    /**
     * Save the completion status of a torrent and the current time in the config file
     * for that torrent.
     * The time is a standard long converted to string.
     * The status is either a bitfield converted to Base64 or "." for a completed
     * torrent to save space in the config file and in memory.
     *
     * @param bitfield non-null
     * @param priorities may be null
     * @param base may be null
     */
    private void saveTorrentStatus(MetaInfo metainfo, BitField bitfield, int[] priorities,
                                   File base, boolean preserveNames, long uploaded) {
        synchronized (_configLock) {
            locked_saveTorrentStatus(metainfo, bitfield, priorities, base, preserveNames, uploaded);
        }
    }

    private void locked_saveTorrentStatus(MetaInfo metainfo, BitField bitfield, int[] priorities,
                                          File base, boolean preserveNames, long uploaded) {
        byte[] ih = metainfo.getInfoHash();
        String bfs;
        if (bitfield.complete()) {
          bfs = ".";
        } else {
          byte[] bf = bitfield.getFieldBytes();
          bfs = Base64.encode(bf);
        }
        Properties config = getConfig(ih);
        config.setProperty(PROP_META_STAMP, Long.toString(System.currentTimeMillis()));
        config.setProperty(PROP_META_BITFIELD, bfs);
        config.setProperty(PROP_META_PRESERVE_NAMES, Boolean.toString(preserveNames));
        config.setProperty(PROP_META_UPLOADED, Long.toString(uploaded));
        if (base != null)
            config.setProperty(PROP_META_BASE, base.getAbsolutePath());

        // now the file priorities
        if (priorities != null) {
            boolean nonzero = false;
            for (int i = 0; i < priorities.length; i++) {
                if (priorities[i] != 0) {
                    nonzero = true;
                    break;
                }
            }
            if (nonzero) {
                // generate string like -5,,4,3,,,,,,-2 where no number is zero.
                StringBuilder buf = new StringBuilder(2 * priorities.length);
                for (int i = 0; i < priorities.length; i++) {
                    if (priorities[i] != 0)
                        buf.append(Integer.toString(priorities[i]));
                    if (i != priorities.length - 1)
                        buf.append(',');
                }
                config.setProperty(PROP_META_PRIORITY, buf.toString());
            } else {
                config.remove(PROP_META_PRIORITY);
            }
        } else {
            config.remove(PROP_META_PRIORITY);
        }

        // TODO save closest DHT nodes too

        File conf = configFile(_configDir, ih);
        File subdir = conf.getParentFile();
        if (!subdir.exists())
            subdir.mkdirs();
        try {
            DataHelper.storeProps(config, conf);
        } catch (IOException ioe) {
            _log.error("Unable to save the config to " + conf);
        }
    }
    
    /**
     * Remove the status of a torrent by removing the config file.
     */
    private void removeTorrentStatus(Snark snark) {
        byte[] ih = snark.getInfoHash();
        File conf = configFile(_configDir, ih);
        synchronized (_configLock) {
            boolean ok = conf.delete();
            if (ok) {
                if (_log.shouldInfo())
                    _log.info("Deleted " + conf + " for " + snark.getName());
            } else {
                if (_log.shouldWarn())
                    _log.warn("Failed to delete " + conf + " for " + snark.getName());
            }
            File subdir = conf.getParentFile();
            String[] files = subdir.list();
            if (files != null && files.length == 0)
                subdir.delete();
        }
    }
    
    /**
     *  Remove all orphaned torrent status files, which weren't removed
     *  before 0.9.20, and could be left around after a manual delete also.
     *  Run this once at startup.
     *  @since 0.9.20
     */
    private void cleanupTorrentStatus() {
        Set<SHA1Hash> torrents = new HashSet<SHA1Hash>(32);
        int found = 0;
        int totalDeleted = 0;
        synchronized (_snarks) {
            for (Snark snark : _snarks.values()) {
                torrents.add(new SHA1Hash(snark.getMetaInfo().getInfoHash()));
            }
            synchronized (_configLock) {
                for (int i = 0; i < B64.length(); i++) {
                    File subdir = new File(_configDir, SUBDIR_PREFIX + B64.charAt(i));
                    File[] configs = subdir.listFiles();
                    if (configs == null)
                        continue;
                    int deleted = 0;
                    for (int j = 0; j < configs.length; j++) {
                        File config = configs[j];
                        SHA1Hash ih = configFileToInfoHash(config);
                        if (ih == null)
                            continue;
                        found++;
                        if (torrents.contains(ih)) {
                            if (_log.shouldInfo())
                                _log.info("Torrent for " + config + " exists");
                        } else {
                            boolean ok = config.delete();
                            if (ok) {
                                if (_log.shouldInfo())
                                    _log.info("Deleted " + config + " for " + ih);
                                deleted++;
                            } else {
                                if (_log.shouldWarn())
                                    _log.warn("Failed to delete " + config + " for " + ih);
                            }
                        }
                    }
                    if (deleted == configs.length) {
                        if (_log.shouldInfo())
                            _log.info("Deleting " + subdir);
                        subdir.delete();
                    }
                    totalDeleted += deleted;
                }
            }
        }
        if (_log.shouldInfo())
            _log.info("Cleanup found " + torrents.size() + " torrents and " + found +
                      " configs, deleted " + totalDeleted + " old configs");
    }
    
    /**
     *  Just remember we have it
     *  @since 0.8.4
     */
    public void saveMagnetStatus(byte[] ih) {
        String infohash = Base64.encode(ih);
        infohash = infohash.replace('=', '$');
        _config.setProperty(PROP_META_MAGNET_PREFIX + infohash, ".");
        saveConfig();
    }
    
    /**
     *  Remove the magnet marker from the config file.
     *  @since 0.8.4
     */
    public void removeMagnetStatus(byte[] ih) {
        String infohash = Base64.encode(ih);
        infohash = infohash.replace('=', '$');
        _config.remove(PROP_META_MAGNET_PREFIX + infohash);
        saveConfig();
    }
    
    /**
     *  Does not really delete on failure, that's the caller's responsibility.
     *  Warning - does not validate announce URL - use TrackerClient.isValidAnnounce()
     *  @return failure message or null on success
     */
    private String validateTorrent(MetaInfo info) {
        List<List<String>> files = info.getFiles();
        if ( (files != null) && (files.size() > MAX_FILES_PER_TORRENT) ) {
            return _("Too many files in \"{0}\" ({1}), deleting it!", info.getName(), files.size());
        } else if ( (files == null) && (info.getName().endsWith(".torrent")) ) {
            return _("Torrent file \"{0}\" cannot end in \".torrent\", deleting it!", info.getName());
        } else if (info.getPieces() <= 0) {
            return _("No pieces in \"{0}\",  deleting it!", info.getName());
        } else if (info.getPieces() > Storage.MAX_PIECES) {
            return _("Too many pieces in \"{0}\", limit is {1}, deleting it!", info.getName(), Storage.MAX_PIECES);
        } else if (info.getPieceLength(0) > Storage.MAX_PIECE_SIZE) {
            return _("Pieces are too large in \"{0}\" ({1}B), deleting it.", info.getName(), DataHelper.formatSize2(info.getPieceLength(0))) + ' ' +
                   _("Limit is {0}B", DataHelper.formatSize2(Storage.MAX_PIECE_SIZE));
        } else if (info.getTotalLength() <= 0) {
            return _("Torrent \"{0}\" has no data, deleting it!", info.getName());
        } else if (info.getTotalLength() > Storage.MAX_TOTAL_SIZE) {
            System.out.println("torrent info: " + info.toString());
            List<Long> lengths = info.getLengths();
            if (lengths != null)
                for (int i = 0; i < lengths.size(); i++)
                    System.out.println("File " + i + " is " + lengths.get(i) + " long.");
            
            return _("Torrents larger than {0}B are not supported yet, deleting \"{1}\"", Storage.MAX_TOTAL_SIZE, info.getName());
        } else {
            // ok
            return null;
        }
    }
    
    /**
     * Stop the torrent, leaving it on the list of torrents unless told to remove it.
     * If shouldRemove is true, removes the config file also.
     */
    public Snark stopTorrent(String filename, boolean shouldRemove) {
        File sfile = new File(filename);
        try {
            filename = sfile.getCanonicalPath();
        } catch (IOException ioe) {
            _log.error("Unable to remove the torrent " + filename, ioe);
            addMessage(_("Error: Could not remove the torrent {0}", filename) + ": " + ioe.getMessage());
            return null;
        }
        int remaining = 0;
        Snark torrent = null;
        synchronized (_snarks) {
            if (shouldRemove)
                torrent = _snarks.remove(filename);
            else
                torrent = _snarks.get(filename);
            remaining = _snarks.size();
        }
        if (torrent != null) {
            boolean wasStopped = torrent.isStopped();
            torrent.stopTorrent();
            if (remaining == 0) {
                // should we disconnect/reconnect here (taking care to deal with the other thread's
                // I2PServerSocket.accept() call properly?)
                ////_util.
            }
            if (shouldRemove)
                removeTorrentStatus(torrent);
            if (!wasStopped)
                addMessage(_("Torrent stopped: \"{0}\"", torrent.getBaseName()));
        }
        return torrent;
    }

    /**
     * Stop the torrent, leaving it on the list of torrents unless told to remove it.
     * If shouldRemove is true, removes the config file also.
     * @since 0.8.4
     */
    public void stopTorrent(Snark torrent, boolean shouldRemove) {
        if (shouldRemove) {
            synchronized (_snarks) {
                _snarks.remove(torrent.getName());
            }
        }
        boolean wasStopped = torrent.isStopped();
        torrent.stopTorrent();
        if (!wasStopped)
            addMessage(_("Torrent stopped: \"{0}\"", torrent.getBaseName()));
        if (shouldRemove)
            removeTorrentStatus(torrent);
    }

    /**
     * Stop the torrent and delete the torrent file itself, but leaving the data
     * behind. Removes saved config file also.
     * Holds the snarks lock to prevent interference from the DirMonitor.
     */
    public void removeTorrent(String filename) {
        Snark torrent;
        // prevent interference by DirMonitor
        synchronized (_snarks) {
            torrent = stopTorrent(filename, true);
            if (torrent == null)
                return;
            File torrentFile = new File(filename);
            torrentFile.delete();
        }
        addMessage(_("Torrent removed: \"{0}\"", torrent.getBaseName()));
    }
    
    private class DirMonitor implements Runnable {
        public void run() {
            // don't bother delaying if auto start is false
            long delay = (60L * 1000) * getStartupDelayMinutes();
            if (delay > 0 && shouldAutoStart()) {
                addMessage(_("Adding torrents in {0}", DataHelper.formatDuration2(delay)));
                try { Thread.sleep(delay); } catch (InterruptedException ie) {}
                // Remove that first message
                if (_messages.size() == 1)
                    _messages.poll();
            }

            // here because we need to delay until I2CP is up
            // although the user will see the default until then
            getBWLimit();
            boolean doMagnets = true;
            while (_running) {
                File dir = getDataDir();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Directory Monitor loop over " + dir.getAbsolutePath());
                try {
                    // Don't let this interfere with .torrent files being added or deleted
                    synchronized (_snarks) {
                        monitorTorrents(dir);
                    }
                } catch (Exception e) {
                    _log.error("Error in the DirectoryMonitor", e);
                }
                if (doMagnets) {
                    // first run only
                    try {
                        addMagnets();
                        doMagnets = false;
                    } catch (Exception e) {
                        _log.error("Error in the DirectoryMonitor", e);
                    }
                    if (!_snarks.isEmpty())
                        addMessage(_("Up bandwidth limit is {0} KBps", _util.getMaxUpBW()));
                    // To fix bug where files were left behind,
                    // but also good for when user removes snarks when i2p is not running
                    cleanupTorrentStatus();
                }
                try { Thread.sleep(60*1000); } catch (InterruptedException ie) {}
            }
        }
    }
    
    // Begin Snark.CompleteListeners

    /**
     * A Snark.CompleteListener method.
     */
    public void torrentComplete(Snark snark) {
        MetaInfo meta = snark.getMetaInfo();
        Storage storage = snark.getStorage();
        if (meta == null || storage == null)
            return;
        StringBuilder buf = new StringBuilder(256);
        String base = DataHelper.escapeHTML(storage.getBaseName());
        buf.append("<a href=\"").append(_contextPath).append('/').append(base);
        if (meta.getFiles() != null)
            buf.append('/');
        buf.append("\">").append(base).append("</a>");
        addMessageNoEscape(_("Download finished: {0}", buf.toString())); //  + " (" + _("size: {0}B", DataHelper.formatSize2(len)) + ')');
        updateStatus(snark);
    }
    
    /**
     * A Snark.CompleteListener method.
     */
    public void updateStatus(Snark snark) {
        MetaInfo meta = snark.getMetaInfo();
        Storage storage = snark.getStorage();
        if (meta != null && storage != null)
            saveTorrentStatus(meta, storage.getBitField(), storage.getFilePriorities(),
                              storage.getBase(), storage.getPreserveFileNames(), snark.getUploaded());
    }
    
    /**
     * We transitioned from magnet mode, we have now initialized our
     * metainfo and storage. The listener should now call getMetaInfo()
     * and save the data to disk.
     * A Snark.CompleteListener method.
     *
     * @return the new name for the torrent or null on error
     * @since 0.8.4
     */
    public String gotMetaInfo(Snark snark) {
        MetaInfo meta = snark.getMetaInfo();
        Storage storage = snark.getStorage();
        if (meta != null && storage != null) {
            String rejectMessage = validateTorrent(meta);
            if (rejectMessage != null) {
                addMessage(rejectMessage);
                snark.stopTorrent();
                return null;
            }
            saveTorrentStatus(meta, storage.getBitField(), null,
                              storage.getBase(), storage.getPreserveFileNames(), 0);
            // temp for addMessage() in case canonical throws
            String name = storage.getBaseName();
            try {
                // _snarks must use canonical
                name = (new File(getDataDir(), storage.getBaseName() + ".torrent")).getCanonicalPath();
                // put the announce URL in the file
                String announce = snark.getTrackerURL();
                if (announce != null)
                    meta = meta.reannounce(announce);
                synchronized (_snarks) {
                    locked_writeMetaInfo(meta, name, areFilesPublic());
                    // put it in the list under the new name
                    _snarks.remove(snark.getName());
                    _snarks.put(name, snark);
                }
                _magnets.remove(snark.getName());
                removeMagnetStatus(snark.getInfoHash());
                addMessage(_("Metainfo received for {0}", snark.getName()));
                addMessage(_("Starting up torrent {0}", storage.getBaseName()));
                return name;
            } catch (IOException ioe) {
                addMessage(_("Failed to copy torrent file to {0}", name));
                _log.error("Failed to write torrent file", ioe);
            }
        }
        return null;
    }

    /**
     * A Snark.CompleteListener method.
     * @since 0.9
     */
    public void fatal(Snark snark, String error) {
        addMessage(_("Error on torrent {0}", snark.getName()) + ": " + error);
    }
    
    /**
     * A Snark.CompleteListener method.
     * @since 0.9.2
     */
    public void addMessage(Snark snark, String message) {
        addMessage(message);
    }

    /**
     * A Snark.CompleteListener method.
     * @since 0.9.4
     */
    public void gotPiece(Snark snark) {}

    // End Snark.CompleteListeners

    /**
     * Add all magnets from the config file
     * @since 0.8.4
     */
    private void addMagnets() {
        for (Object o : _config.keySet()) {
            String k = (String) o;
            if (k.startsWith(PROP_META_MAGNET_PREFIX)) {
                String b64 = k.substring(PROP_META_MAGNET_PREFIX.length());
                b64 = b64.replace('$', '=');
                byte[] ih = Base64.decode(b64);
                // ignore value - TODO put tracker URL in value
                if (ih != null && ih.length == 20)
                    addMagnet(_("Magnet") + ' ' + I2PSnarkUtil.toHex(ih), ih, null, false);
                // else remove from config?
            }
        }
    }

    /**
     *  caller must synchronize on _snarks
     */
    private void monitorTorrents(File dir) {
        String fileNames[] = dir.list(TorrentFilenameFilter.instance());
        List<String> foundNames = new ArrayList<String>(0);
        if (fileNames != null) {
            for (int i = 0; i < fileNames.length; i++) {
                try {
                    foundNames.add(new File(dir, fileNames[i]).getCanonicalPath());
                } catch (IOException ioe) {
                    _log.error("Error resolving '" + fileNames[i] + "' in '" + dir, ioe);
                }
            }
        }
        
        Set<String> existingNames = listTorrentFiles();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("DirMon found: " + DataHelper.toString(foundNames) + " existing: " + DataHelper.toString(existingNames));
        // lets find new ones first...
        for (String name : foundNames) {
            if (existingNames.contains(name)) {
                // already known.  noop
            } else {
                if (shouldAutoStart() && !_util.connect())
                    addMessage(_("Unable to connect to I2P!"));
                try {
                    // Snark.fatal() throws a RuntimeException
                    // don't let one bad torrent kill the whole loop
                    addTorrent(name, null, !shouldAutoStart());
                } catch (Exception e) {
                    addMessage(_("Error: Could not add the torrent {0}", name) + ": " + e);
                    _log.error("Unable to add the torrent " + name, e);
                }
            }
        }
        // Don't remove magnet torrents that don't have a torrent file yet
        existingNames.removeAll(_magnets);
        // now lets see which ones have been removed...
        for (String name : existingNames) {
            if (foundNames.contains(name)) {
                // known and still there.  noop
            } else {
                // known, but removed.  drop it
                try {
                    // Snark.fatal() throws a RuntimeException
                    // don't let one bad torrent kill the whole loop
                    stopTorrent(name, true);
                } catch (Exception e) {
                    // don't bother with message
                }
            }
        }
    }

    /** translate */
    private String _(String s) {
        return _util.getString(s);
    }

    /** translate */
    private String _(String s, Object o) {
        return _util.getString(s, o);
    }

    /** translate */
    private String _(String s, Object o, Object o2) {
        return _util.getString(s, o, o2);
    }

    /**
     *  Unsorted map of name to Tracker object
     *  Modifiable, not a copy
     *  @since 0.9.1
     */
    public Map<String, Tracker> getTrackerMap() { 
        return _trackerMap;
    }

    /**
     *  Unsorted, do not modify
     */
    public Collection<Tracker> getTrackers() { 
        return _trackerMap.values();
    }

    /**
     *  Sorted copy
     *  @since 0.9.1
     */
    public List<Tracker> getSortedTrackers() { 
        List<Tracker> rv = new ArrayList<Tracker>(_trackerMap.values());
        Collections.sort(rv, new IgnoreCaseComparator());
        return rv;
    }

    /** @since 0.9 */
    private void initTrackerMap() {
        String trackers = _config.getProperty(PROP_TRACKERS);
        if ( (trackers == null) || (trackers.trim().length() <= 0) )
            trackers = _context.getProperty(PROP_TRACKERS);
        if ( (trackers == null) || (trackers.trim().length() <= 0) ) {
            setDefaultTrackerMap(true);
        } else {
            String[] toks = trackers.split(",");
            for (int i = 0; i < toks.length; i += 2) {
                String name = toks[i].trim().replace("&#44;", ",");
                String url = toks[i+1].trim().replace("&#44;", ",");
                if ( (name.length() > 0) && (url.length() > 0) ) {
                    String urls[] = url.split("=", 2);
                    String url2 = urls.length > 1 ? urls[1] : "";
                    _trackerMap.put(name, new Tracker(name, urls[0], url2));
                }
            }
        }
    }

    /** @since 0.9 */
    public void setDefaultTrackerMap() {
        setDefaultTrackerMap(true);
    }

    /** @since 0.9.1 */
    private void setDefaultTrackerMap(boolean save) {
        _trackerMap.clear();
        for (int i = 0; i < DEFAULT_TRACKERS.length; i += 2) {
            String name = DEFAULT_TRACKERS[i];
            if (name.equals("TheBland") && !SigType.ECDSA_SHA256_P256.isAvailable())
                continue;
            String urls[] = DEFAULT_TRACKERS[i+1].split("=", 2);
            String url2 = urls.length > 1 ? urls[1] : null;
            _trackerMap.put(name, new Tracker(name, urls[0], url2));
        }
        if (save && _config.remove(PROP_TRACKERS) != null) {
            saveConfig();
        }
    }

    /** @since 0.9 */
    public void saveTrackerMap() {
        StringBuilder buf = new StringBuilder(2048);
        boolean comma = false;
        for (Map.Entry<String, Tracker> e : _trackerMap.entrySet()) {
            if (comma)
                buf.append(',');
            else
                comma = true;
            Tracker t = e.getValue();
            buf.append(e.getKey().replace(",", "&#44;")).append(',').append(t.announceURL.replace(",", "&#44;"));
            if (t.baseURL != null)
                buf.append('=').append(t.baseURL);
        }
        _config.setProperty(PROP_TRACKERS, buf.toString());
        saveConfig();
    }

    private static class TorrentFilenameFilter implements FilenameFilter {
        private static final TorrentFilenameFilter _filter = new TorrentFilenameFilter();
        public static TorrentFilenameFilter instance() { return _filter; }
        public boolean accept(File dir, String name) {
            return (name != null) && (name.endsWith(".torrent"));
        }
    }

    /**
     *  If not connected, thread it, otherwise inline
     *  @since 0.9.1
     */
    public void startTorrent(byte[] infoHash) {
        for (Snark snark : _snarks.values()) {
            if (DataHelper.eq(infoHash, snark.getInfoHash())) {
                if (snark.isStarting() || !snark.isStopped()) {
                    addMessage("Torrent already started");
                    return;
                }
                boolean connected = _util.connected();
                if ((!connected) && !_util.isConnecting())
                    addMessage(_("Opening the I2P tunnel"));
                addMessage(_("Starting up torrent {0}", snark.getBaseName()));
                if (connected) {
                    snark.startTorrent();
                } else {
                    // mark it for the UI
                    snark.setStarting();
                    (new I2PAppThread(new ThreadedStarter(snark), "TorrentStarter", true)).start();
                    try { Thread.sleep(200); } catch (InterruptedException ie) {}
                }
                return;
            }
        }
        addMessage("Torrent not found?");
    }

    /**
     *  If not connected, thread it, otherwise inline
     *  @since 0.9.1
     */
    public void startAllTorrents() {
        if (_util.connected()) {
            startAll();
        } else {
            addMessage(_("Opening the I2P tunnel and starting all torrents."));
            for (Snark snark : _snarks.values()) {
                // mark it for the UI
                snark.setStarting();
            }
            (new I2PAppThread(new ThreadedStarter(null), "TorrentStarterAll", true)).start();
            try { Thread.sleep(200); } catch (InterruptedException ie) {}
        }
    }

    /**
     *  Use null constructor param for all
     *  @since 0.9.1
     */
    private class ThreadedStarter implements Runnable {
        private final Snark snark;
        public ThreadedStarter(Snark s) { snark = s; }
        public void run() {
            try {
                run2();
            } catch (Exception e) {
                _log.error("Error starting", e);
            }
        }

        private void run2() {
            if (snark != null) {
                if (snark.isStopped())
                    snark.startTorrent();
            } else {
                startAll();
            }
        }
    }

    /**
     *  Inline
     *  @since 0.9.1
     */
    private void startAll() {
        for (Snark snark : _snarks.values()) {
            if (snark.isStopped())
                snark.startTorrent();
        }
    }

    /**
     * Stop all running torrents, and close the tunnel after a delay
     * to allow for announces.
     * If called at router shutdown via Jetty shutdown hook -> webapp destroy() -> stop(),
     * the tunnel won't actually be closed as the SimpleTimer2 is already shutdown
     * or will be soon, so we delay a few seconds inline.
     * @param finalShutdown if true, sleep at the end if any torrents were running
     * @since 0.9.1
     */
    public void stopAllTorrents(boolean finalShutdown) {
        _stopping = true;
        if (finalShutdown && _log.shouldLog(Log.WARN))
            _log.warn("SnarkManager final shutdown");
        int count = 0;
        for (Snark snark : _snarks.values()) {
            if (!snark.isStopped()) {
                if (count == 0)
                    addMessage(_("Stopping all torrents and closing the I2P tunnel."));
                count++;
                if (finalShutdown)
                    snark.stopTorrent(true);
                else
                    stopTorrent(snark, false);
                // Throttle since every unannounce is now threaded.
                // How to do this without creating a ton of threads?
                try { Thread.sleep(20); } catch (InterruptedException ie) {}
            }
        }
        if (_util.connected()) {
            if (count > 0) {
                DHT dht = _util.getDHT();
                if (dht != null)
                    dht.stop();
                // Schedule this even for final shutdown, as there's a chance
                // that it's just this webapp that is stopping.
                _context.simpleTimer2().addEvent(new Disconnector(), 60*1000);
                addMessage(_("Closing I2P tunnel after notifying trackers."));
                if (finalShutdown) {
                    try { Thread.sleep(5*1000); } catch (InterruptedException ie) {}
                }
            } else {
                _util.disconnect();
                _stopping = false;
                addMessage(_("I2P tunnel closed."));
            }
        }
    }

    /** @since 0.9.1 */
    private class Disconnector implements SimpleTimer.TimedEvent {
        public void timeReached() {
            if (_util.connected()) {
                _util.disconnect();
                _stopping = false;
                addMessage(_("I2P tunnel closed."));
            }
        }
    }
    
    /**
     *  ignore case, current locale
     *  @since 0.9
     */
    private static class IgnoreCaseComparator implements Comparator<Tracker>, Serializable {
        public int compare(Tracker l, Tracker r) {
            return l.name.toLowerCase().compareTo(r.name.toLowerCase());
        }
    }
}
