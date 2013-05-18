package edu.uw.cs.cse461.consoleapps.solution;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;

import edu.uw.cs.cse461.consoleapps.PingInterface;
import edu.uw.cs.cse461.net.base.NetBase;
import edu.uw.cs.cse461.net.base.NetLoadable.NetLoadableConsoleApp;
import edu.uw.cs.cse461.net.tcpmessagehandler.TCPMessageHandler;
import edu.uw.cs.cse461.service.EchoServiceBase;
import edu.uw.cs.cse461.util.ConfigManager;
import edu.uw.cs.cse461.util.Log;
import edu.uw.cs.cse461.util.SampledStatistic.ElapsedTime;
import edu.uw.cs.cse461.util.SampledStatistic.ElapsedTimeInterval;

public class PingTCPMessageHandler extends NetLoadableConsoleApp implements
        PingInterface.PingTCPMessageHandlerInterface {
    private static final String TAG = "PingTCPMessageHandler";

    public PingTCPMessageHandler() {
        super("pingtcpmessagehandler");
    }

    @Override
    public void run() throws Exception {
        try {
            // Eclipse doesn't support System.console()
            BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
            ConfigManager config = NetBase.theNetBase().config();

            try {

                String targetIP = config.getProperty("net.server.ip");
                if (targetIP == null) {
                    System.out.println("No net.server.ip entry in config file.");
                    System.out.print("Enter the server's ip, or empty line to exit: ");
                    targetIP = console.readLine();
                    if (targetIP == null || targetIP.trim().isEmpty()) {
                        return;
                    }
                }

                int targetTCPPort;
                System.out.print("Enter the server's TCP port, or empty line to exit: ");
                String targetTCPPortStr = console.readLine();
                if (targetTCPPortStr == null || targetTCPPortStr.trim().isEmpty()) {
                    targetTCPPort = 0;
                } else {
                    targetTCPPort = Integer.parseInt(targetTCPPortStr);
                }

                System.out.print("Enter number of trials: ");
                String trialStr = console.readLine();
                int nTrials = Integer.parseInt(trialStr);

                int socketTimeout = config.getAsInt("net.timeout.socket", 5000);

                ElapsedTimeInterval tcpResult = null;

                if (targetTCPPort != 0) {
                    ElapsedTime.clear();
                    tcpResult = ping(new String(EchoServiceBase.HEADER_BYTES), targetIP, targetTCPPort, socketTimeout,
                            nTrials);
                }

                if (tcpResult != null) {
                    System.out.println(String.format(
                            "PingTCPMessageHandler_TotalTime: [%.2f, %.2f, %.2f] (%d samples, %d aborted)\n",
                            tcpResult.mean(), tcpResult.min(), tcpResult.max(), tcpResult.nTrials(),
                            tcpResult.nAborted()));
                }

            } catch (Exception e) {
                System.out.println("Exception: " + e.getMessage());
            }
        } catch (Exception e) {
            System.out.println("PingTCPMessageHandler.run() caught exception: " + e.getMessage());
        }
    }

    @Override
    public ElapsedTimeInterval ping(String header, String hostIP, int port, int timeout, int nTrials) throws Exception {

        for (int i = 0; i < nTrials; i++) {
            try {
                ElapsedTime.start("PingTCPMessageHandler_Total");

                Socket tcpSocket = new Socket(hostIP, port);
                tcpSocket.setSoTimeout(timeout);

                TCPMessageHandler handler = new TCPMessageHandler(tcpSocket);
                handler.setMaxReadLength(EchoServiceBase.RESPONSE_LEN);

                // Send the header to establish what service we want, and then
                // send a an empty message (ping)
                handler.sendMessage(header);
                handler.sendMessage("");

                try {
                    String response = handler.readMessageAsString();
                    String endPing = handler.readMessageAsString();

                    if (!response.equalsIgnoreCase(EchoServiceBase.RESPONSE_OKAY_STR)) {
                        throw new Exception("Bad response header: got '" + response + "' but expected '"
                                + EchoServiceBase.RESPONSE_OKAY_STR + "'");
                    }

                    if (endPing.length() != 0) {
                        throw new Exception("Bad response terminator: expected empty string but received '" + endPing
                                + "'");
                    }
                } finally {
                    handler.close();
                }
                ElapsedTime.stop("PingTCPMessageHandler_Total");
            } catch (Exception e) {
                ElapsedTime.abort("PingTCPMessageHandler_Total");
                Log.e(TAG, "Exception: " + e.getMessage());
            }
        }

        return ElapsedTime.get("PingTCPMessageHandler_Total");
    }
}
