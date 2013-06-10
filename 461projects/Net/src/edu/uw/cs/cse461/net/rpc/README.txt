CSE 461 Lab 3
Conor Reisman and Jackson Roberts

Here's a description of our persistence implementation:

**** Client ****
The RPCCall class keeps a cache of sockets, each uniquely identified by their
host and address. RPCCall also stores the last time a socket was used. When
possible, RPC calls are performed using a previously cached socket. If this
fails, the call can optionally be re-tried on a new socket. A Timer task
periodically evicts connections that have been unused for longer than the
persistence timeout.

RPCCall and RPCCallerSocket also fully supports operation without persistence,
if the server indicates it does not support keep-alive.

**** Server ****
The server spins up a new thread for each client connection it receives, by
creating new instances of the RPCService.RPCCallResponder class. This thread
awaits calls from the client, and responds as necessary. If more than
net.timeout.granularity time passes without a response from the client,
it checks to see whether it should shut down the socket. A socket is shut down
if the RPC service has been instructed to close, if persistence is enabled and
the persistence timeout has been exceeded, or if persistence is disabled and
the network time has been exceeded.


Here is a comparison of the raw and TCPMessageHandler implementations of ping
and dataxfer:

The following data was collected using 10 trials:

        Ping          DataXfer

RPC:   80.26 ms      11915 bytes/sec

RAW:    0.35 ms     693173 bytes/sec

Note that the raw implementations of both applications perform significantly
better. We believe this is due to TCPMessageHandler's use of JSON (which
encodes data less efficiently than raw transmittal, and needs to be parsed
at both the client and server end), as well as the overhead of using an RPC
handshake.