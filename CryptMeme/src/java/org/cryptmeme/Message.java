package org.cryptmeme;

import java.security.PublicKey;

import net.i2p.data.Destination;

public class Message {
	public Destination destination;
	public PublicKey publicKey;
	public byte[] data;
	public byte[] signature;
	public boolean encrypted;
}
