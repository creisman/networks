package edu.uw.cs.cse461.net.rpc;

import java.io.IOException;
import java.net.Socket;

import org.json.JSONException;
import org.json.JSONObject;

import edu.uw.cs.cse461.net.base.NetBase;
import edu.uw.cs.cse461.net.rpc.RPCMessage.RPCCallMessage.RPCControlMessage;
import edu.uw.cs.cse461.net.rpc.RPCMessage.RPCCallMessage.RPCInvokeMessage;
import edu.uw.cs.cse461.net.rpc.RPCMessage.RPCResponseMessage.RPCNormalResponseMessage;
import edu.uw.cs.cse461.net.tcpmessagehandler.TCPMessageHandler;
import edu.uw.cs.cse461.util.Log;

/**
 * Implements a Socket to use in sending remote RPC invocations.  (It must engage
 * in the RPC handshake before sending the invocation request.)
 * @author zahorjan
 *
 */
 class RPCCallerSocket extends Socket {
	private static final String TAG = "RPCCallerSocket";	
	
	/**
	 * The message handler used to communicate over this socket.
	 */
	private TCPMessageHandler messageHandler;
	
	/**
	 * Whether this socket is intended to be persistent
	 */
	private boolean persistent;
	
	/**
	 * Create a socket for sending RPC invocations, connecting it to the specified remote ip and port.
	 * @param Remote host's name. In Project 3, it's not terribly meaningful - repeat the ip.
	 *  In Project 4, it's intended to be the string name of the remote system, allowing a degree of sanity checking.
	 * @param ip  Remote system IP address.
	 * @param port Remote RPC service's port.
	 * @param wantPersistent True if caller wants to try to establish a persistent connection, false otherwise
	 * @throws IOException
	 * @throws JSONException
	 */
	RPCCallerSocket(String ip, int port, boolean wantPersistent) throws IOException, JSONException {
		super(ip, port);
		
		// Useful when debugging:
		// Log.setLevel(Log.DebugLevel.DEBUG.toInt());
		
		// Create the message handler for this socket. Uses a default timeout, since the client has not had an
		// opportunity to set one themselves
		messageHandler = new TCPMessageHandler(this);
		messageHandler.setTimeout(NetBase.theNetBase().config().getAsInt("net.timeout.socket", 2000));
		
		// Handshake with the remote service
		JSONObject options = new JSONObject();
		if (wantPersistent)
			options.put("connection", "keep-alive");
		
		RPCMessage connectMessage = new RPCControlMessage("connect", options);
		Log.d(TAG, "Sending connection message");
		messageHandler.sendMessage(connectMessage.marshall());
		
		// Read the server response
		Log.d(TAG, "Awaiting connection response");
		RPCMessage response = RPCMessage.unmarshall(messageHandler.readMessageAsString());
		
		if ("ERROR".equals(response.type())) {
			// A server error occurred
			String message = response.mObject.optString("message");
			throw new IOException("RPC server error : " + message);
		} else if (!"OK".equals(response.type())) {
			// The type is incorrect
			throw new IOException("RPC server sent incorrect type: " + response.type());
		}
		
		// We must have received an OK message. Validate the message id.
		RPCNormalResponseMessage okResponse = (RPCNormalResponseMessage) response;
		
		if (okResponse.callid() != connectMessage.id()) {
			throw new IOException("RPC message id's do not match");
		}
		
		// Determine whether the server  allows persistence
		JSONObject value = okResponse.value();
		if (wantPersistent && value.has("connection") && value.getString("connection").equals("keep-alive")) {
			Log.d(TAG, "Server and client agree to use persistence");
			persistent = true;
		}
	}
	
	synchronized public JSONObject invoke(String serviceName, String method, JSONObject userRequest, int timeout) 
			throws IOException, JSONException {
		messageHandler.setTimeout(timeout);
		
		// Send the invocation message
		Log.d(TAG, "Sending RPC invocation");
		RPCInvokeMessage invokeMessage = new RPCInvokeMessage(serviceName, method, userRequest);
		messageHandler.sendMessage(invokeMessage.marshall());
		
		// Read a response from the server
		Log.d(TAG, "Waiting for invocation response");
		RPCMessage response = RPCMessage.unmarshall(messageHandler.readMessageAsString());
		Log.d(TAG, "Invocation response received: " + response.mObject);
		
		if ("ERROR".equals(response.type())) {
			// A server error occurred
			String message = response.mObject.optString("message");
			throw new IOException("RPC server error : " + message);
		} else if (!"OK".equals(response.type())) {
			// The type is incorrect
			throw new IOException("RPC server sent incorrect type: " + response.type());
		}
		
		// We must have received an OK message. Validate the message id.
		RPCNormalResponseMessage okResponse = (RPCNormalResponseMessage) response;
				
		if (okResponse.callid() != invokeMessage.id()) {
			throw new IOException("RPC message id's do not match");
		}
		
		// The message is well-formed. Return the result
		Log.d(TAG, "Invocation response is valid, returning to client");
		return okResponse.value();
	}
	
	/**
	 * Returns whether this socket is persistent
	 */
	public boolean isPersistent() {
		return persistent;
	}
	
	/**
	 * Close this socket.
	 */
	synchronized public void discard() {
		messageHandler.close();
	}
}
