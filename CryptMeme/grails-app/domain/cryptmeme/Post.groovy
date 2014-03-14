package cryptmeme

/**
 * The Post object represents a post, much like a twitter tweet or a status update.
 * It is stored as a binary array so that in the future things other than text or
 * pictures can be added.
 * 
 * @author jmorgan
 *
 */
class Post {
	// the types of posts possible, I assume there will be more in the future.
	// perhaps one could even post an I2P torrent!
	public static final String POST_TYPE_TEXT = "text";
	public static final String POST_TYPE_PICTURE = "picture";
	
	/**
	 * Whether or not the post is public. If it is public, it is served as a PGP-signed message
	 */
	public boolean isPublic;
	
	/**
	 * The time that the post was posted. We have to just trust that this information is correct
	 * when we get it from another person, since they could just lie about it anyway.
	 * They are, after all, running the server themselves (as they should be, it's their data!)
	 */
	public Date timeStamp;
	
	/**
	 * The person that created the post.
	 */
	public Person person;
	
	/**
	 * The post type, to be determined
	 */
	public String postType;
	
	/**
	 * The actual data for the post, to be handled depending on the type
	 */
	public byte[] postData;
}
