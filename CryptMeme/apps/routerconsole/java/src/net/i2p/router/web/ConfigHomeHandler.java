package net.i2p.router.web;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.i2p.data.DataHelper;

/**
 *  Simple home page configuration.
 *
 *  @since 0.9
 */
public class ConfigHomeHandler extends FormHandler {

    @Override
    protected void processForm() {
        if (_action == null) return;
        String group = getJettyString("group");
        boolean deleting = _action.equals(_("Delete selected"));
        boolean adding = _action.equals(_("Add item"));
        boolean restoring = _action.equals(_("Restore defaults"));
        if (_action.equals(_("Save")) && "0".equals(group)) {
            boolean old = _context.getBooleanProperty(HomeHelper.PROP_OLDHOME);
            boolean nnew = getJettyString("oldHome") != null;
            if (old != nnew) {
                _context.router().saveConfig(HomeHelper.PROP_OLDHOME, "" + nnew);
                addFormNotice(_("Home page changed"));
            }
        } else if (adding || deleting || restoring) {
            String prop;
            String dflt;
            if ("1".equals(group)) {
                prop = HomeHelper.PROP_FAVORITES;
                dflt = HomeHelper.DEFAULT_FAVORITES;
            } else if ("2".equals(group)) {
                prop = HomeHelper.PROP_SERVICES;
                dflt = HomeHelper.DEFAULT_SERVICES;
            } else if ("3".equals(group)) {
                prop = SearchHelper.PROP_ENGINES;
                dflt = SearchHelper.ENGINES_DEFAULT;
            } else {
                addFormError("Bad group");
                return;
            }
            if (restoring) {
                //_context.router().saveConfig(prop, dflt);
                // remove config so user will see updates
                _context.router().saveConfig(prop, null);
                addFormNotice(_("Restored default settings"));
                return;
            }
            String config = _context.getProperty(prop, dflt);
            Collection<HomeHelper.App> apps;
            if ("3".equals(group))
                apps = HomeHelper.buildSearchApps(config);
            else
                apps = HomeHelper.buildApps(_context, config);
            if (adding) {
                String name = getJettyString("nofilter_name");
                if (name == null || name.length() <= 0) {
                    addFormError(_("No name entered"));
                    return;
                }
                String url = getJettyString("nofilter_url");
                if (url == null || url.length() <= 0) {
                    addFormError(_("No URL entered"));
                    return;
                }
                // these would get double-escaped so we can't do it this way...
                //name = DataHelper.escapeHTML(name).replace(",", "&#44;");
                //url = DataHelper.escapeHTML(url).replace(",", "&#44;");
                name = name.replace(",", ".");
                url = url.replace(",", "."); // fail
                HomeHelper.App app = null;
                if ("1".equals(group))
                    app = new HomeHelper.App(name, "", url, "/themes/console/images/eepsite.png");
                else if ("2".equals(group))
                    app = new HomeHelper.App(name, "", url, "/themes/console/images/title_window.png");
                else
                    app = new HomeHelper.App(name, "", url, "/themes/console/images/question.png");
                apps.add(app);
                addFormNotice(_("Added") + ": " + app.name);
            } else {
                // deleting
                Set<String> toDelete = new HashSet<String>();
                for (Object o : _settings.keySet()) {
                     if (!(o instanceof String))
                         continue;
                     String k = (String) o;
                     if (!k.startsWith("delete_"))
                         continue;
                     k = k.substring(7);
                     toDelete.add(k);
                }
                for (Iterator<HomeHelper.App> iter = apps.iterator(); iter.hasNext(); ) {
                    HomeHelper.App app = iter.next();
                    if (toDelete.contains(app.name)) {
                        iter.remove();
                        addFormNotice(_("Removed") + ": " + app.name);
                    }
                }
            }
            HomeHelper.saveApps(_context, prop, apps, !("3".equals(group)));
        } else {
            //addFormError(_("Unsupported"));
        }
    }
}
