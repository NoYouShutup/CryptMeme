package net.i2p.data.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;

import net.i2p.data.Certificate;
import net.i2p.data.CertificateTest;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataStructure;
import net.i2p.data.PublicKey;
import net.i2p.data.PublicKeyTest;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SigningPublicKeyTest;
import net.i2p.data.StructureTest;

/**
 * Test harness for loading / storing Hash objects
 *
 * @author jrandom
 */
public class RouterIdentityTest extends StructureTest {
    public DataStructure createDataStructure() throws DataFormatException {
        RouterIdentity ident = new RouterIdentity();
        Certificate cert = (Certificate)(new CertificateTest()).createDataStructure();
        ident.setCertificate(cert);
        PublicKey pk = (PublicKey)(new PublicKeyTest()).createDataStructure();
        ident.setPublicKey(pk);
        SigningPublicKey k = (SigningPublicKey)(new SigningPublicKeyTest()).createDataStructure();
        ident.setSigningPublicKey(k);
        return ident;
    }
    public DataStructure createStructureToRead() { return new RouterIdentity(); }
    
    public void testNullCert() throws Exception{
        RouterIdentity ident = new RouterIdentity();
        ident.setCertificate(null);
        PublicKey pk = (PublicKey)(new PublicKeyTest()).createDataStructure();
        ident.setPublicKey(pk);
        SigningPublicKey k = (SigningPublicKey)(new SigningPublicKeyTest()).createDataStructure();
        ident.setSigningPublicKey(k);
        
        boolean error = false;
        try{
            ident.writeBytes(new ByteArrayOutputStream());
        }catch(DataFormatException dfe){
            error = true;
        }
        assertTrue(error);
    }
    
    public void testNullPublicKey() throws Exception{
        RouterIdentity ident = new RouterIdentity();
        Certificate cert = (Certificate)(new CertificateTest()).createDataStructure();
        ident.setCertificate(cert);
        ident.setPublicKey(null);
        SigningPublicKey k = (SigningPublicKey)(new SigningPublicKeyTest()).createDataStructure();
        ident.setSigningPublicKey(k);
        
        boolean error = false;
        try{
            ident.writeBytes(new ByteArrayOutputStream());
        }catch(DataFormatException dfe){
            error = true;
        }
        assertTrue(error);
        
    }
    
    public void testNullSigningKey() throws Exception{
        RouterIdentity ident = new RouterIdentity();
        Certificate cert = (Certificate)(new CertificateTest()).createDataStructure();
        ident.setCertificate(cert);
        PublicKey pk = (PublicKey)(new PublicKeyTest()).createDataStructure();
        ident.setPublicKey(pk);
        ident.setSigningPublicKey(null);
        
        boolean error = false;
        try{
            ident.writeBytes(new ByteArrayOutputStream());
        }catch(DataFormatException dfe){
            error = true;
        }
        assertTrue(error);
    }
    
    public void testNullEquals() throws Exception{
        RouterIdentity ident = new RouterIdentity();
        assertFalse(ident.equals(null));
    }
    
    public void testCalculatedHash() throws Exception{
        RouterIdentity ident = new RouterIdentity();
        Certificate cert = (Certificate)(new CertificateTest()).createDataStructure();
        ident.setCertificate(cert);
        PublicKey pk = (PublicKey)(new PublicKeyTest()).createDataStructure();
        ident.setPublicKey(pk);
        SigningPublicKey k = (SigningPublicKey)(new SigningPublicKeyTest()).createDataStructure();
        ident.setSigningPublicKey(k);
        
        ident.calculateHash();
        ident.calculateHash();
        ident.calculateHash();
        ident.calculateHash();
        ident.calculateHash();
    }
    
    public void testBadHash() throws Exception {
        RouterIdentity ident = new RouterIdentity();
        boolean error = false;
        try {
            ident.getHash();
        } catch (IllegalStateException ise) {
            error = true;
        }
        assertTrue(error);
    }
    
    
}
