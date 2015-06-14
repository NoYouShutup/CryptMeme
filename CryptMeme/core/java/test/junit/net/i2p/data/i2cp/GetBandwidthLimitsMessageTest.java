package net.i2p.data.i2cp;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by str4d in 2012 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.StructureTest;
import net.i2p.data.DataStructure;
import net.i2p.data.DataFormatException;

/**
 * Test harness for loading / storing GetBandwidthLimitsMessage objects
 *
 * @author str4d
 */
public class GetBandwidthLimitsMessageTest extends StructureTest {
    public DataStructure createDataStructure() throws DataFormatException {
        GetBandwidthLimitsMessage msg = new GetBandwidthLimitsMessage();
        return msg;
    }
    public DataStructure createStructureToRead() { return new GetBandwidthLimitsMessage(); }
}
