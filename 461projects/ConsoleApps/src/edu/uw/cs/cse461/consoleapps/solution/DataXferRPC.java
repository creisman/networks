package edu.uw.cs.cse461.consoleapps.solution;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import org.json.JSONException;
import org.json.JSONObject;

import edu.uw.cs.cse461.consoleapps.DataXferInterface.DataXferRPCInterface;
import edu.uw.cs.cse461.net.base.NetBase;
import edu.uw.cs.cse461.net.base.NetLoadable.NetLoadableConsoleApp;
import edu.uw.cs.cse461.net.rpc.RPCCall;
import edu.uw.cs.cse461.service.DataXferRPCService;
import edu.uw.cs.cse461.service.DataXferServiceBase;
import edu.uw.cs.cse461.util.Base64;
import edu.uw.cs.cse461.util.ConfigManager;
import edu.uw.cs.cse461.util.Log;
import edu.uw.cs.cse461.util.SampledStatistic.TransferRate;
import edu.uw.cs.cse461.util.SampledStatistic.TransferRateInterval;

public class DataXferRPC extends NetLoadableConsoleApp implements DataXferRPCInterface {
    private static final String TAG = "DataXferRPC";

    // ConsoleApp's must have a constructor taking no arguments
    public DataXferRPC() {
        super("dataxferrpc");
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

            System.out.print("Enter amount of data to transfer: ");
            String sizeStr = console.readLine();
            int size = Integer.parseInt(sizeStr);
            if (size < 0) {
                System.out.println("The length can't be negative.");
                return;
            }

            JSONObject header = new JSONObject().put(DataXferRPCService.HEADER_TAG_KEY, DataXferServiceBase.HEADER_STR)
                    .put(DataXferRPCService.HEADER_LENGTH_KEY, size);

            TransferRate.clear();
            TransferRateInterval result = DataXferRate(header, targetIP, targetRPCPort, timeout, nTrials);

            if (result != null) {
                System.out.println(String.format("RPC: xfer rate =\t%.2f bytes/sec.", result.mean() * 1000));
                System.out.println(String.format("RPC: failure rate =\t%.1f [%d/%d]", result.failureRate(),
                        result.nAborted(), result.nTrials()));
            }
        } catch (Exception e) {
            System.out.println("DataXferRPC.run() caught exception: " + e.getMessage());
        }
    }

    /**
     * @see edu.uw.cs.cse461.consoleapps.DataXferInterface.DataXferRPCInterface#DataXfer(org.json.JSONObject,
     *      java.lang.String, int, int)
     */
    @Override
    public byte[] DataXfer(JSONObject header, String targetIP, int targetRPCPort, int timeout) throws JSONException,
            IOException {
        JSONObject args = new JSONObject().put(DataXferRPCService.HEADER_KEY, header);
        Log.d(TAG, "Sending RPC: " + args);
        JSONObject response = RPCCall.invoke(targetIP, targetRPCPort, "dataxferrpc", "dataxfer", args, timeout);
        if (response == null) {
            throw new IOException("RPC failed; response is null");
        }
        Log.d(TAG, "RPC response received: " + response);

        return Base64.decode(response.getString(DataXferRPCService.DATA_KEY));
    }

    /**
     * @see edu.uw.cs.cse461.consoleapps.DataXferInterface.DataXferRPCInterface#DataXferRate(org.json.JSONObject,
     *      java.lang.String, int, int, int)
     */
    @Override
    public TransferRateInterval DataXferRate(JSONObject header, String hostIP, int port, int timeout, int nTrials) {
        Long transferLength;
        transferLength = header.optLong(DataXferRPCService.HEADER_LENGTH_KEY);
        if (transferLength == null) {
            Log.e(TAG, "Header doesn't contain length");
            throw new IllegalArgumentException("The provided header does not contain the transfer size.");
        }

        for (int i = 0; i < nTrials; i++) {
            try {
                Log.d(TAG, "Starting ping trial " + i);
                TransferRate.start("DataXferRPC_Total");
                byte[] data = DataXfer(header, hostIP, port, timeout);

                if (data.length != transferLength) {
                    throw new Exception("Incorrect length");
                }

                TransferRate.stop("DataXferRPC_Total", transferLength);
            } catch (Exception ex) {
                Log.w(TAG, "Exception: " + ex.getClass() + " : " + ex.getMessage());
                TransferRate.abort("DataXferRPC_Total", transferLength);
            }
        }

        return TransferRate.get("DataXferRPC_Total");
    }
}
