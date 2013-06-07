package edu.uw.cs.cse461.net.rpc;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;

import org.json.JSONException;
import org.json.JSONObject;

import edu.uw.cs.cse461.net.base.NetBase;
import edu.uw.cs.cse461.net.base.NetLoadable.NetLoadableService;
import edu.uw.cs.cse461.net.tcpmessagehandler.TCPMessageHandler;
import edu.uw.cs.cse461.util.Log;

/**
 * Class implementing the caller side of RPC -- the RPCCall.invoke() method.
 * The invoke() method itself is static, for the convenience of the callers,
 * but this class is a normal, loadable, service.
 * <p>
 * <p>
 * This class is responsible for implementing persistent connections. 
 * (What you might think of as the actual remote call code is in RCPCallerSocket.java.)
 * Implementing persistence requires keeping a cache that must be cleaned periodically.
 * We do that using a cleaner thread.
 * 
 * @author zahorjan
 *
 */
public class RPCCall extends NetLoadableService {
	private static final String TAG="RPCCall";

	//-------------------------------------------------------------------------------------------
	//-------------------------------------------------------------------------------------------
	// The static versions of invoke() are just a convenience for caller's -- it
	// makes sure the RPCCall service is actually running, and then invokes the
	// the code that actually implements invoke.
	
	/**
	 * Invokes method() on serviceName located on remote host ip:port.
	 * @param ip Remote host's ip address
	 * @param port RPC service port on remote host
	 * @param serviceName Name of service to be invoked
	 * @param method Name of method of the service to invoke
	 * @param userRequest Arguments to call
	 * @param socketTimeout Maximum time to wait for a response, in msec.
	 * @return Returns whatever the remote method returns.
	 * @throws JSONException
	 * @throws IOException
	 */
	public static JSONObject invoke(
			String ip,				  // ip or dns name of remote host
			int port,                 // port that RPC is listening on on the remote host
			String serviceName,       // name of the remote service
			String method,            // name of that service's method to invoke
			JSONObject userRequest,   // arguments to send to remote method,
			int socketTimeout         // timeout for this call, in msec.
			) throws JSONException, IOException {
		RPCCall rpcCallObj =  (RPCCall)NetBase.theNetBase().getService( "rpccall" );
		if ( rpcCallObj == null ) throw new IOException("RPCCall.invoke() called but the RPCCall service isn't loaded");
		return rpcCallObj._invoke(ip, port, serviceName, method, userRequest, socketTimeout, true);
	}
	
	/**
	 * A convenience implementation of invoke() that doesn't require caller to set a timeout.
	 * The timeout is set to the net.timeout.socket entry from the config file, or 2 seconds if that
	 * doesn't exist.
	 */
	public static JSONObject invoke(
			String ip,				  // ip or dns name of remote host
			int port,                 // port that RPC is listening on on the remote host
			String serviceName,       // name of the remote service
			String method,            // name of that service's method to invoke
			JSONObject userRequest    // arguments to send to remote method,
			) throws JSONException, IOException {
		int socketTimeout  = NetBase.theNetBase().config().getAsInt("net.timeout.socket", 2000);
		return invoke(ip, port, serviceName, method, userRequest, socketTimeout);
	}

	//-------------------------------------------------------------------------------------------
	//-------------------------------------------------------------------------------------------

	/**
	 * Maps a host address to a cached socket to that host. This field is used
	 * as a lock on the cache.
	 */
	private Map<HostAddress, RPCCallerSocket> socketCache;

	/**
	 * Maps a host address to the last time (in milliseconds) that socket was
	 * used. Lock socketCache before modifying.
	 */
	private Map<HostAddress, Long> socketLastUsed;
	
	/**
	 * A timer, which periodically evicts sockets from the cache
	 */
	private Timer timer;
	
	/**
	 * The infrastructure requires a public constructor taking no arguments.  Plus, we need a constructor.
	 */
	public RPCCall() {
		super("rpccall");

		// Construct the socket cache
		socketCache = new HashMap<HostAddress, RPCCallerSocket>();
		socketLastUsed = new HashMap<HostAddress, Long>();
		
		// Start the cache evictor
		int persistenceTimeout = NetBase.theNetBase().config().getAsInt("rpc.persistence.timeout", 30000);
		timer = new Timer();
		timer.scheduleAtFixedRate(new CacheEvictor(persistenceTimeout), persistenceTimeout, persistenceTimeout);
	}
	
	/**
	 * This private method performs the actual invocation, including the management of persistent connections.
	 * Note that because we may issue the call twice, we  may (a) cause it to be executed twice at the server(!),
	 * and (b) may end up blocking the caller for around twice the timeout specified in the call. (!)
	 * 
	 * @param ip
	 * @param port
	 * @param serviceName
	 * @param method
	 * @param userRequest
	 * @param socketTimeout Max time to wait for this call
	 * @param tryAgain Set to true if you want to repeat call if a socket error occurs; e.g., persistent socket is no good when you use it
	 * @return
	 * @throws JSONException
	 * @throws IOException
	 */
	private JSONObject _invoke(
			String ip,				  // ip or dns name of remote host
			int port,                 // port that RPC is listening on on the remote host
			String serviceName,       // name of the remote service
			String method,            // name of that service's method to invoke
			JSONObject userRequest,   // arguments to send to remote method
			int socketTimeout,        // max time to wait for reply
			boolean tryAgain          // true if an invocation failure on a persistent connection should cause a re-try of the call, false to give up
			) throws JSONException, IOException {
		
		RPCCallerSocket socket = getSocket(ip, port);
		JSONObject result;
		
		try {
			result = socket.invoke(serviceName, method, userRequest, socketTimeout);
		} catch (IOException e) {
			// If the previous invocation failed, re-try once with a new socket
			if (tryAgain) {
				synchronized (socketCache) {
					socketCache.remove(new HostAddress(ip, port));
				}
				return _invoke(ip, port, serviceName, method, userRequest, socketTimeout, false);
			} else {
				throw e;
			}
		}
		return result;
	}
	
	/**
	 * Returns a socket to the given host. Returns a cached socket if possible,
	 * otherwise creates a new socket.
	 * 
	 * @param hostname
	 *            the destination ip address
	 * @param port
	 *            the destination port
	 * @return a socket to the specified host
	 * @throws IOException
	 *             if errors are encountered when communicating with the host
	 * @throws JSONException
	 *             if errors are encountered when parsing JSON messages to or
	 *             from host
	 */
	private RPCCallerSocket getSocket(String hostname, int port) throws IOException, JSONException {
		synchronized(socketCache) {
			HostAddress key = new HostAddress(hostname, port);
			if (socketCache.containsKey(key)) {
				return socketCache.get(key);
			}
			RPCCallerSocket newSocket = new RPCCallerSocket(hostname, port, true);
			
			if (newSocket.isPersistent()) {
				socketCache.put(key, newSocket);
				socketLastUsed.put(key, System.currentTimeMillis());
			}
			
			return newSocket;
		}
	}
	
	@Override
	public void shutdown() {
	}
	
	@Override
	public String dumpState() {
		synchronized (socketCache) {
			StringBuilder builder = new StringBuilder();
			builder.append("Current persistent connections are:\n");
			
			for (HostAddress key : socketCache.keySet()) {
				builder.append("    Socket '" + key + "' last used at " + socketLastUsed.get(key) + "\n");
			}
			
			return builder.toString();
		}
	}
	
	/**
	 * A HostAddress is a hostname/port pair. Used to uniquely identify sockets in the cache.
	 */
	private class HostAddress {
		private String hostname;
		private int port;
		
		public HostAddress(String hostname, int port) {
			this.hostname = hostname;
			this.port = port;
		}
		
		@Override
		public int hashCode() {
			return hostname.hashCode() * port;
		}
		
		@Override
		public boolean equals(Object other) {
			if (! (other instanceof HostAddress))
				return false;
			
			HostAddress cast = (HostAddress) other;
			return port == cast.port && hostname.equals(cast.hostname);
		}
		
		@Override
		public String toString() {
			return hostname + ":" + port;
		}
	}
	
	/**
	 * A task that periodically evicts old sockets
	 */
	private class CacheEvictor extends TimerTask {
		private static final String TAG = "RPCCall:CacheEvictor";

		private int evictionTime;
		
		public CacheEvictor(int evictionTime) {
			this.evictionTime = evictionTime;
		}
		
		/**
		 * Removes any sockets older than evictionTime from the cache
		 */
		@Override
		public void run() {
			synchronized (socketCache) {
				long now = System.currentTimeMillis();
				Log.d(TAG, "Beginning cache eviction at " + now);
				
				// Iterate over the cached sockets, and remove those that were used more than
				// evictionTime milliseconds ago
				Iterator<Entry<HostAddress, Long>> iter = socketLastUsed.entrySet().iterator();
				while (iter.hasNext()) {
					Entry<HostAddress, Long> entry = iter.next();
					HostAddress key = entry.getKey();
					long lastUsed = entry.getValue();
					
					if (lastUsed + evictionTime < now) {
						iter.remove();
						socketCache.get(key).discard();
						socketCache.remove(key);
						Log.d(TAG, "Discarded socket '" + key + "' last used at " + lastUsed);
					}
				}
				
				Log.d(TAG, "Cache eviction complete");
			}
		}	
	}
}
