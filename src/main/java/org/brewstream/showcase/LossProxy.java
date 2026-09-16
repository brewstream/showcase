package org.brewstream.showcase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A UDP relay that throws away a settable fraction of what passes through it.
 *
 * <p>Loopback loses nothing, so every figure that makes this showcase worth
 * watching sits at zero until something manufactures the bad network you do not
 * have. A publisher points here instead of at the listener, and the drop rate
 * can be changed while the stream is running.
 *
 * <p><b>Both directions are lossy.</b> Dropping only the publisher's data would
 * be a gentler test than reality: SRT recovers by having the receiver NAK what
 * is missing and the sender retransmit it, so a network that loses data also
 * loses the NAKs asking for it back and the ACKs confirming arrival. Recovery
 * has to survive losing its own signalling, and only a bidirectional proxy makes
 * it prove that.
 *
 * <p>One thread, one socket, both directions. A packet from the listener goes
 * back to whoever last published; anything else is treated as the publisher and
 * forwarded on. That is enough for one publisher at a time, which is what a
 * demonstration needs.
 */
public final class LossProxy implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LossProxy.class);

    /** Comfortably above any SRT payload, which is 1500 bytes at most. */
    private static final int BUFFER_SIZE = 2048;

    private final DatagramSocket socket;
    private final InetSocketAddress target;
    private final Thread pump;
    private final AtomicLong relayed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    private volatile double dropRate;
    private volatile SocketAddress publisher;
    private volatile boolean running = true;

    /**
     * @param listenPort where publishers connect
     * @param target     the real SRT listener to relay to
     */
    public LossProxy(int listenPort, InetSocketAddress target) throws IOException {
        this.socket = new DatagramSocket(listenPort);
        this.target = target;
        this.pump = new Thread(this::pump, "loss-proxy");
        this.pump.setDaemon(true);
        this.pump.start();
        log.info("loss proxy on port {} relaying to {}", listenPort, target);
    }

    private void pump() {
        byte[] buffer = new byte[BUFFER_SIZE];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                SocketAddress from = packet.getSocketAddress();
                boolean fromListener = from.equals(target);
                if (!fromListener) {
                    publisher = from;
                }

                SocketAddress to = fromListener ? publisher : target;
                if (to == null) {
                    continue; // a reply before anyone has published; nowhere to send it
                }

                // Rolled per packet, and per direction, so ACKs and NAKs are as
                // exposed as the data they are about.
                if (ThreadLocalRandom.current().nextDouble() < dropRate) {
                    dropped.incrementAndGet();
                    continue;
                }

                socket.send(new DatagramPacket(packet.getData(), packet.getLength(), to));
                relayed.incrementAndGet();
            } catch (IOException e) {
                if (running) {
                    log.debug("loss proxy relay failed", e);
                }
            }
        }
    }

    /**
     * Sets the fraction of packets to discard.
     *
     * @param rate 0.0 passes everything, 1.0 discards everything. Clamped rather
     *             than rejected, so a slider cannot put the demo in a bad state
     */
    public void setDropRate(double rate) {
        this.dropRate = Math.clamp(rate, 0.0, 1.0);
        log.info("drop rate set to {}%", String.format("%.1f", this.dropRate * 100));
    }

    public double dropRate() {
        return dropRate;
    }

    /** Packets forwarded, both directions. */
    public long relayed() {
        return relayed.get();
    }

    /**
     * Packets discarded. Shown on the dashboard on purpose: a slider that claims
     * 10% is worth nothing next to a counter proving 10% actually went missing.
     */
    public long dropped() {
        return dropped.get();
    }

    /** Resets the counters, so a run can be measured from a known point. */
    public void resetCounters() {
        relayed.set(0);
        dropped.set(0);
    }

    @Override
    public void close() {
        running = false;
        socket.close();
    }
}
