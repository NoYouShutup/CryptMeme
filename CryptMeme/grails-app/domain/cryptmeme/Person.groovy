package cryptmeme

import net.i2p.data.Destination

/**
 * This object represents a Person, or more accurately an instance of CryptMeme.
 * It is essentially identified by an I2P destination hash (think of it like the public
 * address of a bitcoin wallet, it's basically the same thing) and a PGP key.
 * The PGP key is used to identify themselves to another person, whereas the
 * destination hash is used to contact them over the I2P network.
 * 
 * @author jmorgan
 *
 */
class Person {
	/**
	 * These are PGP keys that are separate from the encryption that I2P uses.
	 * Theoretically somebody could get your public I2P key (or address) and try
	 * to request data, posting a bunk public key. For this reason the data is
	 * encrypted even before it hits the I2P network.
	 */
	public CryptMemeKeyPair keyPair;
	
	/**
	 * I may add some constraints around the nickName in the future, but for now it's just
	 * a string (that is until jackasses inevitably abuse it and break shit)
	 */
	public String nickName;
	
	/**
	 * This is the destination hash for the person's CryptMeme instance.
	 * Stored as a string so that when I2P gets updated it will be (theoretically) easy
	 * to update.
	 * We have to assume that a user's i2pDestination never actually changes. For
	 * all practical purposes, this is really the only real piece of identifying data
	 * we have for them other than their keyPair.
	 */
	public String i2pDestination;
	
	/**
	 * Returns true if this person is yourself. One would think this would be ID=0 but
	 * I'd much rather GORM take care of that instead of trusting that it's alwasy ID=0
	 * 
	 * @return true if the person instance is the owner of the running CryptMeme instance
	 */
	public boolean isMe() {
		return i2pDestination.equals(I2PTunnelService.me);
	}
}
