package edu.uw.cs.cse461.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
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
	
	private TCPThread[] tcpThreads;
	private UDPThread[] udpThreads;
	
	public DataXferRawService() throws Exception {
		super("dataxferraw");
		
		ConfigManager config = NetBase.theNetBase().config();
		mBasePort = config.getAsInt("dataxferraw.server.baseport", 0);
		if ( mBasePort == 0 ) throw new RuntimeException("dataxferraw service can't run -- no dataxferraw.server.baseport entry in config file");
		
		// Get the IP of the server
		String serverIP = IPFinder.localIP();
		if ( serverIP == null ) throw new Exception("IPFinder isn't providing the local IP address.  Can't run.");
		
		// Start the server threads
		tcpThreads = new TCPThread[NPORTS];
		udpThreads = new UDPThread[NPORTS];
		
		for (int i = 0; i < NPORTS; i++) {
			tcpThreads[i] = new TCPThread(serverIP, mBasePort + i, XFERSIZE[i]);
			tcpThreads[i].start();
			udpThreads[i] = new UDPThread(serverIP, mBasePort + i, XFERSIZE[i]);
			udpThreads[i].start();
		}
		
	}
	

	/**
	 * Returns string summarizing the status of this server.  The string is printed by the dumpservicestate
	 * console application, and is also available by executing dumpservicestate through the web interface.
	 */
	@Override
	public String dumpState() {
		StringBuilder sb = new StringBuilder(super.dumpState());
		sb.append("\nListening on:");
		for (int i = 0; i < XFERSIZE.length; i++) {
			sb.append("\n\tTCP: ");
			if ( tcpThreads[i].getServerSocket() != null ) sb.append(tcpThreads[i].getServerSocket());
			else sb.append("Not listening");
		}
		
		for (int i = 0; i < XFERSIZE.length; i++) {
			sb.append("\n\tUDP: ");
			if ( udpThreads[i].getDatagramSocket() != null ) sb.append(udpThreads[i].getDatagramSocket().getLocalSocketAddress());
			else sb.append("Not listening");
		}
		return sb.toString();
	}
	
	/**
	 * TCPThread is a thread for handling TCP requests to the DataXfer service.
	 *
	 */
	private class TCPThread extends Thread {
		private ServerSocket mServerSocket;
		private String serverIP;
		private int port;
		private int xferLength;
		
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
		
		/**
		 * @return the server socket backing this thread.
		 */
		public ServerSocket getServerSocket() {
			return this.mServerSocket;
		}
		
	}
	
	private class UDPThread extends Thread {
		
		private static final int PAYLOAD_SIZE = 1000;
		
		private DatagramSocket mDatagramSocket;
		private String serverIP;
		private int port;
		private int xferLength;
		
		/**
		 * Constructs a new UDPThread on the given IP and port. This constructor does not reserve the
		 * given port.
		 *  
		 * @param serverIP the IP of this server instance
		 * @param port the port on which to a
		 */
		private UDPThread(String serverIP, int port, int xferLength) {
			this.serverIP = serverIP;
			this.port = port;
			this.xferLength = xferLength;
		}
		
		/**
		 * Run the UDP xfer service.
		 */
		@Override
		public void run() {
			byte buf[] = new byte[1000 + RESPONSE_OKAY_LEN];
			DatagramPacket packet = new DatagramPacket(buf, buf.length);

			//	Thread termination in this code is primitive.  When shutdown() is called (by the
			//	application's main thread, so asynchronously to the threads just mentioned) it
			//	closes the sockets.  This causes an exception on any thread trying to read from
			//	it, which is what provokes thread termination.
			try {
				mDatagramSocket = new DatagramSocket(new InetSocketAddress(serverIP, port));
				mDatagramSocket.setSoTimeout(NetBase.theNetBase().config().getAsInt("net.timeout.granularity", 500));
				
				while ( !isShutdown() ) {
					try {
						mDatagramSocket.receive(packet);
						if ( packet.getLength() < HEADER_STR.length() )
							throw new Exception("Bad header: length = " + packet.getLength());
						String headerStr = new String( buf, 0, HEADER_STR.length() );
						if ( ! headerStr.equalsIgnoreCase(HEADER_STR) )
							throw new Exception("Bad header: got '" + headerStr + "', wanted '" + HEADER_STR + "'");
						
						// Set the header and a one byte at the beginning and end of the buffer.
						System.arraycopy(RESPONSE_OKAY_STR.getBytes(), 0, buf, 0, RESPONSE_OKAY_STR.length());
						buf[RESPONSE_OKAY_LEN] = (byte)1;
						buf[buf.length - 1] = (byte)1;
						
						for (int i = xferLength; i > 0; i -= PAYLOAD_SIZE) {
							if (i < PAYLOAD_SIZE) {
								mDatagramSocket.send( new DatagramPacket(buf, i + RESPONSE_OKAY_LEN, packet.getAddress(), packet.getPort()));
							} else {
								mDatagramSocket.send( new DatagramPacket(buf, PAYLOAD_SIZE + RESPONSE_OKAY_LEN, packet.getAddress(), packet.getPort()));
							}
						}
					} catch (SocketTimeoutException e) {
						// socket timeout is normal
					} catch (Exception e) {
						Log.w(TAG,  "Dgram reading thread caught " + e.getClass().getName() + " exception: " + e.getMessage());
					}
				}
			} catch (SocketException e) {
				Log.w(TAG, "UDP server thread exiting due to exception: " + e.getMessage());
			} finally {
				if ( mDatagramSocket != null ) { mDatagramSocket.close(); mDatagramSocket = null; }
			}
		}
		
		/**
		 * @return the datagram socket back this thread.
		 */
		public DatagramSocket getDatagramSocket() {
			return mDatagramSocket;
		}
	}
}
