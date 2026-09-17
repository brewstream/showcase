package org.brewstream.showcase;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Driving outbound connections from the caller page.
 *
 * <p>Deliberately not a mirror of {@link DashboardController}: that one reports
 * on a listener the application owns, while this one is a remote control for
 * connections a person starts and stops. The polling shape is the same, because
 * the figures underneath are the same gauges.
 */
@RestController
public class CallerController {

    private final SrtCallerService caller;

    CallerController(SrtCallerService caller) {
        this.caller = caller;
    }

    /** Every outbound connection: live, still handshaking, failed or closed. */
    @GetMapping(value = "/api/caller", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> connections() {
        List<SrtCallerService.CallerView> views = caller.connections();
        // The default goes with the list so the form can prefill it rather than
        // hard-coding a number the server might disagree with.
        return Map.of("connectionCount", views.size(),
                "defaultLatencyMs", caller.defaultLatencyMillis(),
                "connections", views);
    }

    /**
     * Connects to a source.
     *
     * <p>{@code passphrase} is bound as {@code char[]} rather than {@code String}
     * so that nothing in this application's own code holds one: a String would
     * sit in the heap until GC with no way to clear it, and would turn up in a
     * heap dump long after the connection closed. The array is zeroed by {@link
     * SrtCallerService#connect} before it returns.
     *
     * <p>That is as far as it goes, and it is worth being plain about the limit:
     * the servlet container has already parsed the request line into Strings of
     * its own before this method is called, and on a demo served over plain HTTP
     * the passphrase crossed the wire in the clear. This is a tool for pointing
     * at your own encoder on your own network, not a credential store.
     *
     * @param keyLength 16, 24 or 32 bytes — the caller's choice, since this side
     *                  generates the key material rather than adopting the peer's
     * @param latencyMs the recovery budget for this connection, or omitted for the
     *                  configured default. Per connection because it belongs to the path
     *                  to one source, not to the application
     */
    @PostMapping(value = "/api/caller/connect", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> connect(
            @RequestParam String host,
            @RequestParam int port,
            @RequestParam(defaultValue = "") String streamId,
            @RequestParam(required = false) char[] passphrase,
            @RequestParam(defaultValue = "16") int keyLength,
            @RequestParam(required = false) Integer latencyMs) {

        char[] secret = passphrase == null || passphrase.length == 0 ? null : passphrase;
        String id = caller.connect(host.trim(), port, streamId.trim(), secret, keyLength, latencyMs);
        return Map.of("id", id);
    }

    /** Closes one connection. Unknown ids are accepted rather than 404'd. */
    @PostMapping(value = "/api/caller/{id}/disconnect", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> disconnect(@PathVariable String id) {
        caller.disconnect(id);
        return Map.of("id", id, "disconnected", true);
    }
}
