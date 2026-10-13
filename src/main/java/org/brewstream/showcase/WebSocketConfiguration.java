package org.brewstream.showcase;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** The relay page's live transcript socket. */
@Configuration
@EnableWebSocket
public class WebSocketConfiguration implements WebSocketConfigurer {

    private final TranscriptHub transcripts;

    WebSocketConfiguration(TranscriptHub transcripts) {
        this.transcripts = transcripts;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(transcripts, "/ws/transcript");
    }
}
