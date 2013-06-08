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
                        .println("RPC: " + String.format("%.2f msec (%d failures)", result.mean(), result.nAborted()));
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
                Log.d(TAG, "Starting ping trial " + i);
                ElapsedTime.start("PingRPC_Total");

                // send message
                JSONObject args = new JSONObject().put(EchoRPCService.HEADER_KEY, header).put(
                        EchoRPCService.PAYLOAD_KEY, MESSAGE);
                Log.d(TAG, "Sending RPC: " + args);
                JSONObject response = RPCCall.invoke(targetIP, targetRPCPort, "echorpc", "echo", args, timeout);
                if (response == null) {
                    throw new IOException("RPC failed; response is null");
                }
                Log.d(TAG, "RPC response received: " + response);

                // Since the tester implements an incorrect echo service, we don't validate the response content here.

                if (!response.has(EchoRPCService.PAYLOAD_KEY)
                        || !response.getString(EchoRPCService.PAYLOAD_KEY).equals(MESSAGE)) {
                    throw new Exception("Incorrect message");
                }

                ElapsedTime.stop("PingRPC_Total");
            }
        } catch (Exception e) {
            ElapsedTime.abort("PingRPC_Total");
            Log.w(TAG, "Exception: " + e.getClass() + " : " + e.getMessage());
        }

        return ElapsedTime.get("PingRPC_Total");
    }
}
