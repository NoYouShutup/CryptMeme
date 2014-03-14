package net.i2p.client.datagram;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.IOException;

import net.i2p.I2PAppContext;
import net.i2p.crypto.DSAEngine;
import net.i2p.crypto.SHA256Generator;
import net.i2p.crypto.SigType;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.Signature;
import net.i2p.util.Log;

/**
 * Class for dissecting I2P repliable datagrams, checking the authenticity of
 * the sender.  Note that objects of this class are NOT THREAD SAFE!
 *
 * @author human
 */
public final class I2PDatagramDissector {

    private static final int DGRAM_BUFSIZE = 32768;

    private final DSAEngine dsaEng = DSAEngine.getInstance();
    private final SHA256Generator hashGen = SHA256Generator.getInstance();

    private Hash rxHash;

    private Signature rxSign;

    private Destination rxDest;

    private final byte[] rxPayload = new byte[DGRAM_BUFSIZE];

    private int rxPayloadLen;
    
    private boolean valid;

    /**
     * Crate a new I2P repliable datagram dissector.
     */
    public I2PDatagramDissector() { // nop
    }

    /**
     * Load an I2P repliable datagram into the dissector.
     * Does NOT verify the signature.
     *
     * @param dgram non-null I2P repliable datagram to be loaded
     *
     * @throws DataFormatException If there's an error in the datagram format
     */
    public void loadI2PDatagram(byte[] dgram) throws DataFormatException {
        ByteArrayInputStream dgStream = new ByteArrayInputStream(dgram);
        // set invalid(very important!)
        this.valid = false;
        
        try {
            // read destination
            rxDest = Destination.create(dgStream);
            SigType type = rxDest.getSigningPublicKey().getType();
            if (type == null)
                throw new DataFormatException("unsupported sig type");
            rxSign = new Signature(type);
            // read signature
            rxSign.readBytes(dgStream);
            
            // read payload
            rxPayloadLen = dgStream.read(rxPayload);
            
            // calculate the hash of the payload
            this.rxHash = hashGen.calculateHash(rxPayload, 0, rxPayloadLen);
            assert this.hashGen.calculateHash(this.extractPayload()).equals(this.rxHash);
        } catch (IOException e) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(I2PDatagramDissector.class);
            log.error("Caught IOException - INCONSISTENT STATE!", e);
        } catch(AssertionError e) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(I2PDatagramDissector.class);
            log.error("Assertion failed!", e);
        }
        
        //_log.debug("Datagram payload size: " + rxPayloadLen + "; content:\n"
        //           + HexDump.dump(rxPayload, 0, rxPayloadLen));
    }
    
    /**
     * Get the payload carried by an I2P repliable datagram (previously loaded
     * with the loadI2PDatagram() method), verifying the datagram signature.
     *
     * @return A byte array containing the datagram payload
     *
     * @throws I2PInvalidDatagramException if the signature verification fails
     */
    public byte[] getPayload() throws I2PInvalidDatagramException {
        this.verifySignature();
        
        return this.extractPayload();
    }
    
    /**
     * Get the sender of an I2P repliable datagram (previously loaded with the
     * loadI2PDatagram() method), verifying the datagram signature.
     *
     * @return The Destination of the I2P repliable datagram sender
     *
     * @throws I2PInvalidDatagramException if the signature verification fails
     */
    public Destination getSender() throws I2PInvalidDatagramException {
        this.verifySignature();
        
        return this.extractSender();
    }
    
    /**
     * Extract the hash of the payload of an I2P repliable datagram (previously
     * loaded with the loadI2PDatagram() method), verifying the datagram
     * signature.
     * @return The hash of the payload of the I2P repliable datagram
     * @throws I2PInvalidDatagramException if the signature verification fails
     */
    public Hash getHash() throws I2PInvalidDatagramException {
        // make sure it has a valid signature
        this.verifySignature();
        
        return this.extractHash();
    }
    
    /**
     * Extract the payload carried by an I2P repliable datagram (previously
     * loaded with the loadI2PDatagram() method), without verifying the
     * datagram signature.
     *
     * @return A byte array containing the datagram payload
     */
    public byte[] extractPayload() {
        byte[] retPayload = new byte[this.rxPayloadLen];
        System.arraycopy(this.rxPayload, 0, retPayload, 0, this.rxPayloadLen);
        
        return retPayload;
    }
    
    /**
     * Extract the sender of an I2P repliable datagram (previously loaded with
     * the loadI2PDatagram() method), without verifying the datagram signature.
     *
     * @return The Destination of the I2P repliable datagram sender
     */
    public Destination extractSender() {
      /****
        if (this.rxDest == null)
            return null;
        Destination retDest = new Destination();
        try {
            retDest.fromByteArray(this.rxDest.toByteArray());
        } catch (DataFormatException e) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(I2PDatagramDissector.class);
            log.error("Caught DataFormatException", e);
            return null;
        }
        
        return retDest;
      ****/
        // dests are no longer modifiable
        return rxDest;
    }
    
    /**
     * Extract the hash of the payload of an I2P repliable datagram (previously
     * loaded with the loadI2PDatagram() method), without verifying the datagram
     * signature.
     * @return The hash of the payload of the I2P repliable datagram
     */
    public Hash extractHash() {
        return this.rxHash;
    }
    
    /**
     * Verify the signature of this datagram (previously loaded with the
     * loadI2PDatagram() method)
     * @throws I2PInvalidDatagramException if the signature is invalid
     */
    public void verifySignature() throws I2PInvalidDatagramException {
        // first check if it already got validated
        if(this.valid)
            return;
        
        if (rxSign == null || rxSign.getData() == null || rxDest == null || rxDest.getSigningPublicKey() == null)
            throw new I2PInvalidDatagramException("Datagram not yet read");
        
        // now validate
        if (!this.dsaEng.verifySignature(rxSign, rxHash.getData(), rxDest.getSigningPublicKey()))
            throw new I2PInvalidDatagramException("Incorrect I2P repliable datagram signature");
        
        // set validated
        this.valid = true;
    }
}
