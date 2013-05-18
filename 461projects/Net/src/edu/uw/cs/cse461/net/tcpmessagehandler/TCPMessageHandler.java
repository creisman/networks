package edu.uw.cs.cse461.net.tcpmessagehandler;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import edu.uw.cs.cse461.util.Log;

/**
 * Sends/receives a message over an established TCP connection. To be a message means the unit of write/read is
 * demarcated in some way. In this implementation, that's done by prefixing the data with a 4-byte length field.
 * <p>
 * Design note: TCPMessageHandler cannot usefully subclass Socket, but rather must wrap an existing Socket, because
 * servers must use ServerSocket.accept(), which returns a Socket that must then be turned into a TCPMessageHandler.
 * 
 * @author zahorjan
 * 
 */
public class TCPMessageHandler implements TCPMessageHandlerInterface {
    private static final String TAG = "TCPMessageHandler";

    // --------------------------------------------------------------------------------------
    // helper routines
    // --------------------------------------------------------------------------------------

    /**
     * We need an "on the wire" format for a binary integer. This method encodes into that format, which is little
     * endian (low order bits of int are in element [0] of byte array, etc.).
     * 
     * @param i
     * @return A byte[4] encoding the integer argument.
     */
    protected static byte[] intToByte(int i) {
        ByteBuffer b = ByteBuffer.allocate(4);
        b.order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(i);
        byte buf[] = b.array();
        return buf;
    }

    /**
     * We need an "on the wire" format for a binary integer. This method decodes from that format, which is little
     * endian (low order bits of int are in element [0] of byte array, etc.).
     * 
     * @param buf
     * @return
     */
    protected static int byteToInt(byte buf[]) {
        ByteBuffer b = ByteBuffer.wrap(buf);
        b.order(ByteOrder.LITTLE_ENDIAN);
        return b.getInt();
    }

    /**
     * The socket used for TCP communication
     */
    private final Socket sock;

    /**
     * The maximum allowed size for which decoding of a message will be attempted
     */
    private int maxReadLength;

    /**
     * Constructor, associating this TCPMessageHandler with a connected socket.
     * 
     * @param sock
     * @throws IOException
     */
    public TCPMessageHandler(Socket sock) throws IOException {
        this.sock = sock;
    }

    /**
     * Closes the underlying socket and renders this TCPMessageHandler useless.
     */
    @Override
    public void close() {
        try {
            sock.close();
        } catch (IOException e) {
            Log.e(TAG, "IOException encountered during close: " + e.getMessage());
        }
    }

    /**
     * Set the read timeout on the underlying socket.
     * 
     * @param timeout
     *            Time out, in msec.
     * @return The previous time out.
     */
    @Override
    public int setTimeout(int timeout) throws SocketException {
        int prevTimeout = sock.getSoTimeout();
        sock.setSoTimeout(timeout);
        return prevTimeout;
    }

    /**
     * Enable/disable TCPNoDelay on the underlying TCP socket.
     * 
     * @param value
     *            The value to set
     * @return The old value
     */
    @Override
    public boolean setNoDelay(boolean value) throws SocketException {
        boolean prevVal = sock.getTcpNoDelay();
        sock.setTcpNoDelay(value);
        return prevVal;
    }

    /**
     * Sets the maximum allowed size for which decoding of a message will be attempted.
     * 
     * @return The previous setting of the maximum allowed message length.
     */
    @Override
    public int setMaxReadLength(int maxLen) {
        return maxReadLength = maxLen;
    }

    /**
     * Returns the current setting for the maximum read length
     */
    @Override
    public int getMaxReadLength() {
        return maxReadLength;
    }

    // --------------------------------------------------------------------------------------
    // send routines
    // --------------------------------------------------------------------------------------

    @Override
    public void sendMessage(byte[] buf) throws IOException {
        // Start by writing the length
        OutputStream os = sock.getOutputStream();
        os.write(intToByte(buf.length));

        // Next, send the data
        os.write(buf);
    }

    /**
     * Uses str.getBytes() for conversion.
     */
    @Override
    public void sendMessage(String str) throws IOException {
        sendMessage(str.getBytes());
    }

    /**
     * We convert the int to the one the wire format and send as bytes.
     */
    @Override
    public void sendMessage(int value) throws IOException {
        sendMessage(intToByte(value));
    }

    /**
     * Sends JSON string representation of the JSONArray.
     */
    @Override
    public void sendMessage(JSONArray jsArray) throws IOException {
        sendMessage(jsArray.toString());
    }

    /**
     * Sends JSON string representation of the JSONObject.
     */
    @Override
    public void sendMessage(JSONObject jsObject) throws IOException {
        sendMessage(jsObject.toString());
    }

    // --------------------------------------------------------------------------------------
    // read routines
    // All of these invert any encoding done by the corresponding send method.
    // --------------------------------------------------------------------------------------

    @Override
    public byte[] readMessageAsBytes() throws IOException {
        InputStream is = sock.getInputStream();

        // Read the length
        byte lengthBuf[] = new byte[4];
        int read = is.read(lengthBuf);
        if (read == -1) {
            throw new EOFException("EOF reached on socket");
        } else if (read < 3) {
            throw new IOException("Message length too short");
        }
        int length = byteToInt(lengthBuf);

        // Sanity check the length
        if (length < 0) {
            throw new IOException("Negative length");
        }
        if (length > maxReadLength) {
            throw new IOException("Length larger than getMaxReadLength()");
        }

        // Read the payload and return
        byte payload[] = new byte[length];
        is.read(payload);

        return payload;
    }

    @Override
    public String readMessageAsString() throws IOException {
        return new String(readMessageAsBytes());
    }

    @Override
    public int readMessageAsInt() throws IOException {
        return byteToInt(readMessageAsBytes());
    }

    @Override
    public JSONArray readMessageAsJSONArray() throws IOException, JSONException {
        return new JSONArray(readMessageAsString());
    }

    @Override
    public JSONObject readMessageAsJSONObject() throws IOException, JSONException {
        return new JSONObject(readMessageAsString());
    }
}
