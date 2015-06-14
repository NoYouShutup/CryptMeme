package net.i2p.router.tasks;

import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import net.i2p.crypto.SigType;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 *  Warn about unavailable crypto to router and wrapper logs
 *
 *  @since 0.9.15
 */
public class CryptoChecker {

    private static String JRE6 = "http://www.oracle.com/technetwork/java/javase/downloads/index.html";
    // these two are US-only and can change?
    //private static String JRE7 = "http://www.oracle.com/technetwork/java/javase/documentation/java-se-7-doc-download-435117.html";
    //private static String JRE8 = "http://www.oracle.com/technetwork/java/javase/documentation/jdk8-doc-downloads-2133158.html";

    /**
     *  @param ctx if null, logs only to System.out (called from main)
     */
    public static void warnUnavailableCrypto(RouterContext ctx) {
        if (SystemVersion.isAndroid())
            return;
        boolean unavail = false;
        Log log = null;
        for (SigType t : SigType.values()) {
            if (!t.isAvailable()) {
                if (!unavail) {
                    unavail = true;
                    if (ctx != null)
                        log = ctx.logManager().getLog(CryptoChecker.class);
                }
                String s = "Crypto " + t + " is not available";
                if (log != null)
                    log.logAlways(Log.WARN, s);
                System.out.println("Warning: " + s);
            }
        }
        if (unavail) {
            String s = "Java version: " + System.getProperty("java.version") +
                       " OS: " + System.getProperty("os.name") + ' ' +
                       System.getProperty("os.arch") + ' ' +
                       System.getProperty("os.version");
            if (log != null)
                log.logAlways(Log.WARN, s);
            System.out.println("Warning: " + s);
            if (!SystemVersion.isJava7()) {
                s = "Please consider upgrading to Java 7";
                if (log != null)
                    log.logAlways(Log.WARN, s);
                System.out.println(s);
            }
            if (!isUnlimited()) {
                s = "Please consider installing the Java Cryptography Unlimited Strength Jurisdiction Policy Files from ";
                //if (SystemVersion.isJava8())
                //    s  += JRE8;
                //else if (SystemVersion.isJava7())
                //    s  += JRE7;
                //else
                    s  += JRE6;
                if (log != null)
                    log.logAlways(Log.WARN, s);
                System.out.println(s);
            }
            s = "This crypto will be required in a future release";
            if (log != null)
                log.logAlways(Log.WARN, s);
            System.out.println("Warning: " + s);
        } else if (ctx == null) {
            // called from main()
            System.out.println("All crypto available");
        }
    }

    /**
     *  Copied from CryptixAESEngine
     */
    private static boolean isUnlimited() {
        try {
            if (Cipher.getMaxAllowedKeyLength("AES") < 256)
                return false;
        } catch (NoSuchAlgorithmException e) {
            return false;
        } catch (NoSuchMethodError e) {
            // JamVM, gij
            try {
                Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
                SecretKeySpec key = new SecretKeySpec(new byte[32], "AES");
                cipher.init(Cipher.ENCRYPT_MODE, key);
            } catch (GeneralSecurityException gse) {
                return false;
            }
        }
        return true;
    }

    public static void main(String[] args) {
        warnUnavailableCrypto(null);
    }
}

