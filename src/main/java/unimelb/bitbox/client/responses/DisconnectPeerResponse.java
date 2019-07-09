package unimelb.bitbox.client.responses;

import unimelb.bitbox.PeerConnection;
import unimelb.bitbox.ServerMain;
import unimelb.bitbox.util.HostPort;

/**
 * Generates the message content of DISCONNECT_PEER
 * to be sent by a Peer to a Client.
 *
 * Known issue in the design:
 * due to the design issue of Bitbox,
 * host name "localhost" may sometimes be stored as "127.0.0.1".<br/>
 *
 * For example, if a peer tries to disconnect from "localhost:8114" and
 * the peer was stored as "127.0.0.1:8114", the attempt would fail
 * given the existing constraints.
 */
class DisconnectPeerResponse extends ClientResponse {

    protected DisconnectPeerResponse(ServerMain server, HostPort hostPort) {
        response.append("command", "DISCONNECT_PEER_RESPONSE");

        final String SUCCESS = "disconnected from peer";
        String reply = SUCCESS;

        PeerConnection peer = server.getPeer(hostPort);
        if (peer != null) {
            server.closeConnection(peer);
        } else {
            reply = "connection not active";
        }

        response.append("host", hostPort.hostname);
        response.append("port", hostPort.port);
        response.append("status", reply == SUCCESS);
        response.append("message", reply);
    }

}
