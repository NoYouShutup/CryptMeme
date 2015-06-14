package cryptmeme

import net.i2p.I2PAppContext
import net.i2p.client.streaming.I2PSocketManager
import net.i2p.client.streaming.I2PSocketManagerFactory
import net.i2p.data.Destination
import net.i2p.i2ptunnel.I2PTunnel
import net.i2p.i2ptunnel.I2PTunnelClientBase
import net.i2p.i2ptunnel.I2PTunnelServer
import net.i2p.i2ptunnel.TunnelController
import org.springframework.beans.factory.InitializingBean;

/**
 * This is the grails service that runs the I2P tunnel. It redirects any internal requests to the
 * locally running grails instance. It also runs a local proxy for outgoing HTTP requests, so in this
 * manner one could easily just use CryptMeme as a means to browse the I2P network.
 *
 * @author jmorgan
 *
 */
class I2PTunnelService implements InitializingBean {

	// properties for the tunnel service, change with care
	public static final String TUNNEL_TYPE = "httpserver";
	public static final String TUNNEL_TARGET_HOST = "127.0.0.1";
	public static final String TUNNEL_TARGET_PORT = "8080";
	public static final String TUNNEL_PRIV_KEY_FILE = "eepPriv.dat";

	static scope = "singleton";

	/**
	 * The tunnel controller object used for CryptMeme's built-in I2P tunnels
	 */
	public TunnelController controller;

	/**
	 * The local instance's I2P destination hash
	 */
	public static String me;

	/**
	 * Sets up the I2P tunnel service.
	 * All this really does is create an I2P endpoint that forwards all requests to the local grails instance.
	 * Theoretically, one could use this to keep a cryptmeme instance running and access it from any web browser
	 * through I2P, as long as the instance was actually running.
	 *
	 *
	 *commented out because I don't think this is how we should do things.
	public void afterPropertiesSet() throws Exception {
		// set up the I2P tunnel service.
		Properties props = new Properties();

		props.setProperty("type", TUNNEL_TYPE);
		props.setProperty("targetHost", TUNNEL_TARGET_HOST);
		props.setProperty("targetPort", TUNNEL_TARGET_PORT);
		props.setProperty("privKeyFile", TUNNEL_PRIV_KEY_FILE);

		controller = new TunnelController(props,"cm");

		this.me = controller.getMyDestination();

		Runtime.runtime.addShutdownHook {
			try {
				controller.stopTunnel();
			} catch (Exception e) {
				println "Error trying to shut down the tunnel service: " + e.getMessage()
			}
		}
	} */
	public void afterPropertiesSet() throws Exception {
		
	}

	def serviceMethod() {

	}
}
