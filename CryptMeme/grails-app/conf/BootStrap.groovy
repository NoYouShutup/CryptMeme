import net.i2p.router.Router

class BootStrap {
	def I2PClientService
	
	public static Router router;
	
	def init = {
		println "In BootStrap. Initializing I2P...\n";
		I2PClientService.init();
	}
	def destroy = {
		router.shutdownGracefully();
	}
}
