package edu.uw.cs.cse461.service;

import java.io.IOException;

import org.json.JSONException;
import org.json.JSONObject;

import edu.uw.cs.cse461.net.base.NetBase;
import edu.uw.cs.cse461.net.rpc.RPCCallableMethod;
import edu.uw.cs.cse461.net.rpc.RPCService;
import edu.uw.cs.cse461.util.Base64;

/**
 * A simple service that sends back the amount of data requested. It exposes a single method via RPC: dataxfer.
 * <p>
 * To make a method available via RPC you must do two key things:
 * <ol>
 * <li>Create an <tt>RPCCallableMethod</tt> object that describes the method you want to expose. In this class, that's
 * done with two statements:
 * <ol>
 * <li><tt>private RPCCallableMethod<EchoService> dataxfer;</tt> <br>
 * declares a variable that can hold a method description of the type the infrastructure requires to invoke a method.
 * <li><tt>dataxfer = new RPCCallableMethod<EchoService>(this, "_dataxfer");</tt> <br>
 * initializes that variable. The arguments mean that the method to invoke is <tt>this->_dataxfer()</tt>.
 * </ol>
 * <p>
 * <li>Register the method with the RPC service: <br>
 * <tt>((RPCService)OS.getService("rpc")).registerHandler(servicename(), "dataxfer", dataxfer );</tt> <br>
 * This means that when an incoming RPC specifies service "dataxferrpc" (the 1st argument) and method "dataxfer" (the
 * 2nd), that the method described by RPCCallableMethod variable <tt>dataxfer</tt> should be invoked.
 * </ol>
 * 
 * @author creisman
 * 
 */
public class DataXferRPCService extends DataXferServiceBase {
    private static final String TAG = "DataXferRPC";

    /**
     * Key used for DataXferRPC's header, in the args of an RPC call. The header element is a string
     * (DataXferServiceBase.HEADER_STR).
     */
    public static final String HEADER_KEY = "header";
    public static final String HEADER_TAG_KEY = "tag";
    public static final String HEADER_LENGTH_KEY = "xferLength";

    /**
     * Key used for DataXferRPC's data, in the response of an RPC call
     */
    public static final String DATA_KEY = "data";

    // A variable capable of describing a method that can be invoked by RPC.
    private final RPCCallableMethod dataxfer;

    /**
     * The constructor registers RPC-callable methods with the RPCService.
     * 
     * @throws IOException
     * @throws NoSuchMethodException
     */
    public DataXferRPCService() throws Exception {
        super("dataxferrpc");

        // Set up the method descriptor variable to refer to this->_dataxfer()
        dataxfer = new RPCCallableMethod(this, "_dataxfer");
        // Register the method with the RPC service as externally invocable method "dataxfer"
        ((RPCService) NetBase.theNetBase().getService("rpc")).registerHandler(loadablename(), "dataxfer", dataxfer);
    }

    /**
     * This method is callable by RPC (because of the actions taken by the constructor).
     * <p>
     * All RPC-callable methods take a JSONObject as their single parameter, and return a JSONObject. (The return value
     * can be null.) This particular method simply echos its arguments back to the caller.
     * 
     * @param args
     * @return
     * @throws JSONException
     */
    public JSONObject _dataxfer(JSONObject args) throws Exception {

        // check header
        JSONObject header = args.getJSONObject(DataXferRPCService.HEADER_KEY);
        if (header == null || !header.has(HEADER_TAG_KEY)
                || !header.getString(HEADER_TAG_KEY).equalsIgnoreCase(DataXferServiceBase.HEADER_STR)
                || !header.has(HEADER_LENGTH_KEY)) {
            throw new Exception("Missing or incorrect header value: '" + header + "'");
        }

        header.put(HEADER_TAG_KEY, RESPONSE_OKAY_STR);

        byte[] payload = new byte[header.getInt(HEADER_LENGTH_KEY)];
        args.put(DATA_KEY, Base64.encodeBytes(payload));
        return args;
    }
}
