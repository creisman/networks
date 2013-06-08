package edu.uw.cs.cse461.consoleapps.solution;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import org.json.JSONObject;

import edu.uw.cs.cse461.consoleapps.PingInterface.PingRPCInterface;
import edu.uw.cs.cse461.net.base.NetBase;
import edu.uw.cs.cse461.net.base.NetLoadable.NetLoadableConsoleApp;
import edu.uw.cs.cse461.net.rpc.RPCCall;
import edu.uw.cs.cse461.service.EchoRPCService;
import edu.uw.cs.cse461.service.EchoServiceBase;
import edu.uw.cs.cse461.util.ConfigManager;
import edu.uw.cs.cse461.util.Log;
import edu.uw.cs.cse461.util.SampledStatistic.ElapsedTime;
import edu.uw.cs.cse461.util.SampledStatistic.ElapsedTimeInterval;

public class PingRPC extends NetLoadableConsoleApp implements PingRPCInterface {
    private static final String TAG = "PingRPC";
    private static final String MESSAGE = "";

    // ConsoleApp's must have a constructor taking no arguments
    public PingRPC() {
        super("pingrpc");
    }

    @Override
    public void run() {
        try {
            // Eclipse doesn't support System.console()
            BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
            ConfigManager config = NetBase.theNetBase().config();

            int timeout = config.getAsInt("net.timeout.socket", 500);

            String targetIP = config.getProperty("net.server.ip");
            if (targetIP == null) {
                System.out.println("No net.server.ip entry in config file.");
                System.out.print("Enter a server ip, or empty line to exit: ");
                targetIP = console.readLine();
                if (targetIP == null || targetIP.trim().isEmpty()) {
                    return;
                }
            }

            System.out.print("Enter the server's RPC port, or empty line to exit: ");
            String targetTCPPortStr = console.readLine();
            if (targetTCPPortStr == null || targetTCPPortStr.trim().isEmpty()) {
                return;
            }
            int targetRPCPort = Integer.parseInt(targetTCPPortStr);

            System.out.print("Enter number of trials: ");
            String trialStr = console.readLine();
            int nTrials = Integer.parseInt(trialStr);

            JSONObject header = new JSONObject().put(EchoRPCService.HEADER_TAG_KEY, EchoServiceBase.HEADER_STR);

            ElapsedTimeInterval result = ping(header, targetIP, targetRPCPort, timeout, nTrials);

            if (result != null) {
                System.out
                        .println("TCP: " + String.format("%.2f msec (%d failures)", result.mean(), result.nAborted()));
            }
        } catch (Exception e) {
            System.out.println("PingRPC.run() caught exception: " + e.getMessage());
        }
    }

    /**
     * @see edu.uw.cs.cse461.consoleapps.PingInterface.PingRPCInterface#ping(org.json.JSONObject, java.lang.String, int,
     *      int, int)
     */
    @Override
    public ElapsedTimeInterval ping(JSONObject header, String targetIP, int targetRPCPort, int timeout, int nTrials)
            throws Exception {
        try {
            for (int i = 0; i < nTrials; i++) {
                ElapsedTime.start("PingRPC_Total");

                // send message

                JSONObject args = new JSONObject().put(EchoRPCService.HEADER_KEY, header).put(
                        EchoRPCService.PAYLOAD_KEY, MESSAGE);
                JSONObject response = RPCCall.invoke(targetIP, targetRPCPort, "echorpc", "echo", args, timeout);
                if (response == null) {
                    throw new IOException("RPC failed; response is null");
                }

                // examine response
                JSONObject rcvdHeader = response.optJSONObject(EchoRPCService.HEADER_KEY);
                if (rcvdHeader == null
                        || !rcvdHeader.has(EchoRPCService.HEADER_TAG_KEY)
                        || !rcvdHeader.getString(EchoRPCService.HEADER_TAG_KEY).equalsIgnoreCase(
                                EchoServiceBase.RESPONSE_OKAY_STR)) {
                    throw new IOException("Bad response header: got '" + rcvdHeader.toString()
                            + "' but wanted a JSONOBject with key '" + EchoRPCService.HEADER_TAG_KEY
                            + "' and string value '" + EchoServiceBase.RESPONSE_OKAY_STR + "'");
                }

                if (!response.has(EchoRPCService.PAYLOAD_KEY)
                        || !response.getString(EchoRPCService.PAYLOAD_KEY).equals(MESSAGE)) {
                    throw new Exception("Incorrect message");
                }

                ElapsedTime.stop("PingRPC_Total");
            }
        } catch (Exception e) {
            ElapsedTime.abort("PingRPC_Total");
            Log.w(TAG, "Exception: " + e.getMessage());
        }

        return ElapsedTime.get("PingRPC_Total");
    }
}
