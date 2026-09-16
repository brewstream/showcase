package org.brewstream.showcase;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * What the dashboard page polls.
 *
 * <p>Plain JSON over a short poll rather than server-sent events: the figures
 * are gauges that only mean anything sampled, the page wants a whole consistent
 * picture each time rather than a stream of deltas, and a poll survives a
 * reconnect without any client-side state. A live router would push; a showcase
 * is clearer this way.
 */
@RestController
public class DashboardController {

    private final SrtIngestService ingest;

    DashboardController(SrtIngestService ingest) {
        this.ingest = ingest;
    }

    /** Every live stream, transport and media sampled together. */
    @GetMapping(value = "/api/streams", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> streams() {
        List<StreamSnapshot> snapshots = ingest.snapshots();
        return Map.of(
                "srtPort", ingest.port(),
                "streamCount", snapshots.size(),
                "streams", snapshots);
    }
}
