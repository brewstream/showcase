package org.brewstream.showcase;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * BrewStream showcase: an SRT listener that reports, live, both the health of
 * the transport and the health of the media inside it.
 *
 * <p>The point is the pair. Roast can tell you packets were lost and
 * retransmitted; Grind can tell you a continuity counter jumped on the H.264
 * track of program 1. Separately those are numbers. Together they are a causal
 * chain you can watch: induce loss, see retransmissions climb, see TLPKTDROP
 * give up, see the continuity error land on a specific track, see the picture
 * break. That is the thing a libsrt binding cannot easily show, because you
 * cannot see inside it.
 */
@SpringBootApplication
public class ShowcaseApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShowcaseApplication.class, args);
    }
}
