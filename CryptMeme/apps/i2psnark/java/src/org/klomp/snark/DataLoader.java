package org.klomp.snark;

import net.i2p.data.ByteArray;

/**
 * Callback used to fetch data
 * @since 0.8.2
 */
interface DataLoader
{
  /**
   *  This is the callback that PeerConnectionOut calls to get the data from disk
   *  @return bytes or null for errors
   */
    public ByteArray loadData(int piece, int begin, int length);
}
