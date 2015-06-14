package net.i2p.router.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** set the theme */
public class ConfigUIHandler extends FormHandler {
    private boolean _shouldSave;
    private boolean _universalTheming;
    private boolean _forceMobileConsole;
    private String _config;
    
    @Override
    protected void processForm() {
        if (_shouldSave) {
            saveChanges();
        } else if (_action.equals(_("Delete selected"))) {
            delUser();
        } else if (_action.equals(_("Add user"))) {
            addUser();
        }
    }
    
    public void setShouldsave(String moo) { _shouldSave = true; }

    public void setUniversalTheming(String baa) { _universalTheming = true; }

    public void setForceMobileConsole(String baa) { _forceMobileConsole = true; }

    public void setTheme(String val) {
        _config = val;
    }
    
    /** note - lang change is handled in CSSHelper but we still need to save it here */
    private void saveChanges() {
        if (_config == null || _config.length() <= 0)
            return;
        if (_config.replaceAll("[a-zA-Z0-9_-]", "").length() != 0) {
            addFormError("Bad theme name");
            return;
        }
        Map<String, String> changes = new HashMap<String, String>();
        List<String> removes = new ArrayList<String>();
        String oldTheme = _context.getProperty(CSSHelper.PROP_THEME_NAME, CSSHelper.DEFAULT_THEME);
        boolean oldForceMobileConsole = _context.getBooleanProperty(CSSHelper.PROP_FORCE_MOBILE_CONSOLE);
        if (_config.equals("default")) // obsolete
            removes.add(CSSHelper.PROP_THEME_NAME);
        else
            changes.put(CSSHelper.PROP_THEME_NAME, _config);
        if (_universalTheming)
            changes.put(CSSHelper.PROP_UNIVERSAL_THEMING, "true");
        else
            removes.add(CSSHelper.PROP_UNIVERSAL_THEMING);
        if (_forceMobileConsole)
            changes.put(CSSHelper.PROP_FORCE_MOBILE_CONSOLE, "true");
        else
            removes.add(CSSHelper.PROP_FORCE_MOBILE_CONSOLE);
        boolean ok = _context.router().saveConfig(changes, removes);
        if (ok) {
            if (!oldTheme.equals(_config))
                addFormNoticeNoEscape(_("Theme change saved.") +
                              " <a href=\"configui\">" +
                              _("Refresh the page to view.") +
                              "</a>");
            if (oldForceMobileConsole != _forceMobileConsole)
                addFormNoticeNoEscape(_("Mobile console option saved.") +
                              " <a href=\"configui\">" +
                              _("Refresh the page to view.") +
                              "</a>");
        } else {
            addFormError(_("Error saving the configuration (applied but not saved) - please see the error logs."));
        }
    }

    private void addUser() {
        String name = getJettyString("name");
        if (name == null || name.length() <= 0) {
            addFormError(_("No user name entered"));
            return;
        }
        String pw = getJettyString("nofilter_pw");
        if (pw == null || pw.length() <= 0) {
            addFormError(_("No password entered"));
            return;
        }
        ConsolePasswordManager mgr = new ConsolePasswordManager(_context);
        // rfc 2617
        if (mgr.saveMD5(RouterConsoleRunner.PROP_CONSOLE_PW, RouterConsoleRunner.JETTY_REALM, name, pw)) {
            if (!_context.getBooleanProperty(RouterConsoleRunner.PROP_PW_ENABLE))
                _context.router().saveConfig(RouterConsoleRunner.PROP_PW_ENABLE, "true");
            addFormNotice(_("Added user {0}", name));
            addFormError(_("Restart required to take effect"));
        } else {
            addFormError(_("Error saving the configuration (applied but not saved) - please see the error logs."));
        }
    }

    private void delUser() {
        ConsolePasswordManager mgr = new ConsolePasswordManager(_context);
        boolean success = false;
        for (Object o : _settings.keySet()) {
            if (!(o instanceof String))
                continue;
            String k = (String) o;
            if (!k.startsWith("delete_"))
                continue;
            k = k.substring(7);
            if (mgr.remove(RouterConsoleRunner.PROP_CONSOLE_PW, k)) {
                addFormNotice(_("Removed user {0}", k));
                success = true;
            } else {
                addFormError(_("Error saving the configuration (applied but not saved) - please see the error logs."));
            }
        }
        if (success)
            addFormError(_("Restart required to take effect"));
    }
}
