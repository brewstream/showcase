package org.brewstream.showcase;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.brewstream.grind.TsAnalyzer;
import org.brewstream.grind.TsPacket;
import org.brewstream.grind.scte.SpliceEvent;
import org.brewstream.grind.scte.SpliceMonitor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Watches one stream for ad markers and keeps the recent ones for display.
 *
 * <p>A pipeline handler, so it composes the same way everything else does — the
 * decoder turns bytes into packets, and each interested party reads them:
 *
 * <pre>{@code
 * pipeline.addLast(new MpegTsDecoder(analyzer), new TsHealthHandler(analyzer), spliceLog);
 * }</pre>
 *
 * <p><b>Repeated copies are collapsed.</b> A muxer sends each section more than
 * once for redundancy — twice by default — and a list showing every copy would
 * read as twice as many ad breaks as the stream actually signals. They are
 * folded into one row with a count, which is both truer and how a monitoring
 * tool presents them.
 *
 * <p>Touched only from the connection's event loop, like the analyzer beside it,
 * except for {@link #recent()} which the dashboard calls. That one copies under
 * a lock rather than handing out the live deque.
 */
public final class SpliceLog extends ChannelInboundHandlerAdapter {

    /** Enough history for a demonstration; a real tool would page through them. */
    private static final int LIMIT = 20;

    private final SpliceMonitor monitor = new SpliceMonitor();
    private final TsAnalyzer analyzer;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private final Object lock = new Object();

    SpliceLog(TsAnalyzer analyzer) {
        this.analyzer = analyzer;
        monitor.addListener(this::record);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (message instanceof TsPacket packet) {
            // The tables come from the analyzer already running beside this, so
            // the splice PIDs are discovered without parsing PSI twice.
            monitor.programs(analyzer.stats().programs());
            monitor.consume(packet);
        }
        ctx.fireChannelRead(message);
    }

    private void record(SpliceEvent event) {
        synchronized (lock) {
            // Every entry, not just the most recent. Copies of one marker are not
            // adjacent in a real stream: several events are in flight at once, so
            // the second copy of a break arrives after the first copy of the next
            // one. Comparing only against the last entry silently lists a single
            // break twice, which an end-to-end run showed and a unit test on one
            // event at a time could not.
            for (Entry entry : entries) {
                if (entry.isSameEventAs(event)) {
                    entry.copies++;
                    return;
                }
            }
            entries.addLast(new Entry(event));
            while (entries.size() > LIMIT) {
                entries.removeFirst();
            }
        }
    }

    /** The markers seen so far, newest last, as the dashboard shows them. */
    public List<StreamSnapshot.Splice> recent() {
        synchronized (lock) {
            List<StreamSnapshot.Splice> out = new ArrayList<>(entries.size());
            for (Entry entry : entries) {
                SpliceEvent event = entry.event;
                out.add(new StreamSnapshot.Splice(
                        event.describe(),
                        event.section().commandType().label(),
                        event.arrivalSeconds(),
                        event.spliceSeconds(),
                        event.preRollSeconds(),
                        entry.copies));
            }
            return out;
        }
    }

    /** Whether the stream declares a splice PID at all, silent or not. */
    public boolean carriesSpliceInformation() {
        return !monitor.splicePids().isEmpty();
    }

    private static final class Entry {

        private final SpliceEvent event;
        private int copies = 1;

        Entry(SpliceEvent event) {
            this.event = event;
        }

        /**
         * Whether this is another copy of the same signal.
         *
         * <p>Compared on what it says and when it fires, not on arrival time —
         * two copies of one event differ precisely in when they arrived.
         */
        boolean isSameEventAs(SpliceEvent other) {
            return event.section().equals(other.section());
        }
    }
}
