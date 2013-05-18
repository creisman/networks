package edu.uw.cs.cse461.consoleapps.solution;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

import javax.xml.crypto.Data;

import org.json.JSONException;
import org.json.JSONObject;

import edu.uw.cs.cse461.consoleapps.DataXferInterface;
import edu.uw.cs.cse461.consoleapps.PingInterface;
import edu.uw.cs.cse461.net.base.NetBase;
import edu.uw.cs.cse461.net.base.NetLoadable.NetLoadableConsoleApp;
import edu.uw.cs.cse461.net.tcpmessagehandler.TCPMessageHandler;
import edu.uw.cs.cse461.service.DataXferServiceBase;
import edu.uw.cs.cse461.service.DataXferTCPMessageHandlerService;
import edu.uw.cs.cse461.service.EchoServiceBase;
import edu.uw.cs.cse461.util.ConfigManager;
import edu.uw.cs.cse461.util.Log;
import edu.uw.cs.cse461.util.SampledStatistic.ElapsedTime;
import edu.uw.cs.cse461.util.SampledStatistic.ElapsedTimeInterval;
import edu.uw.cs.cse461.util.SampledStatistic.TransferRate;
import edu.uw.cs.cse461.util.SampledStatistic.TransferRateInterval;

public class DataXferTCPMessageHandler extends NetLoadableConsoleApp implements
		DataXferInterface.DataXferTCPMessageHandlerInterface {
	private static final String TAG = "DataXferTCPMessageHandler";

	public DataXferTCPMessageHandler() {
		super("dataxfertcpmessagehandler");
	}

	@Override
	public void run() throws Exception {
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

				int targetTCPPort;
				System.out
						.print("Enter the server's TCP port, or empty line to exit: ");
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

				while (true) {
					System.out
							.print("Enter amount of data to transfer (-1 to exit): ");
					String sizeStr = console.readLine();
					int size = Integer.parseInt(sizeStr);
					if (size == -1) {
						break;
					}
					
					System.out.println("\n" + size + " bytes\n");
	
					TransferRateInterval tcpResult = null;
	
					if (targetTCPPort != 0) {
						TransferRate.clear();
						tcpResult = DataXferRate(DataXferServiceBase.HEADER_STR,
								targetIP, targetTCPPort, socketTimeout, size,
								nTrials);
					}
	
					if (tcpResult != null) {
						// Print out the rate in seconds (not ms)
						System.out.println(String.format("TCP: xfer rate =\t%.2f bytes/sec.", tcpResult.mean() * 1000));
						System.out.println(String.format("TCP: failure rate =\t%.1f [%d/%d]", tcpResult.failureRate(), tcpResult.nAborted(), tcpResult.nTrials()));
					}
				}

			} catch (Exception e) {
				System.out.println("Exception: " + e.getMessage());
			}
		} catch (Exception e) {
			System.out
					.println("DataXferTCPMessageHandler.run() caught exception: "
							+ e.getMessage());
		}
	}

	/**
	 * @see edu.uw.cs.cse461.consoleapps.DataXferInterface.DataXferTCPMessageHandlerInterface#DataXfer(java.lang.String, java.lang.String, int, int, int)
	 */
	@Override
	public byte[] DataXfer(String header, String hostIP, int port, int timeout,
			int xferLength) throws JSONException, IOException {
		ByteBuffer bytes = ByteBuffer.allocate(xferLength);
		
		Socket tcpSocket = new Socket(hostIP, port);
		tcpSocket.setSoTimeout(timeout);

		TCPMessageHandler handler = new TCPMessageHandler(tcpSocket);

		JSONObject json = new JSONObject();
		json.put(DataXferTCPMessageHandlerService.TRANSFER_SIZE_KEY, xferLength);

		handler.sendMessage(header);
		handler.sendMessage(json);

		try {
			handler.setMaxReadLength(DataXferServiceBase.RESPONSE_OKAY_LEN);
			String response = handler.readMessageAsString();

			if (!response.equalsIgnoreCase(DataXferServiceBase.RESPONSE_OKAY_STR)) {
				throw new IOException("Bad response header: got '"
						+ response + "' but expected '"
						+ DataXferServiceBase.RESPONSE_OKAY_STR + "'");
			}

			try {
				handler.setMaxReadLength(DataXferTCPMessageHandlerService.MAX_SIZE);
				while (true) {
					bytes.put(handler.readMessageAsBytes());
				}
			} catch (EOFException ex) {
				// This is the only way to find EOF.
			} catch (BufferOverflowException ex) {
				throw new IOException("Too many bytes read.");
			}
			
			if (bytes.remaining() != 0) {
				throw new IOException("Not enough bytes read.");
			}
		} finally {
			handler.close();
		}
			
		return bytes.array();
	}

	/**
	 * @see edu.uw.cs.cse461.consoleapps.DataXferInterface.DataXferTCPMessageHandlerInterface#DataXferRate(java.lang.String, java.lang.String, int, int, int, int)
	 */
	@Override
	public TransferRateInterval DataXferRate(String header, String hostIP,
			int port, int timeout, int xferLength, int nTrials) {
		for (int i = 0; i < nTrials; i++) {
			try {
				TransferRate.start("DataXferTCPMessageHandler_Total");
				DataXfer(header, hostIP, port, timeout, xferLength);
				TransferRate.stop("DataXferTCPMessageHandler_Total", xferLength);
			} catch (Exception ex) {
				TransferRate.abort("DataXferTCPMessageHandler_Total", xferLength);
			}
		}
		
		return TransferRate.get("DataXferTCPMessageHandler_Total");
	}
}
