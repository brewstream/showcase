package org.brewstream.showcase;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.InetSocketAddress;

/** Wiring for the pieces that need constructor arguments from configuration. */
@Configuration
public class ShowcaseConfiguration {

    /**
     * The proxy publishers should point at, relaying to the real listener.
     *
     * <p>Created after {@link SrtIngestService} so the listener it relays to is
     * already bound — declaring the dependency rather than relying on bean
     * ordering, because a proxy pointing at a port nothing is listening on fails
     * in a way that looks like packet loss, which is a confusing thing to debug
     * in a demonstration about packet loss.
     */
    @Bean
    LossProxy lossProxy(SrtIngestService ingest,
            @Value("${brewstream.proxy.port}") int proxyPort) throws IOException {
        return new LossProxy(proxyPort, new InetSocketAddress("127.0.0.1", ingest.port()));
    }
}
