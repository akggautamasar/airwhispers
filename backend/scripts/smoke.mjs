/**
 * Live end-to-end smoke test against a running AirWhispers server.
 *
 *   node scripts/smoke.mjs [baseUrl]
 *
 * Exercises exactly the path the Android app uses: register two accounts, open
 * the realtime socket, authenticate with a frame, send a "Speak Now" message and
 * confirm the recipient's socket receives `message.created`, then report the
 * message as spoken (the Call Assist feedback loop).
 */
import { WebSocket } from "ws";

const BASE = (process.argv[2] ?? "http://127.0.0.1:8080").replace(/\/$/, "");
const API = `${BASE}/api/v1`;
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

const alice = await request("POST", "/api/v1/auth/register", {
  email: `smoke-alice-${suffix}@example.com`,
  password: "correct-horse-battery",
  displayName: "Smoke Alice",
  deviceId: `device-a-${suffix}`,
});
const bob = await request("POST", "/api/v1/auth/register", {
  email: `smoke-bob-${suffix}@example.com`,
  password: "correct-horse-battery",
  displayName: "Smoke Bob",
  deviceId: `device-b-${suffix}`,
});
if (alice.status !== 201 || bob.status !== 201) fail(`register failed (${alice.status}/${bob.status})`);
console.log("✓ two accounts created");

const socket = new WebSocket(WS_URL);
const frames = [];
await new Promise((resolve, reject) => {
  const timer = setTimeout(() => reject(new Error("timeout waiting for auth.ok")), 5000);
  socket.on("open", () =>
    socket.send(
      JSON.stringify({
        type: "auth",
        data: { accessToken: bob.body.tokens.accessToken, deviceId: bob.body.deviceId ?? "device-b" },
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

const contact = await request("POST", "/api/v1/contacts", { email: `smoke-bob-${suffix}@example.com` }, alice.body.tokens.accessToken);
if (contact.status !== 201) fail(`add contact failed (${contact.status})`);
const conversationId = contact.body.contact.conversationId;
console.log(`✓ contact + conversation (trusted=${contact.body.contact.isTrusted})`);

const send = await request(
  "POST",
  `/api/v1/conversations/${conversationId}/messages`,
  { clientMessageId: `smoke-${suffix}`, text: "Are you alone?", priority: "SPEAK_NOW" },
  alice.body.tokens.accessToken,
);
if (send.status !== 201) fail(`send failed (${send.status})`);
console.log(`✓ message sent (priority=${send.body.message.priority}, speakEligible=${send.body.message.speakEligible})`);

await new Promise((resolve) => setTimeout(resolve, 300));
const created = frames.find((frame) => frame.type === "message.created");
if (!created) fail("recipient socket never received message.created");
if (created.data.text !== "Are you alone?") fail("unexpected message text over the socket");
console.log("✓ recipient socket received message.created");

const spoken = await request("POST", `/api/v1/messages/${send.body.message.id}/spoken`, {}, bob.body.tokens.accessToken);
if (spoken.status !== 204) fail(`spoken report failed (${spoken.status})`);
console.log("✓ spoken feedback recorded");

const history = await request("GET", `/api/v1/conversations/${conversationId}/messages`, undefined, bob.body.tokens.accessToken);
if (history.body.messages.length !== 1) fail(`expected 1 message, got ${history.body.messages.length}`);
console.log("✓ history contains the message exactly once");

socket.close();
console.log("\nAll smoke checks passed. Frame types seen:", [...new Set(frames.map((f) => f.type))].join(", "));
