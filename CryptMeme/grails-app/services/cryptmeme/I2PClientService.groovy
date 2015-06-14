package cryptmeme

import net.i2p.client.I2PClient
import net.i2p.client.I2PClientFactory
import net.i2p.client.I2PSession
import net.i2p.client.I2PSessionListener
import net.i2p.client.datagram.I2PDatagramMaker
import net.i2p.data.Destination
import net.i2p.data.RouterInfo
import net.i2p.router.Router

import org.cryptmeme.MessageListener
import org.springframework.beans.factory.InitializingBean;

class I2PClientService implements InitializingBean, I2PSessionListener {
	
	public static final String PRIVATE_KEY_FILE = "./myDest.key";
	public static I2PSession session;
	public static Router router;
	public static boolean isRunning = false;
	
	static scope = "singleton";
	
	private I2PDatagramMaker _dgram_maker;
	private I2PClient _client;
	private List<Person> _peers;
	private List<MessageListener> _listeners;
	
	public Destination me;
	
	public void afterPropertiesSet() throws Exception {
		
	}
	
	public void init() {
		println "Starting up the I2P Router..."
		// Properties props = new Properties();
		// props.put("i2p.dir.base","./i2p");
		router = new Router();
		router.runRouter();
		
		println "Setting up I2P Client Service...";
		
		Map configMap = router.getConfigMap();
		RouterInfo routerInfo = router.getRouterInfo();
		
		_peers = new ArrayList<Person>();
		_listeners = new ArrayList<MessageListener>();
		
		_client = I2PClientFactory.createClient();
		if (!new File(PRIVATE_KEY_FILE).exists()) {
			FileOutputStream fos = new FileOutputStream(PRIVATE_KEY_FILE);
			_client.createDestination(fos);
			fos.flush();
			fos.close();
		}
		
		FileInputStream fis = new FileInputStream(PRIVATE_KEY_FILE);
		session = _client.createSession(fis, null);
		boolean connected = false;
		while (!connected) {
			try {
				session.connect();
				connected = true;
			} catch (Exception e) {
				println "Failed to connect, trying again:";
				Thread.sleep(10000);
				println e.toString();
			}
		}
		fis.close();
		session.setSessionListener(this);
		// _dgram_maker = new I2PDatagramMaker(session);
		me = session.getMyDestination();
		print "I2P Client Service set up. My destination:\n" + me.toBase64();
		isRunning = true;
	}

	def serviceMethod() {

	}

	@Override
	public void messageAvailable(I2PSession session, int msgId, long size) {
		// TODO Auto-generated method stub
		print "messageAvailable: " + msgId + "\n";
	}

	@Override
	public void reportAbuse(I2PSession session, int severity) {
		// TODO Auto-generated method stub
		print "reportAbuse called!\n";
	}

	@Override
	public void disconnected(I2PSession session) {
		// TODO Auto-generated method stub
		print "Disconnected from I2P client service!\n";
	}

	@Override
	public void errorOccurred(I2PSession session, String message,
			Throwable error) {
		// TODO Auto-generated method stub
		print "A I2P error occurred:\n" + message + "\n" + error.getMessage() + "\n";
	}
}
