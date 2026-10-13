package org.brewstream.showcase;

import org.brewstream.barista.Barista;
import org.brewstream.barista.Brew;
import org.brewstream.barista.BrewStatus;
import org.brewstream.barista.EndpointEvent;
import org.brewstream.barista.EndpointStatus;
import org.brewstream.barista.spec.BrewId;
import org.brewstream.barista.spec.BrewSpec;
import org.brewstream.barista.spec.OutputSpec;
import org.brewstream.barista.spec.SourceSpec;
import org.brewstream.barista.spec.SrtListenerEndpoint;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Driving relays from the relay page.
 *
 * <p>Each relay is a Barista brew with SRT listeners: an encoder publishes
 * MPEG-TS to one, and a player such as ffplay calls another to watch it. Both
 * ends dial in, so neither needs to know where the other is — which is the
 * point of a relay, and why the output is a listener rather than a caller.
 *
 * <p>A third output, {@code transcriber}, is called over loopback by {@link
 * RelayTranscriber}, whose transcript reaches the page over
 * {@code /ws/transcript?brew=<id>}; see {@link TranscriptHub}.
 */
@RestController
public class RelayController {

    static final String SOURCE = "encoder";
    static final String OUTPUT = "player";
    static final String TRANSCRIBER = "transcriber";

    private final Barista barista;
    private final TranscriptHub transcripts;
    private final RelayTranscriber transcriber;

    RelayController(Barista barista, TranscriptHub transcripts, RelayTranscriber transcriber) {
        this.barista = barista;
        this.transcripts = transcripts;
        this.transcriber = transcriber;
    }

    /** Every relay on this node, newest last. */
    @GetMapping(value = "/api/relay", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> relays() {
        List<RelayView> views = barista.brews().stream()
                .map(Brew::status)
                .map(RelayController::view)
                .sorted(Comparator.comparing(RelayView::id))
                .toList();
        return Map.of("relays", views);
    }

    /**
     * Starts a relay.
     *
     * @param inPort  the port the encoder publishes to, or 0 to take one from {@code barista.ports.srt}
     * @param outPort the port the player calls, or 0 likewise
     */
    @PostMapping(value = "/api/relay", produces = MediaType.APPLICATION_JSON_VALUE)
    public RelayView create(
            @RequestParam(defaultValue = "Relay") String name,
            @RequestParam(defaultValue = "0") int inPort,
            @RequestParam(defaultValue = "0") int outPort) {
        BrewSpec spec = BrewSpec.of(name.trim(),
                List.of(SourceSpec.of(SOURCE, 0, SrtListenerEndpoint.any().withPort(inPort))),
                List.of(OutputSpec.of(OUTPUT, SrtListenerEndpoint.any().withPort(outPort)),
                        OutputSpec.of(TRANSCRIBER, SrtListenerEndpoint.any())));
        Brew brew = barista.create(spec);
        int port = brew.spec().outputs().stream()
                .filter(o -> o.id().value().equals(TRANSCRIBER))
                .map(o -> ((SrtListenerEndpoint) o.endpoint()).port())
                .findFirst().orElseThrow();
        transcriber.start(brew.id(), new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        return view(brew.status());
    }

    /** Stops a relay, releases its ports and closes its transcript sockets. */
    @DeleteMapping(value = "/api/relay/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> delete(@PathVariable String id) {
        BrewId brew = new BrewId(id);
        // Transcriber first, so the output closing under it is not taken for a drop to reconnect after
        transcriber.stop(brew);
        barista.delete(brew);
        transcripts.closeAll(brew);
        return Map.of("id", id, "deleted", true);
    }

    /**
     * What the page shows of a brew. A slim copy rather than {@link BrewStatus}
     * itself, which carries Grind's full TS statistics and per-connection
     * transport stats this page has no use for yet.
     */
    record RelayView(String id, String name, String state, long inputBitsPerSecond,
            List<LegView> sources, List<LegView> outputs, String error) {
    }

    record LegView(String id, String kind, String address, String state, String health,
            int connections, long bytes, long droppedChunks, String videoCodec,
            String lastEvent, String error) {
    }

    static RelayView view(BrewStatus status) {
        return new RelayView(status.id().value(), status.name(), status.state().name(),
                status.inputBitsPerSecond(),
                status.sources().stream().map(RelayController::leg).toList(),
                status.outputs().stream().map(RelayController::leg).toList(),
                status.error());
    }

    private static LegView leg(EndpointStatus e) {
        List<EndpointEvent> history = e.history();
        String last = history.isEmpty() ? null : history.getLast().reason();
        return new LegView(e.id(), e.kind(), e.address(), e.state().name(), e.health().name(),
                e.connections().size(), e.bytes(), e.droppedChunks(), e.videoCodec(), last, e.error());
    }
}
