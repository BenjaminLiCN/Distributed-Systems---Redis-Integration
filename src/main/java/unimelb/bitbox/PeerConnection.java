package unimelb.bitbox;

import unimelb.bitbox.messages.HandshakeRequest;
import unimelb.bitbox.messages.InvalidProtocol;
import unimelb.bitbox.messages.Message;
import unimelb.bitbox.messages.ReceivedMessage;
import unimelb.bitbox.util.Configuration;
import unimelb.bitbox.util.HostPort;

import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A PeerConnection is a combination of an OutgoingConnection (used to write to the socket) and an IncomingConnectionTCP
 * (used to read from the socket). The OutgoingConnection has a BlockingQueue; messages to be sent should be placed in
 * this queue. The IncomingConnectionTCP relays messages to the ServerThread's queue.
 */
public abstract class PeerConnection {
    private CountDownLatch activated = new CountDownLatch(1);

    private final String name;
    private boolean wasOutgoing;
    protected OutgoingConnection outConn;
    public final ServerMain server;

    private HostPort localHostPort;
    private HostPort hostPort;

    public void forceIncoming() {
        wasOutgoing = false;
    }

    // ELEANOR: Added INACTIVE so that a peer waiting to be closed doesn't keep sending more messages.
    public enum State {
        WAIT_FOR_REQUEST,
        WAIT_FOR_RESPONSE,
        ACTIVE,
        CLOSED,
        INACTIVE
    }

    protected State state;

    public boolean awaitActivation() throws InterruptedException {
        activated.await();
        return getState() == State.ACTIVE;
    }

    // Returns the current state of the PeerConnection
    State getState() {
        synchronized (this) {
            return state;
        }
    }

    void activate(HostPort hostPort) {
        if (server.getActivePeers().stream().anyMatch(p -> p.equals(this))) {
            close();
        } else {
            synchronized (this) {
                // Don't do anything if we're not waiting to be activated.
                if (state == State.WAIT_FOR_RESPONSE || state == State.WAIT_FOR_REQUEST) {
                    ServerMain.log.info("Activating " + getForeignName());
                    this.hostPort = hostPort;
                    state = State.ACTIVE;
                }
            }
            activated.countDown();
        }
    }

    private void deactivate() {
        synchronized (this) {
            state = State.INACTIVE;
        }
    }

    // Returns true if this peer was an outgoing connection (i.e. was in the peers list)
    boolean getOutgoing() {
        return wasOutgoing;
    }

    /**
     * Returns a HostPort object representing the actual host and port of the connected peer,
     * to the best of our knowledge.
     * @return the host and port
     */
    public HostPort getHostPort() {
        return hostPort;
    }
    /**
     * Returns a HostPort object representing the host and port that was specified at the start of the connection.
     * May not be the correct host and port to send messages back to.
     * @return the host and port
     */
    public HostPort getLocalHostPort() {
        return localHostPort;
    }

    String getName() {
        return name;
    }

    public String getForeignName() {
        return getName() + "-" + hostPort;
    }

    PeerConnection(String name, ServerMain server, State state, String host, int port, OutgoingConnection outConn) {
        ServerMain.log.info("Peer created: " + name + " @ " + host + ":" + port);
        this.name = name;
        this.server = server;
        this.state = state;
        this.outConn = outConn;
        localHostPort = new HostPort(host, port);
        hostPort = localHostPort;

        outConn.setParent(this);
        outConn.start();
        wasOutgoing = state == State.WAIT_FOR_RESPONSE;

        if (wasOutgoing) {
            ServerMain.log.info(getForeignName() + ": Sending handshake request");
            sendMessageInternal(new HandshakeRequest());
        }
    }

    // Closes this peer.
    public void close() {
        synchronized (this) {
            if (state == State.CLOSED) {
                return;
            }
            state = State.CLOSED;
        }
        activated.countDown();
        outConn.deactivate();
        server.closeConnection(this);
        ServerMain.log.warning("Connection to peer `" + getForeignName() + "` closed.");
        closeInternal();
    }

    protected abstract void closeInternal();

    /**
     * Send a message to this peer. Validates the state first.
     */
    public void sendMessage(Message message) {
        sendMessage(message, () -> {});
    }

    /**
     * Send a message to this peer, and then close the connection.
     */
    public void sendMessageAndClose(Message message) {
        sendMessage(message, this::close);
        deactivate();
    }

    /**
     * Implementation of the actual sendMessage code, to allow default parameters.
     */
    private void sendMessage(Message message, Runnable onSent) {
        // EXTENSION: Allow lack of handshakes.
        State state = getState();
        if (message.getCommand().equals(Message.INVALID_PROTOCOL) || state == State.ACTIVE) {
            sendMessageInternal(message, onSent);
        } else if (state != State.CLOSED && state != State.INACTIVE) {
            sendMessageInternal(new InvalidProtocol(this, "handshake must be completed first"), this::close);
        }
    }

    /**
     * Send a message to this peer, regardless of state.
     */
    protected void sendMessageInternal(Message message) {
        sendMessageInternal(message, () -> {
        });
    }

    /**
     * Send a message to this peer, regardless of state. Allows a function to run after the message is sent.
     */
    protected void sendMessageInternal(Message message, Runnable onSent) {
        synchronized (this) {
            if (state == State.CLOSED) {
                return;
            }
        }
        message.setFriendlyName(name + "-" + ServerMain.getHostPort());
        ServerMain.log.info(getForeignName() + " sent: " + message.getCommand());
        outConn.addMessage(new OutgoingMessage(message.encode(), onSent));
    }

    /**
     * This method is called when a message is received from this peer.
     */
    public void receiveMessage(String message) {
        server.enqueueMessage(new ReceivedMessage(message, this));
    }

    /**
     * This method is called when a message has been received from this peer and successfuly parsed.
     */
    public void notify(Message message) {}

    public String toString() {
        String address = hostPort.asAddress();
        String alias = hostPort.asAliasedAddress();
        if (!address.equals(alias)) {
            address += " (" + alias + ")";
        }

        return name + " @ " + address;
    }

    public boolean equals(Object other) {
        if (other instanceof PeerConnection) {
            return ((PeerConnection)other).hostPort.fuzzyEquals(hostPort) || ((PeerConnection)other).localHostPort.fuzzyEquals(hostPort);
        }
        return false;
    }
}

class PeerTCP extends PeerConnection {
    private Socket socket;

    PeerTCP(String name, Socket socket, ServerMain server, State state) {
        super(name, server, state, socket.getInetAddress().getHostAddress(), socket.getPort(), new OutgoingConnectionTCP(socket));
        IncomingConnectionTCP inConn = new IncomingConnectionTCP(socket, this);
        inConn.start();

        this.socket = socket;
    }

    @Override
    protected void closeInternal() {
        try {
            socket.close();
            System.out.println("Closed TCP socket");
        } catch (IOException e) {
            ServerMain.log.severe("Error closing socket: " + e.getMessage());
        }
    }
}

class PeerUDP extends PeerConnection {
    private Map<String, RetryThread> retryThreads;
    private Map<String, RetryThread> getRetryThreads() {
        if (retryThreads == null) {
            retryThreads = new ConcurrentHashMap<>();
        }
        return retryThreads;
    }

    // EXTENSION: Allow sockets with the wrong port
    /*@Override
    void activateDefault(String host, long port) {
        synchronized (this) {
            if (state != State.CLOSED && state != State.INACTIVE) {
                // UDP peer already has accurate host and port
                state = State.ACTIVE;
                KnownPeerTracker.addAddress(getHost() + ":" + getPort());
            }
        }
    }*/

    PeerUDP(String name, ServerMain server, State state, DatagramSocket socket, DatagramPacket packet) {
        super(name, server, state,packet.getAddress().toString().split("/")[1],
              packet.getPort(), new OutgoingConnectionUDP(socket, packet));
    }

    @Override
    // Override to not close the socket, as it's shared between all peers.
    protected void closeInternal() {
        getRetryThreads().forEach((ignored, thread) -> thread.kill());
    }

    public void retryMessage(Message message) {
        sendMessageInternal(message);
    }

    @Override
    protected void sendMessageInternal(Message message, Runnable onSent) {
        if (message.isRequest() && !getRetryThreads().containsKey(message.getSummary())) {
            ServerMain.log.info(getForeignName() + ": waiting for response: " + message.getSummary());
            RetryThread thread = new RetryThread(this, message);
            getRetryThreads().put(message.getSummary(), thread);
            thread.start();
        }
        super.sendMessageInternal(message, onSent);
    }

    @Override
    public void notify(Message message) {
        if (!message.isRequest()) {
            ServerMain.log.info(getForeignName() + ": notified response: " + message.getSummary());
            Optional.ofNullable(getRetryThreads().get(message.getSummary()))
                    .ifPresent(t -> {
                        t.interrupt();
                        synchronized (retryThreads) {
                            getRetryThreads().remove(message.getSummary());
                        }
                    });
        }
    }

    private class RetryThread extends Thread {
        private Message message;
        private PeerUDP parent;
        private final int RETRY_COUNT;
        private final int RETRY_TIME;
        private int retries = 0;

        public RetryThread(PeerUDP parent, Message message) {
            this.parent = parent;
            this.message = message;

            // Load settings
            RETRY_COUNT = Integer.parseInt(Configuration.getConfigurationValue("udpRetries"));
            RETRY_TIME = Integer.parseInt(Configuration.getConfigurationValue("udpTimeout"));
        }

        private boolean shouldRetry() {
            synchronized (this) {
                return retries < RETRY_COUNT;
            }
        }

        public void kill() {
            synchronized (this) {
                retries = RETRY_COUNT;
                interrupt();
            }
        }

        @Override
        public void run() {
            while (shouldRetry()) {
                try {
                    Thread.sleep(RETRY_TIME);
                } catch (InterruptedException e) {
                    return;
                }
                ServerMain.log.info(parent.getForeignName() + ": resending "  + message.getCommand() + " (" + retries + ")");
                parent.retryMessage(message);

                synchronized (this) {
                    ++retries;
                }
            }
            ServerMain.log.warning(parent.getForeignName() + ": timed out: " + message.getCommand());
            parent.close();
        }
    }
}


/**
 * A class to pair a message with a function to run when the message is sent.
 */
class OutgoingMessage {
    public final String message;
    public final Runnable onSent;

    public OutgoingMessage(String message, Runnable onSent) {
        this.message = message;
        this.onSent = onSent;
    }
}

abstract class OutgoingConnection extends Thread {
    private PeerConnection parent;

    private AtomicBoolean active = new AtomicBoolean(true);
    private final BlockingQueue<OutgoingMessage> messages = new LinkedBlockingQueue<>();
    void deactivate() {
        active.set(false);
        interrupt();
    }

    boolean isActive() {
        return !isInterrupted() && active.get();
    }

    protected void addMessage(OutgoingMessage message) {
        messages.add(message);
    }
    protected OutgoingMessage takeMessage() throws InterruptedException {
        return messages.take();
    }
    protected void closeParent() {
        if (parent != null) {
            parent.close();
        }
    }

    public void setParent(PeerConnection peer) {
        parent = peer;
    }
}

class OutgoingConnectionTCP extends OutgoingConnection {
    private Socket socket;

    OutgoingConnectionTCP(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            while (!socket.isClosed() && isActive()) {
                OutgoingMessage message = takeMessage();
                out.write(message.message + "\n");
                out.flush();
                message.onSent.run();
            }
        } catch (IOException | InterruptedException e) {
            ServerMain.log.severe("Error writing to socket: " + e.getMessage());
        }
        closeParent();
    }
}
class OutgoingConnectionUDP extends OutgoingConnection {
    private DatagramSocket udpSocket;
    private DatagramPacket packet;

    OutgoingConnectionUDP(DatagramSocket socket, DatagramPacket packet) {
        this.udpSocket = socket;
        this.packet = packet;
    }

    @Override
    public void run() {
        ServerMain.log.info("Outgoing thread starting");
        while (!udpSocket.isClosed() && isActive()) {
            try {
                OutgoingMessage message = takeMessage();
                byte[] buffer = (message.message + "\n").getBytes(StandardCharsets.UTF_8);
                packet.setData(buffer);
                packet.setLength(buffer.length);
                udpSocket.send(packet);
                message.onSent.run();
            } catch (IOException e) {
                ServerMain.log.severe("Error sending packet to UDP socket: " + e.getMessage());
            } catch (InterruptedException ignored) {
            }
        }
        closeParent();
    }
}

class IncomingConnectionTCP extends Thread {
    private Socket socket;
    // the PeerConnection object that will forward our received messages
    private PeerConnection consumer;

    IncomingConnectionTCP(Socket socket, PeerConnection consumer) {
        this.socket = socket;
        this.consumer = consumer;
    }

    @Override
    public void run() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            String message;
            while ((message = in.readLine()) != null) {
                consumer.receiveMessage(message);
            }
        } catch (IOException e) {
            if (consumer.getState() != PeerConnection.State.CLOSED) {
                ServerMain.log.severe("Error reading from socket: " + e.getMessage());
            }
        }
        consumer.close();
    }
}