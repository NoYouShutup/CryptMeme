package cryptmeme

import net.i2p.client.I2PClient
import net.i2p.client.I2PClientFactory
import net.i2p.client.I2PSession
import net.i2p.client.I2PSessionListener
import net.i2p.client.datagram.I2PDatagramMaker
import net.i2p.data.Destination

import org.cryptmeme.MessageListener
import org.springframework.beans.factory.InitializingBean;

class I2PClientService implements InitializingBean, I2PSessionListener {
	
	public static final String PRIVATE_KEY_FILE = "./myDest.key";
	public static I2PSession session;
	
	static scope = "singleton";
	
	private I2PDatagramMaker _dgram_maker;
	private I2PClient _client;
	private List<Person> _peers;
	private List<MessageListener> _listeners;
	
	public Destination me;
	
	public void afterPropertiesSet() throws Exception {
		
	}
	
	public void init() {
		println "Setting up I2P Client Service...";
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
		session.connect();
		fis.close();
		session.setSessionListener(this);
		_dgram_maker = new I2PDatagramMaker(_session);
		me = session.getMyDestination();
		print "I2P Client Service set up. My destination:\n" + me.toBase64();
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
