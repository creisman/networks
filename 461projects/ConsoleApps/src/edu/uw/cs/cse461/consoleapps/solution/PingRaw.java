package edu.uw.cs.cse461.consoleapps.solution;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;

import edu.uw.cs.cse461.consoleapps.PingInterface.PingRawInterface;
import edu.uw.cs.cse461.net.base.NetBase;
import edu.uw.cs.cse461.net.base.NetLoadable.NetLoadableConsoleApp;
import edu.uw.cs.cse461.service.EchoServiceBase;
import edu.uw.cs.cse461.util.ConfigManager;
import edu.uw.cs.cse461.util.Log;
import edu.uw.cs.cse461.util.SampledStatistic.ElapsedTime;
import edu.uw.cs.cse461.util.SampledStatistic.ElapsedTimeInterval;

/**
 * Raw sockets version of ping client.
 * 
 * @author zahorjan
 * 
 */
public class PingRaw extends NetLoadableConsoleApp implements PingRawInterface {
	private static final String TAG = "PingRaw";

	/**
	 * ConsoleApp's must have a constructor taking no arguments
	 */
	public PingRaw() {
		super("pingraw");
	}

	/**
	 * @see edu.uw.cs.cse461.net.base.NetLoadable.NetLoadableConsoleApp#run()
	 */
	@Override
	public void run() {
		try {
			// Eclipse doesn't support System.console()
			BufferedReader console = new BufferedReader(new InputStreamReader(
					System.in));
			ConfigManager config = NetBase.theNetBase().config();

			try {

				String targetIP = config.getProperty("net.server.ip");
				if (targetIP == null) {
					System.out
							.println("No net.server.ip entry in config file.");
					System.out
							.print("Enter the server's ip, or empty line to exit: ");
					targetIP = console.readLine();
					if (targetIP == null || targetIP.trim().isEmpty())
						return;
				}

				int targetUDPPort;
				System.out
						.print("Enter the server's UDP port, or empty line to skip: ");
				String targetUDPPortStr = console.readLine();
				if (targetUDPPortStr == null
						|| targetUDPPortStr.trim().isEmpty())
					return;
				targetUDPPort = Integer.parseInt(targetUDPPortStr);

				int targetTCPPort;
				System.out
						.print("Enter the server's TCP port, or empty line to skip: ");
				String targetTCPPortStr = console.readLine();
				if (targetTCPPortStr == null
						|| targetTCPPortStr.trim().isEmpty())
					targetTCPPort = 0;
				else
					targetTCPPort = Integer.parseInt(targetTCPPortStr);

				System.out.print("Enter number of trials: ");
				String trialStr = console.readLine();
				int nTrials = Integer.parseInt(trialStr);

				int socketTimeout = config.getAsInt("net.timeout.socket", 5000);

				System.out.println("Host: " + targetIP);
				System.out.println("udp port: " + targetUDPPort);
				System.out.println("tcp port: " + targetTCPPort);
				System.out.println("trials: " + nTrials);

				ElapsedTimeInterval udpResult = null;
				ElapsedTimeInterval tcpResult = null;

				if (targetUDPPort != 0) {
					ElapsedTime.clear();
					// we rely on knowing the implementation of udpPing here --
					// we throw
					// away the return value because we'll print the ElaspedTime
					// stats
					udpResult = udpPing(EchoServiceBase.HEADER_BYTES, targetIP,
							targetUDPPort, socketTimeout, nTrials);
				}

				if (targetTCPPort != 0) {
					ElapsedTime.clear();
					tcpResult = tcpPing(EchoServiceBase.HEADER_BYTES, targetIP,
							targetTCPPort, socketTimeout, nTrials);
				}

				if (udpResult != null)
					System.out.println("UDP: "
							+ String.format("%.2f msec (%d failures)",
									udpResult.mean(), udpResult.nAborted()));
				if (tcpResult != null)
					System.out.println("TCP: "
							+ String.format("%.2f msec (%d failures)",
									tcpResult.mean(), tcpResult.nAborted()));

			} catch (Exception e) {
				System.out.println("Exception: " + e.getMessage());
			}
		} catch (Exception e) {
			System.out.println("PingRaw.run() caught exception: "
					+ e.getMessage());
		}
	}

	/**
	 * Pings the host/port named by the arguments the number of times named by
	 * the arguments. Returns the mean ping time of the trials.
	 */
	@Override
	public ElapsedTimeInterval udpPing(byte[] header, String hostIP,
			int udpPort, int socketTimeout, int nTrials) {
		try {
			for (int i = 0; i < nTrials; i++) {
				ElapsedTime.start("PingRaw_UDPTotalDelay");

				DatagramSocket socket = new DatagramSocket();
				socket.setSoTimeout(socketTimeout); // wait at most a bounded
													// time when receiving on
													// this socket

				int dataLength = header.length;
				if (dataLength > 1400)
					throw new Exception("Data is too long for UDP echo");
				ByteBuffer bufBB = ByteBuffer.wrap(header);
				bufBB.put(header);
				DatagramPacket packet = new DatagramPacket(header,
						header.length, new InetSocketAddress(hostIP, udpPort));
				socket.send(packet); // tell the server we're here. The server
										// will get our IP and port from the
										// received packet.

				// we're supposed to get back what we sent (but with header
				// contents changed),
				// so the amount of buffer we need is equal to size of what we
				// sent.
				byte[] receiveBuf = new byte[dataLength];
				DatagramPacket receivePacket = new DatagramPacket(receiveBuf,
						receiveBuf.length);
				try {
					socket.receive(receivePacket);
					String rcvdHeader = new String(receiveBuf, 0,
							EchoServiceBase.RESPONSE_LEN);
					if (!rcvdHeader
							.equalsIgnoreCase(EchoServiceBase.RESPONSE_OKAY_STR))
						throw new Exception("Bad returned header: got '"
								+ rcvdHeader + "' but wanted '"
								+ EchoServiceBase.RESPONSE_OKAY_STR);
				} catch (SocketTimeoutException e) {
					// This exception is thrown if we wait on receive() longer
					// than the timeout
					throw new Exception("UDP socket timeout");
				} finally {
					socket.close();
				}

				ElapsedTime.stop("PingRaw_UDPTotalDelay");
			}
		} catch (Exception e) {
			ElapsedTime.abort("PingRaw_UDPTotalDelay");
			Log.w(TAG, "Exception: " + e.getMessage());
		}

		return ElapsedTime.get("PingRaw_UDPTotalDelay");
	}

	/**
	 * @see edu.uw.cs.cse461.consoleapps.PingInterface.PingRawInterface#tcpPing(byte[],
	 *      java.lang.String, int, int, int)
	 */
	@Override
	public ElapsedTimeInterval tcpPing(byte[] header, String hostIP,
			int tcpPort, int socketTimeout, int nTrials) {
		try {
			for (int i = 0; i < nTrials; i++) {
				ElapsedTime.start("PingRaw_TCPTotal");

				Socket tcpSocket = new Socket(hostIP, tcpPort);
				tcpSocket.setSoTimeout(socketTimeout);
				InputStream is = tcpSocket.getInputStream();
				OutputStream os = tcpSocket.getOutputStream();

				// send header
				os.write(header);
				tcpSocket.shutdownOutput();

				// read the header. Either the entire header arrives in one
				// chunk, or we
				// (mistakenly) reject it.
				byte[] headerBuf = new byte[EchoServiceBase.RESPONSE_LEN];
				try {
					int len = is.read(headerBuf);
					if (len <= 0)
						throw new Exception("Bad response header length: got "
								+ len + " but expected >0");
					String headerStr = new String(headerBuf);
					if (!headerStr
							.equalsIgnoreCase(EchoServiceBase.RESPONSE_OKAY_STR))
						throw new Exception("Bad response header: got '"
								+ headerStr + "' but expected '"
								+ EchoServiceBase.RESPONSE_OKAY_STR + "'");
				} finally {
					tcpSocket.close();
				}

				ElapsedTime.stop("PingRaw_TCPTotal");
			}
		} catch (Exception e) {
			ElapsedTime.abort("PingRaw_TCPTotal");
			Log.w(TAG, "Exception: " + e.getMessage());
		}

		return ElapsedTime.get("PingRaw_TCPTotal");
	}
}
