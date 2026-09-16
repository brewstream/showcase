package org.brewstream.showcase;

import org.brewstream.grind.ElementaryStream;
import org.brewstream.grind.PidStats;
import org.brewstream.grind.ProgramMap;
import org.brewstream.grind.TsStreamStats;
import org.brewstream.roast.socket.ConnectionStats;

import java.util.ArrayList;
import java.util.List;

/**
 * One connection as the dashboard sees it: the transport underneath and the
 * media inside, sampled at the same instant.
 *
 * <p>Sampling both together is the whole point. Read separately, a spike in
 * retransmissions and a continuity error on a video track are two unrelated
 * numbers; read from the same instant they are cause and effect.
 *
 * @param streamId    the StreamID the publisher connected with
 * @param peer        where it is publishing from
 * @param transport   Roast's view: loss, retransmission, RTT, buffer occupancy
 * @param media       Grind's view: continuity, clock, programs and tracks
 * @param viaRelay    whether this publisher came through the loss relay. A stream that
 *                    went straight to the listener is unaffected by the slider, and
 *                    silently looks perfectly healthy no matter where it is set - which
 *                    is a confusing thing to stare at, so the page says so
 * @param tracks      the media tracks, flattened for display
 */
public record StreamSnapshot(
        String streamId,
        String peer,
        Transport transport,
        Media media,
        boolean viaRelay,
        List<Track> tracks) {

    /**
     * What the SRT connection is doing.
     *
     * @param rttMicros            smoothed round-trip time
     * @param packetsReceived      DATA packets accepted
     * @param packetsLost          packets a gap was detected for — most are recovered
     * @param packetsDropped       packets given up on by TLPKTDROP. <b>This is data that is
     *                             gone</b>, and the number that turns into visible damage
     * @param retransmitRate       retransmitted packets as a fraction of all sent, lifetime. Always
     *                             zero here: this side only receives, so it retransmits nothing
     * @param packetsRecovered     packets that arrived as retransmissions — what the peer resent
     *                             because we asked. <b>The figure that shows ARQ working</b>
     * @param recoveryRate         those as a fraction of everything that should have arrived
     * @param receiveRateBytes     arrival rate over the last measurement window
     * @param flowWindowPackets    the receive window advertised to the peer
     * @param receiveBuffered      packets held awaiting their TSBPD deadline
     * @param droppedEvents        observability events discarded — nonzero means this very
     *                             dashboard is missing some of what happened
     */
    public record Transport(
            long rttMicros,
            long packetsReceived,
            long packetsLost,
            long packetsDropped,
            double retransmitRate,
            long packetsRecovered,
            double recoveryRate,
            int receiveRateBytes,
            int flowWindowPackets,
            int receiveBuffered,
            long droppedEvents) {
    }

    /**
     * What the transport stream inside looks like.
     *
     * @param healthy           nothing lost, corrupt, unaligned or failing a checksum
     * @param packets           transport packets seen
     * @param continuityErrors  counter jumps — where transport loss becomes media damage
     * @param packetsLostInTs   how many packets those jumps account for
     * @param tableCrcFailures  PSI sections discarded for a bad checksum
     * @param syncLosses        how many times packet alignment had to be regained
     * @param transportStreamId from the PAT, or -1 before one has arrived
     * @param programCount      how many programs the tables describe
     */
    public record Media(
            boolean healthy,
            long packets,
            long continuityErrors,
            long packetsLostInTs,
            long tableCrcFailures,
            long syncLosses,
            int transportStreamId,
            int programCount) {
    }

    /**
     * One elementary stream, named the way a person would name it.
     *
     * @param pid            the PID on the wire
     * @param label          "program 1 H.264 / AVC", say
     * @param kind           VIDEO, AUDIO or DATA, for grouping
     * @param packets        transport packets carrying this track
     * @param continuityErrors gaps detected on it
     * @param packetsLost    packets those gaps account for
     * @param pesPackets     PES packets — frames for video, but not for audio
     * @param lastPtsSeconds presentation time of the most recent unit, or -1
     * @param carriesPcr     whether this track carries the program clock
     */
    public record Track(
            int pid,
            String label,
            String kind,
            long packets,
            long continuityErrors,
            long packetsLost,
            long pesPackets,
            double lastPtsSeconds,
            boolean carriesPcr) {
    }

    /** Builds a snapshot from the two libraries' own views, taken together. */
    public static StreamSnapshot of(String streamId, String peer, boolean viaRelay,
            ConnectionStats connection, TsStreamStats stream) {

        Transport transport = new Transport(
                connection.rttMicros(),
                connection.packetsReceived(),
                connection.packetsLost(),
                connection.packetsDropped(),
                connection.retransmitRate(),
                connection.packetsRecovered(),
                connection.recoveryRate(),
                connection.receiveRateBytesPerSecond(),
                connection.flowWindowPackets(),
                connection.receiveBufferedPackets(),
                connection.droppedEvents());

        ProgramMap programs = stream.programs();
        Media media = new Media(
                stream.isHealthy(),
                stream.packets(),
                stream.continuityErrors(),
                stream.packetsLost(),
                stream.tableCrcFailures(),
                stream.syncLosses(),
                programs.transportStreamId(),
                programs.programs().size());

        List<Track> tracks = new ArrayList<>();
        for (ElementaryStream elementary : programs.allStreams()) {
            PidStats pid = stream.pid(elementary.pid());
            if (pid == null) {
                // The tables name a track whose packets have not arrived yet.
                // Shown anyway: a track that is declared but silent is exactly
                // the kind of thing worth noticing.
                tracks.add(new Track(elementary.pid(), describe(programs, elementary),
                        elementary.streamType().kind().name(), 0, 0, 0, 0, -1, false));
                continue;
            }
            tracks.add(new Track(
                    pid.pid(),
                    describe(programs, elementary),
                    elementary.streamType().kind().name(),
                    pid.packets(),
                    pid.continuityErrors(),
                    pid.packetsLost(),
                    pid.pesPackets(),
                    pid.lastPtsSeconds(),
                    pid.carriesPcr()));
        }

        return new StreamSnapshot(streamId, peer, transport, media, viaRelay, List.copyOf(tracks));
    }

    private static String describe(ProgramMap programs, ElementaryStream elementary) {
        String described = programs.describe(elementary.pid());
        return described != null ? described : elementary.label();
    }
}
