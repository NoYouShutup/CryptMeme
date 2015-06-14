package net.i2p.client.streaming.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

import org.junit.Before;
import org.junit.Test;

import junit.framework.TestCase;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * Stress test the MessageInputStream
 */
public class MessageInputStreamTest extends TestCase {
    private I2PAppContext _context;
    private Log _log;
    private ConnectionOptions _options;
    
    @Before
    public void setUp() {
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(MessageInputStreamTest.class);
        _options = new ConnectionOptions();
    }
    
    @Test
    public void testInOrder() {
        byte orig[] = new byte[256*1024];
        _context.random().nextBytes(orig);
        
        MessageInputStream in = new MessageInputStream(_context, _options.getMaxMessageSize(),
                                                       _options.getMaxWindowSize(), _options.getInboundBufferSize());
        for (int i = 0; i < orig.length / 1024; i++) {
            byte msg[] = new byte[1024];
            System.arraycopy(orig, i*1024, msg, 0, 1024);
            in.messageReceived(i, new ByteArray(msg));
        }
        
        byte read[] = new byte[orig.length];
        try {
            int howMany = DataHelper.read(in, read);
            if (howMany != orig.length)
                throw new RuntimeException("Failed test: not enough bytes read [" + howMany + "]");
            if (!DataHelper.eq(orig, read))
                throw new RuntimeException("Failed test: data read is not equal");
            
            _log.info("Passed test: in order");
        } catch (IOException ioe) {
            throw new RuntimeException("IOError reading: " + ioe.getMessage());
        }
    }
    
    @Test
    public void testRandomOrder() {
        byte orig[] = new byte[256*1024];
        _context.random().nextBytes(orig);
        
        MessageInputStream in = new MessageInputStream(_context, _options.getMaxMessageSize(),
                                                       _options.getMaxWindowSize(), _options.getInboundBufferSize());
        ArrayList<Integer> order = new ArrayList<Integer>(32);
        for (int i = 0; i < orig.length / 1024; i++)
            order.add(new Integer(i));
        Collections.shuffle(order);
        for (int i = 0; i < orig.length / 1024; i++) {
            byte msg[] = new byte[1024];
            Integer cur = (Integer)order.get(i);
            System.arraycopy(orig, cur.intValue()*1024, msg, 0, 1024);
            in.messageReceived(cur.intValue(), new ByteArray(msg));
            _log.debug("Injecting " + cur);
        }
        
        byte read[] = new byte[orig.length];
        try {
            int howMany = DataHelper.read(in, read);
            if (howMany != orig.length)
                throw new RuntimeException("Failed test: not enough bytes read [" + howMany + "]");
            if (!DataHelper.eq(orig, read))
                throw new RuntimeException("Failed test: data read is not equal");
            
            _log.info("Passed test: random order");
        } catch (IOException ioe) {
            throw new RuntimeException("IOError reading: " + ioe.getMessage());
        }
    }
    
    @Test
    public void testRandomDups() {
        byte orig[] = new byte[256*1024];
        _context.random().nextBytes(orig);
        
        MessageInputStream in = new MessageInputStream(_context, _options.getMaxMessageSize(),
                                                       _options.getMaxWindowSize(), _options.getInboundBufferSize());
        for (int n = 0; n < 3; n++) {
            ArrayList<Integer> order = new ArrayList<Integer>(32);
            for (int i = 0; i < orig.length / 1024; i++)
                order.add(new Integer(i));
            Collections.shuffle(order);
            for (int i = 0; i < orig.length / 1024; i++) {
                byte msg[] = new byte[1024];
                Integer cur = (Integer)order.get(i);
                System.arraycopy(orig, cur.intValue()*1024, msg, 0, 1024);
                in.messageReceived(cur.intValue(), new ByteArray(msg));
                _log.debug("Injecting " + cur);
            }
        }
        
        byte read[] = new byte[orig.length];
        try {
            int howMany = DataHelper.read(in, read);
            if (howMany != orig.length)
                throw new RuntimeException("Failed test: not enough bytes read [" + howMany + "]");
            if (!DataHelper.eq(orig, read))
                throw new RuntimeException("Failed test: data read is not equal");
            
            _log.info("Passed test: random dups");
        } catch (IOException ioe) {
            throw new RuntimeException("IOError reading: " + ioe.getMessage());
        }
    }
    
    @Test
    public void testStaggered() {
        byte orig[] = new byte[256*1024];
        byte read[] = new byte[orig.length];
        _context.random().nextBytes(orig);
        
        MessageInputStream in = new MessageInputStream(_context, _options.getMaxMessageSize(),
                                                       _options.getMaxWindowSize(), _options.getInboundBufferSize());
        ArrayList<Integer> order = new ArrayList<Integer>(32);
        for (int i = 0; i < orig.length / 1024; i++)
            order.add(new Integer(i));
        Collections.shuffle(order);
        
        int offset = 0;
        for (int i = 0; i < orig.length / 1024; i++) {
            byte msg[] = new byte[1024];
            Integer cur = (Integer)order.get(i);
            System.arraycopy(orig, cur.intValue()*1024, msg, 0, 1024);
            in.messageReceived(cur.intValue(), new ByteArray(msg));
            _log.debug("Injecting " + cur);
            
            try {
                if (in.available() > 0) {
                    int curRead = in.read(read, offset, read.length-offset);
                    _log.debug("read " + curRead);
                    if (curRead == -1)
                        throw new RuntimeException("EOF with offset " + offset);
                    else
                        offset += curRead;
                }
            } catch (IOException ioe) {
                throw new RuntimeException("IOE: " + ioe.getMessage());
            }
        }
        
        if (!DataHelper.eq(orig, read))
            throw new RuntimeException("Failed test: data read is not equal");

        _log.info("Passed test: staggered");
    }
}
