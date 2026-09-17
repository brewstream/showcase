package org.brewstream.showcase;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.brewstream.roast.socket.SrtTransport;

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
    /**
     * One event loop group shared by every outbound connection.
     *
     * <p>Roast's default gives each caller its own group, and so its own threads.
     * That is a sensible default for an application making one or two
     * connections, and the wrong one for a page whose entire purpose is to open
     * several at once — twenty sources would mean twenty thread pools.
     */
    @Bean(destroyMethod = "")
    SrtTransport srtTransport(EventLoopGroup callerEventLoopGroup) {
        return SrtTransport.shared(callerEventLoopGroup, NioDatagramChannel.class);
    }

    /**
     * The group behind that transport, shut down by Spring on the way out.
     *
     * <p>Separate from the {@code SrtTransport} bean deliberately: a borrowed
     * transport never shuts its group down, which is correct — it does not own
     * it. Something still has to, so the group is a bean in its own right and
     * Spring's lifecycle handles it.
     */
    @Bean(destroyMethod = "shutdownGracefully")
    EventLoopGroup callerEventLoopGroup() {
        return new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    }

    @Bean
    LossProxy lossProxy(SrtIngestService ingest,
            @Value("${brewstream.proxy.port}") int proxyPort) throws IOException {
        return new LossProxy(proxyPort, new InetSocketAddress("127.0.0.1", ingest.port()));
    }
}
