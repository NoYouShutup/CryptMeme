package cryptmeme

import net.i2p.client.I2PSession
import net.i2p.data.Destination

class PostController {

	def I2PClientService

	def index() { }
	
	def sendTest() {
		def friendId = params["friendId"]
		
		Destination dest = new Destination(friendId);
		((I2PSession)I2PClientService.session).sendMessage(dest, new String("Test message").getBytes());
		render "Message sent";
	}
}
