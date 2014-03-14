package net.i2p.util;

import java.io.IOException;
import java.io.Writer;

import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.Hash;
import net.i2p.data.SessionKey;

/**
 *  simple
 */
public class KeyRing extends ConcurrentHashMap<Hash, SessionKey> {
    public KeyRing() {
        super(0);
    }

    public void renderStatusHTML(Writer out) throws IOException {}
}
