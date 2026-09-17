package org.brewstream.showcase;

import org.brewstream.grind.TsAnalyzer;
import org.brewstream.grind.netty.MpegTsDecoder;
import org.brewstream.grind.netty.TsHealthHandler;
import org.brewstream.roast.socket.SrtCaller;
import org.brewstream.roast.socket.SrtConfig;
import org.brewstream.roast.socket.SrtConnection;
import org.brewstream.roast.socket.SrtConnectionListener;
import org.brewstream.roast.socket.SrtTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The other half of SRT: connecting out to a source rather than waiting to be
 * published to.
 *
 * <p>The listener page demonstrates recovery, because a relay in front of it can
 * manufacture the bad network a loopback does not have. This side demonstrates
 * something a synthetic test cannot — what a real encoder on a real network
 * actually delivers. There is no loss to induce here; whatever the path does is
 * what the figures show, which is the point.
 *
 * <p>Everything downstream is identical. The same two Grind handlers go on the
 * pipeline, the same {@link StreamSnapshot} comes out, and the same dashboard
 * renders it. That is the claim Roast makes by giving both roles one connection
 * type, and this service is only worth reading as evidence for it:
 *
 * <pre>{@code
 * connection.pipeline().addLast(new MpegTsDecoder(analyzer), new TsHealthHandler(analyzer));
 * }</pre>
 */
@Service
public class SrtCallerService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SrtCallerService.class);

    /** How long to wait for a handshake before calling it a failure. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Bounds on the recovery budget a connection may be given.
     *
     * <p>Clamped rather than rejected, so a typo in a form field cannot put the
     * page into a state that needs explaining. Below about 20ms the budget is
     * shorter than the jitter on most real paths and everything looks broken;
     * above 8s the connection is buffering more than any live use would want.
     */
    private static final int MIN_LATENCY_MS = 20;
    private static final int MAX_LATENCY_MS = 8000;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final SrtTransport transport;
    private final int defaultLatencyMillis;

    SrtCallerService(SrtTransport transport,
            @Value("${brewstream.caller.latency-ms}") int defaultLatencyMillis) {
        this.transport = transport;
        this.defaultLatencyMillis = defaultLatencyMillis;
    }

    /** The budget a connection gets when the page does not ask for one. */
    public int defaultLatencyMillis() {
        return defaultLatencyMillis;
    }

    /**
     * Connects to a source and starts inspecting what it sends.
     *
     * <p>Returns as soon as the attempt is registered rather than when the
     * handshake completes: a source that is unreachable takes the full timeout to
     * say so, and a page that blocks for ten seconds on a typo is worse than one
     * showing a row that says "connecting".
     *
     * <p><b>The passphrase array is zeroed before this returns</b>, whether the
     * connection succeeded or not. Roast copies what it needs and zeroes its own
     * copy on close, so nothing here has to outlive the call.
     *
     * @param passphrase    the stream's passphrase, or {@code null} when unencrypted
     * @param latencyMillis the recovery budget for this connection, or null for the default.
     *                      Per connection rather than global because it is a property of the
     *                      path to one source: a local encoder and a satellite feed want very
     *                      different budgets, and this page exists to watch several at once
     * @return the id the dashboard will refer to this connection by
     */
    public String connect(String host, int port, String streamId, char[] passphrase, int keyLength,
            Integer latencyMillis) {
        String id = "c" + ids.incrementAndGet();
        InetSocketAddress target = new InetSocketAddress(host, port);
        int latency = latencyMillis == null
                ? defaultLatencyMillis
                : Math.clamp(latencyMillis, MIN_LATENCY_MS, MAX_LATENCY_MS);
        Session session = new Session(id, host + ":" + port, streamId, passphrase != null, latency);
        sessions.put(id, session);

        try {
            SrtConfig config = SrtConfig.defaults().withLatency(Duration.ofMillis(latency));
            SrtCaller.connect(target, streamId, passphrase, keyLength, config, transport)
                    .orTimeout(CONNECT_TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)
                    .whenComplete((connection, failure) -> {
                        if (failure != null) {
                            session.failed(failure);
                            log.info("caller {} failed: {}", id, session.error);
                        } else {
                            session.connected(connection);
                            log.info("caller {} connected to {} as {}", id, target, streamId);
                        }
                    });
        } finally {
            // Zeroed on every path. Roast has already copied whatever it needs by
            // the time connect() returns its future.
            if (passphrase != null) {
                Arrays.fill(passphrase, '\0');
            }
        }
        return id;
    }

    /** Closes one connection and forgets it. Unknown ids are ignored. */
    public void disconnect(String id) {
        Session session = sessions.remove(id);
        if (session != null) {
            session.close();
            log.info("caller {} disconnected", id);
        }
    }

    /** Every attempt, live or failed, oldest first so the list does not jump about. */
    public List<CallerView> connections() {
        List<Session> ordered = new ArrayList<>(sessions.values());
        ordered.sort((a, b) -> a.startedAt.compareTo(b.startedAt));

        List<CallerView> out = new ArrayList<>(ordered.size());
        for (Session session : ordered) {
            out.add(session.view());
        }
        return out;
    }

    @Override
    public void close() {
        sessions.values().forEach(Session::close);
        sessions.clear();
    }

    /**
     * One outbound attempt and everything known about it.
     *
     * <p>Failed attempts are kept rather than discarded. A connection that was
     * rejected for a bad passphrase is the single most useful thing this page can
     * tell someone, and dropping the row would leave the screen looking exactly
     * as it did before they pressed connect.
     *
     * @param status        CONNECTING, CONNECTED, FAILED or CLOSED
     * @param error         why it failed, or null. Never contains the passphrase
     * @param latencyMillis the budget this connection was given, shown so two connections at
     *                      different settings can be compared directly
     * @param stream        the live figures, or null until the handshake completes
     */
    public record CallerView(
            String id,
            String target,
            String streamId,
            boolean encrypted,
            String status,
            String error,
            int latencyMillis,
            StreamSnapshot stream) {
    }

    /**
     * The mutable half, kept out of the record the controller serialises.
     *
     * <p>The analyzer is per connection deliberately: it is stateful, not
     * thread-safe, and only ever touched from that connection's event loop, which
     * is where {@code TsHealthHandler} calls it from.
     */
    private final class Session {

        private final String id;
        private final String target;
        private final String streamId;
        private final boolean encrypted;
        private final int latencyMillis;
        private final Instant startedAt = Instant.now();
        private final TsAnalyzer analyzer = new TsAnalyzer();
        private final SpliceLog splices = new SpliceLog(analyzer);

        private volatile SrtConnection connection;
        private volatile String status = "CONNECTING";
        private volatile String error;

        Session(String id, String target, String streamId, boolean encrypted, int latencyMillis) {
            this.id = id;
            this.target = target;
            this.streamId = streamId;
            this.encrypted = encrypted;
            this.latencyMillis = latencyMillis;
        }

        void connected(SrtConnection connection) {
            this.connection = connection;
            this.status = "CONNECTED";
            connection.pipeline().addLast(
                    new MpegTsDecoder(analyzer),
                    new TsHealthHandler(analyzer),
                    splices,
                    new io.netty.channel.ChannelInboundHandlerAdapter());
            connection.addEventListener(new SrtConnectionListener() {
                @Override
                public void onDisconnected(SrtConnection closed) {
                    // Kept in the list rather than removed: a source that dropped
                    // after two minutes is worth seeing, and its final figures are
                    // the evidence for why.
                    status = "CLOSED";
                }
            });
        }

        void failed(Throwable failure) {
            Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                    ? failure.getCause()
                    : failure;
            this.status = "FAILED";
            this.error = describe(cause);
        }

        /**
         * A cause a person can act on.
         *
         * <p>Roast surfaces a rejection as {@code IOException("connection
         * rejected: BADSECRET")}, which is accurate and means nothing to someone
         * who mistyped a passphrase. The common ones are translated; anything
         * else keeps its own message rather than being flattened into "failed".
         */
        private String describe(Throwable cause) {
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            if (message.contains("BADSECRET")) {
                return "Rejected: wrong passphrase, or the source is not encrypted";
            }
            if (message.contains("UNAUTHORIZED") || message.contains("REJ_")) {
                return "Rejected by the source: " + message;
            }
            if (cause instanceof java.util.concurrent.TimeoutException) {
                return "No response — wrong address or port, or a firewall in the way";
            }
            return message;
        }

        CallerView view() {
            SrtConnection live = connection;
            StreamSnapshot snapshot = null;
            if (live != null) {
                // Both halves read together, so the transport and media figures
                // describe the same instant. viaRelay is false and stays false:
                // there is no relay on this path, which is the whole point of it.
                snapshot = StreamSnapshot.of(streamId, String.valueOf(live.metadata().peerAddress()),
                        false, live.stats(), analyzer.stats(), splices);
            }
            return new CallerView(id, target, streamId, encrypted, status, error,
                    latencyMillis, snapshot);
        }

        void close() {
            SrtConnection live = connection;
            if (live != null) {
                try {
                    live.close();
                } catch (Exception e) {
                    log.debug("closing caller {} failed", id, e);
                }
            }
        }
    }
}
