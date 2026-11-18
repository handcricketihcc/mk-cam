// MK Cam signaling server (MVP: live audio).
// Job: PIN pairing + relaying WebRTC offer/answer/ICE between Device and Viewer.
// Media never passes through here (it goes peer-to-peer, or via TURN).
const http = require("http");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { Server } = require("socket.io");

const PORT = process.env.PORT || 3000;
const DATA_FILE = process.env.DATA_FILE || path.join(__dirname, "pairs.json");
const PIN_TTL_MS = 10 * 60 * 1000;

// ICE servers handed to the apps. Add your TURN via env TURN_URL / TURN_USER / TURN_PASS.
function iceServers() {
  const list = [{ urls: "stun:stun.l.google.com:19302" }];
  if (process.env.TURN_URL) {
    list.push({
      urls: process.env.TURN_URL.split(","),
      username: process.env.TURN_USER || "",
      credential: process.env.TURN_PASS || "",
    });
  }
  return list;
}

// ---- persistence: pairs survive server restarts ----
// pairs[pairId] = { secretHash: {viewer, device} }
let pairs = {};
try {
  pairs = JSON.parse(fs.readFileSync(DATA_FILE, "utf8"));
} catch (_) {
  pairs = {};
}
function save() {
  try {
    fs.writeFileSync(DATA_FILE, JSON.stringify(pairs));
  } catch (e) {
    console.error("save failed", e.message);
  }
}
const sha = (s) => crypto.createHash("sha256").update(String(s)).digest("hex");
const rand = (n) => crypto.randomBytes(n).toString("hex");

// pending PINs: pin -> { pairId, viewerSecret, expires }
const pending = new Map();
// online sockets: pairId -> { viewer: socket, device: socket }
const online = new Map();

// ---- tiny rate limiter (per IP, per event bucket) ----
const hits = new Map();
function limited(ip, bucket, max, windowMs) {
  const key = ip + "|" + bucket;
  const now = Date.now();
  const arr = (hits.get(key) || []).filter((t) => now - t < windowMs);
  arr.push(now);
  hits.set(key, arr);
  return arr.length > max;
}
setInterval(() => {
  const now = Date.now();
  for (const [k, v] of pending) if (v.expires < now) pending.delete(k);
  for (const [k, v] of hits) if (!v.some((t) => now - t < 60000)) hits.delete(k);
}, 30000).unref();

const httpServer = http.createServer((req, res) => {
  if (req.url === "/health") {
    res.writeHead(200, { "Content-Type": "text/plain" });
    return res.end("ok");
  }
  res.writeHead(404);
  res.end();
});

const io = new Server(httpServer, { cors: { origin: "*" }, maxHttpBufferSize: 1e5 });

io.on("connection", (socket) => {
  const ip = socket.handshake.address;
  let ctx = null; // { pairId, role }

  const ack = (cb, obj) => typeof cb === "function" && cb(obj);

  // Viewer starts pairing -> gets a 6-digit PIN
  socket.on("pair:create", (_d, cb) => {
    if (limited(ip, "create", 10, 60000)) return ack(cb, { ok: false, error: "rate_limited" });
    let pin;
    do {
      pin = String(crypto.randomInt(0, 1000000)).padStart(6, "0");
    } while (pending.has(pin));
    const pairId = rand(8);
    const viewerSecret = rand(24);
    pending.set(pin, { pairId, viewerSecret, expires: Date.now() + PIN_TTL_MS, socketId: socket.id });
    ack(cb, { ok: true, pin, pairId, secret: viewerSecret, iceServers: iceServers() });
  });

  // Device claims the PIN -> gets its own secret
  socket.on("pair:claim", (d, cb) => {
    if (limited(ip, "claim", 8, 60000)) return ack(cb, { ok: false, error: "rate_limited" });
    const pin = String((d && d.pin) || "");
    const p = pending.get(pin);
    if (!p || p.expires < Date.now()) return ack(cb, { ok: false, error: "bad_pin" });
    pending.delete(pin); // one-time
    const deviceSecret = rand(24);
    pairs[p.pairId] = { viewer: sha(p.viewerSecret), device: sha(deviceSecret) };
    save();
    ack(cb, { ok: true, pairId: p.pairId, secret: deviceSecret, iceServers: iceServers() });
    io.to(p.socketId).emit("pair:claimed", { pairId: p.pairId });
  });

  // Both sides connect with pairId + secret + role
  socket.on("join", (d, cb) => {
    if (limited(ip, "join", 30, 60000)) return ack(cb, { ok: false, error: "rate_limited" });
    const { pairId, role, secret } = d || {};
    const pair = pairs[pairId];
    if (!pair || (role !== "viewer" && role !== "device") || sha(secret) !== pair[role]) {
      return ack(cb, { ok: false, error: "unauthorized" });
    }
    const room = online.get(pairId) || {};
    if (room[role] && room[role].id !== socket.id) room[role].disconnect(true); // replace stale
    room[role] = socket;
    online.set(pairId, room);
    ctx = { pairId, role };
    socket.join(pairId);
    const other = role === "viewer" ? "device" : "viewer";
    ack(cb, { ok: true, peerOnline: !!room[other], iceServers: iceServers() });
    if (room[other]) room[other].emit("peer:online", { role });
  });

  // Viewer asks the device to start streaming audio
  socket.on("listen:request", () => {
    if (!ctx || ctx.role !== "viewer") return;
    const room = online.get(ctx.pairId);
    if (room && room.device) room.device.emit("listen:request");
    else socket.emit("peer:offline", {});
  });
  socket.on("listen:stop", () => {
    if (!ctx) return;
    const room = online.get(ctx.pairId);
    const target = room && room[ctx.role === "viewer" ? "device" : "viewer"];
    if (target) target.emit("listen:stop");
  });

  // Generic relay for offer / answer / ice
  socket.on("signal", (data) => {
    if (!ctx) return;
    const room = online.get(ctx.pairId);
    const target = room && room[ctx.role === "viewer" ? "device" : "viewer"];
    if (target) target.emit("signal", data);
  });

  socket.on("disconnect", () => {
    if (!ctx) return;
    const room = online.get(ctx.pairId);
    if (room && room[ctx.role] && room[ctx.role].id === socket.id) {
      delete room[ctx.role];
      const other = ctx.role === "viewer" ? "device" : "viewer";
      if (room[other]) room[other].emit("peer:offline", { role: ctx.role });
      if (!room.viewer && !room.device) online.delete(ctx.pairId);
    }
  });
});

httpServer.listen(PORT, () => console.log("MK Cam signaling on :" + PORT));
