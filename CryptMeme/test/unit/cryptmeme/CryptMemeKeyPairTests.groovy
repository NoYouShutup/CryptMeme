package cryptmeme



import java.security.Security;

import grails.test.mixin.*

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.*

/**
 * See the API for {@link grails.test.mixin.domain.DomainClassUnitTestMixin} for usage instructions
 */
@TestFor(CryptMemeKeyPair)
class CryptMemeKeyPairTests {

	void testSomething() {
		Security.addProvider(new BouncyCastleProvider());
		
		CryptMemeKeyPair test = new CryptMemeKeyPair();
		test.generateNewKeys();
		
		byte[] encrypted = test.encrypt("This is a test!".getBytes());
		print "Encrypted:\n" + new String(encrypted);
		
		print "\n\nPublic Key:\n" + test.getPublicKey();
		
		String decrypted = new String(test.decrypt(encrypted));
		print "\n\nDecrypted:\n" + decrypted;
		
		assert decrypted == "This is a test!";
	}
}
