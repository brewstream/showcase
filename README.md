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

Then open <http://localhost:8080> and publish something to it:

```sh
ffmpeg -re -f lavfi -i testsrc=size=640x360:rate=25 -f lavfi -i sine \
  -c:v libx264 -preset ultrafast -c:a aac \
  -f mpegts "srt://127.0.0.1:9000?streamid=live/demo"
```

The dashboard polls `/api/streams` once a second.

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
| `brewstream.srt.port` | 9000 | the port publishers connect to |
| `brewstream.srt.latency-ms` | 120 | TSBPD latency: the recovery budget ARQ gets before TLPKTDROP gives up. The most visible knob here |
| `server.port` | 8080 | the dashboard |

## Licence

[Apache License 2.0](LICENSE).
