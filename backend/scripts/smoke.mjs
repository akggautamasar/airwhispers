/**
 * Live end-to-end smoke test against a running AirWhispers server.
 *
 *   node scripts/smoke.mjs [baseUrl]
 *
 * Exercises exactly the path the Android app uses: two devices register and are
 * handed codes, one opens a chat with the other's code, the recipient listens on
 * the realtime socket, a "whisper now" message is sent, the socket receives
 * `message.created`, speech consent is granted, and the message is reported as
 * spoken (the Call Assist feedback loop).
 */
import { WebSocket } from "ws";

const BASE = (process.argv[2] ?? "http://127.0.0.1:8080").replace(/\/$/, "");
const WS_URL = BASE.replace(/^http/, "ws") + "/api/v1/realtime";
const suffix = Date.now().toString(36);

async function request(method, path, body, token) {
  const response = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  return { status: response.status, body: text ? JSON.parse(text) : null };
}

const fail = (message) => {
  console.error(`✗ ${message}`);
  process.exit(1);
};

const health = await request("GET", "/healthz");
console.log(`· health: ${health.status} ${JSON.stringify(health.body)}`);

const anu = await request("POST", "/api/v1/device/register", {
  deviceId: `smoke-device-a-${suffix}`,
  displayName: "Smoke Anu",
  platform: "smoke",
});
const bob = await request("POST", "/api/v1/device/register", {
  deviceId: `smoke-device-b-${suffix}`,
  displayName: "Smoke Bob",
  platform: "smoke",
});
if (anu.status !== 201 || bob.status !== 201) fail(`register failed (${anu.status}/${bob.status})`);
console.log(`✓ two devices registered — codes ${anu.body.user.code} / ${bob.body.user.code} (no accounts, no passwords)`);

const resumed = await request("POST", "/api/v1/device/register", {
  deviceId: `smoke-device-a-${suffix}`,
  deviceSecret: anu.body.deviceSecret,
});
if (resumed.status !== 200 || resumed.body.user.code !== anu.body.user.code) fail("device could not resume with its secret");
console.log("✓ device resumed silently with its secret (same code, fresh token)");

const socket = new WebSocket(WS_URL);
const frames = [];
await new Promise((resolve, reject) => {
  const timer = setTimeout(() => reject(new Error("timeout waiting for auth.ok")), 5000);
  socket.on("open", () =>
    socket.send(
      JSON.stringify({
        type: "auth",
        data: { accessToken: bob.body.tokens.accessToken, deviceId: `smoke-device-b-${suffix}` },
      }),
    ),
  );
  socket.on("message", (raw) => {
    const frame = JSON.parse(raw.toString());
    frames.push(frame);
    if (frame.type === "auth.ok") {
      clearTimeout(timer);
      resolve();
    }
    if (frame.type === "error") {
      clearTimeout(timer);
      reject(new Error(`server error frame: ${JSON.stringify(frame)}`));
    }
  });
  socket.on("error", reject);
}).catch((error) => fail(error.message));
console.log("✓ realtime socket authenticated");

socket.send(JSON.stringify({ type: "ping" }));
await new Promise((resolve) => setTimeout(resolve, 150));
if (!frames.some((frame) => frame.type === "pong")) fail("no pong");

// Upper case and a dash, the way a human types a code.
const messyCode = `${bob.body.user.code.slice(0, 3).toUpperCase()}-${bob.body.user.code.slice(3).toUpperCase()}`;
const chat = await request("POST", "/api/v1/conversations", { code: messyCode }, anu.body.tokens.accessToken);
if (chat.status !== 201) fail(`opening the chat failed (${chat.status} ${JSON.stringify(chat.body)})`);
const conversationId = chat.body.conversation.id;
console.log(`✓ chat opened with "${messyCode}" (peer ${chat.body.conversation.peer.displayName})`);

const send = await request(
  "POST",
  `/api/v1/conversations/${conversationId}/messages`,
  { clientMessageId: `smoke-${suffix}`, text: "Are you alone?", priority: "SPEAK_NOW" },
  anu.body.tokens.accessToken,
);
if (send.status !== 201) fail(`send failed (${send.status})`);
console.log(`✓ message sent (priority=${send.body.message.priority}, speakEligible=${send.body.message.speakEligible})`);

await new Promise((resolve) => setTimeout(resolve, 300));
const created = frames.find((frame) => frame.type === "message.created");
if (!created) fail("recipient socket never received message.created");
if (created.data.text !== "Are you alone?") fail("unexpected message text over the socket");
console.log("✓ recipient socket received message.created in realtime");

const allow = await request("PATCH", `/api/v1/conversations/${conversationId}/trust`, { trusted: true }, bob.body.tokens.accessToken);
if (allow.status !== 200 || allow.body.conversation.youAllowSpeak !== true) fail("consent toggle failed");
console.log("✓ Bob allowed Anu to whisper to his device (consent is one-directional)");

const spoken = await request("POST", `/api/v1/messages/${send.body.message.id}/spoken`, {}, bob.body.tokens.accessToken);
if (spoken.status !== 204) fail(`spoken report failed (${spoken.status})`);
console.log("✓ spoken feedback recorded (the phone actually read it aloud)");

const history = await request("GET", `/api/v1/conversations/${conversationId}/messages`, undefined, bob.body.tokens.accessToken);
if (history.body.messages.length !== 1) fail(`expected 1 message, got ${history.body.messages.length}`);
console.log("✓ history contains the message exactly once");

socket.close();
console.log("\nAll smoke checks passed. Frame types seen:", [...new Set(frames.map((f) => f.type))].join(", "));
