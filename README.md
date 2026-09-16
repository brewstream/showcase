# BrewStream Showcase

A Spring Boot app that accepts SRT publishers and shows, live, both the health of
the transport and the health of the media inside it.

It exists to demonstrate the pair. [Roast](https://github.com/brewstream/roast)
can tell you packets were lost and retransmitted.
[Grind](https://github.com/brewstream/grind) can tell you a continuity counter
jumped on the H.264 track of program 1. Separately those are numbers. Sampled
together they are a causal chain you can watch.

## Running it

```sh
./gradlew bootRun
```

Then open <http://localhost:8080> and publish to port **9001**, the loss relay:

```sh
ffmpeg -re -f lavfi -i testsrc=size=640x360:rate=25 -f lavfi -i sine \
  -c:v libx264 -preset ultrafast -bf 2 -g 25 -c:a aac \
  -f mpegts "srt://127.0.0.1:9001?streamid=live/demo"
```

Publishing to 9000 goes straight to the listener and works fine — it just
bypasses the relay, so the loss slider does nothing.

The dashboard polls `/api/streams` once a second.

## The loss slider

Loopback loses nothing, so without help every figure worth watching sits at zero.
A relay in front of the listener discards a settable fraction of packets, and the
slider changes it while the stream runs.

| Setting | What happens |
|---|---|
| **0%** | everything green, nothing recovered because nothing was lost |
| **5%** | *lost* and *recovered* climb together while *dropped* and continuity errors stay at zero. Every lost packet is retransmitted and arrives in time; the media is untouched. This is SRT working, and it is the more interesting half |
| **higher** | recovery runs out of room. `dropped` becomes non-zero, and moments later a continuity error lands on a named track |

Loss is applied in **both directions**, so the NAKs asking for a retransmission
and the ACKs confirming arrival are as exposed as the data itself — recovery has
to survive losing its own signalling.

The measured rate is shown beside the requested one on purpose: a slider claiming
10% is worth nothing next to the relay's own count proving 10% went missing.

**Recovered** counts packets that arrived carrying the retransmit flag — the peer
resending what we asked for. It is the number that explains "lost but undamaged",
and without it the dashboard shows loss going in, no damage coming out, and no
sign of the work in between. Retransmit *rate* is deliberately not shown: it is a
send-side figure, and this side only receives, so it reads zero however hard ARQ
is working.

**The other knob is latency.** `brewstream.srt.latency-ms` is the recovery budget,
and it defaults to **30ms here — deliberately below the 120ms a real deployment
would use.** On loopback the round trip is under a millisecond, so at 120ms ARQ
gets hundreds of retry opportunities and recovers essentially anything the relay
throws away: every slider setting looks healthy and the demonstration has no
reachable breaking point. Raise it back to 120 to watch the damage disappear
again — that comparison is the point. Unlike the drop rate it is negotiated during
the handshake, so changing it means restarting both the app and the publisher.

## What it shows

| Panel | Source | Says |
|---|---|---|
| Transport | Roast `ConnectionStats` | RTT, packets lost and dropped, retransmit rate, flow window, buffer occupancy |
| Media | Grind `TsStreamStats` | TS packets, continuity errors, table CRC failures, sync losses, programs |
| Tracks | Grind `ProgramMap` + `PidStats` | each track by name — "program 1 H.264 / AVC" — with its own packet counts, errors and presentation time |

**The distinction worth understanding:** packets *lost* are usually recovered by
retransmission and cost nothing visible. Packets *dropped* are the ones ARQ could
not recover inside the latency budget — those are gone, and they land as
continuity errors on a specific track a moment later. Watching the second number
follow the first is the demonstration.

## How the two libraries compose

Three lines, and the reason Roast makes every connection a Netty channel:

```java
connection.pipeline().addLast(
        new MpegTsDecoder(analyzer),
        new TsHealthHandler(analyzer));
```

Roast handles the transport and knows nothing about MPEG-TS. Grind parses the
media and knows nothing about SRT. `SrtIngestService` is the only place that
knows both exist, and neither library gained a dependency on the other.

Both halves of each snapshot are read at the same instant, deliberately — sampled
apart, a retransmission spike and the continuity error it caused can land in
different samples and appear unrelated.

## Building

Roast comes from Maven Central. Grind is not published yet, so `settings.gradle`
pulls it from the sibling checkout via a composite build, which means this repo
expects `../grind` to exist.

```
brewstream/
├── roast/
├── grind/
└── brewstream-showcase/
```

## Configuration

| Property | Default | |
|---|---|---|
| `brewstream.srt.port` | 9000 | the SRT listener itself |
| `brewstream.proxy.port` | 9001 | the loss relay publishers should point at |
| `brewstream.srt.latency-ms` | 30 | TSBPD latency: the recovery budget ARQ gets before TLPKTDROP gives up. Low on purpose, so loopback has a reachable breaking point |
| `server.port` | 8080 | the dashboard |

## Licence

[Apache License 2.0](LICENSE).
