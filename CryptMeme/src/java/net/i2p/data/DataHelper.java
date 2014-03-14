package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import gnu.crypto.hash.Sha256Standalone;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.zip.Deflater;

import net.i2p.I2PAppContext;
import net.i2p.util.ByteCache;
import net.i2p.util.OrderedProperties;
import net.i2p.util.ReusableGZIPInputStream;
import net.i2p.util.ReusableGZIPOutputStream;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.Translate;

/**
 * Defines some simple IO routines for dealing with marshalling data structures
 *
 * @author jrandom
 */
public class DataHelper {
    private static final byte[] EQUAL_BYTES = getUTF8("=");
    private static final byte[] SEMICOLON_BYTES = getUTF8(";");

    /**
     *  Map of String to itself to cache common
     *  keys in RouterInfo, RouterAddress, and BlockfileNamingService properties.
     *  Reduces Object proliferation caused by frequent deserialization.
     *  @since 0.8.12
     */
    private static final Map<String, String> _propertiesKeyCache;
    static {
        String keys[] = {
            // NTCP/SSU RouterAddress options
            "cost", "host", "port",
            // SSU RouterAddress options
            "key", "mtu",
            "ihost0", "iport0", "ikey0", "itag0",
            "ihost1", "iport1", "ikey1", "itag1",
            "ihost2", "iport2", "ikey2", "itag2",
            // RouterInfo options
            "caps", "coreVersion", "netId", "router.version",
            "netdb.knownLeaseSets", "netdb.knownRouters",
            "stat_bandwidthReceiveBps.60m",
            "stat_bandwidthSendBps.60m",
            "stat_tunnel.buildClientExpire.60m",
            "stat_tunnel.buildClientReject.60m",
            "stat_tunnel.buildClientSuccess.60m",
            "stat_tunnel.buildExploratoryExpire.60m",
            "stat_tunnel.buildExploratoryReject.60m",
            "stat_tunnel.buildExploratorySuccess.60m",
            "stat_tunnel.participatingTunnels.60m",
            "stat_uptime",
            // BlockfileNamingService
            "version", "created", "upgraded", "lists",
            "a", "s",
        };
        _propertiesKeyCache = new HashMap<String, String>(keys.length);
        for (int i = 0; i < keys.length; i++) {
            _propertiesKeyCache.put(keys[i], keys[i]);
        }
    }

    /** Read a mapping from the stream, as defined by the I2P data structure spec,
     * and store it into a Properties object.
     *
     * A mapping is a set of key / value pairs. It starts with a 2 byte Integer (ala readLong(rawStream, 2))
     * defining how many bytes make up the mapping.  After that comes that many bytes making
     * up a set of UTF-8 encoded characters. The characters are organized as key=value;.
     * The key is a String (ala readString(rawStream)) unique as a key within the current
     * mapping that does not include the UTF-8 characters '=' or ';'.  After the key
     * comes the literal UTF-8 character '='.  After that comes a String (ala readString(rawStream))
     * for the value. Finally after that comes the literal UTF-8 character ';'. This key=value;
     * is repeated until there are no more bytes (not characters!) left as defined by the
     * first two byte integer.
     * @param rawStream stream to read the mapping from
     * @throws DataFormatException if the format is invalid
     * @throws IOException if there is a problem reading the data
     * @return an OrderedProperties
     */
    public static Properties readProperties(InputStream rawStream) 
        throws DataFormatException, IOException {
        Properties props = new OrderedProperties();
        readProperties(rawStream, props);
        return props;
    }

    /**
     *  Ditto, load into an existing properties
     *  @param props the Properties to load into
     *  @since 0.8.13
     */
    public static Properties readProperties(InputStream rawStream, Properties props) 
        throws DataFormatException, IOException {
        long size = readLong(rawStream, 2);
        byte data[] = new byte[(int) size];
        int read = read(rawStream, data);
        if (read != size) throw new DataFormatException("Not enough data to read the properties, expected " + size + " but got " + read);
        ByteArrayInputStream in = new ByteArrayInputStream(data);
        byte eqBuf[] = new byte[EQUAL_BYTES.length];
        byte semiBuf[] = new byte[SEMICOLON_BYTES.length];
        while (in.available() > 0) {
            String key = readString(in);
            String cached = _propertiesKeyCache.get(key);
            if (cached != null)
                key = cached;
            read = read(in, eqBuf);
            if ((read != eqBuf.length) || (!eq(eqBuf, EQUAL_BYTES))) {
                throw new DataFormatException("Bad key");
            }
            String val = readString(in);
            read = read(in, semiBuf);
            if ((read != semiBuf.length) || (!eq(semiBuf, SEMICOLON_BYTES))) {
                throw new DataFormatException("Bad value");
            }
            props.put(key, val);
        }
        return props;
    }

    /**
     * Write a mapping to the stream, as defined by the I2P data structure spec,
     * and store it into a Properties object.  See readProperties for the format.
     * Output is sorted by property name.
     * Property keys and values must not contain '=' or ';', this is not checked and they are not escaped
     * Keys and values must be 255 bytes or less,
     * Formatted length must not exceed 65535 bytes
     * @throws DataFormatException if either is too long.
     *
     * @param rawStream stream to write to
     * @param props properties to write out
     * @throws DataFormatException if there is not enough valid data to write out
     * @throws IOException if there is an IO error writing out the data
     */
    public static void writeProperties(OutputStream rawStream, Properties props) 
            throws DataFormatException, IOException {
        writeProperties(rawStream, props, false);
    }

    /**
     * Writes the props to the stream, sorted by property name.
     * See readProperties() for the format.
     * Property keys and values must not contain '=' or ';', this is not checked and they are not escaped
     * Keys and values must be 255 bytes or less,
     * Formatted length must not exceed 65535 bytes
     * @throws DataFormatException if either is too long.
     *
     * jrandom disabled UTF-8 in mid-2004, for performance reasons,
     * i.e. slow foo.getBytes("UTF-8")
     * Re-enable it so we can pass UTF-8 tunnel names through the I2CP SessionConfig.
     *
     * Use utf8 = false for RouterAddress (fast, non UTF-8)
     * Use utf8 = true for SessionConfig (slow, UTF-8)
     * @param props source may be null
     */
    public static void writeProperties(OutputStream rawStream, Properties props, boolean utf8) 
            throws DataFormatException, IOException {
        writeProperties(rawStream, props, utf8, props != null && !(props instanceof OrderedProperties));
    }

    /**
     * Writes the props to the stream, sorted by property name if sort == true or
     * if props is an OrderedProperties.
     * See readProperties() for the format.
     * Property keys and values must not contain '=' or ';', this is not checked and they are not escaped
     * Keys and values must be 255 bytes or less,
     * Formatted length must not exceed 65535 bytes
     *
     * jrandom disabled UTF-8 in mid-2004, for performance reasons,
     * i.e. slow foo.getBytes("UTF-8")
     * Re-enable it so we can pass UTF-8 tunnel names through the I2CP SessionConfig.
     *
     * Use utf8 = false for RouterAddress (fast, non UTF-8)
     * Use utf8 = true for SessionConfig (slow, UTF-8)
     * @param props source may be null
     * @param sort should we sort the properties? (set to false if already sorted, e.g. OrderedProperties)
     * @throws DataFormatException if any string is over 255 bytes long, or if the total length
     *                             (not including the two length bytes) is greater than 65535 bytes.
     * @since 0.8.7
     */
    public static void writeProperties(OutputStream rawStream, Properties props, boolean utf8, boolean sort) 
            throws DataFormatException, IOException {
        if (props != null) {
            Properties p;
            if (sort) {
                p = new OrderedProperties();
                p.putAll(props);
            } else {
                p = props;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream(p.size() * 64);
            for (Map.Entry<Object, Object> entry : p.entrySet()) {
                String key = (String) entry.getKey();
                String val = (String) entry.getValue();
                if (utf8)
                    writeStringUTF8(baos, key);
                else
                    writeString(baos, key);
                baos.write(EQUAL_BYTES);
                if (utf8)
                    writeStringUTF8(baos, val);
                else
                    writeString(baos, val);
                baos.write(SEMICOLON_BYTES);
            }
            if (baos.size() > 65535)
                throw new DataFormatException("Properties too big (65535 max): " + baos.size());
            byte propBytes[] = baos.toByteArray();
            writeLong(rawStream, 2, propBytes.length);
            rawStream.write(propBytes);
        } else {
            writeLong(rawStream, 2, 0);
        }
    }
    
    /*
     * Writes the props to the byte array, sorted
     * See readProperties() for the format.
     * Property keys and values must not contain '=' or ';', this is not checked and they are not escaped
     * Keys and values must be 255 bytes or less,
     * Formatted length must not exceed 65535 bytes
     * Strings will be UTF-8 encoded in the byte array.
     * Warning - confusing method name, Properties is the source.
     *
     * @deprecated unused
     *
     * @param target returned array as specified in data structure spec
     * @param props source may be null
     * @return new offset
     * @throws DataFormatException if any string is over 255 bytes long, or if the total length
     *                             (not including the two length bytes) is greater than 65535 bytes.
     */
    @Deprecated
    public static int toProperties(byte target[], int offset, Properties props) throws DataFormatException, IOException {
        if (props != null) {
            OrderedProperties p = new OrderedProperties();
            p.putAll(props);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(p.size() * 64);
            for (Map.Entry<Object, Object> entry : p.entrySet()) {
                String key = (String) entry.getKey();
                String val = (String) entry.getValue();
                writeStringUTF8(baos, key);
                baos.write(EQUAL_BYTES);
                writeStringUTF8(baos, val);
                baos.write(SEMICOLON_BYTES);
            }
            if (baos.size() > 65535)
                throw new DataFormatException("Properties too big (65535 max): " + baos.size());
            byte propBytes[] = baos.toByteArray();
            toLong(target, offset, 2, propBytes.length);
            offset += 2;
            System.arraycopy(propBytes, 0, target, offset, propBytes.length);
            offset += propBytes.length;
            return offset;
        } else {
            toLong(target, offset, 2, 0);
            return offset + 2;
        }
    }
    
    /**
     * Reads the props from the byte array and puts them in the Properties target
     * See readProperties() for the format.
     * Warning - confusing method name, Properties is the target.
     * Strings must be UTF-8 encoded in the byte array.
     *
     * @param source source
     * @param target returned Properties
     * @return new offset
     */
    public static int fromProperties(byte source[], int offset, Properties target) throws DataFormatException {
        int size = (int)fromLong(source, offset, 2);
        offset += 2;
        ByteArrayInputStream in = new ByteArrayInputStream(source, offset, size);
        byte eqBuf[] = new byte[EQUAL_BYTES.length];
        byte semiBuf[] = new byte[SEMICOLON_BYTES.length];
        while (in.available() > 0) {
            String key;
            try {
                key = readString(in);
                String cached = _propertiesKeyCache.get(key);
                if (cached != null)
                    key = cached;
                int read = read(in, eqBuf);
                if ((read != eqBuf.length) || (!eq(eqBuf, EQUAL_BYTES))) {
                    throw new DataFormatException("Bad key");
                }
            } catch (IOException ioe) {
                throw new DataFormatException("Bad key", ioe);
            }
            String val;
            try {
                val = readString(in);
                int read = read(in, semiBuf);
                if ((read != semiBuf.length) || (!eq(semiBuf, SEMICOLON_BYTES))) {
                    throw new DataFormatException("Bad value");
                }
            } catch (IOException ioe) {
                throw new DataFormatException("Bad value", ioe);
            }
            target.put(key, val);
        }
        return offset + size;
    }

    /**
     * Writes the props to returned byte array, not sorted
     * (unless the opts param is an OrderedProperties)
     * Strings will be UTF-8 encoded in the byte array.
     * See readProperties() for the format.
     * Property keys and values must not contain '=' or ';', this is not checked and they are not escaped
     * Keys and values must be 255 bytes or less,
     * Formatted length must not exceed 65535 bytes
     * Warning - confusing method name, Properties is the source.
     *
     * @throws DataFormatException if key, value, or total is too long
     */
    public static byte[] toProperties(Properties opts) throws DataFormatException {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(2 + (32 * opts.size()));
            writeProperties(baos, opts, true, false);
            return baos.toByteArray();
        } catch (IOException ioe) {
            throw new RuntimeException("IO error writing to memory?! " + ioe.getMessage());
        }
    }

    /**
     * Pretty print the mapping, unsorted
     * (unless the options param is an OrderedProperties)
     */
    public static String toString(Properties options) {
        return toString((Map<?, ?>) options);
    }

    /**
     * Pretty print the mapping, unsorted
     * (unless the options param is an OrderedProperties)
     * @since 0.9.4
     */
    public static String toString(Map<?, ?> options) {
        StringBuilder buf = new StringBuilder();
        if (options != null) {
            for (Map.Entry<?, ?> entry : options.entrySet()) {
                String key = (String) entry.getKey();
                String val = (String) entry.getValue();
                buf.append("[").append(key).append("] = [").append(val).append("]");
            }
        } else {
            buf.append("(null properties map)");
        }
        return buf.toString();
    }

    /**
     * A more efficient Properties.load
     *
     * Some of the other differences:
     * - UTF-8 encoding, not ISO-8859-1
     * - No escaping! This does not process or drop backslashes
     * - '#' or ';' starts a comment line, but '!' does not
     * - Leading whitespace is not trimmed
     * - '=' is the only key-termination character (not ':' or whitespace)
     *
     * As of 0.9.10, an empty value is allowed.
     */
    public static void loadProps(Properties props, File file) throws IOException {
        loadProps(props, file, false);
    }

    /**
     *  @param forceLowerCase if true forces the keys to lower case (not the values)
     */
    public static void loadProps(Properties props, File file, boolean forceLowerCase) throws IOException {
        loadProps(props, new FileInputStream(file), forceLowerCase);
    }

    public static void loadProps(Properties props, InputStream inStr) throws IOException {
        loadProps(props, inStr, false);
    }

    /**
     *  @param forceLowerCase if true forces the keys to lower case (not the values)
     */
    public static void loadProps(Properties props, InputStream inStr, boolean forceLowerCase) throws IOException {
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(inStr, "UTF-8"), 16*1024);
            String line = null;
            while ( (line = in.readLine()) != null) {
                if (line.trim().length() <= 0) continue;
                if (line.charAt(0) == '#') continue;
                if (line.charAt(0) == ';') continue;
                if (line.indexOf('#') > 0)  // trim off any end of line comment
                    line = line.substring(0, line.indexOf('#')).trim();
                int split = line.indexOf('=');
                if (split <= 0) continue;
                String key = line.substring(0, split);
                String val = line.substring(split+1).trim();
                // Unescape line breaks after loading.
                // Remember: "\" needs escaping both for regex and string.

                // For some reason this was turning \r (one backslash) into CR,
                // I think it needed one more \\ in the pattern?,
                // which sucks if your username is randy on DOS,
                // it was a horrible idea anyway
                //val = val.replaceAll("\\\\r","\r");
                //val = val.replaceAll("\\\\n","\n");

                // as of 0.9.10, an empty value is allowed
                if (forceLowerCase)
                    props.setProperty(key.toLowerCase(Locale.US), val);
                else
                    props.setProperty(key, val);
            }
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
    }
    
    /**
     * Writes the props to the file, unsorted (unless props is an OrderedProperties)
     * Note that this does not escape the \r or \n that are unescaped in loadProps() above.
     * As of 0.8.1, file will be mode 600.
     *
     * Leading or trailing whitespace in values is not checked but
     * will be trimmed by loadProps()
     *
     * @throws IllegalArgumentException if a key contains any of "#=\n" or starts with ';',
     *                                  or a value contains '#' or '\n'
     */
    public static void storeProps(Properties props, File file) throws IOException {
        PrintWriter out = null;
        IllegalArgumentException iae = null;
        try {
            out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(file), "UTF-8")));
            out.println("# NOTE: This I2P config file must use UTF-8 encoding");
            for (Map.Entry<Object, Object> entry : props.entrySet()) {
                String name = (String) entry.getKey();
                String val = (String) entry.getValue();
                if (name.contains("#") ||
                    name.contains("=") ||
                    name.contains("\n") ||
                    name.startsWith(";") ||
                    val.contains("#") ||
                    val.contains("\n")) {
                    if (iae == null)
                        iae = new IllegalArgumentException("Invalid character (one of \"#;=\\n\") in key or value: \"" +
                                                           name + "\" = \"" + val + '\"');
                    continue;
                }
                out.println(name + "=" + val);
            }
        } finally {
            if (out != null) out.close();
        }
        if (iae != null)
            throw iae;
    }
    
    /**
     * Pretty print the collection
     *
     */
    public static String toString(Collection<?> col) {
        StringBuilder buf = new StringBuilder();
        if (col != null) {
            for (Iterator<?> iter = col.iterator(); iter.hasNext();) {
                Object o = iter.next();
                buf.append("[").append(o).append("]");
                if (iter.hasNext()) buf.append(", ");
            }
        } else {
            buf.append("null");
        }
        return buf.toString();
    }

    /**
     *  Lower-case hex with leading zeros.
     *  Use toHexString(byte[]) to not get leading zeros
     *  @param buf may be null (returns "")
     *  @return String of length 2*buf.length
     */
    public static String toString(byte buf[]) {
        if (buf == null) return "";

        return toString(buf, buf.length);
    }

    private static final byte[] EMPTY_BUFFER = "".getBytes();
    
    /**
     *  Lower-case hex with leading zeros.
     *  Use toHexString(byte[]) to not get leading zeros
     *  @param buf may be null
     *  @param len number of bytes. If greater than buf.length, additional zeros will be prepended
     *  @return String of length 2*len
     */
    public static String toString(byte buf[], int len) {
        if (buf == null) buf = EMPTY_BUFFER;
        StringBuilder out = new StringBuilder();
        if (len > buf.length) {
            for (int i = 0; i < len - buf.length; i++)
                out.append("00");
        }
        int min = Math.min(buf.length, len);
        for (int i = 0; i < min; i++) {
            int bi = buf[i] & 0xff;
            if (bi < 16)
                out.append('0');
            out.append(Integer.toHexString(bi));
        }
        return out.toString();
    }

    public static String toDecimalString(byte buf[], int len) {
        if (buf == null) buf = EMPTY_BUFFER;
        BigInteger val = new BigInteger(1, buf);
        return val.toString(10);
    }

    /**
     *  Lower-case hex without leading zeros.
     *  Use toString(byte[] to get leading zeros
     *  @param data may be null (returns "00")
     */
    public final static String toHexString(byte data[]) {
        if ((data == null) || (data.length <= 0)) return "00";
        BigInteger bi = new BigInteger(1, data);
        return bi.toString(16);
    }

    public final static byte[] fromHexString(String val) {
        BigInteger bv = new BigInteger(val, 16);
        return bv.toByteArray();
    }

    /** Read the stream for an integer as defined by the I2P data structure specification.
     * Integers are a fixed number of bytes (numBytes), stored as unsigned integers in network byte order.
     * @param rawStream stream to read from
     * @param numBytes number of bytes to read and format into a number, 1 to 8
     * @throws DataFormatException if negative (only possible if numBytes = 8) (since 0.8.12)
     * @throws EOFException since 0.8.2, if there aren't enough bytes to read the number
     * @throws IOException if there is an IO error reading the number
     * @return number
     */
    public static long readLong(InputStream rawStream, int numBytes) 
        throws DataFormatException, IOException {
        if (numBytes > 8)
            throw new DataFormatException("readLong doesn't currently support reading numbers > 8 bytes [as thats bigger than java's long]");

        long rv = 0;
        for (int i = 0; i < numBytes; i++) {
            int cur = rawStream.read();
            // was DataFormatException
            if (cur == -1) throw new EOFException("EOF reading " + numBytes + " byte value");
            // we loop until we find a nonzero byte (or we reach the end)
            if (cur != 0) {
                // ok, data found, now iterate through it to fill the rv
                rv = cur & 0xff;
                for (int j = i + 1; j < numBytes; j++) {
                    rv <<= 8;
                    cur = rawStream.read();
                    // was DataFormatException
                    if (cur == -1)
                        throw new EOFException("EOF reading " + numBytes + " byte value");
                    rv |= cur & 0xff;
                }
                break;
            }
        }
        
        if (rv < 0)
            throw new DataFormatException("wtf, fromLong got a negative? " + rv + " numBytes=" + numBytes);
        return rv;
    }
    
    /** Write an integer as defined by the I2P data structure specification to the stream.
     * Integers are a fixed number of bytes (numBytes), stored as unsigned integers in network byte order.
     * @param value value to write out, non-negative
     * @param rawStream stream to write to
     * @param numBytes number of bytes to write the number into (padding as necessary)
     * @throws DataFormatException if value is negative
     * @throws IOException if there is an IO error writing to the stream
     */
    public static void writeLong(OutputStream rawStream, int numBytes, long value) 
        throws DataFormatException, IOException {
        if (value < 0) throw new DataFormatException("Value is negative (" + value + ")");
        for (int i = (numBytes - 1) * 8; i >= 0; i -= 8) {
            byte cur = (byte) (value >> i);
            rawStream.write(cur);
        }
    }
    
    /**
     * @param numBytes 1-8
     * @param value non-negative
     */
    public static byte[] toLong(int numBytes, long value) throws IllegalArgumentException {
        if (value < 0) throw new IllegalArgumentException("Negative value not allowed");
        byte val[] = new byte[numBytes];
        toLong(val, 0, numBytes, value);
        return val;
    }
    
    /**
     * @param numBytes 1-8
     * @param value non-negative
     */
    public static void toLong(byte target[], int offset, int numBytes, long value) throws IllegalArgumentException {
        if (numBytes <= 0) throw new IllegalArgumentException("Invalid number of bytes");
        if (value < 0) throw new IllegalArgumentException("Negative value not allowed");

        for (int i = offset + numBytes - 1; i >= offset; i--) {
            target[i] = (byte) value;
            value >>= 8;
        }
    }
    
    /**
     * Little endian, i.e. backwards. Not for use in I2P protocols.
     *
     * @param numBytes 1-8
     * @param value non-negative
     * @since 0.8.12
     */
    public static void toLongLE(byte target[], int offset, int numBytes, long value) {
        if (value < 0) throw new IllegalArgumentException("Negative value not allowed");
        int limit = offset + numBytes;
        for (int i = offset; i < limit; i++) {
            target[i] = (byte) value;
            value >>= 8;
        }
    }
    
    /**
     * @param src if null returns 0
     * @param numBytes 1-8
     * @return non-negative
     * @throws AIOOBE
     * @throws IllegalArgumentException if negative (only possible if numBytes = 8)
     */
    public static long fromLong(byte src[], int offset, int numBytes) {
        if ( (src == null) || (src.length == 0) )
            return 0;
        
        long rv = 0;
        int limit = offset + numBytes;
        for (int i = offset; i < limit; i++) {
            rv <<= 8;
            rv |= src[i] & 0xFF;
        }
        if (rv < 0)
            throw new IllegalArgumentException("wtf, fromLong got a negative? " + rv + ": offset="+ offset +" numBytes="+numBytes);
        return rv;
    }
    
    /**
     * Little endian, i.e. backwards. Not for use in I2P protocols.
     *
     * @param numBytes 1-8
     * @return non-negative
     * @throws AIOOBE
     * @throws IllegalArgumentException if negative (only possible if numBytes = 8)
     * @since 0.8.12
     */
    public static long fromLongLE(byte src[], int offset, int numBytes) {
        long rv = 0;
        for (int i = offset + numBytes - 1; i >= offset; i--) {
            rv <<= 8;
            rv |= src[i] & 0xFF;
        }
        if (rv < 0)
            throw new IllegalArgumentException("wtf, fromLong got a negative? " + rv + ": offset="+ offset +" numBytes="+numBytes);
        return rv;
    }
    
    /** Read in a date from the stream as specified by the I2P data structure spec.
     * A date is an 8 byte unsigned integer in network byte order specifying the number of
     * milliseconds since midnight on January 1, 1970 in the GMT timezone. If the number is
     * 0, the date is undefined or null. (yes, this means you can't represent midnight on 1/1/1970)
     * @param in stream to read from
     * @throws DataFormatException if the stream doesn't contain a validly formatted date
     * @throws IOException if there is an IO error reading the date
     * @return date read, or null
     */
    public static Date readDate(InputStream in) throws DataFormatException, IOException {
        long date = readLong(in, DATE_LENGTH);
        if (date == 0L) return null;

        return new Date(date);
    }
    
    /** Write out a date to the stream as specified by the I2P data structure spec.
     * @param out stream to write to
     * @param date date to write (can be null)
     * @throws DataFormatException if the date is not valid
     * @throws IOException if there is an IO error writing the date
     */
    public static void writeDate(OutputStream out, Date date) 
        throws DataFormatException, IOException {
        if (date == null)
            writeLong(out, DATE_LENGTH, 0L);
        else
            writeLong(out, DATE_LENGTH, date.getTime());
    }

    /** @deprecated unused */
    @Deprecated
    public static byte[] toDate(Date date) throws IllegalArgumentException {
        if (date == null)
            return toLong(DATE_LENGTH, 0L);
        else
            return toLong(DATE_LENGTH, date.getTime());
    }

    public static void toDate(byte target[], int offset, long when) throws IllegalArgumentException {
        toLong(target, offset, DATE_LENGTH, when);
    }

    public static Date fromDate(byte src[], int offset) throws DataFormatException {
        if ( (src == null) || (offset + DATE_LENGTH > src.length) )
            throw new DataFormatException("Not enough data to read a date");
        try {
            long when = fromLong(src, offset, DATE_LENGTH);
            if (when <= 0) 
                return null;
            else
                return new Date(when);
        } catch (IllegalArgumentException iae) {
            throw new DataFormatException(iae.getMessage());
        }
    }
    
    public static final int DATE_LENGTH = 8;

    /** Read in a string from the stream as specified by the I2P data structure spec.
     * A string is 1 or more bytes where the first byte is the number of bytes (not characters!)
     * in the string and the remaining 0-255 bytes are the non-null terminated UTF-8 encoded character array.
     *
     * @param in stream to read from
     * @throws DataFormatException if the stream doesn't contain a validly formatted string
     * @throws EOFException since 0.8.2, if there aren't enough bytes to read the string
     * @throws IOException if there is an IO error reading the string
     * @return UTF-8 string
     */
    public static String readString(InputStream in) throws DataFormatException, IOException {
        int size = in.read();
        if (size == -1)
            throw new EOFException("EOF reading string");
        if (size == 0)
            return "";   // reduce object proliferation
        size &= 0xff;
        byte raw[] = new byte[size];
        int read = read(in, raw);
        // was DataFormatException
        if (read != size) throw new EOFException("EOF reading string");
        // the following constructor throws an UnsupportedEncodingException which is an IOException,
        // but that's only if UTF-8 is not supported. Other encoding errors are not thrown.
        return new String(raw, "UTF-8");
    }

    /** Write out a string to the stream as specified by the I2P data structure spec.  Note that the max
     * size for a string allowed by the spec is 255 bytes.
     *
     * WARNING - this method destroys the encoding, and therefore violates
     * the data structure spec.
     *
     * @param out stream to write string
     * @param string string to write out: null strings are perfectly valid, but strings of excess length will
     *               cause a DataFormatException to be thrown
     * @throws DataFormatException if the string is not valid
     * @throws IOException if there is an IO error writing the string
     */
    public static void writeString(OutputStream out, String string) 
        throws DataFormatException, IOException {
        if (string == null) {
            out.write((byte) 0);
        } else {
            int len = string.length();
            if (len > 255)
                throw new DataFormatException("The I2P data spec limits strings to 255 bytes or less, but this is "
                                              + len + " [" + string + "]");
            out.write((byte) len);
            for (int i = 0; i < len; i++)
                out.write((byte)(string.charAt(i) & 0xFF));
        }
    }

    /** Write out a string to the stream as specified by the I2P data structure spec.  Note that the max
     * size for a string allowed by the spec is 255 bytes.
     *
     * This method correctly uses UTF-8
     *
     * @param out stream to write string
     * @param string UTF-8 string to write out: null strings are perfectly valid, but strings of excess length will
     *               cause a DataFormatException to be thrown
     * @throws DataFormatException if the string is not valid
     * @throws IOException if there is an IO error writing the string
     */
    private static void writeStringUTF8(OutputStream out, String string) 
        throws DataFormatException, IOException {
        if (string == null) {
            out.write((byte) 0);
        } else {
            // the following method throws an UnsupportedEncodingException which is an IOException,
            // but that's only if UTF-8 is not supported. Other encoding errors are not thrown.
            byte[] raw = string.getBytes("UTF-8");
            int len = raw.length;
            if (len > 255)
                throw new DataFormatException("The I2P data spec limits strings to 255 bytes or less, but this is "
                                              + len + " [" + string + "]");
            out.write((byte) len);
            out.write(raw);
        }
    }

    /** Read in a boolean as specified by the I2P data structure spec.
     * A boolean is 1 byte that is either 0 (false), 1 (true), or 2 (null)
     * @param in stream to read from
     * @throws DataFormatException if the boolean is not valid
     * @throws IOException if there is an IO error reading the boolean
     * @return boolean value, or null
     * @deprecated unused
     */
    public static Boolean readBoolean(InputStream in) throws DataFormatException, IOException {
        int val = in.read();
        switch (val) {
        case -1:
            throw new EOFException("EOF reading boolean");
        case 0:
            return Boolean.FALSE;
        case 1:
            return Boolean.TRUE;
        case 2:
            return null;
        default:
            throw new DataFormatException("Uhhh.. readBoolean read a value that isn't a known ternary val (0,1,2): "
                                          + val);
        }
    }

    /** Write out a boolean as specified by the I2P data structure spec.
     * A boolean is 1 byte that is either 0 (false), 1 (true), or 2 (null)
     * @param out stream to write to
     * @param bool boolean value, or null
     * @throws DataFormatException if the boolean is not valid
     * @throws IOException if there is an IO error writing the boolean
     * @deprecated unused
     */
    @Deprecated
    public static void writeBoolean(OutputStream out, Boolean bool) 
        throws DataFormatException, IOException {
        if (bool == null)
            writeLong(out, 1, BOOLEAN_UNKNOWN);
        else if (Boolean.TRUE.equals(bool))
            writeLong(out, 1, BOOLEAN_TRUE);
        else
            writeLong(out, 1, BOOLEAN_FALSE);
    }
    
    /** @deprecated unused */
    @Deprecated
    public static Boolean fromBoolean(byte data[], int offset) {
        if (data[offset] == BOOLEAN_TRUE)
            return Boolean.TRUE;
        else if (data[offset] == BOOLEAN_FALSE)
            return Boolean.FALSE;
        else
            return null;
    }
    
    /** @deprecated unused */
    @Deprecated
    public static void toBoolean(byte data[], int offset, boolean value) {
        data[offset] = (value ? BOOLEAN_TRUE : BOOLEAN_FALSE);
    }

    /** @deprecated unused */
    @Deprecated
    public static void toBoolean(byte data[], int offset, Boolean value) {
        if (value == null)
            data[offset] = BOOLEAN_UNKNOWN;
        else
            data[offset] = (value.booleanValue() ? BOOLEAN_TRUE : BOOLEAN_FALSE);
    }
    
    /** deprecated - used only in DatabaseLookupMessage */
    public static final byte BOOLEAN_TRUE = 0x1;
    /** deprecated - used only in DatabaseLookupMessage */
    public static final byte BOOLEAN_FALSE = 0x0;
    /** @deprecated unused */
    @Deprecated
    public static final byte BOOLEAN_UNKNOWN = 0x2;
    /** @deprecated unused */
    @Deprecated
    public static final int BOOLEAN_LENGTH = 1;

    //
    // The following comparator helpers make it simpler to write consistently comparing
    // functions for objects based on their value, not JVM memory address
    //

    /**
     * Helper util to compare two objects, including null handling.
     * <p />
     *
     * This treats (null == null) as true, and (null == (!null)) as false.
     */
    public final static boolean eq(Object lhs, Object rhs) {
        try {
            boolean eq = (((lhs == null) && (rhs == null)) || ((lhs != null) && (lhs.equals(rhs))));
            return eq;
        } catch (ClassCastException cce) {
            return false;
        }
    }

    /**
     * Run a deep comparison across the two collections.  
     * <p />
     *
     * This treats (null == null) as true, (null == (!null)) as false, and then 
     * comparing each element via eq(object, object). <p />
     *
     * If the size of the collections are not equal, the comparison returns false.
     * The collection order should be consistent, as this simply iterates across both and compares
     * based on the value of each at each step along the way.
     *
     */
    public final static boolean eq(Collection<?> lhs, Collection<?> rhs) {
        if ((lhs == null) && (rhs == null)) return true;
        if ((lhs == null) || (rhs == null)) return false;
        if (lhs.size() != rhs.size()) return false;
        Iterator<?> liter = lhs.iterator();
        Iterator<?> riter = rhs.iterator();
        while ((liter.hasNext()) && (riter.hasNext()))
            if (!(eq(liter.next(), riter.next()))) return false;
        return true;
    }

    /**
     * Run a comparison on the byte arrays, byte by byte.  <p />
     *
     * This treats (null == null) as true, (null == (!null)) as false, 
     * and unequal length arrays as false.
     *
     * @return Arrays.equals(lhs, rhs)
     */
    public final static boolean eq(byte lhs[], byte rhs[]) {
        return Arrays.equals(lhs, rhs);
    }

    /**
     * Compare two integers, really just for consistency.
     * @deprecated inefficient
     */
    @Deprecated
    public final static boolean eq(int lhs, int rhs) {
        return lhs == rhs;
    }

    /**
     * Compare two longs, really just for consistency.
     * @deprecated inefficient
     */
    @Deprecated
    public final static boolean eq(long lhs, long rhs) {
        return lhs == rhs;
    }

    /**
     * Compare two bytes, really just for consistency.
     * @deprecated inefficient
     */
    @Deprecated
    public final static boolean eq(byte lhs, byte rhs) {
        return lhs == rhs;
    }

    /**
     *  Unlike eq(byte[], byte[]), this returns false if either lhs or rhs is null.
     *  @throws AIOOBE if either array isn't long enough
     */
    public final static boolean eq(byte lhs[], int offsetLeft, byte rhs[], int offsetRight, int length) {
        if ( (lhs == null) || (rhs == null) ) return false;
        if (length <= 0) return true;
        for (int i = 0; i < length; i++) {
            if (lhs[offsetLeft + i] != rhs[offsetRight + i]) 
                return false;
        }
        return true;
    }
    
    /**
     *  Big endian compare, treats bytes as unsigned.
     *  Shorter arg is lesser.
     *  Args may be null, null is less than non-null.
     */
    public final static int compareTo(byte lhs[], byte rhs[]) {
        if ((rhs == null) && (lhs == null)) return 0;
        if (lhs == null) return -1;
        if (rhs == null) return 1;
        if (rhs.length < lhs.length) return 1;
        if (rhs.length > lhs.length) return -1;
        for (int i = 0; i < rhs.length; i++) {
            if ((rhs[i] & 0xff) > (lhs[i] & 0xff))
                return -1;
            else if ((rhs[i] & 0xff) < (lhs[i] & 0xff)) return 1;
        }
        return 0;
    }

    /**
     *  @return null if either arg is null or the args are not equal length
     */
    public final static byte[] xor(byte lhs[], byte rhs[]) {
        if ((lhs == null) || (rhs == null) || (lhs.length != rhs.length)) return null;
        byte diff[] = new byte[lhs.length];
        xor(lhs, 0, rhs, 0, diff, 0, lhs.length);
        return diff;
    }

    /**
     * xor the lhs with the rhs, storing the result in out.  
     *
     * @param lhs one of the source arrays
     * @param startLeft starting index in the lhs array to begin the xor
     * @param rhs the other source array
     * @param startRight starting index in the rhs array to begin the xor
     * @param out output array
     * @param startOut starting index in the out array to store the result
     * @param len how many bytes into the various arrays to xor
     */
    public final static void xor(byte lhs[], int startLeft, byte rhs[], int startRight, byte out[], int startOut, int len) {
        if ( (lhs == null) || (rhs == null) || (out == null) )
            throw new NullPointerException("Null params to xor");
        if (lhs.length < startLeft + len)
            throw new IllegalArgumentException("Left hand side is too short");
        if (rhs.length < startRight + len)
            throw new IllegalArgumentException("Right hand side is too short");
        if (out.length < startOut + len)
            throw new IllegalArgumentException("Result is too short");
        
        for (int i = 0; i < len; i++)
            out[startOut + i] = (byte) (lhs[startLeft + i] ^ rhs[startRight + i]);
    }

    //
    // The following hashcode helpers make it simpler to write consistently hashing
    // functions for objects based on their value, not JVM memory address
    //

    /**
     * Calculate the hashcode of the object, using 0 for null
     * 
     */
    public static int hashCode(Object obj) {
        if (obj == null) return 0;

        return obj.hashCode();
    }

    /**
     * Calculate the hashcode of the date, using 0 for null
     * 
     */
    public static int hashCode(Date obj) {
        if (obj == null) return 0;

        return (int) obj.getTime();
    }

    /**
     * Calculate the hashcode of the byte array, using 0 for null
     * 
     */
    public static int hashCode(byte b[]) {
        // Java 5 now has its own method, and the old way
        // was horrible for arrays much smaller than 32.
        // otoh, for sizes >> 32, java's method may be too slow
        int rv = 0;
        if (b != null) {
            if (b.length <= 32) {
                rv = Arrays.hashCode(b);
            } else {
                for (int i = 0; i < 32; i++)
                    rv ^= (b[i] << i);  // xor better than + in tests
            }
        }
        return rv;

    }

    /**
     * Calculate the hashcode of the collection, using 0 for null
     * 
     */
    public static int hashCode(Collection<?> col) {
        if (col == null) return 0;
        int c = 0;
        for (Iterator<?> iter = col.iterator(); iter.hasNext();)
            c = 7 * c + hashCode(iter.next());
        return c;
    }

    /**
     *  This is different than InputStream.skip(), in that it
     *  does repeated reads until the full amount is skipped.
     *  To fix findbugs issues with skip().
     *
     *  Guaranteed to skip exactly n bytes or throw an IOE.
     *
     *  http://stackoverflow.com/questions/14057720/robust-skipping-of-data-in-a-java-io-inputstream-and-its-subtypes
     *  http://stackoverflow.com/questions/11511093/java-inputstream-skip-return-value-near-end-of-file
     *
     *  @since 0.9.9
     */
    public static void skip(InputStream in, long n) throws IOException {
        if (n < 0)
            throw new IllegalArgumentException();
        if (n == 0)
            return;
        long read = 0;
        long nm1 = n - 1;
        if (nm1 > 0) {
            // skip all but the last byte
            do {
                long c = in.skip(nm1 - read);
                if (c < 0)
                    throw new EOFException("EOF while skipping " + n + ", read only " + read);
                if (c == 0) {
                    // see second SO link above
                    if (in.read() == -1)
                        throw new EOFException("EOF while skipping " + n + ", read only " + read);
                    read++;
                } else {
                    read += c;
                }
            } while (read < nm1);
        }
        // read the last byte to check for EOF
        if (in.read() == -1)
            throw new EOFException("EOF while skipping " + n + ", read only " + read);
    }

    /**
     *  This is different than InputStream.read(target), in that it
     *  does repeated reads until the full data is received.
     */
    public static int read(InputStream in, byte target[]) throws IOException {
        return read(in, target, 0, target.length);
    }

    /**
     *  This is different than InputStream.read(target, offset, length), in that it
     *  returns the new offset (== old offset + bytes read).
     *  It also does repeated reads until the full data is received.
     *  @return the new offset (== old offset + bytes read)
     */
    public static int read(InputStream in, byte target[], int offset, int length) throws IOException {
        int cur = offset;
        while (cur < length) {
            int numRead = in.read(target, cur, length - cur);
            if (numRead == -1) {
                if (cur == offset) return -1; // throw new EOFException("EOF Encountered during reading");
                return cur;
            }
            cur += numRead;
        }
        return cur;
    }
    
    
    /**
     * Read a newline delimited line from the stream, returning the line (without
     * the newline), or null if EOF reached on an empty line
     * Warning - strips \n but not \r
     * Warning - 8KB line length limit as of 0.7.13, @throws IOException if exceeded
     * Warning - not UTF-8
     *
     * @return null on EOF
     */
    public static String readLine(InputStream in) throws IOException { return readLine(in, (MessageDigest) null); }

    /**
     * update the hash along the way
     * Warning - strips \n but not \r
     * Warning - 8KB line length limit as of 0.7.13, @throws IOException if exceeded
     * Warning - not UTF-8
     *
     * @param hash null OK
     * @return null on EOF
     * @deprecated use MessageDigest version
     */
    public static String readLine(InputStream in, Sha256Standalone hash) throws IOException {
        StringBuilder buf = new StringBuilder(128);
        boolean ok = readLine(in, buf, hash);
        if (ok)
            return buf.toString();
        else
            return null;
    }

    /**
     * update the hash along the way
     * Warning - strips \n but not \r
     * Warning - 8KB line length limit as of 0.7.13, @throws IOException if exceeded
     * Warning - not UTF-8
     *
     * @param hash null OK
     * @return null on EOF
     * @since 0.8.8
     */
    public static String readLine(InputStream in, MessageDigest hash) throws IOException {
        StringBuilder buf = new StringBuilder(128);
        boolean ok = readLine(in, buf, hash);
        if (ok)
            return buf.toString();
        else
            return null;
    }

    /**
     * Read in a line, placing it into the buffer (excluding the newline).
     * Warning - strips \n but not \r
     * Warning - 8KB line length limit as of 0.7.13, @throws IOException if exceeded
     * Warning - not UTF-8
     *
     * @deprecated use StringBuilder version
     * @return true if the line was read, false if eof was reached on an empty line
     *              (returns true for non-empty last line without a newline)
     */
    @Deprecated
    public static boolean readLine(InputStream in, StringBuffer buf) throws IOException {
        return readLine(in, buf, null);
    }

    /** ridiculously long, just to prevent OOM DOS @since 0.7.13 */
    private static final int MAX_LINE_LENGTH = 8*1024;

    /**
     * update the hash along the way
     * Warning - strips \n but not \r
     * Warning - 8KB line length limit as of 0.7.13, @throws IOException if exceeded
     * Warning - not UTF-8
     *
     * @return true if the line was read, false if eof was reached on an empty line
     *              (returns true for non-empty last line without a newline)
     * @deprecated use StringBuilder / MessageDigest version
     */
    @Deprecated
    public static boolean readLine(InputStream in, StringBuffer buf, Sha256Standalone hash) throws IOException {
        int c = -1;
        int i = 0;
        while ( (c = in.read()) != -1) {
            if (++i > MAX_LINE_LENGTH)
                throw new IOException("Line too long - max " + MAX_LINE_LENGTH);
            if (hash != null) hash.update((byte)c);
            if (c == '\n')
                break;
            buf.append((char)c);
        }
        return c != -1 || i > 0;
    }
    
    /**
     * Read in a line, placing it into the buffer (excluding the newline).
     * Warning - strips \n but not \r
     * Warning - 8KB line length limit as of 0.7.13, @throws IOException if exceeded
     * Warning - not UTF-8
     *
     * @return true if the line was read, false if eof was reached on an empty line
     *              (returns true for non-empty last line without a newline)
     */
    public static boolean readLine(InputStream in, StringBuilder buf) throws IOException {
        return readLine(in, buf, (MessageDigest) null);
    }

    /**
     * update the hash along the way
     * Warning - strips \n but not \r
     * Warning - 8KB line length limit as of 0.7.13, @throws IOException if exceeded
     * Warning - not UTF-8
     *
     *
     * @param hash null OK
     * @return true if the line was read, false if eof was reached on an empty line
     *              (returns true for non-empty last line without a newline)
     * @deprecated use MessageDigest version
     */
    public static boolean readLine(InputStream in, StringBuilder buf, Sha256Standalone hash) throws IOException {
        int c = -1;
        int i = 0;
        while ( (c = in.read()) != -1) {
            if (++i > MAX_LINE_LENGTH)
                throw new IOException("Line too long - max " + MAX_LINE_LENGTH);
            if (hash != null) hash.update((byte)c);
            if (c == '\n')
                break;
            buf.append((char)c);
        }
        return c != -1 || i > 0;
    }
    
    /**
     * update the hash along the way
     * Warning - strips \n but not \r
     * Warning - 8KB line length limit as of 0.7.13, @throws IOException if exceeded
     * Warning - not UTF-8
     *
     * @param hash null OK
     * @return true if the line was read, false if eof was reached on an empty line
     *              (returns true for non-empty last line without a newline)
     * @since 0.8.8
     */
    public static boolean readLine(InputStream in, StringBuilder buf, MessageDigest hash) throws IOException {
        int c = -1;
        int i = 0;
        while ( (c = in.read()) != -1) {
            if (++i > MAX_LINE_LENGTH)
                throw new IOException("Line too long - max " + MAX_LINE_LENGTH);
            if (hash != null) hash.update((byte)c);
            if (c == '\n')
                break;
            buf.append((char)c);
        }
        return c != -1 || i > 0;
    }
    
    /**
     *  update the hash along the way
     *  @deprecated use MessageDigest version
     */
    public static void write(OutputStream out, byte data[], Sha256Standalone hash) throws IOException {
        hash.update(data);
        out.write(data);
    }
    
    /**
     *  update the hash along the way
     *  @since 0.8.8
     */
    public static void write(OutputStream out, byte data[], MessageDigest hash) throws IOException {
        hash.update(data);
        out.write(data);
    }

    /**
     *  Sort based on the Hash of the DataStructure.
     *  Warning - relatively slow.
     *  WARNING - this sort order must be consistent network-wide, so while the order is arbitrary,
     *  it cannot be changed.
     *  Why? Just because it has to be consistent so signing will work.
     *  How to spec as returning the same type as the param?
     *  DEPRECATED - Only used by RouterInfo.
     *
     *  @return a new list
     */
    public static List<? extends DataStructure> sortStructures(Collection<? extends DataStructure> dataStructures) {
        if (dataStructures == null) return Collections.emptyList();

        // This used to use Hash.toString(), which is insane, since a change to toString()
        // would break the whole network. Now use Hash.toBase64().
        // Note that the Base64 sort order is NOT the same as the raw byte sort order,
        // despite what you may read elsewhere.

        //ArrayList<DataStructure> rv = new ArrayList(dataStructures.size());
        //TreeMap<String, DataStructure> tm = new TreeMap();
        //for (DataStructure struct : dataStructures) {
        //    tm.put(struct.calculateHash().toString(), struct);
        //}
        //for (DataStructure struct : tm.values()) {
        //    rv.add(struct);
        //}
        ArrayList<DataStructure> rv = new ArrayList<DataStructure>(dataStructures);
        sortStructureList(rv);
        return rv;
    }

    /**
     *  See above.
     *  DEPRECATED - Only used by RouterInfo.
     *
     *  @since 0.9
     */
    static void sortStructureList(List<? extends DataStructure> dataStructures) {
        Collections.sort(dataStructures, new DataStructureComparator());
    }

    /**
     * See sortStructures() comments.
     * @since 0.8.3
     */
    private static class DataStructureComparator implements Comparator<DataStructure> {
        public int compare(DataStructure l, DataStructure r) {
            return l.calculateHash().toBase64().compareTo(r.calculateHash().toBase64());
        }
    }

    /**
     *  NOTE: formatDuration2() recommended in most cases for readability
     */
    public static String formatDuration(long ms) {
        if (ms < 5 * 1000) {
            return ms + "ms";
        } else if (ms < 3 * 60 * 1000) {
            return (ms / 1000) + "s";
        } else if (ms < 120 * 60 * 1000) {
            return (ms / (60 * 1000)) + "m";
        } else if (ms < 3 * 24 * 60 * 60 * 1000) {
            return (ms / (60 * 60 * 1000)) + "h";
        } else if (ms > 1000l * 24l * 60l * 60l * 1000l) {
            return "n/a";
        } else {
            return (ms / (24 * 60 * 60 * 1000)) + "d";
        }
    }
    
    /**
     * Like formatDuration but with a non-breaking space after the number,
     * 0 is unitless, and the unit is translated.
     * This seems consistent with most style guides out there.
     * Use only in HTML.
     * Thresholds are a little lower than in formatDuration() also,
     * as precision is less important in the GUI than in logging.
     *
     * Negative numbers handled correctly.
     *
     * @since 0.8.2
     */
    public static String formatDuration2(long ms) {
        String t;
        long ams = ms >= 0 ? ms : 0 - ms;
        if (ms == 0) {
            return "0";
        } else if (ams < 3 * 1000) {
            // NOTE TO TRANSLATORS: Feel free to translate all these as you see fit, there are several options...
            // spaces or not, '.' or not, plural or not. Try not to make it too long, it is used in
            // a lot of tables.
            // milliseconds
            // Note to translators, may be negative or zero, 2999 maximum.
            // {0,number,####} prevents 1234 from being output as 1,234 in the English locale.
            // If you want the digit separator in your locale, translate as {0}.
            // alternates: msec, msecs
            t = ngettext("1 ms", "{0,number,####} ms", (int) ms);
        } else if (ams < 2 * 60 * 1000) {
            // seconds
            // alternates: secs, sec. 'seconds' is probably too long.
            t = ngettext("1 sec", "{0} sec", (int) (ms / 1000));
        } else if (ams < 120 * 60 * 1000) {
            // minutes
            // alternates: mins, min. 'minutes' is probably too long.
            t = ngettext("1 min", "{0} min", (int) (ms / (60 * 1000)));
        } else if (ams < 2 * 24 * 60 * 60 * 1000) {
            // hours
            // alternates: hrs, hr., hrs.
            t = ngettext("1 hour", "{0} hours", (int) (ms / (60 * 60 * 1000)));
        } else if (ams > 1000l * 24l * 60l * 60l * 1000l) {
            return _("n/a");
        } else {
            // days
            t = ngettext("1 day", "{0} days", (int) (ms / (24 * 60 * 60 * 1000)));
        }
        // Replace minus sign to work around
        // bug in Chrome (and IE?), line breaks at the minus sign
        // http://code.google.com/p/chromium/issues/detail?id=46683
        // &minus; seems to work on text browsers OK
        // Although it's longer than a standard '-' on graphical browsers
        // http://www.cs.tut.fi/~jkorpela/dashes.html
        if (ms < 0)
            t = t.replace("-", "&minus;");
        // do it here to keep &nbsp; out of the tags for translator sanity
        return t.replace(" ", "&nbsp;");
    }
    
    private static final String BUNDLE_NAME = "net.i2p.router.web.messages";

    private static String _(String key) {
        return Translate.getString(key, I2PAppContext.getGlobalContext(), BUNDLE_NAME);
    }

    private static String ngettext(String s, String p, int n) {
        return Translate.getString(n, s, p, I2PAppContext.getGlobalContext(), BUNDLE_NAME);
    }

    /**
     * Caller should append 'B' or 'b' as appropriate
     * NOTE: formatDuration2() recommended in most cases for readability
     */
    public static String formatSize(long bytes) {
        double val = bytes;
        int scale = 0;
        while (val >= 1024) {
            scale++; 
            val /= 1024;
        }
        
        DecimalFormat fmt = new DecimalFormat("##0.00");

        String str = fmt.format(val);
        switch (scale) {
            case 1: return str + "K";
            case 2: return str + "M";
            case 3: return str + "G";
            case 4: return str + "T";
            case 5: return str + "P";
            case 6: return str + "E";
            case 7: return str + "Z";
            case 8: return str + "Y";
            default: return bytes + "";
        }
    }
    
    /**
     * Like formatSize but with a non-breaking space after the number
     * This seems consistent with most style guides out there.
     * Use only in HTML
     * @since 0.7.14
     */
    public static String formatSize2(long bytes) {
        double val = bytes;
        int scale = 0;
        while (val >= 1024) {
            scale++; 
            val /= 1024;
        }
        
        DecimalFormat fmt = new DecimalFormat("##0.00");

        String str = fmt.format(val);
        switch (scale) {
            case 1: return str + "&nbsp;K";
            case 2: return str + "&nbsp;M";
            case 3: return str + "&nbsp;G";
            case 4: return str + "&nbsp;T";
            case 5: return str + "&nbsp;P";
            case 6: return str + "&nbsp;E";
            case 7: return str + "&nbsp;Z";
            case 8: return str + "&nbsp;Y";
            default: return bytes + "&nbsp;";
        }
    }
    
    /**
     * Strip out any HTML (simply removing any less than / greater than symbols)
     * @param orig may be null, returns empty string if null
     */
    public static String stripHTML(String orig) {
        if (orig == null) return "";
        String t1 = orig.replace('<', ' ');
        String rv = t1.replace('>', ' ');
        return rv;
    }

    private static final String escapeChars[] = {"&", "\"", "<", ">"};
    private static final String escapeCodes[] = {"&amp;", "&quot;", "&lt;", "&gt;"};

    /**
     * Escape a string for inclusion in HTML
     * @param unescaped the unescaped string, may be null
     * @return the escaped string, or null if null is passed in
     */
    public static String escapeHTML(String unescaped) {
        if (unescaped == null) return null;
        String escaped = unescaped;
        for (int i = 0; i < escapeChars.length; i++) {
            escaped = escaped.replaceAll(escapeChars[i], escapeCodes[i]);
        }
        return escaped;
    }

    /**
     * Unescape a string taken from HTML
     * @param escaped the escaped string, may be null
     * @return the unescaped string, or null if null is passed in
     */
/**** unused, uncomment if you need it
    public static String unescapeHTML(String escaped) {
        if (escaped == null) return null;
        String unescaped = escaped;
        for (int i = 0; i < escapeChars.length; i++) {
            unescaped = unescaped.replaceAll(escapeCodes[i], escapeChars[i]);
        }
        return unescaped;
    }
****/

    /** */
    public static final int MAX_UNCOMPRESSED = 40*1024;
    public static final int MAX_COMPRESSION = Deflater.BEST_COMPRESSION;
    public static final int NO_COMPRESSION = Deflater.NO_COMPRESSION;

    /**
     *  Compress the data and return a new GZIP compressed byte array.
     *  @throws IllegalArgumentException if size is over 40KB
     */
    public static byte[] compress(byte orig[]) {
        return compress(orig, 0, orig.length);
    }

    /**
     *  Compress the data and return a new GZIP compressed byte array.
     *  @throws IllegalArgumentException if size is over 40KB
     */
    public static byte[] compress(byte orig[], int offset, int size) {
        return compress(orig, offset, size, MAX_COMPRESSION);
    }

    /**
     *  Compress the data and return a new GZIP compressed byte array.
     *  @throws IllegalArgumentException if size is over 40KB
     */
    public static byte[] compress(byte orig[], int offset, int size, int level) {
        if ((orig == null) || (orig.length <= 0)) return orig;
        if (size > MAX_UNCOMPRESSED) 
            throw new IllegalArgumentException("tell jrandom size=" + size);
        ReusableGZIPOutputStream out = ReusableGZIPOutputStream.acquire();
        out.setLevel(level);
        try {
            out.write(orig, offset, size);
            out.finish();
            out.flush();
            byte rv[] = out.getData();
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Compression of " + orig.length + " into " + rv.length + " (or " + 100.0d
            //               * (((double) orig.length) / ((double) rv.length)) + "% savings)");
            return rv;
        } catch (IOException ioe) {
            // Apache Harmony 5.0M13
            //java.io.IOException: attempt to write after finish
            //at java.util.zip.DeflaterOutputStream.write(DeflaterOutputStream.java:181)
            //at net.i2p.util.ResettableGZIPOutputStream.write(ResettableGZIPOutputStream.java:122)
            //at net.i2p.data.DataHelper.compress(DataHelper.java:1048)
            //   ...
            ioe.printStackTrace();
            return null;
        } finally {
            ReusableGZIPOutputStream.release(out);
        }
        
    }
    
    /**
     *  Decompress the GZIP compressed data (returning null on error).
     *  @throws IOE if uncompressed is over 40 KB
     */
    public static byte[] decompress(byte orig[]) throws IOException {
        return (orig != null ? decompress(orig, 0, orig.length) : null);
    }

    /**
     *  Decompress the GZIP compressed data (returning null on error).
     *  @throws IOE if uncompressed is over 40 KB
     */
    public static byte[] decompress(byte orig[], int offset, int length) throws IOException {
        if ((orig == null) || (orig.length <= 0)) return orig;
        if (offset + length > orig.length)
            throw new IOException("Bad params arrlen " + orig.length + " off " + offset + " len " + length);
        
        ReusableGZIPInputStream in = ReusableGZIPInputStream.acquire();
        in.initialize(new ByteArrayInputStream(orig, offset, length));
        
        // don't make this a static field, or else I2PAppContext gets initialized too early
        ByteCache cache = ByteCache.getInstance(8, MAX_UNCOMPRESSED);
        ByteArray outBuf = cache.acquire();
        int written = 0;
        while (true) {
            int read = in.read(outBuf.getData(), written, MAX_UNCOMPRESSED-written);
            if (read == -1)
                break;
            written += read;
            if (written >= MAX_UNCOMPRESSED) {
                if (in.available() > 0)
                    throw new IOException("Uncompressed data larger than " + MAX_UNCOMPRESSED);
                break;
            }
        }
        byte rv[] = new byte[written];
        System.arraycopy(outBuf.getData(), 0, rv, 0, written);
        cache.release(outBuf);
        // TODO release in finally block
        ReusableGZIPInputStream.release(in);
        return rv;
    }

    /**
     *  Same as orig.getBytes("UTF-8") but throws an unchecked RuntimeException
     *  instead of an UnsupportedEncodingException if no UTF-8, for ease of use.
     *
     *  @return null if orig is null
     *  @throws RuntimeException
     */
    public static byte[] getUTF8(String orig) {
        if (orig == null) return null;
        try {
            return orig.getBytes("UTF-8");
        } catch (UnsupportedEncodingException uee) {
            throw new RuntimeException("no utf8!?");
        }
    }

    /**
     *  Same as orig.getBytes("UTF-8") but throws an unchecked RuntimeException
     *  instead of an UnsupportedEncodingException if no UTF-8, for ease of use.
     *
     *  @return null if orig is null
     *  @throws RuntimeException
     *  @deprecated unused
     */
    public static byte[] getUTF8(StringBuffer orig) {
        if (orig == null) return null;
        return getUTF8(orig.toString());
    }

    /**
     *  Same as new String(orig, "UTF-8") but throws an unchecked RuntimeException
     *  instead of an UnsupportedEncodingException if no UTF-8, for ease of use.
     *  Used by Syndie.
     *
     *  @return null if orig is null
     *  @throws RuntimeException
     */
    public static String getUTF8(byte orig[]) {
        if (orig == null) return null;
        try {
            return new String(orig, "UTF-8");
        } catch (UnsupportedEncodingException uee) {
            throw new RuntimeException("no utf8!?");
        }
    }

    /**
     *  Same as new String(orig, "UTF-8") but throws an unchecked RuntimeException
     *  instead of an UnsupportedEncodingException if no UTF-8, for ease of use.
     *
     *  @return null if orig is null
     *  @throws RuntimeException
     *  @deprecated unused
     */
    public static String getUTF8(byte orig[], int offset, int len) {
        if (orig == null) return null;
        try {
            return new String(orig, offset, len, "UTF-8");
        } catch (UnsupportedEncodingException uee) {
            throw new RuntimeException("no utf8!?");
        }
    }

    /**
     *  Roughly the same as orig.getBytes("ISO-8859-1") but much faster and
     *  will not throw an exception.
     *
     *  @param orig non-null, must be 7-bit chars
     *  @since 0.9.5
     */
    public static byte[] getASCII(String orig) {
        byte[] rv = new byte[orig.length()];
        for (int i = 0; i < rv.length; i++) {
            rv[i] = (byte)orig.charAt(i);
        }
        return rv;
    }
}
