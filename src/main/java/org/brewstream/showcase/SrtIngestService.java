package org.brewstream.showcase;

import io.netty.buffer.ByteBuf;
import org.brewstream.grind.TsAnalyzer;
import org.brewstream.grind.netty.MpegTsDecoder;
import org.brewstream.grind.netty.TsHealthHandler;
import org.brewstream.roast.socket.AcceptDecision;
import org.brewstream.roast.socket.SrtConfig;
import org.brewstream.roast.socket.SrtConnection;
import org.brewstream.roast.socket.SrtConnectionListener;
import org.brewstream.roast.socket.SrtListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accepts SRT publishers and inspects what they send.
 *
 * <p>The composition is three lines, and it is the reason Roast makes every
 * connection a Netty channel:
 *
 * <pre>{@code
 * connection.pipeline().addLast(new MpegTsDecoder(analyzer), new TsHealthHandler(analyzer));
 * }</pre>
 *
 * <p>Roast handles the transport and knows nothing about MPEG-TS; Grind parses
 * the media and knows nothing about SRT; this class is the only place that knows
 * both exist. Neither library gained a dependency on the other to make this work.
 */
@Service
public class SrtIngestService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SrtIngestService.class);

    private final Map<String, Ingest> byStreamId = new ConcurrentHashMap<>();
    private final SrtListener listener;
    private final int port;
    private final int proxyPort;

    SrtIngestService(@Value("${brewstream.srt.port}") int port,
            @Value("${brewstream.proxy.port}") int proxyPort,
            @Value("${brewstream.srt.latency-ms}") int latencyMillis) throws InterruptedException {
        this.port = port;
        this.proxyPort = proxyPort;
        this.listener = SrtListener.bind(new InetSocketAddress(port),
                SrtConfig.defaults().withLatency(Duration.ofMillis(latencyMillis)));

        // Anything with a StreamID is welcome; a demo should not make you guess a
        // magic string. An empty one is refused so the dashboard always has
        // something to label a row with.
        listener.setAcceptHandler(request -> request.streamId().isEmpty()
                ? AcceptDecision.reject(org.brewstream.roast.packet.cif.RejectionReason.BAD_REQUEST)
                : AcceptDecision.accept());

        listener.onConnection(this::attach);
        listener.addEventListener(new SrtConnectionListener() {
            @Override
            public void onDisconnected(SrtConnection connection) {
                byStreamId.remove(connection.metadata().streamId());
                log.info("stream ended: {}", connection.metadata().streamId());
            }
        });

        log.info("SRT listener ready on port {} with {}ms latency", port, latencyMillis);
    }

    private void attach(SrtConnection connection) {
        String streamId = connection.metadata().streamId();
        TsAnalyzer analyzer = new TsAnalyzer();

        // Decode transport packets and account for them, then discard. A real
        // router would forward here instead; the showcase only measures.
        SpliceLog splices = new SpliceLog(analyzer);
        connection.pipeline().addLast(
                new MpegTsDecoder(analyzer),
                new TsHealthHandler(analyzer),
                splices,
                new io.netty.channel.ChannelInboundHandlerAdapter());

        byStreamId.put(streamId, new Ingest(connection, analyzer, splices));
        log.info("stream started: {} from {}", streamId, connection.metadata().peerAddress());
    }

    /**
     * A snapshot of every live stream.
     *
     * <p>Both halves of each snapshot are read together, so the transport and
     * media figures describe the same instant. Reading them at different times
     * would let the dashboard show a retransmission spike that has no
     * corresponding continuity error simply because the two samples missed each
     * other.
     */
    public List<StreamSnapshot> snapshots() {
        List<StreamSnapshot> out = new ArrayList<>(byStreamId.size());
        for (Ingest ingest : byStreamId.values()) {
            // A publisher that came through the relay appears to the listener as
            // the relay itself, so the peer port is what distinguishes the two.
            // Worth detecting rather than documenting: publishing to the listener
            // directly works perfectly and looks completely healthy whatever the
            // slider says, which is a confusing thing to debug.
            java.net.InetSocketAddress peer = ingest.connection.metadata().peerAddress();
            out.add(StreamSnapshot.of(
                    ingest.connection.metadata().streamId(),
                    String.valueOf(peer),
                    peer.getPort() == proxyPort,
                    ingest.connection.stats(),
                    ingest.analyzer.stats(),
                    ingest.splices));
        }
        return out;
    }

    /** The port publishers connect to, so the dashboard can show the URL. */
    public int port() {
        return port;
    }

    @Override
    public void close() throws InterruptedException {
        listener.close();
    }

    /**
     * One publisher: its connection and the analyzer watching what it sends.
     *
     * <p>The analyzer is per connection deliberately — it is stateful and not
     * thread-safe, and each stream's continuity counters and clock are its own.
     * It is only ever touched from that connection's event loop, which is where
     * {@code TsHealthHandler} calls it from.
     */
    private record Ingest(SrtConnection connection, TsAnalyzer analyzer, SpliceLog splices) {
    }
}
