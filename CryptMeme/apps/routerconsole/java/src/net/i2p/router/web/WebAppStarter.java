package net.i2p.router.web;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.router.RouterContext;
import net.i2p.util.FileUtil;
import net.i2p.util.SecureDirectory;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.webapp.WebAppContext;


/**
 *  Add, start or stop a webapp.
 *  Add to the webapp classpath if specified in webapps.config.
 *
 *  Sadly, setting Class-Path in MANIFEST.MF doesn't work for jetty wars.
 *  See WebAppConfiguration for more information.
 *  but let's just do it in webapps.config.
 *
 *  No, wac.addClassPath() does not work. For more info see:
 *
 *  http://servlets.com/archive/servlet/ReadMsg?msgId=511113&listName=jetty-support
 *
 *  @since 0.7.12
 *  @author zzz
 */
public class WebAppStarter {

    private static final Map<String, Long> warModTimes = new ConcurrentHashMap<String, Long>();
    static final Map<String, String> INIT_PARAMS = new HashMap<String, String>(4);
    //static private Log _log;

    static {
        //_log = ContextHelper.getContext(null).logManager().getLog(WebAppStarter.class); ;
        // see DefaultServlet javadocs
        String pfx = "org.eclipse.jetty.servlet.Default.";
        INIT_PARAMS.put(pfx + "cacheControl", "max-age=86400");
        INIT_PARAMS.put(pfx + "dirAllowed", "false");
    }


    /**
     *  adds and starts
     *  @throws just about anything, caller would be wise to catch Throwable
     */
    static void startWebApp(RouterContext ctx, ContextHandlerCollection server,
                            String appName, String warPath) throws Exception {
         File tmpdir = new SecureDirectory(ctx.getTempDir(), "jetty-work-" + appName + ctx.random().nextInt());
         WebAppContext wac = addWebApp(ctx, server, appName, warPath, tmpdir);      
         //_log.debug("Loading war from: " + warPath);
         LocaleWebAppHandler.setInitParams(wac, INIT_PARAMS);
         wac.start();
    }

    /**
     *  add but don't start
     *  This is used only by RouterConsoleRunner, which adds all the webapps first
     *  and then starts all at once.
     */
    static WebAppContext addWebApp(RouterContext ctx, ContextHandlerCollection server,
                                   String appName, String warPath, File tmpdir) throws IOException {

        // Jetty will happily load one context on top of another without stopping
        // the first one, so we remove any previous one here
        try {
            stopWebApp(appName);
        } catch (Throwable t) {}

        // To avoid ZipErrors from JarURLConnetion caching,
        // (used by Jetty JarResource and JarFileResource)
        // copy the war to a new directory if it is newer than the one we loaded originally.
        // Yes, URLConnection has a setDefaultUseCaches() method, but it's hard to get to
        // because it's non-static and the class is abstract, and we don't really want to
        // set the default to false for everything.
        long newmod = (new File(warPath)).lastModified();
        if (newmod <= 0)
            throw new IOException("Web app " + warPath + " does not exist");
        Long oldmod = warModTimes.get(warPath);
        if (oldmod == null) {
            warModTimes.put(warPath, Long.valueOf(newmod));
        } else if (oldmod.longValue() < newmod) {
            // copy war to temporary directory
            File warTmpDir = new SecureDirectory(ctx.getTempDir(), "war-copy-" + appName + ctx.random().nextInt());
            warTmpDir.mkdir();
            String tmpPath = (new File(warTmpDir, appName + ".war")).getAbsolutePath();
            if (!FileUtil.copy(warPath, tmpPath, true))
                throw new IOException("Web app failed copy from " + warPath + " to " + tmpPath);
            warPath = tmpPath;
        }

        WebAppContext wac = new WebAppContext(warPath, "/"+ appName);
        tmpdir.mkdir();
        wac.setTempDirectory(tmpdir);
        // all the JSPs are precompiled, no need to extract
        wac.setExtractWAR(false);

        // this does the passwords...
        RouterConsoleRunner.initialize(ctx, wac);

        // see WebAppConfiguration for info
        String[] classNames = wac.getConfigurationClasses();
        String[] newClassNames = new String[classNames.length + 1];
        for (int j = 0; j < classNames.length; j++)
             newClassNames[j] = classNames[j];
        newClassNames[classNames.length] = WebAppConfiguration.class.getName();
        wac.setConfigurationClasses(newClassNames);
        server.addHandler(wac);
        server.mapContexts();
        return wac;
    }

    /**
     *  stop it and remove the context
     *  @throws just about anything, caller would be wise to catch Throwable
     */
    static void stopWebApp(String appName) {
        ContextHandler wac = getWebApp(appName);
        if (wac == null)
            return;
        try {
            // not graceful is default in Jetty 6?
            wac.stop();
        } catch (Exception ie) {}
        ContextHandlerCollection server = getConsoleServer();
        if (server == null)
            return;
        try {
            server.removeHandler(wac);
            server.mapContexts();
        } catch (IllegalStateException ise) {}
    }

    static boolean isWebAppRunning(String appName) {
        ContextHandler wac = getWebApp(appName);
        if (wac == null)
            return false;
        return wac.isStarted();
    }
    
    /** @since Jetty 6 */
    static ContextHandler getWebApp(String appName) {
        ContextHandlerCollection server = getConsoleServer();
        if (server == null)
            return null;
        Handler handlers[] = server.getHandlers();
        if (handlers == null)
            return null;
        String path = '/'+ appName;
        for (int i = 0; i < handlers.length; i++) {
            if (!(handlers[i] instanceof ContextHandler))
                continue;
            ContextHandler ch = (ContextHandler) handlers[i];
            if (path.equals(ch.getContextPath()))
                return ch;
        }
        return null;
    }

    /** see comments in ConfigClientsHandler */
    static ContextHandlerCollection getConsoleServer() {
        Server s = RouterConsoleRunner.getConsoleServer();
        if (s == null)
            return null;
        Handler h = s.getChildHandlerByClass(ContextHandlerCollection.class);
        if (h == null)
            return null;
        return (ContextHandlerCollection) h;
    }
}
