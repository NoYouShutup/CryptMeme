package net.i2p.router.web;

import java.util.Map;
import java.util.TreeMap;

import net.i2p.data.DataHelper;

public class ConfigAdvancedHelper extends HelperBase {
    private final static String CHECKED = " checked=\"checked\" ";
    static final String PROP_FLOODFILL_PARTICIPANT = "router.floodfillParticipant";

    public String getSettings() {
        StringBuilder buf = new StringBuilder(4*1024);
        TreeMap<String, String> sorted = new TreeMap<String, String>();
        sorted.putAll(_context.router().getConfigMap());
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            String name = DataHelper.escapeHTML(e.getKey());
            String val = DataHelper.escapeHTML(e.getValue());
            buf.append(name).append('=').append(val).append('\n');
        }
        return buf.toString();
    }

    /** @since 0.9.14.1 */
    public String getConfigFileName() {
        return _context.router().getConfigFilename();
    }

    /** @since 0.9.20 */
    public String getFFChecked(int mode) {
        String ff = _context.getProperty(PROP_FLOODFILL_PARTICIPANT, "auto");
        if ((mode == 0 && ff.equals("false")) ||
            (mode == 1 && ff.equals("true")) ||
            (mode == 2 && ff.equals("auto")))
            return CHECKED;
        return "";
    }
}
