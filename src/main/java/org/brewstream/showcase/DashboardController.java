package org.brewstream.showcase;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * What the dashboard page polls, and how it drives the loss relay.
 *
 * <p>Plain JSON over a short poll rather than server-sent events: the figures are
 * gauges that only mean anything sampled, the page wants a whole consistent
 * picture each time rather than a stream of deltas, and a poll survives a
 * reconnect without any client-side state. A live router would push; a showcase
 * is clearer this way.
 */
@RestController
public class DashboardController {

    private final SrtIngestService ingest;
    private final LossProxy proxy;
    private final int publishPort;

    DashboardController(SrtIngestService ingest, LossProxy proxy,
            @Value("${brewstream.proxy.port}") int publishPort) {
        this.ingest = ingest;
        this.proxy = proxy;
        this.publishPort = publishPort;
    }

    /** Every live stream, transport and media sampled together, plus the relay's state. */
    @GetMapping(value = "/api/streams", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> streams() {
        List<StreamSnapshot> snapshots = ingest.snapshots();
        return Map.of(
                "srtPort", ingest.port(),
                "publishPort", publishPort,
                "streamCount", snapshots.size(),
                "loss", lossState(),
                "streams", snapshots);
    }

    /**
     * Sets the proportion of packets the relay discards, while a stream runs.
     *
     * @param percent 0 to 100; clamped by the proxy rather than rejected, so a
     *                slider cannot put the demonstration into a bad state
     */
    @PostMapping(value = "/api/loss", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> setLoss(@RequestParam double percent) {
        proxy.setDropRate(percent / 100.0);
        return lossState();
    }

    /** Zeroes the relay's counters, so a run can be measured from a known point. */
    @PostMapping(value = "/api/loss/reset", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> resetLoss() {
        proxy.resetCounters();
        return lossState();
    }

    /**
     * The relay's own view.
     *
     * <p>The measured rate is reported next to the requested one deliberately: a
     * slider claiming ten percent is worth nothing beside a counter proving ten
     * percent of packets actually went missing. They should track closely, and
     * if they ever do not, that is worth seeing rather than hiding.
     */
    private Map<String, Object> lossState() {
        long relayed = proxy.relayed();
        long dropped = proxy.dropped();
        long total = relayed + dropped;
        return Map.of(
                "requestedPercent", proxy.dropRate() * 100,
                "relayed", relayed,
                "dropped", dropped,
                "actualPercent", total == 0 ? 0.0 : (double) dropped / total * 100);
    }
}
