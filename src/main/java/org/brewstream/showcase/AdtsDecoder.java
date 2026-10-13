package org.brewstream.showcase;

import net.sourceforge.jaad.SampleBuffer;
import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.adts.ADTSDemultiplexer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * AAC in ADTS framing, as an MPEG-TS carries it on a {@code stream_type 0x0F}
 * track, decoded to mono float PCM.
 *
 * <p>Fed the payload of each PES packet in order. A frame is usually whole
 * inside one PES, but nothing requires it, so the tail of one payload is kept
 * and joined to the next.
 *
 * <p>The decoder is JAAD, Tianscar's fork. Two things learned the hard way:
 * the older {@code de.sfuhrm} build gets PNS wrong, and ffmpeg's default AAC
 * encoder uses PNS, so its output transcribes as nonsense; and the decoder must
 * be configured from JAAD's own reading of the ADTS header, because a config
 * built from the header fields by hand leaves HE-AAC's SBR unsignalled and
 * decodes it at half the sample rate, or not at all.
 *
 * <p>Not thread-safe: one instance per track, used from one thread.
 */
final class AdtsDecoder {

    /** Receives each decoded frame. */
    interface Sink {
        void accept(float[] mono, int sampleRate);
    }

    /** Above the largest legal ADTS frame (13-bit length), so a corrupt stream cannot grow this. */
    private static final int MAX_PENDING = 16 * 1024;

    private final Sink sink;
    private final SampleBuffer buffer = new SampleBuffer();
    private byte[] pending = new byte[0];
    private Decoder decoder;
    private int configKey = -1;
    private long frames;
    private long failures;

    AdtsDecoder(Sink sink) {
        this.sink = sink;
        buffer.setBigEndian(false);
    }

    /** Decodes every complete frame in this payload, keeping a partial one for the next. */
    void feed(byte[] payload) {
        byte[] data = pending.length == 0 ? payload : concat(pending, payload);
        int pos = 0;
        while (data.length - pos >= 7) {
            if ((data[pos] & 0xFF) != 0xFF || (data[pos + 1] & 0xF6) != 0xF0) {
                pos++;
                continue;
            }
            int length = ((data[pos + 3] & 0x03) << 11) | ((data[pos + 4] & 0xFF) << 3) | ((data[pos + 5] & 0xFF) >> 5);
            int header = (data[pos + 1] & 0x01) == 1 ? 7 : 9;
            if (length <= header) {
                pos++;
                continue;
            }
            if (data.length - pos < length) {
                break;
            }
            decode(data, pos, length, header);
            pos += length;
        }
        pending = Arrays.copyOfRange(data, pos, data.length);
        if (pending.length > MAX_PENDING) {
            pending = new byte[0];
        }
    }

    /** Forgets a partial frame, after packets were lost: it cannot be completed. */
    void discontinuity() {
        pending = new byte[0];
    }

    long frames() {
        return frames;
    }

    long failures() {
        return failures;
    }

    private void decode(byte[] data, int pos, int length, int header) {
        // Profile, sampling frequency index and channel configuration: when they
        // change, the encoder was reconfigured and the decoder must be too
        int key = (data[pos + 2] & 0xFD) << 8 | (data[pos + 3] & 0xC0);
        try {
            if (decoder == null || key != configKey) {
                ADTSDemultiplexer adts = new ADTSDemultiplexer(new ByteArrayInputStream(data, pos, length));
                decoder = Decoder.create(adts.getDecoderInfo());
                configKey = key;
            }
            decoder.decodeFrame(Arrays.copyOfRange(data, pos + header, pos + length), buffer);
        } catch (IOException | RuntimeException e) {
            failures++;
            return;
        }
        frames++;
        sink.accept(mono(buffer), buffer.getSampleRate());
    }

    /** Interleaved 16-bit little-endian, averaged across channels. */
    private static float[] mono(SampleBuffer buffer) {
        byte[] pcm = buffer.getData();
        int channels = Math.max(1, buffer.getChannels());
        int samples = pcm.length / 2 / channels;
        float[] out = new float[samples];
        for (int i = 0; i < samples; i++) {
            float sum = 0;
            for (int c = 0; c < channels; c++) {
                int at = (i * channels + c) * 2;
                sum += (short) ((pcm[at] & 0xFF) | (pcm[at + 1] << 8)) / 32768f;
            }
            out[i] = sum / channels;
        }
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
