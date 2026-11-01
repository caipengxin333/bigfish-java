package cn.bigfish.playback;

import cn.bigfish.contract.PlaybackAck;
import com.fasterxml.jackson.databind.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** At most eight connections, sixteen outbound frames each; ingress never writes to a socket. */
public final class StageSocketHandler extends TextWebSocketHandler {
    private final Map<String, SocketPeer> peers = new ConcurrentHashMap<>();
    private final PlaybackCoordinator playback;
    private final ObjectMapper mapper;
    public StageSocketHandler(PlaybackCoordinator playback, ObjectMapper mapper) {
        this.playback = playback; this.mapper = mapper;
    }
    @Override public synchronized void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        if (peers.size() >= 8 || peers.containsKey(socket.getId())) { socket.close(CloseStatus.POLICY_VIOLATION); return; }
        socket.setTextMessageSizeLimit(8192);
        var peer = new SocketPeer(socket);
        peers.put(socket.getId(), peer);
        Thread.ofVirtual().name("stage-writer-" + socket.getId()).start(peer::write);
    }
    @Override protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
        var peer = peerFor(socket);
        if (peer == null || peer.closing) return;
        try {
            if (!peer.allowMessage()) throw new IllegalArgumentException();
            JsonNode frame = mapper.readTree(message.getPayload());
            if (frame.path("schemaVersion").asInt() != 1 || !text(frame, "messageId", 128)) throw new IllegalArgumentException();
            String session = frame.path("sessionId").asText();
            JsonNode payload = frame.path("payload");
            if (!payload.isObject()) throw new IllegalArgumentException();
            String type = frame.path("type").asText();
            if ("PLAYER_HELLO".equals(type)) {
                if (!text(payload,"playerId",128)) throw new IllegalArgumentException();
                peer.hello = true;
                playback.hello(peer, payload.path("playerId").asText());
            } else if ("PLAYBACK_ACK".equals(type)) {
                var ack = mapper.treeToValue(payload, PlaybackAck.class);
                if (!session.equals(ack.sessionId()) || ack.state() == null || ack.occurredAt() == null) throw new IllegalArgumentException();
                playback.acknowledge(peer, ack);
            } else if ("PLAYER_READY".equals(type) || "PLAYER_HEARTBEAT".equals(type)) {
                if (!text(payload,"playerId",128) || !payload.path("leaseGeneration").canConvertToLong()) throw new IllegalArgumentException();
                String id = payload.path("playerId").asText(); long generation = payload.path("leaseGeneration").longValue();
                if ("PLAYER_READY".equals(type)) playback.ready(peer,session,id,generation);
                else playback.heartbeat(peer,session,id,generation);
            } else throw new IllegalArgumentException();
        } catch (Exception ignored) {
            // Do not log frames, payloads or deserializer exception messages.
            playback.disconnecting(peer); peer.close();
        }
    }
    @Override public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        var peer = peerFor(socket);
        if (peer != null && peers.remove(socket.getId(),peer)) { playback.disconnected(peer); peer.close(); }
    }
    @Override public void handleTransportError(WebSocketSession socket, Throwable error) {
        var peer = peerFor(socket);
        if (peer != null) { playback.disconnecting(peer); peer.close(); }
    }
    private SocketPeer peerFor(WebSocketSession socket) {
        var peer=peers.get(socket.getId());
        return peer!=null && peer.socket==socket ? peer : null;
    }
    @Scheduled(fixedDelay = 1000)
    public void pruneIdleConnections() {
        for (var peer : peers.values()) {
            if (!peer.hello && System.nanoTime() - peer.createdAt > TimeUnit.SECONDS.toNanos(10)) peer.close();
        }
    }
    private static boolean text(JsonNode node, String key, int max) {
        return node.path(key).isTextual() && !node.path(key).asText().isBlank() && node.path(key).asText().length() <= max;
    }
    private record Frame(String type, String session, Object payload) {}
    private final class SocketPeer implements PlaybackCoordinator.Peer {
        private final WebSocketSession socket;
        private final ArrayBlockingQueue<Frame> outbound = new ArrayBlockingQueue<>(16);
        private final long createdAt = System.nanoTime();
        private long rateWindow = createdAt;
        private int count;
        private volatile boolean closing, hello;
        SocketPeer(WebSocketSession socket) { this.socket = socket; }
        public String id() { return socket.getId(); }
        public boolean send(String type, String session, Object payload) {
            return !closing && outbound.offer(new Frame(type, session, payload));
        }
        public synchronized void close() {
            if (closing) return;
            closing = true;
            if (!outbound.offer(new Frame("CLOSE", "", Map.of()))) {
                outbound.clear(); outbound.offer(new Frame("CLOSE", "", Map.of()));
            }
        }
        boolean allowMessage() {
            if (System.nanoTime() - rateWindow >= TimeUnit.SECONDS.toNanos(1)) { count = 0; rateWindow = System.nanoTime(); }
            return ++count <= 30;
        }
        void write() {
            try {
                while (socket.isOpen()) {
                    var frame = outbound.take();
                    if (frame.type().equals("CLOSE")) break;
                    if (frame.type().equals("PLAY_TASK") && !playback.deliveryAllowed(this,
                        ((cn.bigfish.contract.PlaybackTask)frame.payload()).taskId())) continue;
                    socket.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of("schemaVersion",1,
                        "messageId",UUID.randomUUID().toString(),"type",frame.type(),"sessionId",frame.session(),"payload",frame.payload()))));
                }
            } catch (Exception ignored) {
                // Socket failure is handled by lease revocation; never include raw frames in logs.
            } finally {
                closing = true;
                playback.disconnecting(this);
                try { socket.close(); } catch (Exception ignored) {}
                // close() may block or fail. Never claim a live transport has already closed.
                // A failed close retains its bounded connection slot until the framework callback.
                if (!socket.isOpen()) {
                    playback.disconnected(this);
                    peers.remove(id(),this);
                }
            }
        }
    }
}
