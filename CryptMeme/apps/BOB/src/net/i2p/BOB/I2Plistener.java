/**
 *                    WTFPL
 *                    Version 2, December 2004
 *
 * Copyright (C) sponge
 *   Planet Earth
 *
 * See...
 *
 *	http://sam.zoy.org/wtfpl/
 *	and
 *	http://en.wikipedia.org/wiki/WTFPL
 *
 * ...for any additional details and license questions.
 */
package net.i2p.BOB;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.i2p.I2PException;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;

/**
 * Listen on I2P and connect to TCP
 *
 * @author sponge
 */
public class I2Plistener implements Runnable {

	private NamedDB info,  database;
	private Logger _log;
	public I2PSocketManager socketManager;
	public I2PServerSocket serverSocket;
	private AtomicBoolean lives;

	/**
	 * Constructor
	 * @param SS
	 * @param S
	 * @param info
	 * @param database
	 * @param _log
	 */
	I2Plistener(I2PServerSocket SS, I2PSocketManager S, NamedDB info, NamedDB database, Logger _log, AtomicBoolean lives) {
		this.database = database;
		this.info = info;
		this._log = _log;
		this.socketManager = S;
		this.serverSocket = SS;
		this.lives = lives;
	}

	/**
	 * Simply listen on I2P port, and thread connections
	 *
	 */
	public void run() {
		boolean g = false;
		I2PSocket sessSocket = null;
		int conn = 0;
		try {
			try {
				serverSocket.setSoTimeout(50);

				while (lives.get()) {
					try {
						sessSocket = serverSocket.accept();
						g = true;
					} catch (ConnectException ce) {
						g = false;
					} catch (SocketTimeoutException ste) {
						g = false;
					}
					if (g) {
						g = false;
						conn++;
						// toss the connection to a new thread.
						I2PtoTCP conn_c = new I2PtoTCP(sessSocket, info, database, lives);
						Thread t = new Thread(conn_c, Thread.currentThread().getName() + " I2PtoTCP " + conn);
						t.start();
					}

				}
			} catch (I2PException e) {
				// bad stuff
				System.out.println("Exception " + e);
			}
		} finally {
			try {
				serverSocket.close();
			} catch (I2PException ex) {
			}
			// System.out.println("I2Plistener: Close");
		}
	}
}
