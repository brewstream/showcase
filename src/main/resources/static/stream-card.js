/*
 * Rendering one stream, shared by the listener dashboard and the caller page.
 *
 * Both pages receive the same StreamSnapshot JSON, because Roast gives a
 * connection the same type whichever end opened it and Grind does not know
 * which end it is attached to. Rendering them from one function is the visible
 * form of that: if the two pages ever need different code here, something below
 * them has stopped being symmetric.
 */

const fmt = n => n === undefined || n === null ? "–" : n.toLocaleString();
const bytes = n => !n ? "0" : n > 1e6 ? (n / 1e6).toFixed(1) + " MB/s"
                    : n > 1e3 ? (n / 1e3).toFixed(0) + " kB/s" : n + " B/s";

function metric(label, value, cls) {
  return `<div class="metric"><div class="label">${label}</div>
          <div class="value ${cls || ""}">${value}</div></div>`;
}

/** Escapes text that came from a form or a peer, so a stream id cannot inject markup. */
function esc(s) {
  return String(s === undefined || s === null ? "" : s).replace(/[&<>"']/g,
    c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

/**
 * Ad markers, when the stream signals them.
 *
 * Absent entirely from a stream that declares no splice PID, rather than shown
 * as an empty table: most streams do not carry ad markers, and a permanently
 * empty panel would read as something being broken.
 *
 * A declared-but-silent PID is the normal state between breaks, so that case
 * says so in words instead.
 */
function splicePanel(s) {
  if (!s.carriesSplices) {
    return "";
  }
  if (!s.splices.length) {
    return `<div class="panel">
      <h3>Ad markers · SCTE-35</h3>
      <p class="quiet">This stream signals ad markers, and none has arrived yet.
         A splice PID is silent between breaks, so this is the usual state.</p>
    </div>`;
  }

  // Newest first: a live view is read from the top.
  const rows = s.splices.slice().reverse().map(sp => {
    // A warning that arrived after the moment it describes could not have been
    // acted on, which is worth colouring rather than hiding.
    const late = sp.preRollSeconds >= 0 && sp.preRollSeconds < 0.5;
    const preRoll = sp.preRollSeconds < 0
      ? '<span class="bad">after the fact</span>'
      : `<span class="${late ? "warn" : ""}">${sp.preRollSeconds.toFixed(1)}s</span>`;
    return `
      <tr>
        <td>${esc(sp.description)}${sp.copies > 1
          ? `<span class="kind">&times;${sp.copies}</span>` : ""}</td>
        <td class="num">${sp.arrivalSeconds >= 0 ? sp.arrivalSeconds.toFixed(2) + "s" : "–"}</td>
        <td class="num">${sp.spliceSeconds >= 0 ? sp.spliceSeconds.toFixed(2) + "s" : "–"}</td>
        <td class="num">${preRoll}</td>
      </tr>`;
  }).join("");

  return `<div class="panel">
    <h3>Ad markers · SCTE-35</h3>
    <table>
      <thead><tr><th>Marker</th><th>Received</th><th>Fires</th><th>Warning</th></tr></thead>
      <tbody>${rows}</tbody>
    </table>
  </div>`;
}

/**
 * The two panels and the track table for one stream.
 *
 * `head` is whatever belongs in the card's title bar beyond the stream id — the
 * relay-bypass pill on one page, a disconnect button on the other — and `banner`
 * a full-width strip under it. Everything below those is identical by design.
 */
function streamCard(s, head, banner) {
  const t = s.transport, m = s.media;
  // Dropped packets are the ones that became visible damage; lost ones were
  // mostly recovered. Coloured differently on purpose.
  const droppedClass = t.packetsDropped > 0 ? "bad" : "ok";
  const errorClass = m.continuityErrors > 0 ? "bad" : "ok";
  // Recovery is the number that explains "lost but undamaged". Without it the
  // dashboard shows loss going in and nothing coming out, with no sign of the
  // work in between.
  const recovered = t.packetsRecovered > 0
    ? `${fmt(t.packetsRecovered)} <small style="color:var(--dim)">(${(t.recoveryRate * 100).toFixed(1)}%)</small>`
    : "0";

  const rows = s.tracks.map(tr => `
    <tr>
      <td>${esc(tr.label)}<span class="kind">${esc(tr.kind)}</span>${tr.carriesPcr ? '<span class="kind">PCR</span>' : ""}</td>
      <td class="num">0x${tr.pid.toString(16).toUpperCase()}</td>
      <td class="num">${fmt(tr.packets)}</td>
      <td class="num">${fmt(tr.pesPackets)}</td>
      <td class="num ${tr.continuityErrors > 0 ? "bad" : ""}">${fmt(tr.continuityErrors)}</td>
      <td class="num ${tr.erroredSeconds > 0 ? "bad" : ""}">${
        m.observedSeconds > 0 ? `${fmt(tr.erroredSeconds)} / ${fmt(m.observedSeconds)}` : "–"}</td>
      <td class="num ${tr.damagedGops > 0 ? "bad" : ""}">${
        tr.randomAccessPoints > 0 ? `${fmt(tr.damagedGops)} / ${fmt(tr.randomAccessPoints)}` : "–"}</td>
      <td class="num ${tr.minPtsSkewMillis < 0 ? "" : tr.minPtsSkewMillis < 50 ? "warn" : ""}">${
        tr.minPtsSkewMillis >= 0 ? tr.minPtsSkewMillis.toFixed(0) + " ms" : "–"}</td>
      <td class="num">${tr.lastPtsSeconds >= 0 ? tr.lastPtsSeconds.toFixed(2) + "s" : "–"}</td>
    </tr>`).join("");

  return `<section class="stream">
    <h2>${esc(s.streamId) || "<i>no stream id</i>"}<span class="peer">${esc(s.peer)}</span>
      ${head || ""}
      <span class="pill ${m.healthy ? "ok" : "bad"}">${m.healthy ? "healthy" : "degraded"}</span>
    </h2>
    ${banner || ""}
    <div class="panels">
      <div class="panel">
        <h3>Transport · Roast</h3>
        <div class="grid">
          ${metric("RTT", (t.rttMicros / 1000).toFixed(1) + " ms")}
          ${metric("Received", fmt(t.packetsReceived))}
          ${metric("Lost", fmt(t.packetsLost), t.packetsLost > 0 ? "warn" : "")}
          ${metric("Recovered", recovered, t.packetsRecovered > 0 ? "ok" : "")}
          ${metric("Dropped", fmt(t.packetsDropped), droppedClass)}
          ${metric("Rate", bytes(t.receiveRateBytes))}
          ${metric("Buffered", fmt(t.receiveBuffered))}
          ${metric("Flow window", fmt(t.flowWindowPackets))}
        </div>
      </div>
      <div class="panel">
        <h3>Media · Grind</h3>
        <div class="grid">
          ${metric("TS packets", fmt(m.packets))}
          ${metric("Continuity errors", fmt(m.continuityErrors), errorClass)}
          ${metric("Packets lost", fmt(m.packetsLostInTs), m.packetsLostInTs > 0 ? "bad" : "")}
          ${metric("CRC errors", fmt(m.crcErrors), m.crcErrors > 0 ? "bad" : "")}
          ${metric("Errored seconds", m.observedSeconds > 0
            ? `${fmt(m.erroredSeconds)} / ${fmt(m.observedSeconds)}`
            : "–", m.erroredSeconds > 0 ? "bad" : "")}
          ${metric("PCR jumps", fmt(m.pcrDiscontinuities), m.pcrDiscontinuities > 0 ? "bad" : "")}
          ${metric("Sync losses", fmt(m.syncLosses), m.syncLosses > 0 ? "bad" : "")}
          ${metric("PCR spacing", m.maxPcrIntervalMillis > 0
            ? m.maxPcrIntervalMillis.toFixed(0) + " ms"
            : "–", m.pcrRepetitionErrors > 0 ? "warn" : "")}
          ${metric("PTS gaps", fmt(m.ptsErrors), m.ptsErrors > 0 ? "bad" : "")}
          ${metric("Late frames", fmt(m.lateTimestamps), m.lateTimestamps > 0 ? "bad" : "")}
          ${metric("Table gaps", m.maxTableIntervalMillis > 0
            ? `${fmt(m.tableErrors)} · ${m.maxTableIntervalMillis.toFixed(0)} ms`
            : "–", m.tableErrors > 0 ? "bad" : "")}
          ${metric("Programs", fmt(m.programCount))}
        </div>
      </div>
    </div>
    ${splicePanel(s)}
    <div class="panel">
      <h3>Tracks</h3>
      <table>
        <thead><tr><th>Track</th><th>PID</th><th>Packets</th><th>PES</th>
          <th>Continuity errors</th><th>Errored seconds</th><th>GOP damage</th>
          <th>Slack</th><th>Last PTS</th></tr></thead>
        <tbody>${rows || '<tr><td colspan="9">Waiting for the program tables…</td></tr>'}</tbody>
      </table>
    </div>
  </section>`;
}
