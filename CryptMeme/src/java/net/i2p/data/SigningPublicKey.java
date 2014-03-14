package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import net.i2p.crypto.SigType;

/**
 * Defines the SigningPublicKey as defined by the I2P data structure spec.
 * A signing public key is by default 128 byte Integer. The public key represents only the 
 * exponent, not the primes, which are constant and defined in the crypto spec.
 * This key varies from the PrivateKey in its usage (verifying signatures, not encrypting)
 *
 * As of release 0.9.8, keys of arbitrary length and type are supported.
 * See SigType.
 *
 * @author jrandom
 */
public class SigningPublicKey extends SimpleDataStructure {
    private static final SigType DEF_TYPE = SigType.DSA_SHA1;
    public final static int KEYSIZE_BYTES = DEF_TYPE.getPubkeyLen();
    private static final int CACHE_SIZE = 1024;

    private static final SDSCache<SigningPublicKey> _cache = new SDSCache<SigningPublicKey>(SigningPublicKey.class, KEYSIZE_BYTES, CACHE_SIZE);

    private final SigType _type;

    /**
     * Pull from cache or return new.
     * Deprecated - used only by deprecated Destination.readBytes(data, off)
     *
     * @throws AIOOBE if not enough bytes, FIXME should throw DataFormatException
     * @since 0.8.3
     */
    public static SigningPublicKey create(byte[] data, int off) {
        return _cache.get(data, off);
    }

    /**
     * Pull from cache or return new
     * @since 0.8.3
     */
    public static SigningPublicKey create(InputStream in) throws IOException {
        return _cache.get(in);
    }

    public SigningPublicKey() {
        this(DEF_TYPE);
    }

    /**
     *  @param type if null, type is unknown
     *  @since 0.9.8
     */
    public SigningPublicKey(SigType type) {
        super();
        _type = type;
    }

    public SigningPublicKey(byte data[]) {
        this(DEF_TYPE, data);
    }

    /**
     *  @param type if null, type is unknown
     *  @since 0.9.8
     */
    public SigningPublicKey(SigType type, byte data[]) {
        super();
        _type = type;
        if (type != null || data == null)
            setData(data);
        else
            _data = data;  // bypass length check
    }

    /** constructs from base64
     * @param base64Data a string of base64 data (the output of .toBase64() called
     * on a prior instance of SigningPublicKey
     */
    public SigningPublicKey(String base64Data)  throws DataFormatException {
        this();
        fromBase64(base64Data);
    }

    /**
     *  @return if type unknown, the length of the data, or 128 if no data
     */
    public int length() {
        if (_type != null)
            return _type.getPubkeyLen();
        if (_data != null)
            return _data.length;
        return KEYSIZE_BYTES;
    }

    /**
     *  @return null if unknown
     *  @since 0.9.8
     */
    public SigType getType() {
        return _type;
    }

    /**
     *  Up-convert this from an untyped (type 0) SPK to a typed SPK based on the Key Cert given
     *
     *  @throws IllegalArgumentException if this is already typed to a different type
     *  @since 0.9.12
     */
    public SigningPublicKey toTypedKey(KeyCertificate kcert) {
        if (_data == null)
            throw new IllegalStateException();
        SigType newType = kcert.getSigType();
        if (_type == newType)
            return this;
        if (_type != SigType.DSA_SHA1)
            throw new IllegalArgumentException("Cannot convert " + _type + " to " + newType);
        int newLen = newType.getPubkeyLen();
        if (newLen == SigType.DSA_SHA1.getPubkeyLen())
            return new SigningPublicKey(newType, _data);
        byte[] newData = new byte[newLen];
        if (newLen < SigType.DSA_SHA1.getPubkeyLen()) {
            // right-justified
            System.arraycopy(_data, _data.length - newLen, newData, 0, newLen);
        } else {
            // full 128 bytes + fragment in kcert
            System.arraycopy(_data, 0, newData, 0, _data.length);
            System.arraycopy(kcert.getPayload(), KeyCertificate.HEADER_LENGTH, newData, _data.length, newLen - _data.length);
        }
        return new SigningPublicKey(newType, newData);
    }

    /**
     *  Get the portion of this (type 0) SPK that is really padding based on the Key Cert type given,
     *  if any
     *
     *  @return leading padding length > 0 or null
     *  @throws IllegalArgumentException if this is already typed to a different type
     *  @since 0.9.12
     */
    public byte[] getPadding(KeyCertificate kcert) {
        if (_data == null)
            throw new IllegalStateException();
        SigType newType = kcert.getSigType();
        if (_type == newType)
            return null;
        if (_type != SigType.DSA_SHA1)
            throw new IllegalStateException("Cannot convert " + _type + " to " + newType);
        int newLen = newType.getPubkeyLen();
        if (newLen >= SigType.DSA_SHA1.getPubkeyLen())
            return null;
        int padLen = SigType.DSA_SHA1.getPubkeyLen() - newLen;
        byte[] pad = new byte[padLen];
        System.arraycopy(_data, 0, pad, 0, padLen);
        return pad;
    }
    
    /**
     *  Write the data up to a max of 128 bytes.
     *  If longer, the rest will be written in the KeyCertificate.
     *  @since 0.9.12
     */
    public void writeTruncatedBytes(OutputStream out) throws DataFormatException, IOException {
        if (_type.getPubkeyLen() <= KEYSIZE_BYTES)
            out.write(_data);
        else
            out.write(_data, 0, KEYSIZE_BYTES);
    }

    /**
     *  @since 0.9.8
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append('[').append(getClass().getSimpleName()).append(' ').append(_type).append(": ");
        if (_data == null) {
            buf.append("null");
        } else {
            buf.append("size: ").append(Integer.toString(length()));
        }
        buf.append(']');
        return buf.toString();
    }
}