package edu.uw.cs.cse461.net.rpc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONObject;

import edu.uw.cs.cse461.net.base.NetBase;
import edu.uw.cs.cse461.net.base.NetLoadable.NetLoadableService;
import edu.uw.cs.cse461.net.rpc.RPCMessage.RPCCallMessage.RPCControlMessage;
import edu.uw.cs.cse461.net.rpc.RPCMessage.RPCCallMessage.RPCInvokeMessage;
import edu.uw.cs.cse461.net.rpc.RPCMessage.RPCResponseMessage.RPCErrorResponseMessage;
import edu.uw.cs.cse461.net.rpc.RPCMessage.RPCResponseMessage.RPCNormalResponseMessage;
import edu.uw.cs.cse461.net.tcpmessagehandler.TCPMessageHandler;
import edu.uw.cs.cse461.util.IPFinder;
import edu.uw.cs.cse461.util.Log;

/**
 * Implements the side of RPC that receives remote invocation requests.
 * 
 * @author zahorjan
 * 
 */
public class RPCService extends NetLoadableService implements Runnable, RPCServiceInterface {
    private static final String TAG = "RPCService";

    /**
     * The socket on which we listen for client connections.
     */
    private ServerSocket mServerSocket;

    /**
     * The collection of handlers registered with the RPC service. The outer map stores service names, the inner method
     * names.
     */
    private final Map<String, Map<String, RPCCallableMethod>> handlers;

    /**
     * Constructor. Creates the Java ServerSocket and binds it to a port. If the config file specifies an
     * rpc.server.port value, it should be bound to that port. Otherwise, you should specify port 0, meaning the
     * operating system should choose a currently unused port.
     * <p>
     * Once the port is created, a thread needs to be spun up to listen for connections on it.
     * 
     * @throws Exception
     */
    public RPCService() throws Exception {
        super("rpc");

        // Useful when debugging:
        Log.setLevel(Log.DebugLevel.DEBUG.toInt());

        handlers = new HashMap<String, Map<String, RPCCallableMethod>>();

        String serverIP = IPFinder.localIP();
        int tcpPort = NetBase.theNetBase().config().getAsInt("rpc.server.port", 0);

        mServerSocket = new ServerSocket();
        mServerSocket.bind(new InetSocketAddress(serverIP, tcpPort));
        mServerSocket.setSoTimeout(NetBase.theNetBase().config().getAsInt("net.timeout.granularity", 500));

        Log.d(TAG, "RPC server socket bound. Starting listener thread");
        Thread tcpThread = new Thread(this);
        tcpThread.start();
    }

    /**
     * Executed by an RPCService-created thread. Sits in loop waiting for connections, then creates an RPCCalleeSocket
     * to handle each one.
     */
    @Override
    public void run() {
        try {
            while (!mAmShutdown) {
                Socket sock = null;
                try {
                    // Start a responder thread, and continue listening
                    sock = mServerSocket.accept();
                    Log.d(TAG, "RPC server thread accepted connection. Starting response thread");
                    new Thread(new RPCCallResponder(sock)).start();
                } catch (SocketTimeoutException e) {
                    // this is normal. Just loop back and see if we're
                    // terminating.
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Server thread exiting due to exception: " + e.getMessage());
        } finally {
            if (mServerSocket != null) {
                try {
                    mServerSocket.close();
                } catch (Exception e) {
                }
            }
            mServerSocket = null;
        }
    }

    /**
     * Services and applications with RPC callable methods register them with the RPC service using this routine. Those
     * methods are then invoked as callbacks when an remote RPC request for them arrives.
     * 
     * @param serviceName
     *            The name of the service.
     * @param methodName
     *            The external, well-known name of the service's method to call
     * @param method
     *            The descriptor allowing invocation of the Java method implementing the call
     * @throws Exception
     */
    @Override
    public synchronized void registerHandler(String serviceName, String methodName, RPCCallableMethod method)
            throws Exception {
        if (!handlers.containsKey(serviceName)) {
            handlers.put(serviceName, new HashMap<String, RPCCallableMethod>());
        }

        handlers.get(serviceName).put(methodName, method);
        Log.d(TAG, "Registered handler " + method + " as " + serviceName + "." + methodName + "()");
    }

    /**
     * Some of the testing code needs to retrieve the current registration for a particular service and method, so this
     * interface is required. You probably won't find a use for it in your code, though.
     * 
     * @param serviceName
     *            The service name
     * @param methodName
     *            The method name
     * @return The existing registration for that method of that service, or null if no registration exists.
     */
    @Override
    public RPCCallableMethod getRegistrationFor(String serviceName, String methodName) {
        if (!handlers.containsKey(serviceName)) {
            return null;
        }

        return handlers.get(serviceName).get(methodName);
    }

    /**
     * Returns the port to which the RPC ServerSocket is bound.
     * 
     * @return The RPC service's port number on this node
     */
    @Override
    public int localPort() {
        return mServerSocket.getLocalPort();
    }

    @Override
    public String dumpState() {
        StringBuilder sb = new StringBuilder();
        sb.append("Listening at: ");
        if (mServerSocket != null) {
            sb.append(mServerSocket.getInetAddress() + ":" + mServerSocket.getLocalPort());
        }
        sb.append("\n");
        // TODO: This should be expanded to list the registered services
        return sb.toString();
    }

    /**
     * A runnable handler that responds to RPC call requests. Persists until rpc.persistence.timeout milliseconds have
     * passed since the most recent client interaction.
     * 
     * TODO: This class needs to send error messages to the client when appropriate.
     */
    public class RPCCallResponder implements Runnable {
        private static final String TAG = "RPCCallResponder";

        /**
         * The message handler for communicating with a client
         */
        private final TCPMessageHandler messageHandler;

        /**
         * The last time we communicated with the client
         */
        private long lastUsed;

        /**
         * How long to wait before closing a connection
         */
        private final long persistenceTimeout;

        public RPCCallResponder(Socket socket) throws IOException {
            messageHandler = new TCPMessageHandler(socket);
            messageHandler.setMaxReadLength(NetBase.theNetBase().config()
                    .getAsInt("tcpmessagehandler.maxmsglength", 2097148));

            messageHandler.setTimeout(NetBase.theNetBase().config().getAsInt("net.timeout.socket", 10000));

            persistenceTimeout = NetBase.theNetBase().config().getAsInt("rpc.persistence.timeout", 25000);
        }

        /**
         * Check whether this connection should persist
         * 
         * @return true if the persistence timeout has not passed, false otherwise
         */
        private boolean persist() {
            return lastUsed + persistenceTimeout > System.currentTimeMillis();
        }

        @Override
        public void run() {
            try {
                // Initialize the lastUsed field
                lastUsed = System.currentTimeMillis();

                // Read the connect message
                Log.d(TAG, "Awaiting connect message from client");
                RPCMessage rawMessage = RPCMessage.unmarshall(messageHandler.readMessageAsString());

                // Validate the connection message
                if (!"control".equals(rawMessage.type())) {
                    throw new IOException("Unexpected message of type " + rawMessage.type());
                }

                RPCControlMessage connectionMessage = (RPCControlMessage) rawMessage;

                if (!"connect".equals(connectionMessage.action())) {
                    throw new IOException("Expected connect message, received " + connectionMessage.action()
                            + " instead");
                }

                // Determine whether persistence should be enabled
                boolean keepAlive = "keep-alive".equals(connectionMessage.getOption("connection"));
                if (keepAlive) {
                    Log.d(TAG, "Client requests a persistent connection");
                } else {
                    Log.d(TAG, "Client doest not request a persistent connection");
                }

                // Respond with OK
                Log.d(TAG, "Connect message is valid. Responding with OK.");

                JSONObject data = new JSONObject();
                if (keepAlive) {
                    data.put("connection", "keep-alive");
                }

                RPCNormalResponseMessage connectionResponse = new RPCNormalResponseMessage(connectionMessage.id(), data);

                messageHandler.sendMessage(connectionResponse.marshall());

                // Update the socket timeout to check for shutdown signals
                messageHandler.setTimeout(NetBase.theNetBase().config().getAsInt("net.timeout.granularity", 500));

                // Continually accept calls from the client, until we are told to shut down,
                // or the persistence timeout expires.
                do {
                    try {
                        // Read the invocation message
                        // Log.d(TAG, "Awaiting invocation message from client");
                        rawMessage = RPCMessage.unmarshall(messageHandler.readMessageAsString());

                        // Note that we heard from the client
                        lastUsed = System.currentTimeMillis();

                        // Validate the invocation message
                        if (!"invoke".equals(rawMessage.type())) {
                            throw new IOException("Unexpected message of type " + rawMessage.type());
                        }

                        RPCInvokeMessage invokeMessage = (RPCInvokeMessage) rawMessage;
                        String service = invokeMessage.app();
                        String method = invokeMessage.method();
                        JSONObject args = invokeMessage.args();

                        Log.d(TAG, "Received valid call to " + service + "." + method + "() with args " + args);

                        // Perform the RPC
                        RPCCallableMethod callable = getRegistrationFor(service, method);
                        JSONObject result = null;
                        try {
                            result = callable.handleCall(args);
                        } catch (Exception e) {
                            Log.d(TAG, "Error processing RPC: " + e.getMessage());
                            RPCErrorResponseMessage errMessage = new RPCErrorResponseMessage(invokeMessage.id(),
                                    e.getMessage(), invokeMessage);
                            messageHandler.sendMessage(errMessage.marshall());
                            continue;
                        }

                        Log.d(TAG, "RPC return value is " + result);

                        // Return the result
                        Log.d(TAG, "Sending response to client");
                        RPCNormalResponseMessage responseMessage = new RPCNormalResponseMessage(invokeMessage.id(),
                                result);
                        messageHandler.sendMessage(responseMessage.marshall());
                    } catch (SocketTimeoutException e) {
                        // This is expected. Proceed through loop again to see
                        // if we should shut down.
                    }
                } while (keepAlive && !mAmShutdown && persist());

                // Log why this thread shut down
                if (mAmShutdown) {
                    Log.d(TAG, "Encountered stop signal");
                } else {
                    Log.d(TAG, "Persistence timeout exceeded");
                }

            } catch (Exception e) {
                Log.d(TAG, "Caught exception: " + e);
            } finally {
                Log.d(TAG, "Closing down socket");
                messageHandler.close();
            }

        }
    }

}
