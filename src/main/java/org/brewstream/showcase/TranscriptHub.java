package org.brewstream.showcase;

import org.brewstream.barista.spec.BrewId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live transcript of each relay, pushed to the relay page over a WebSocket.
 *
 * <p>The page connects to {@code /ws/transcript?brew=<id>} and only listens. The
 * server answers with JSON text messages in the shape inference4j's own live
 * transcription demo uses, so the same rendering works for both:
 * <ul>
 *   <li>{@code {"type":"ready","brew":..,"attached":false,"status":..}} on connect, where
 *       {@code attached} says whether anything is producing a transcript yet and
 *       {@code status} is the transcriber's latest word on what it is doing, or null;</li>
 *   <li>{@code {"type":"status","text":..}} when that changes: waiting for an AAC
 *       track, loading the model, listening, or why it cannot;</li>
 *   <li>{@code {"type":"started"|"changed"|"completed","id":..,"text":..,"start":..,"end":..}}
 *       for each line, times in seconds on the relay's timeline. A line is
 *       {@code started}, then {@code changed} as words are revised, then
 *       {@code completed} once final;</li>
 *   <li>{@code {"type":"closed"}} when the relay is deleted.</li>
 * </ul>
 *
 * <p>Lines come from {@link RelayTranscriber}.
 */
@Component
public class TranscriptHub extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TranscriptHub.class);
    private static final String BREW = "brew";

    /** One line event, as it is sent to the page. */
    public record LineEvent(String type, long id, String text, double startSeconds, double endSeconds) {
    }

    private final Map<BrewId, Set<WebSocketSession>> listeners = new ConcurrentHashMap<>();
    private final Set<BrewId> attached = ConcurrentHashMap.newKeySet();
    private final Map<BrewId, String> statuses = new ConcurrentHashMap<>();

    /** Sends one line event to every page watching this relay. Safe from any thread. */
    public void publish(BrewId brew, LineEvent event) {
        broadcast(brew, "{\"type\":" + quote(event.type()) + ",\"id\":" + event.id()
                + ",\"text\":" + quote(event.text())
                + ",\"start\":" + seconds(event.startSeconds())
                + ",\"end\":" + seconds(event.endSeconds()) + "}");
    }

    /** Says what the transcriber of this relay is doing, to every page watching it now or later. */
    public void status(BrewId brew, String text) {
        statuses.put(brew, text);
        broadcast(brew, "{\"type\":\"status\",\"text\":" + quote(text) + "}");
    }

    /** Marks whether something is producing a transcript for this relay, for pages connecting later. */
    public void attached(BrewId brew, boolean on) {
        if (on) {
            attached.add(brew);
        } else {
            attached.remove(brew);
        }
    }

    /** Tells every page watching this relay that it is gone, and disconnects them. */
    public void closeAll(BrewId brew) {
        attached.remove(brew);
        statuses.remove(brew);
        Set<WebSocketSession> sessions = listeners.remove(brew);
        if (sessions == null) {
            return;
        }
        for (WebSocketSession session : sessions) {
            send(session, "{\"type\":\"closed\"}");
            try {
                session.close(CloseStatus.GOING_AWAY);
            } catch (IOException e) {
                log.debug("Could not close transcript socket", e);
            }
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
        URI uri = raw.getUri();
        String id = uri == null ? null
                : UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(BREW);
        BrewId brew;
        try {
            brew = new BrewId(id);
        } catch (RuntimeException e) {
            raw.close(CloseStatus.BAD_DATA.withReason("brew id missing or invalid"));
            return;
        }
        // Broadcasts can come from any thread; the decorator serializes sends
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 5_000, 1 << 20);
        raw.getAttributes().put(BREW, brew);
        listeners.computeIfAbsent(brew, b -> ConcurrentHashMap.newKeySet()).add(session);
        send(session, "{\"type\":\"ready\",\"brew\":" + quote(brew.value())
                + ",\"attached\":" + attached.contains(brew)
                + ",\"status\":" + quote(statuses.get(brew)) + "}");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        if (raw.getAttributes().get(BREW) instanceof BrewId brew) {
            Set<WebSocketSession> sessions = listeners.get(brew);
            if (sessions != null) {
                sessions.removeIf(s -> s.getId().equals(raw.getId()));
            }
        }
    }

    private void broadcast(BrewId brew, String json) {
        Set<WebSocketSession> sessions = listeners.get(brew);
        if (sessions != null) {
            sessions.forEach(session -> send(session, json));
        }
    }

    private static void send(WebSocketSession session, String json) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Could not send transcript event", e);
        }
    }

    private static String seconds(double seconds) {
        return String.format(Locale.ROOT, "%.2f", seconds);
    }

    private static String quote(String text) {
        if (text == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
