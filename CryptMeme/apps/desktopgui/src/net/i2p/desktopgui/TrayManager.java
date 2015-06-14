package net.i2p.desktopgui;

import java.awt.AWTException;
import java.awt.Image;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.net.URL;

import net.i2p.desktopgui.i18n.DesktopguiTranslator;
import net.i2p.desktopgui.router.RouterManager;
import net.i2p.util.Log;

/**
 * Manages the tray icon life.
 */
public abstract class TrayManager {

    private static TrayManager instance = null;
    ///The tray area, or null if unsupported
    protected SystemTray tray = null;
    ///Our tray icon, or null if unsupported
    protected TrayIcon trayIcon = null;
    private final static Log log = new Log(TrayManager.class);
    
    /**
     * Instantiate tray manager.
     */
    protected TrayManager() {}
    
    protected static TrayManager getInstance() {
        if(instance == null) {
            boolean inI2P = RouterManager.inI2P();
            if(inI2P) {
                instance = new InternalTrayManager();
            }
            else {
                instance = new ExternalTrayManager();
            }
        }
        return instance;
    }

    /**
     * Add the tray icon to the system tray and start everything up.
     */
    protected void startManager() {
        if(SystemTray.isSupported()) {
            tray = SystemTray.getSystemTray();
            trayIcon = new TrayIcon(getTrayImage(), "I2P", getMainMenu());
            trayIcon.setImageAutoSize(true); //Resize image to fit the system tray
            try {
                tray.add(trayIcon);
            } catch (AWTException e) {
                log.log(Log.WARN, "Problem creating system tray icon!", e);
            }
        }
    }
    
    protected void languageChanged() {
        trayIcon.setPopupMenu(getMainMenu());
    }
    
    /**
     * Build a popup menu, adding callbacks to the different items.
     * @return popup menu
     */
    protected abstract PopupMenu getMainMenu();
    
    /**
     * Get tray icon image from the desktopgui resources in the jar file.
     * @return image used for the tray icon
     */
    private Image getTrayImage() {
        URL url = getClass().getResource("/desktopgui/resources/images/logo.png");
        Image image = Toolkit.getDefaultToolkit().getImage(url);
        return image;
    }
    
    protected static String _(String s) {
        return DesktopguiTranslator._(s);
    }
}
