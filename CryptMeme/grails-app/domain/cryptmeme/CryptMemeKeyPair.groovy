package cryptmeme

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Date;
import java.util.Iterator;
import javax.crypto.Cipher
import javax.xml.bind.annotation.adapters.HexBinaryAdapter
import net.i2p.data.Base64
import org.cryptmeme.exception.CryptMemeEncryptionException

/**
 * A public and private PGP key, and a passPhrase (in case it's owned by this user's instance),
 * used to pass allowed content between contacts.
 * 
 * In CryptMeme, all data is encrypted or signed before it even hits the I2P network. The reason for this is
 * because we want to make sure that the user who requests information (be it pictures, updates or otherwise)
 * is actually the person they say they are (we don't want people who are "not on the list" to be able to
 * read private posts, or make public posts claiming they are somebody they are not).
 * 
 * @author jmorgan
 *
 */
class CryptMemeKeyPair {

	static belongsTo = [person:Person];

	public PrivateKey privateKey;

	public PublicKey publicKey;

	public Date timeStamp;

	/**
	 * Decrypts the passed-in byte array based on the private key in this keypair
	 * @param encrypted
	 * @return
	 */
	public byte[] decrypt(byte[] encryptedData) throws CryptMemeEncryptionException {
		if (this.privateKey == null) {
			throw new CryptMemeEncryptionException("Cannot decrypt: no private key defined for KeyPair ID:" + this.id);
		}

		Cipher cipher = Cipher.getInstance("RSA");
		cipher.init(Cipher.DECRYPT_MODE, this.privateKey);
		return cipher.doFinal(encryptedData);
	}

	/**
	 * Encrypts the passed in byte array based on the public key in this keypair
	 * @param clearData
	 * @return
	 * @throws CryptMemeEncryptionException
	 */
	public byte[] encrypt(byte[] clearData) throws CryptMemeEncryptionException {
		if (this.publicKey == null) {
			throw new CryptMemeEncryptionException("Cannot encrypt: no public key defined for KeyPair ID:" + this.id);
		}

		Cipher cipher = Cipher.getInstance("RSA");
		cipher.init(Cipher.ENCRYPT_MODE, this.publicKey);
		return cipher.doFinal(clearData);
	}

	/**
	 * Generates a new keyring and populates the properties of this object appropriately.
	 * All the properties must be blank or null in order for this to work (we don't want
	 * it to get called accidentally)
	 */
	public void generateNewKeys() {
		if (this.privateKey != null || this.publicKey != null) {
			return;
		}
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		SecureRandom random = SecureRandom.getInstance("SHA1PRNG", "SUN");
		kpg.initialize(1024, random);
		KeyPair pair = kpg.generateKeyPair();

		this.publicKey = pair.getPublic();
		this.privateKey = pair.getPrivate();

		this.timeStamp = new Date(System.currentTimeMillis());
	}

	/**
	 * Returns the public key as a byte array (to share with the public)
	 * @return
	 */
	public String getPublicKey() {
		if (this.publicKey != null) {
			KeyFactory keyFactory = KeyFactory.getInstance("RSA");
			RSAPublicKeySpec rsaPubKeySpec = keyFactory.getKeySpec(publicKey, RSAPublicKeySpec.class);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(baos));
			oos.writeObject(rsaPubKeySpec.getModulus());
			oos.writeObject(rsaPubKeySpec.getPublicExponent());
			oos.flush();
			return toBase64(baos.toByteArray());
		}
		return null;
	}

	/**
	 * Sets the public key for this pair from the output of getPublicKey()
	 * @param pubKey
	 */
	public void setPublicKey(String pubKey) throws Exception {
		ByteArrayInputStream bais = new ByteArrayInputStream(toBytes(pubKey));
		ObjectInputStream ois = new ObjectInputStream(bais);
		BigInteger modulus = (BigInteger) ois.readObject();
		BigInteger exponent = (BigInteger) ois.readObject();
		RSAPublicKeySpec rsaPublicKeySpec = new RSAPublicKeySpec(modulus, exponent);
		KeyFactory fact = KeyFactory.getInstance("RSA");
		this.publicKey = fact.generatePublic(rsaPublicKeySpec);
	}
	
	/**
	 * Creates a signature based on the private key and message for this key pair
	 * @param message
	 * @return
	 */
	public byte[] sign(byte[] message) {
		Signature sig = Signature.getInstance("SHA256withRSA","SunRsaSign");
		sig.initSign(this.privateKey);
		sig.update(message);
		return sig.sign();
	}
	
	/**
	 * Verifies the message based on the public key in this key pair
	 * @param message
	 * @param signature
	 * @return true if message is authentic
	 */
	public boolean verify(byte[] message, byte[] signature) {
		Signature sig = Signature.getInstance("SHA256withRSA","SunRsaSign");
		sig.initVerify(this.publicKey);
		sig.update(message);
		return sig.verify(signature);
	}

	/**
	 * Converts a byte array to a hex string
	 * @param bytes
	 * @return
	 */
	private String toBase64(byte[] bytes) {
		return Base64.encode(bytes,true);
	}

	/**
	 * Converts a hex string into a byte array
	 * @param hex
	 * @return
	 */
	private byte[] toBytes(String base64) {
		return Base64.decode(base64)
	}
}
