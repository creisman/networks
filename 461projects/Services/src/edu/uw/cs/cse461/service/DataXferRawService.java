package edu.uw.cs.cse461.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;

import edu.uw.cs.cse461.net.base.NetBase;
import edu.uw.cs.cse461.util.ConfigManager;
import edu.uw.cs.cse461.util.IPFinder;
import edu.uw.cs.cse461.util.Log;
import edu.uw.cs.cse461.net.base.NetLoadableInterface.NetLoadableServiceInterface;

/**
 * Transfers reasonably large amounts of data to client over raw TCP and UDP sockets.  In both cases,
 * the server simply sends as fast as it can.  The server does not implement any correctness mechanisms,
 * so, when using UDP, clients may not receive all the data sent.
 * <p>
 * Four consecutive ports are used to send fixed amounts of data of various sizes.
 * <p>
 * @author zahorjan
 *
 */
public class DataXferRawService extends DataXferServiceBase implements NetLoadableServiceInterface {
	private static final String TAG="DataXferRawService";
	
	public static final int NPORTS = 4;
	public static final int[] XFERSIZE = {1000, 10000, 100000, 1000000};

	private int mBasePort;
	
	public DataXferRawService() throws Exception {
		super("dataxferraw");
		
		ConfigManager config = NetBase.theNetBase().config();
		mBasePort = config.getAsInt("dataxferraw.server.baseport", 0);
		if ( mBasePort == 0 ) throw new RuntimeException("dataxferraw service can't run -- no dataxferraw.server.baseport entry in config file");
		//TODO: implement this method (hint: look at echo raw service)
		
		// Get the IP of the server
		String serverIP = IPFinder.localIP();
		if ( serverIP == null ) throw new Exception("IPFinder isn't providing the local IP address.  Can't run.");
		
		// Start the server threads
		TCPThread[] tcpThreads = new TCPThread[NPORTS];
		UDPThread[] udpThreads = new UDPThread[NPORTS];
		
		for (int i = 0; i < NPORTS; i++) {
			tcpThreads[i] = new TCPThread(serverIP, mBasePort + i, XFERSIZE[i]);
			tcpThreads[i].start();
			// udpThreads[i] = new UDPThread(serverIP, mBasePort + i, XFERSIZE[i]);
			// udpThreads[i].start();
		}
		
	}
	

	/**
	 * Returns string summarizing the status of this server.  The string is printed by the dumpservicestate
	 * console application, and is also available by executing dumpservicestate through the web interface.
	 */
	@Override
	public String dumpState() {
		//TODO: not necessary, but filling this in is useful
		return "";
		
	}
	
	/**
	 * TCPThread is a thread for handling TCP requests to the DataXfer service.
	 *
	 */
	private class TCPThread extends Thread {
		private ServerSocket mServerSocket;
		String serverIP;
		int port;
		int xferLength;
		
		/**
		 * Constructs a new TCPThread on the given IP and port. This constructor does not reserve the
		 * given port or open a serversocket.
		 *  
		 * @param serverIP the IP of this server instance
		 * @param port the port on which to a
		 */
		private TCPThread(String serverIP, int port, int xferLength) {
			this.serverIP = serverIP;
			this.port = port;
			this.xferLength = xferLength;
		}
		
		/**
		 * Runs the TCP DataXfer service
		 */
		@Override
		public void run() {
			// Initialize some buffers and server constants
			byte[] header = new byte[4];
			int socketTimeout = NetBase.theNetBase().config().getAsInt("net.timeout.socket", 5000);
			
			try {
				// Create a server socket to listen for client connections
				mServerSocket = new ServerSocket();
				mServerSocket.bind(new InetSocketAddress(serverIP, port));
				mServerSocket.setSoTimeout(NetBase.theNetBase().config().getAsInt("net.timeout.granularity", 500));
				
				// Keep checking for the shutdown signal
				while (!isShutdown()) {
					Socket sock = null;
					try {
						// Block until a client connects
						sock = mServerSocket.accept();
						
						// Establish input and output streams to the client.
						sock.setSoTimeout(socketTimeout);
						InputStream is = sock.getInputStream();
						OutputStream os = sock.getOutputStream();
						
						// Read and validate the header.
						int len = is.read(header);
						if ( len != HEADER_STR.length() )
							throw new Exception("Bad header length: got " + len + " but wanted " + HEADER_STR.length());
						
						String headerStr = new String(header); 
						if ( !headerStr.equalsIgnoreCase(HEADER_STR) )
							throw new Exception("Bad header: got '" + headerStr + "' but wanted '" + HEADER_STR + "'");
						
						// Send the response header
						os.write(RESPONSE_OKAY_STR.getBytes());
						
						// Write the appropriate number of bytes
						byte[] byteBuf = new byte[1];
						byteBuf[0] = 42; // This byte value is arbitrary
						for (int i = 0; i < xferLength; i++) {
							os.write(byteBuf);
						}
						
					} catch (SocketTimeoutException e) {
						// normal behavior, but we're done with the client we were talking with
					} catch (Exception e) {
						Log.i(TAG, "TCP thread caught " + e.getClass().getName() + " exception: " + e.getMessage());
					} finally {
						if ( sock != null ) try { sock.close(); sock = null;} catch (Exception e) {}
					}
				}
			} catch (Exception e) {
				Log.w(TAG, "TCP server thread exiting due to exception: " + e.getMessage());
			} finally {
				if ( mServerSocket != null ) try { mServerSocket.close(); mServerSocket = null; } catch (Exception e) {}
			}
		}
		
	}
	
	private class UDPThread extends Thread {
		// TODO: Implement
	}
}
