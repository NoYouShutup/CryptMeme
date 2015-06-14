package net.i2p.router.web;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.crypto.SigType;
import net.i2p.util.FileUtil;
import net.i2p.util.VersionComparator;

import org.eclipse.jetty.server.Server;
import org.tanukisoftware.wrapper.WrapperManager;

public class LogsHelper extends HelperBase {

    private static final String LOCATION_AVAILABLE = "3.3.7";
    
    /** @since 0.8.12 */
    public String getJettyVersion() {
        return Server.getVersion();
    }

    /** @since 0.8.13 */
    public static String jettyVersion() {
        return Server.getVersion();
    }

    /** @since 0.9.15 */
    public String getUnavailableCrypto() {
        StringBuilder buf = new StringBuilder(128);
        for (SigType t : SigType.values()) {
            if (!t.isAvailable()) {
                buf.append("<b>Crypto:</b> ").append(t.toString()).append(" unavailable<br>");
            }
        }
        return buf.toString();
    }

    /**
     *  Does not call logManager.flush(); call getCriticalLogs() first to flush
     */
    public String getLogs() {
        String str = formatMessages(_context.logManager().getBuffer().getMostRecentMessages());
        return "<p>" + _("File location") + ": <b><code>" + _context.logManager().currentFile() + "</code></b></p>" + str;
    }
    
    /**
     *  Side effect - calls logManager.flush()
     */
    public String getCriticalLogs() {
        _context.logManager().flush();
        return formatMessages(_context.logManager().getBuffer().getMostRecentCriticalMessages());
    }
    
    /**
     *  Does not necessarily exist.
     *  @since 0.9.1
     */
    static File wrapperLogFile(I2PAppContext ctx) {
        File f = null;
        if (ctx.hasWrapper()) {
            String wv = System.getProperty("wrapper.version");
            if (wv != null && VersionComparator.comp(wv, LOCATION_AVAILABLE) >= 0) {
                try {
                   f = WrapperManager.getWrapperLogFile();
                } catch (Throwable t) {}
            }
        }
        if (f == null || !f.exists()) {
            // RouterLaunch puts the location here if no wrapper
            String path = System.getProperty("wrapper.logfile");
            if (path != null) {
                f = new File(path);
            } else {
                // look in new and old places
                f = new File(System.getProperty("java.io.tmpdir"), "wrapper.log");
                if (!f.exists())
                    f = new File(ctx.getBaseDir(), "wrapper.log");
            }
        }
        return f;
    }

    public String getServiceLogs() {
        File f = wrapperLogFile(_context);
        String str;
        if (_context.hasWrapper()) {
            // platform encoding
            str = readTextFile(f, 250);
        } else {
            // UTF-8
            str = FileUtil.readTextFile(f.getAbsolutePath(), 250, false);
        }
        if (str == null) {
            return "<p>" + _("File not found") + ": <b><code>" + f.getAbsolutePath() + "</code></b></p>";
        } else {
            str = str.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            return "<p>" + _("File location") + ": <b><code>" + f.getAbsolutePath() + "</code></b></p><pre>" + str + "</pre>";
        }
    }
    
    /*****  unused
    public String getConnectionLogs() {
        return formatMessages(_context.commSystem().getMostRecentErrorMessages());
    }
    ******/

    private final static String NL = System.getProperty("line.separator");

    /** formats in reverse order */
    private String formatMessages(List<String> msgs) {
        if (msgs.isEmpty())
            return "<p><i>" + _("No log messages") + "</i></p>";
        boolean colorize = _context.getBooleanPropertyDefaultTrue("routerconsole.logs.color");
        StringBuilder buf = new StringBuilder(16*1024); 
        buf.append("<ul>");
        for (int i = msgs.size() - 1; i >= 0; i--) { 
            String msg = msgs.get(i);
            // don't display the dup message if it is last
            if (i == 0 && msg.contains("&darr;"))
                break;
            msg = msg.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            msg = msg.replace("&amp;darr;", "&darr;");  // hack - undo the damage (LogWriter)
            // remove  last \n that LogRecordFormatter added
            if (msg.endsWith(NL))
                msg = msg.substring(0, msg.length() - NL.length());
            // replace \n so that exception stack traces will format correctly and will paste nicely into pastebin
            msg = msg.replace("\n", "<br>&nbsp;&nbsp;&nbsp;&nbsp;\n");
            buf.append("<li>");
            if (colorize) {
                // TODO this would be a lot easier if LogConsoleBuffer stored LogRecords instead of formatted strings
                String color;
                // Homeland Security Advisory System
                // http://www.dhs.gov/xinfoshare/programs/Copy_of_press_release_0046.shtm
                // but pink instead of yellow for WARN
                if (msg.contains(_("CRIT")))
                    color = "#cc0000";
                else if (msg.contains(_("ERROR")))
                    color = "#ff3300";
                else if (msg.contains(_("WARN")))
                    color = "#ff00cc";
                else if (msg.contains(_("INFO")))
                    color = "#000099";
                else
                    color = "#006600";
                buf.append("<font color=\"").append(color).append("\">");
                buf.append(msg);
                buf.append("</font>");
            } else {
                buf.append(msg);
            }
            buf.append("</li>\n");
        }
        buf.append("</ul>\n");
        
        return buf.toString();
    }

    /**
     * Read in the last few lines of a (newline delimited) textfile, or null if
     * the file doesn't exist.  
     *
     * Same as FileUtil.readTextFile but uses platform encoding,
     * not UTF-8, since the wrapper log cannot be configured:
     * http://stackoverflow.com/questions/14887690/how-do-i-get-the-tanuki-wrapper-log-files-to-be-utf-8-encoded
     *
     * Warning - this inefficiently allocates a StringBuilder of size maxNumLines*80,
     *           so don't make it too big.
     * Warning - converts \r\n to \n
     *
     * @param maxNumLines max number of lines (greater than zero)
     * @return string or null; does not throw IOException.
     * @since 0.9.11 modded from FileUtil.readTextFile()
     */
    private static String readTextFile(File f, int maxNumLines) {
        if (!f.exists()) return null;
        FileInputStream fis = null;
        BufferedReader in = null;
        try {
            fis = new FileInputStream(f);
            in = new BufferedReader(new InputStreamReader(fis));
            List<String> lines = new ArrayList<String>(maxNumLines);
            String line = null;
            while ( (line = in.readLine()) != null) {
                lines.add(line);
                if (lines.size() >= maxNumLines)
                    lines.remove(0);
            }
            StringBuilder buf = new StringBuilder(lines.size() * 80);
            for (int i = 0; i < lines.size(); i++) {
                buf.append(lines.get(i)).append('\n');
            }
            return buf.toString();
        } catch (IOException ioe) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
    }
}
