package org.brewstream.showcase;

import io.github.inference4j.audio.MoonshineRecognizer;
import io.github.inference4j.audio.SileroVadDetector;
import io.github.inference4j.audio.TranscriptLine;
import io.github.inference4j.audio.TranscriptListener;
import io.github.inference4j.audio.TranscriptionStream;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.brewstream.barista.spec.BrewId;
import org.brewstream.grind.AccessUnit;
import org.brewstream.grind.AccessUnitAssembler;
import org.brewstream.grind.ElementaryStream;
import org.brewstream.grind.StreamType;
import org.brewstream.grind.TsAnalyzer;
import org.brewstream.grind.TsPacket;
import org.brewstream.grind.netty.MpegTsDecoder;
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
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Transcribes the audio of each relay as it passes.
 *
 * <p>Every relay has a third output, an SRT listener nobody outside dials: this
 * service calls it over loopback with Roast, exactly as ffplay calls the player
 * output. Barista hands an application no packets of its own, and an output is
 * the one thing every relay already knows how to feed, queue and drop for.
 *
 * <p>From the connection: Grind splits the TS into packets and finds the AAC
 * track in the PMT, {@link AccessUnitAssembler} rebuilds its PES payloads (ADTS
 * frames), {@link AdtsDecoder} decodes them to mono PCM, and inference4j's
 * Moonshine streaming recognizer turns that into lines, which go to the page
 * through {@link TranscriptHub}.
 *
 * <p>Decoding and recognition run on one worker thread per relay, never on the
 * event loop: an update takes tens of milliseconds. The worker's queue is
 * bounded, and if recognition falls behind real time the oldest audio is
 * dropped rather than letting the transcript drift further and further behind.
 */
@Service
public class RelayTranscriber implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RelayTranscriber.class);

    private static final Duration LATENCY = Duration.ofMillis(120);
    private static final Duration RETRY = Duration.ofSeconds(2);
    /** PES payloads waiting for the worker: an ffmpeg audio PES is a few frames, so this is many seconds. */
    private static final int QUEUE = 500;
    /** Audio stopping mid-line (the encoder went away) never produces the pause that would end it. */
    private static final Duration SILENCE = Duration.ofMillis(1500);

    private final SrtTransport transport;
    private final TranscriptHub hub;
    private final String modelId;
    private final Map<BrewId, Session> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService retries = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("transcriber-retry").daemon().factory());

    private volatile MoonshineRecognizer recognizer;
    private volatile SileroVadDetector vad;

    RelayTranscriber(SrtTransport transport, TranscriptHub hub,
            @Value("${brewstream.transcript.model}") String modelId) {
        this.transport = transport;
        this.hub = hub;
        this.modelId = modelId;
    }

    /** Starts transcribing a relay whose transcriber output listens at {@code output}. */
    public void start(BrewId brew, InetSocketAddress output) {
        Session session = new Session(brew, output);
        Session previous = sessions.put(brew, session);
        if (previous != null) {
            previous.close();
        }
        session.connect();
    }

    public void stop(BrewId brew) {
        Session session = sessions.remove(brew);
        if (session != null) {
            session.close();
        }
    }

    @Override
    public void close() {
        sessions.values().forEach(Session::close);
        sessions.clear();
        retries.shutdownNow();
    }

    /**
     * One recognizer and one voice detector for every relay. Streams of one
     * recognizer are serialized inside it, so sharing is safe; loading one per
     * relay would cost a model load and its memory each time.
     */
    private synchronized TranscriptionStream newStream(TranscriptListener listener) {
        if (recognizer == null) {
            recognizer = MoonshineRecognizer.builder().modelId(modelId).build();
            vad = SileroVadDetector.builder().build();
        }
        return recognizer.stream(vad).listener(listener).build();
    }

    private final class Session {

        private final BrewId brew;
        private final InetSocketAddress output;
        private final ThreadPoolExecutor worker;
        private final AdtsDecoder decoder;

        private volatile boolean closed;
        private volatile SrtConnection connection;
        private final java.util.concurrent.ScheduledFuture<?> idleCheck;
        // Touched only on the worker thread
        private TranscriptionStream stream;
        private long lastAudio;
        private boolean open;
        private long dropped;

        Session(BrewId brew, InetSocketAddress output) {
            this.brew = brew;
            this.output = output;
            this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(QUEUE),
                    Thread.ofPlatform().name("transcriber-" + brew.value()).daemon().factory(),
                    (task, pool) -> {
                        // Behind real time: drop the oldest audio, keep the newest
                        pool.getQueue().poll();
                        pool.getQueue().offer(task);
                        if (dropped++ % 100 == 0) {
                            log.warn("transcriber {} behind real time, dropping audio", brew.value());
                        }
                    });
            this.decoder = new AdtsDecoder(this::recognize);
            this.idleCheck = retries.scheduleWithFixedDelay(() -> worker.execute(this::flushIfIdle),
                    1, 1, TimeUnit.SECONDS);
        }

        void connect() {
            if (closed) {
                return;
            }
            SrtConfig config = SrtConfig.defaults().withLatency(LATENCY);
            SrtCaller.connect(output, null, null, 0, config, transport)
                    .orTimeout(10, TimeUnit.SECONDS)
                    .whenComplete((connection, failure) -> {
                        if (failure != null) {
                            log.debug("transcriber {} could not reach {}: {}", brew.value(), output, failure.toString());
                            retry();
                        } else if (closed) {
                            connection.close();
                        } else {
                            attach(connection);
                        }
                    });
        }

        private void retry() {
            if (!closed) {
                retries.schedule(this::connect, RETRY.toMillis(), TimeUnit.MILLISECONDS);
            }
        }

        private void attach(SrtConnection connection) {
            this.connection = connection;
            TsAnalyzer analyzer = new TsAnalyzer();
            connection.pipeline().addLast(new MpegTsDecoder(analyzer), new AudioTap(analyzer));
            connection.addEventListener(new SrtConnectionListener() {
                @Override
                public void onDisconnected(SrtConnection gone) {
                    if (closed) {
                        return;
                    }
                    hub.status(brew, "transcriber disconnected, reconnecting");
                    worker.execute(decoder::discontinuity);
                    retry();
                }
            });
            hub.attached(brew, true);
            hub.status(brew, "waiting for an AAC track");
            log.info("transcriber {} attached to {}", brew.value(), output);
        }

        /** On the worker thread. */
        private void recognize(float[] mono, int sampleRate) {
            if (closed) {
                return;
            }
            if (stream == null) {
                hub.status(brew, "loading the speech model");
                stream = newStream(new Lines());
                hub.status(brew, "listening");
            }
            stream.addAudio(mono, sampleRate);
            lastAudio = System.nanoTime();
            open = true;
        }

        /** On the worker thread: completes the open line once audio has stopped arriving. */
        private void flushIfIdle() {
            if (open && !closed && System.nanoTime() - lastAudio > SILENCE.toNanos()) {
                open = false;
                stream.flush();
            }
        }

        void close() {
            closed = true;
            idleCheck.cancel(false);
            hub.attached(brew, false);
            SrtConnection live = connection;
            if (live != null) {
                try {
                    live.close();
                } catch (Exception e) {
                    log.debug("closing transcriber {} failed", brew.value(), e);
                }
            }
            worker.execute(() -> {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (RuntimeException e) {
                        log.debug("closing transcript stream failed", e);
                    }
                }
            });
            worker.shutdown();
        }

        /** Takes the AAC track's packets off the event loop. Ends the pipeline. */
        private final class AudioTap extends ChannelInboundHandlerAdapter {

            private final TsAnalyzer analyzer;
            private AccessUnitAssembler assembler;
            private boolean reportedNone;

            AudioTap(TsAnalyzer analyzer) {
                this.analyzer = analyzer;
            }

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object message) {
                if (!(message instanceof TsPacket packet)) {
                    return;
                }
                // The analyzer is only here for the PMT; MpegTsDecoder does not feed it
                analyzer.consume(packet);
                if (assembler == null && !findTrack()) {
                    return;
                }
                long lost = assembler.discardedForLoss();
                AccessUnit unit = assembler.consume(packet);
                if (assembler.discardedForLoss() != lost) {
                    worker.execute(decoder::discontinuity);
                }
                if (unit != null) {
                    byte[] payload = unit.data();
                    worker.execute(() -> decoder.feed(payload));
                }
            }

            /** The first ADTS AAC track in the PMT, once the PMT has arrived. */
            private boolean findTrack() {
                var programs = analyzer.programs();
                if (!programs.isKnown() || programs.allStreams().isEmpty()) {
                    return false;
                }
                for (ElementaryStream track : programs.allStreams()) {
                    if (track.streamType() == StreamType.ADTS_AAC) {
                        assembler = new AccessUnitAssembler(track.pid());
                        hub.status(brew, "decoding AAC on PID " + track.pid());
                        return true;
                    }
                }
                if (!reportedNone) {
                    reportedNone = true;
                    boolean latm = programs.allStreams().stream().anyMatch(t -> t.streamType() == StreamType.LATM_AAC);
                    hub.status(brew, latm ? "AAC in LATM framing is not supported, only ADTS"
                            : "no AAC audio track in this stream");
                }
                return false;
            }
        }

        private final class Lines implements TranscriptListener {

            @Override
            public void onLineStarted(TranscriptLine line) {
                send("started", line);
            }

            @Override
            public void onLineTextChanged(TranscriptLine line) {
                send("changed", line);
            }

            @Override
            public void onLineCompleted(TranscriptLine line) {
                send("completed", line);
            }

            private void send(String type, TranscriptLine line) {
                hub.publish(brew, new TranscriptHub.LineEvent(type, line.id(), line.text(),
                        line.start().toMillis() / 1000.0, line.end().toMillis() / 1000.0));
            }
        }
    }
}
