package net.i2p.util;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import freenet.support.CPUInformation.AMDCPUInfo;
import freenet.support.CPUInformation.CPUID;
import freenet.support.CPUInformation.CPUInfo;
import freenet.support.CPUInformation.IntelCPUInfo;
import freenet.support.CPUInformation.VIACPUInfo;
import freenet.support.CPUInformation.UnknownCPUException;

import net.i2p.I2PAppContext;
import net.i2p.crypto.CryptoConstants;

/**
 * <p>BigInteger that takes advantage of the jbigi library for the modPow operation,
 * which accounts for a massive segment of the processing cost of asymmetric 
 * crypto.
 * 
 * The jbigi library itself is basically just a JNI wrapper around the 
 * GMP library - a collection of insanely efficient routines for dealing with 
 * big numbers.</p>
 *
 * There are three environmental properties for configuring this component: <ul>
 * <li><b>jbigi.enable</b>: whether to use the native library (defaults to "true")</li>
 * <li><b>jbigi.impl</b>: select which resource to use as the native implementation</li>
 * <li><b>jbigi.ref</b>: the file specified in this parameter may contain a resource
 *                       name to override jbigi.impl (defaults to "jbigi.cfg")</li>
 * </ul>
 *
 * <p>If jbigi.enable is set to false, this class won't even attempt to use the 
 * native library, but if it is set to true (or is not specified), it will first 
 * check the platform specific library path for the "jbigi" library, as defined by 
 * {@link Runtime#loadLibrary} - e.g. C:\windows\jbigi.dll or /lib/libjbigi.so, as
 * well as the CLASSPATH for a resource named 'jbigi'.  If that fails, it reviews 
 * the jbigi.impl environment property - if that is set, it checks all of the 
 * components in the CLASSPATH for the file specified and attempts to load it as 
 * the native library.  If jbigi.impl is not set, it uses the jcpuid library 
 * described below.  If there is still no matching resource, or if that resource 
 * is not a valid OS/architecture specific library, the NativeBigInteger will 
 * revert to using the pure java implementation.</p>
 * 
 * <p>When attempting to load the native implementation as a resource from the CLASSPATH,
 * the NativeBigInteger will make use of the jcpuid component which runs some assembly 
 * code to determine the current CPU implementation, such as "pentium4" or "k623".
 * We then use that, combined with the OS, to build an optimized resource name - e.g. 
 * "net/i2p/util/libjbigi-freebsd-pentium4.so" or "net/i2p/util/jbigi-windows-k623.dll".
 * If that resource exists, we use it.  If it doesn't (or the jcpuid component fails), 
 * we try a generic native implementation using "none" for the CPU (ala 
 * "net/i2p/util/jbigi-windows-none.dll").</p>
 *
 * <p>Running this class by itself does a basic unit test and benchmarks the
 * NativeBigInteger.modPow vs. the BigInteger.modPow by running a 2Kbit op 100
 * times.  At the end of each test, if the native implementation is loaded this will output 
 * something like:</p>
 * <pre>
 *  native run time:        6090ms (60ms each)
 *  java run time:          68067ms (673ms each)
 *  native = 8.947066860593239% of pure java time
 * </pre>
 * 
 * <p>If the native implementation is not loaded, it will start by saying:</p>
 * <pre>
 *  WARN: Native BigInteger library jbigi not loaded - using pure java
 * </pre>
 * <p>Then go on to run the test, finally outputting:</p>
 * <pre>
 *  java run time:  64653ms (640ms each)
 *  However, we couldn't load the native library, so this doesn't test much
 * </pre>
 *
 */
public class NativeBigInteger extends BigInteger {
    /** did we load the native lib correctly? */
    private static boolean _nativeOk = false;
    private static String _loadStatus = "uninitialized";
    private static String _cpuModel = "uninitialized";
    /** 
     * do we want to dump some basic success/failure info to stderr during 
     * initialization?  this would otherwise use the Log component, but this makes
     * it easier for other systems to reuse this class
     *
     * Well, we really want to use Log so if you are one of those "other systems"
     * then comment out the I2PAppContext usage below.
     *
     * Set to false if not in router context, so scripts using TrustedUpdate
     * don't spew log messages. main() below overrides to true.
     */
    private static boolean _doLog = System.getProperty("jbigi.dontLog") == null &&
                                    I2PAppContext.getCurrentContext() != null &&
                                    I2PAppContext.getCurrentContext().isRouterContext();
    
    /**
     *  The following libraries are be available in jbigi.jar in all I2P versions
     *  originally installed as release 0.6.1.10 or later (released 2006-01-16),
     *  for linux, freebsd, and windows, EXCEPT:
     *   - k63 was removed for linux and freebsd in 0.8.7 (identical to k62)
     *   - athlon64 not available for freebsd
     *   - viac3 not available for windows
     */
    private final static String JBIGI_OPTIMIZATION_K6         = "k6";
    private final static String JBIGI_OPTIMIZATION_K6_2       = "k62";
    private final static String JBIGI_OPTIMIZATION_K6_3       = "k63";
    private final static String JBIGI_OPTIMIZATION_ATHLON     = "athlon";
    private final static String JBIGI_OPTIMIZATION_ATHLON64   = "athlon64";
    private final static String JBIGI_OPTIMIZATION_PENTIUM    = "pentium";
    private final static String JBIGI_OPTIMIZATION_PENTIUMMMX = "pentiummmx";
    private final static String JBIGI_OPTIMIZATION_PENTIUM2   = "pentium2";
    private final static String JBIGI_OPTIMIZATION_PENTIUM3   = "pentium3";
    private final static String JBIGI_OPTIMIZATION_PENTIUM4   = "pentium4";
    private final static String JBIGI_OPTIMIZATION_VIAC3      = "viac3";
    /**
     * The 7 optimizations below here are since 0.8.7. Each of the 32-bit processors below
     * needs an explicit fallback in getResourceList() or getMiddleName2().
     * 64-bit processors will fallback to athlon64 and athlon in getResourceList().
     * @since 0.8.7
     */
    private final static String JBIGI_OPTIMIZATION_ATOM       = "atom";
    private final static String JBIGI_OPTIMIZATION_CORE2      = "core2";
    private final static String JBIGI_OPTIMIZATION_COREI      = "corei";
    private final static String JBIGI_OPTIMIZATION_GEODE      = "geode";
    private final static String JBIGI_OPTIMIZATION_NANO       = "nano";
    private final static String JBIGI_OPTIMIZATION_PENTIUMM   = "pentiumm";
    /** all libjbibi builds are identical to pentium3, case handled in getMiddleName2() */
    private final static String JBIGI_OPTIMIZATION_VIAC32     = "viac32";

    /**
     * Non-x86, no fallbacks to older libs or to "none"
     * @since 0.8.7
     */
    private final static String JBIGI_OPTIMIZATION_ARM        = "arm";
    private final static String JBIGI_OPTIMIZATION_PPC        = "ppc";

    /**
     * Operating systems
     */
    private static final boolean _isWin = SystemVersion.isWindows();
    private static final boolean _isOS2 = System.getProperty("os.name").startsWith("OS/2");
    private static final boolean _isMac = SystemVersion.isMac();
    private static final boolean _isLinux = System.getProperty("os.name").toLowerCase(Locale.US).contains("linux");
    private static final boolean _isKFreebsd = System.getProperty("os.name").toLowerCase(Locale.US).contains("kfreebsd");
    private static final boolean _isFreebsd = (!_isKFreebsd) && System.getProperty("os.name").toLowerCase(Locale.US).contains("freebsd");
    private static final boolean _isNetbsd = System.getProperty("os.name").toLowerCase(Locale.US).contains("netbsd");
    private static final boolean _isOpenbsd = System.getProperty("os.name").toLowerCase(Locale.US).contains("openbsd");
    private static final boolean _isSunos = System.getProperty("os.name").toLowerCase(Locale.US).contains("sunos");
    private static final boolean _isAndroid = SystemVersion.isAndroid();

    /*
     * This isn't always correct.
     * http://stackoverflow.com/questions/807263/how-do-i-detect-which-kind-of-jre-is-installed-32bit-vs-64bit
     * http://mark.koli.ch/2009/10/javas-osarch-system-property-is-the-bitness-of-the-jre-not-the-operating-system.html
     * http://mark.koli.ch/2009/10/reliably-checking-os-bitness-32-or-64-bit-on-windows-with-a-tiny-c-app.html
     * sun.arch.data.model not on all JVMs
     * sun.arch.data.model == 64 => 64 bit processor
     * sun.arch.data.model == 32 => A 32 bit JVM but could be either 32 or 64 bit processor or libs
     * os.arch contains "64" could be 32 or 64 bit libs
     */
    private static final boolean _is64 = SystemVersion.is64Bit();

    private static final boolean _isX86 = System.getProperty("os.arch").contains("86") ||
	                                 System.getProperty("os.arch").equals("amd64");

    private static final boolean _isArm = SystemVersion.isARM();

    private static final boolean _isPPC = System.getProperty("os.arch").contains("ppc");

    /* libjbigi.so vs jbigi.dll */
    private static final String _libPrefix = (_isWin || _isOS2 ? "" : "lib");
    private static final String _libSuffix = (_isWin || _isOS2 ? ".dll" : _isMac ? ".jnilib" : ".so");

    private final static String sCPUType; //The CPU Type to optimize for (one of the above strings)
    
    static {
        if (_isX86) // Don't try to resolve CPU type on non x86 hardware
            sCPUType = resolveCPUType();
        else if (_isArm)
            sCPUType = JBIGI_OPTIMIZATION_ARM;
        else if (_isPPC && !_isMac)
	    sCPUType = JBIGI_OPTIMIZATION_PPC;
	else
	    sCPUType = null;
        loadNative();
    }
    
    /**
      * Tries to resolve the best type of CPU that we have an optimized jbigi-dll/so for.
      * This is for x86 only.
      * @return A string containing the CPU-type or null if CPU type is unknown
      */
    private static String resolveCPUType() {
        try {
            CPUInfo c = CPUID.getInfo();
            try {
                _cpuModel = c.getCPUModelString();
            } catch (UnknownCPUException e) {}
            if (c instanceof VIACPUInfo){
            	VIACPUInfo viacpu = (VIACPUInfo) c;
            	if (viacpu.IsNanoCompatible())
            	    return JBIGI_OPTIMIZATION_NANO;
            	return JBIGI_OPTIMIZATION_VIAC3;
            } else if(c instanceof AMDCPUInfo) {
                AMDCPUInfo amdcpu = (AMDCPUInfo) c;
                // Supported in CPUID, no GMP support
                //if (amdcpu.IsBobcatCompatible())
                //    return JBIGI_OPTIMIZATION_BOBCAT;
                if (amdcpu.IsAthlon64Compatible())
                    return JBIGI_OPTIMIZATION_ATHLON64;
                if (amdcpu.IsAthlonCompatible())
                    return JBIGI_OPTIMIZATION_ATHLON;
                // FIXME lots of geodes, but GMP configures like a K6-3
                if (amdcpu.IsGeodeCompatible())
                    return JBIGI_OPTIMIZATION_GEODE;
                if (amdcpu.IsK6_3_Compatible())
                    return JBIGI_OPTIMIZATION_K6_3;
                if (amdcpu.IsK6_2_Compatible())
                    return JBIGI_OPTIMIZATION_K6_2;
                if (amdcpu.IsK6Compatible())
                    return JBIGI_OPTIMIZATION_K6;
            } else if (c instanceof IntelCPUInfo) {
                IntelCPUInfo intelcpu = (IntelCPUInfo) c;
                if (intelcpu.IsCoreiCompatible())
                    return JBIGI_OPTIMIZATION_COREI;
                if (intelcpu.IsCore2Compatible())
                    return JBIGI_OPTIMIZATION_CORE2;
                if (intelcpu.IsPentium4Compatible())
                    return JBIGI_OPTIMIZATION_PENTIUM4;
                if (intelcpu.IsAtomCompatible())
                    return JBIGI_OPTIMIZATION_ATOM;
                if (intelcpu.IsPentiumMCompatible())
                    return JBIGI_OPTIMIZATION_PENTIUMM;
                if (intelcpu.IsPentium3Compatible())
                    return JBIGI_OPTIMIZATION_PENTIUM3;
                if (intelcpu.IsPentium2Compatible())
                    return JBIGI_OPTIMIZATION_PENTIUM2;
                if (intelcpu.IsPentiumMMXCompatible())
                    return JBIGI_OPTIMIZATION_PENTIUMMMX;
                if (intelcpu.IsPentiumCompatible())
                    return JBIGI_OPTIMIZATION_PENTIUM;
            }
            return null;
        } catch (UnknownCPUException e) {
            return null; //TODO: Log something here maybe..
        }
    }

    /**
     * calculate (base ^ exponent) % modulus.
     * 
     * @param base
     *            big endian twos complement representation of the base (but it must be positive)
     * @param exponent
     *            big endian twos complement representation of the exponent
     * @param modulus
     *            big endian twos complement representation of the modulus
     * @return big endian twos complement representation of (base ^ exponent) % modulus
     */
    public native static byte[] nativeModPow(byte base[], byte exponent[], byte modulus[]);
 
    private byte[] cachedBa;

    public NativeBigInteger(byte[] val) {
        super(val);
    }

    public NativeBigInteger(int signum, byte[] magnitude) {
        super(signum, magnitude);
    }

    public NativeBigInteger(int bitlen, int certainty, Random rnd) {
        super(bitlen, certainty, rnd);
    }

    public NativeBigInteger(int numbits, Random rnd) {
        super(numbits, rnd);
    }

    public NativeBigInteger(String val) {
        super(val);
    }

    public NativeBigInteger(String val, int radix) {
        super(val, radix);
    }

    /**Creates a new NativeBigInteger with the same value
    *  as the supplied BigInteger. Warning!, not very efficent
    */
    public NativeBigInteger(BigInteger integer) {
        //Now, why doesn't sun provide a constructor
        //like this one in BigInteger?
        this(integer.toByteArray());
    }

    @Override
    public BigInteger modPow(BigInteger exponent, BigInteger m) {
        // jbigi.c convert_j2mp() and convert_mp2j() do NOT currently support negative numbers
        // Where negative or zero values aren't legal in modPow() anyway, avoid native,
        // as the Java code will throw an exception rather than silently fail
        if (_nativeOk && signum() >= 0 && exponent.signum() >= 0 && m.signum() > 0)
            return new NativeBigInteger(nativeModPow(toByteArray(), exponent.toByteArray(), m.toByteArray()));
        else
            return super.modPow(exponent, m);
    }

    @Override
    public byte[] toByteArray(){
        if(cachedBa == null) //Since we are immutable it is safe to never update the cached ba after it has initially been generated
            cachedBa = super.toByteArray();
        return cachedBa;
    }
    
    /**
     * 
     * @return True iff native methods will be used by this class
     */
    public static boolean isNative(){
        return _nativeOk;
    }
 
    public static String loadStatus() {
        return _loadStatus;
    }
 
    public static String cpuType() {
        if (sCPUType != null)
            return sCPUType;
        return "unrecognized";
    }
 
    public static String cpuModel() {
        return _cpuModel;
    }
 
    /**
     * <p>Compare the BigInteger.modPow vs the NativeBigInteger.modPow of some 
     * really big (2Kbit) numbers 100 different times and benchmark the 
     * performance.</p>
     *
     */
    public static void main(String args[]) {
        _doLog = true;
        runModPowTest(100);
    }

    private static void runModPowTest(int numRuns) {
        System.out.println("DEBUG: Warming up the random number generator...");
        SecureRandom rand = new SecureRandom();
        rand.nextBoolean();
        System.out.println("DEBUG: Random number generator warmed up");

        /* the sample numbers are elG generator/prime so we can test with reasonable numbers */
        byte[] _sampleGenerator = CryptoConstants.elgg.toByteArray();
        byte[] _samplePrime = CryptoConstants.elgp.toByteArray();

        BigInteger jg = new BigInteger(_sampleGenerator);
        BigInteger jp = new BigInteger(_samplePrime);

        long totalTime = 0;
        long javaTime = 0;

        int runsProcessed = 0;
        for (runsProcessed = 0; runsProcessed < numRuns; runsProcessed++) {
            BigInteger bi = new BigInteger(226, rand); // 2048, rand); //
            NativeBigInteger g = new NativeBigInteger(_sampleGenerator);
            NativeBigInteger p = new NativeBigInteger(_samplePrime);
            NativeBigInteger k = new NativeBigInteger(1, bi.toByteArray());
            long beforeModPow = System.currentTimeMillis();
            BigInteger myValue = g.modPow(k, p);
            long afterModPow = System.currentTimeMillis();
            BigInteger jval = jg.modPow(bi, jp);
            long afterJavaModPow = System.currentTimeMillis();

            totalTime += (afterModPow - beforeModPow);
            javaTime += (afterJavaModPow - afterModPow);
            if (!myValue.equals(jval)) {
                System.err.println("ERROR: [" + runsProcessed + "]\tnative modPow != java modPow");
                System.err.println("ERROR: native modPow value: " + myValue.toString());
                System.err.println("ERROR: java modPow value: " + jval.toString());
                System.err.println("ERROR: run time: " + totalTime + "ms (" + (totalTime / (runsProcessed + 1)) + "ms each)");
                break;
            } else {
                System.out.println("DEBUG: current run time: " + (afterModPow - beforeModPow) + "ms (total: " 
                                   + totalTime + "ms, " + (totalTime / (runsProcessed + 1)) + "ms each)");
            }
        }
        System.out.println("INFO: run time: " + totalTime + "ms (" + (totalTime / (runsProcessed + 1)) + "ms each)");
        if (numRuns == runsProcessed)
            System.out.println("INFO: " + runsProcessed + " runs complete without any errors");
        else
            System.out.println("ERROR: " + runsProcessed + " runs until we got an error");

        if (_nativeOk) {
            System.out.println("native run time: \t" + totalTime + "ms (" + (totalTime / (runsProcessed + 1))
                               + "ms each)");
            System.out.println("java run time:   \t" + javaTime + "ms (" + (javaTime / (runsProcessed + 1)) + "ms each)");
            System.out.println("native = " + ((totalTime * 100.0d) / javaTime) + "% of pure java time");
        } else {
            System.out.println("java run time: \t" + javaTime + "ms (" + (javaTime / (runsProcessed + 1)) + "ms each)");
            System.out.println("However, we couldn't load the native library, so this doesn't test much");
        }
    }
    
    /**
     * <p>Do whatever we can to load up the native library backing this BigInteger's native methods.
     * If it can find a custom built jbigi.dll / libjbigi.so, it'll use that.  Otherwise
     * it'll try to look in the classpath for the correct library (see loadFromResource).
     * If the user specifies -Djbigi.enable=false it'll skip all of this.</p>
     *
     * <pre>
     * Load order (using linux naming with cpu type "xxx")
     * Old order 0.8.6 and earlier:
     *   - filesystem libjbigi.so
     *   - jbigi.jar libjbigi.so
     *   - jbigi.jar libjbigi-linux-xxx.so
     *   - filesystem libjbigi-linux-xxx.so
     *   - jbigi.jar libjbigi-linux-none.so
     *   - filesystem libjbigi-linux-none.so
     *
     * New order as of 0.8.7:
     *   - filesystem libjbigi.so
     *   - jbigi.jar libjbigi-linux-xxx_64.so if it may be 64 bit
     *   - jbigi.jar libjbigi-linux-athlon64_64.so if it may be 64 bit
     *   - jbigi.jar libjbigi-linux-xxx.so
     *   - jbigi.jar libjbigi-linux-athlon64.so if it may be 64 bit
     *   - jbigi.jar libjbigi-linux-yyy.so 0 or more other alternates
     *   - jbigi.jar libjbigi-linux-none_64.so if it may be 64 bit
     *   - jbigi.jar libjbigi-linux-none.so
     * </pre>
     */
    private static final void loadNative() {
        try{
            String wantedProp = System.getProperty("jbigi.enable", "true");
            boolean wantNative = Boolean.parseBoolean(wantedProp);
            if (wantNative) {
                debug("trying loadGeneric");
                boolean loaded = loadGeneric("jbigi");
                if (loaded) {
                    _nativeOk = true;
                    info("Locally optimized native BigInteger library loaded from file");
                } else {
                    List<String> toTry = getResourceList();
                    debug("loadResource list to try is: " + toTry);
                    for (String s : toTry) {
                        debug("trying loadResource " + s);
                        if (loadFromResource(s)) {
                            _nativeOk = true;
                            info("Native BigInteger library " + s + " loaded from resource");
                            break;
                        }
                    }
                }
            }
            if (!_nativeOk) {
                warn("Native BigInteger library jbigi not loaded - using pure Java - " +
                     "poor performance may result - see http://www.i2p2.i2p/jbigi for help");
            }
        } catch(Exception e) {
            warn("Native BigInteger library jbigi not loaded, using pure java", e);
        }
    }
    
    /** @since 0.8.7 */
    private static void debug(String s) {
        I2PAppContext.getGlobalContext().logManager().getLog(NativeBigInteger.class).debug(s);
    }

    
    private static void info(String s) {
        if(_doLog)
            System.err.println("INFO: " + s);
        I2PAppContext.getGlobalContext().logManager().getLog(NativeBigInteger.class).info(s);
        _loadStatus = s;
    }

    private static void warn(String s) {
        warn(s, null);
    }

    /** @since 0.8.7 */
    private static void warn(String s, Throwable t) {
        if(_doLog) {
            System.err.println("WARNING: " + s);
            if (t != null)
                t.printStackTrace();
        }
        I2PAppContext.getGlobalContext().logManager().getLog(NativeBigInteger.class).warn(s, t);
        if (t != null)
            _loadStatus = s + ' ' + t;
        else
            _loadStatus = s;
    }

    /** 
     * <p>Try loading it from an explictly build jbigi.dll / libjbigi.so first, before 
     * looking into a jbigi.jar for any other libraries.</p>
     *
     * @return true if it was loaded successfully, else false
     *
     */
/****
    private static final boolean loadGeneric(boolean optimized) {
        return loadGeneric(getMiddleName(optimized));
    }
****/

    private static final boolean loadGeneric(String name) {
        try {
            if(name == null)
                return false;
            System.loadLibrary(name);
            return true;
        } catch (UnsatisfiedLinkError ule) {
            if (_isAndroid) {
                // Unfortunately,
                // this is not interesting on Android, it says "file not found"
                // on link errors too.
                warn("jbigi loadLibrary() fail", ule);
            }
            return false;
        }
    }
    
    /**
     * <p>Check all of the jars in the classpath for the file specified by the 
     * environmental property "jbigi.impl" and load it as the native library 
     * implementation.  For instance, a windows user on a p4 would define
     * -Djbigi.impl=win-686 if there is a jbigi.jar in the classpath containing the 
     * files "win-686", "win-athlon", "freebsd-p4", "linux-p3", where each 
     * of those files contain the correct binary file for a native library (e.g.
     * windows DLL, or a *nix .so).  </p>
     * 
     * <p>This is a pretty ugly hack, using the general technique illustrated by the
     * onion FEC libraries.  It works by pulling the resource, writing out the 
     * byte stream to a temporary file, loading the native library from that file.
     * We then attempt to copy the file from the temporary dir to the base install dir,
     * so we don't have to do this next time - but we don't complain if it fails,
     * so we transparently support read-only base dirs.
     * </p>
     *
     * @return true if it was loaded successfully, else false
     *
     */
/****
    private static final boolean loadFromResource(boolean optimized) {
        String resourceName = getResourceName(optimized);
        return loadFromResource(resourceName);
    }
****/

    private static final boolean loadFromResource(String resourceName) {
        if (resourceName == null) return false;
        //URL resource = NativeBigInteger.class.getClassLoader().getResource(resourceName);
        URL resource = ClassLoader.getSystemResource(resourceName);
        if (resource == null) {
            info("Resource name [" + resourceName + "] was not found");
            return false;
        }

        File outFile = null;
        FileOutputStream fos = null;
        String filename =  _libPrefix + "jbigi" + _libSuffix;
        try {
            InputStream libStream = resource.openStream();
            outFile = new File(I2PAppContext.getGlobalContext().getTempDir(), filename);
            fos = new FileOutputStream(outFile);
            byte buf[] = new byte[4096];
            while (true) {
                int read = libStream.read(buf);
                if (read < 0) break;
                fos.write(buf, 0, read);
            }
            fos.close();
            fos = null;
            System.load(outFile.getAbsolutePath()); //System.load requires an absolute path to the lib
        } catch (UnsatisfiedLinkError ule) {
            // don't include the exception in the message - too much
            warn("Failed to load the resource " + resourceName + " - not a valid library for this platform");
            if (outFile != null)
                outFile.delete();
            return false;
        } catch (IOException ioe) {
            warn("Problem writing out the temporary native library data", ioe);
            if (outFile != null)
                outFile.delete();
            return false;
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (IOException ioe) {}
            }
        }
        // copy to install dir, ignore failure
        File newFile = new File(I2PAppContext.getGlobalContext().getBaseDir(), filename);
        FileUtil.copy(outFile, newFile, false, true);
        return true;
    }
    
    /**
     *  Generate a list of resources to search for, in-order.
     *  See loadNative() comments for more info.
     *  @return non-null
     *  @since 0.8.7
     */
    private static List<String> getResourceList() {
        if (_isAndroid)
            return Collections.emptyList();
        List<String> rv = new ArrayList<String>(8);
        String primary = getMiddleName2(true);
        if (primary != null) {
            if (_is64) {
                // add 64 bit variants at the front
                if (!primary.equals(JBIGI_OPTIMIZATION_ATHLON64))
                    rv.add(_libPrefix + getMiddleName1() + primary + "_64" + _libSuffix);
                // athlon64_64 is always a fallback for 64 bit
                rv.add(_libPrefix + getMiddleName1() + JBIGI_OPTIMIZATION_ATHLON64 + "_64" + _libSuffix);
            }

            if (_isArm) {
                Map<String, String> cpuinfo = getCPUInfo();
                int ver = 0;
                String proc = cpuinfo.get("processor");
                String arch = cpuinfo.get("cpu architecture");
                if (proc != null && proc.contains("ARMv6")) {
                    // Raspberry Pi workaround
                    // Processor       : ARMv6-compatible processor rev 7 (v6l)
                    // CPU architecture: 7
                    ver = 6;
                } else if (arch != null && arch.length() > 0) {
                    //CPU architecture: 5TEJ
                    //CPU architecture: 7
                    String sver = arch.substring(0, 1);
                    try {
                        ver = Integer.parseInt(sver);
                    } catch (NumberFormatException nfe) {}
                }
                // add libjbigi-linux-armv7.so, libjbigi-linux-armv6.so, ...
                for (int i = ver; i >= 3; i--) {
                    rv.add(_libPrefix + getMiddleName1() + primary + 'v' + i + _libSuffix);
                }
            }

            // the preferred selection
            rv.add(_libPrefix + getMiddleName1() + primary + _libSuffix);

            // athlon64 is always a fallback for 64 bit
            if (_is64 && !primary.equals(JBIGI_OPTIMIZATION_ATHLON64))
                rv.add(_libPrefix + getMiddleName1() + JBIGI_OPTIMIZATION_ATHLON64 + _libSuffix);

            // Add fallbacks for any 32-bit that were added 0.8.7 or later here
            // FIXME lots of geodes, but GMP configures like a K6-3, so pentium3 is probably a good backup
            if (primary.equals(JBIGI_OPTIMIZATION_ATOM) ||
                primary.equals(JBIGI_OPTIMIZATION_PENTIUMM) ||
                primary.equals(JBIGI_OPTIMIZATION_GEODE))
                rv.add(_libPrefix + getMiddleName1() + JBIGI_OPTIMIZATION_PENTIUM3 + _libSuffix);

            // athlon is always a fallback for 64 bit, we have it for all architectures
            // and it should be much better than "none"
            if (_is64)
                rv.add(_libPrefix + getMiddleName1() + JBIGI_OPTIMIZATION_ATHLON + _libSuffix);

        } else {
            if (_is64) {
                rv.add(_libPrefix + getMiddleName1() + JBIGI_OPTIMIZATION_ATHLON64 + "_64" + _libSuffix);
                rv.add(_libPrefix + getMiddleName1() + JBIGI_OPTIMIZATION_ATHLON64 + _libSuffix);
            }
        }
        // Add libjbigi-xxx-none_64.so
        if (_is64)
            rv.add(_libPrefix + getMiddleName1() + "none_64" + _libSuffix);
        // Add libjbigi-xxx-none.so
        // Note that libjbigi-osx-none.jnilib is a 'fat binary' with both PPC and x86-32
        if (!_isArm && !_isPPC && !_isMac)
            rv.add(getResourceName(false));
        return rv;
    }

    /**
     *  Return /proc/cpuinfo as a key-value mapping.
     *  All keys mapped to lower case.
     *  All keys and values trimmed.
     *  For dup keys, first one wins.
     *  Currently used for ARM only.
     *  @return non-null, empty on failure
     *  @since 0.9.1
     */
    private static Map<String, String> getCPUInfo() {
        Map<String, String> rv = new HashMap<String, String>(32);
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/cpuinfo"), "ISO-8859-1"), 4096);
            String line = null;
            while ( (line = in.readLine()) != null) {
                String[] parts = line.split(":", 2);
                if (parts.length < 2)
                    continue;
                String key = parts[0].trim().toLowerCase(Locale.US);
                if (!rv.containsKey(key))
                    rv.put(key, parts[1].trim());
            }
        } catch (IOException ioe) {
            warn("Unable to read /proc/cpuinfo", ioe);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
        return rv;
    }

    /**
     *  @return may be null if optimized is true
     */
    private static final String getResourceName(boolean optimized) {
        String middle = getMiddleName(optimized);
        if (middle == null)
            return null;
        return _libPrefix + middle + _libSuffix;
    }
    
    /**
     *  @return may be null if optimized is true; returns jbigi-xxx-none if optimize is false
     */
    private static final String getMiddleName(boolean optimized) {
        String m2 = getMiddleName2(optimized);
        if (m2 == null)
            return null;
        return getMiddleName1() + m2;
    }

    /**
     *  @return may be null if optimized is true; returns "none" if optimize is false
     *  @since 0.8.7
     */
    private static final String getMiddleName2(boolean optimized) {
        String sAppend;
        if (optimized) {
            if (sCPUType == null)
                return null;
            // Add exceptions here if library files are identical,
            // instead of adding duplicates to jbigi.jar
            if (sCPUType.equals(JBIGI_OPTIMIZATION_K6_3) && !_isWin)
                // k62 and k63 identical except on windows
                sAppend = JBIGI_OPTIMIZATION_K6_2;
            else if (sCPUType.equals(JBIGI_OPTIMIZATION_COREI) && (!_is64) && ((_isKFreebsd) || (_isNetbsd) || (_isOpenbsd)))
                // corei and core2 are identical on 32bit kfreebsd, openbsd, and netbsd
                sAppend = JBIGI_OPTIMIZATION_CORE2;
            else if (sCPUType.equals(JBIGI_OPTIMIZATION_PENTIUM2) && _isSunos && _isX86)
                // pentium2 and pentium3 identical on X86 Solaris
                sAppend = JBIGI_OPTIMIZATION_PENTIUM3;
            else if (sCPUType.equals(JBIGI_OPTIMIZATION_VIAC32))
                // viac32 and pentium3 identical
                sAppend = JBIGI_OPTIMIZATION_PENTIUM3;
            //else if (sCPUType.equals(JBIGI_OPTIMIZATION_VIAC3) && _isWin)
                // FIXME no viac3 available for windows, what to use instead?
            else
                sAppend = sCPUType;        
        } else {
            sAppend = "none";
        }
        return sAppend;
    }

    /**
     *  @return "jbigi-xxx-"
     *  @since 0.8.7
     */
    private static final String getMiddleName1() {
        if(_isWin)
             return "jbigi-windows-";
        if(_isKFreebsd)
            return "jbigi-kfreebsd-";
        if(_isFreebsd)
            return "jbigi-freebsd-";
        if(_isNetbsd)
            return "jbigi-netbsd-";
        if(_isOpenbsd)
            return "jbigi-openbsd-";
        if(_isMac)
            return "jbigi-osx-";
        if(_isOS2)
            return "jbigi-os2-";
        if(_isSunos)
            return "jbigi-solaris-";
        //throw new RuntimeException("Dont know jbigi library name for os type '"+System.getProperty("os.name")+"'");
        // use linux as the default, don't throw exception
        return "jbigi-linux-";
    }
}
