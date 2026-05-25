# QR Relay Self-Hosting Guide

This guide explains how to deploy the AURA relay server that powers the QR Exchange feature. The relay is a stateless, ephemeral key-value store: the initiator POSTs an AES-256-GCM-encrypted blob to a time-limited slot, and the recipient GETs and deletes it. The relay never sees plaintext.

---

## Relay Contract

### POST `/relay/{slotId}`
- Body: raw binary (AES-256-GCM ciphertext + IV + tag, max 4 KB)
- Response `200 OK`: `{"ok":true}`
- Slot TTL: 5 minutes; auto-deleted after first GET
- Error `409`: slot already occupied (retry with new `slotId`)
- Error `413`: body exceeds 4 KB limit

### GET `/relay/{slotId}`
- Response `200 OK`: raw binary (same bytes as POST body)
- Response `404`: slot expired or already claimed
- Slot is deleted atomically on first successful GET (one-time pickup)

### Security guarantees
- The relay stores and returns ciphertext only — never decrypts anything
- No authentication required (slot IDs are 128-bit random UUIDs — brute-force infeasible)
- SAS verification on the AURA client provides MITM protection independent of the relay
- HTTPS enforced end-to-end; use a TLS-terminating edge (Firebase, Cloudflare, etc.)

---

## Option A — Firebase Realtime Database (recommended, zero-ops)

Firebase RTDB offers a free Spark plan with 1 GB storage and 10 GB/month transfer, more than enough for ephemeral QR slots.

### 1. Create a Firebase project

```
https://console.firebase.google.com → Add project → (disable Analytics) → Create
```

### 2. Enable Realtime Database

Console → Build → Realtime Database → Create database → Start in **test mode** (you will lock it down in step 4).

Note your database URL, e.g. `https://your-project-default-rtdb.firebaseio.com`.

### 3. Deploy Cloud Functions (TTL enforcement)

Firebase RTDB has no native TTL. Use a scheduled Cloud Function to purge expired slots.

```bash
npm install -g firebase-tools
firebase login
firebase init functions   # choose JavaScript, enable ESLint
```

`functions/index.js`:

```js
const functions = require("firebase-functions");
const admin = require("firebase-admin");
admin.initializeApp();

// HTTP relay endpoints
exports.relayPost = functions.https.onRequest(async (req, res) => {
  if (req.method !== "POST") return res.status(405).send("Method Not Allowed");
  const slotId = req.path.replace(/^\/+/, "").split("/")[0];
  if (!slotId || slotId.length < 8) return res.status(400).send("Bad slot");
  if (req.rawBody.length > 4096) return res.status(413).send("Payload Too Large");

  const db = admin.database();
  const ref = db.ref(`slots/${slotId}`);
  const snap = await ref.once("value");
  if (snap.exists()) return res.status(409).json({ error: "slot_occupied" });

  await ref.set({
    data: req.rawBody.toString("base64"),
    expiresAt: Date.now() + 5 * 60 * 1000,
  });
  return res.json({ ok: true });
});

exports.relayGet = functions.https.onRequest(async (req, res) => {
  if (req.method !== "GET") return res.status(405).send("Method Not Allowed");
  const slotId = req.path.replace(/^\/+/, "").split("/")[0];
  const db = admin.database();
  const ref = db.ref(`slots/${slotId}`);

  const snap = await ref.once("value");
  if (!snap.exists()) return res.status(404).json({ error: "not_found" });

  const { data, expiresAt } = snap.val();
  if (Date.now() > expiresAt) {
    await ref.remove();
    return res.status(404).json({ error: "expired" });
  }

  await ref.remove(); // one-time pickup
  const buf = Buffer.from(data, "base64");
  res.set("Content-Type", "application/octet-stream");
  return res.send(buf);
});

// Scheduled purge — runs every 10 minutes
exports.purgeExpiredSlots = functions.pubsub
  .schedule("every 10 minutes")
  .onRun(async () => {
    const db = admin.database();
    const snap = await db.ref("slots").once("value");
    const now = Date.now();
    const deletes = [];
    snap.forEach((child) => {
      if (child.val().expiresAt < now) deletes.push(child.ref.remove());
    });
    await Promise.all(deletes);
    console.log(`Purged ${deletes.length} expired slots`);
  });
```

```bash
firebase deploy --only functions
```

Functions will be available at:
- `https://us-central1-your-project.cloudfunctions.net/relayPost/{slotId}`
- `https://us-central1-your-project.cloudfunctions.net/relayGet/{slotId}`

### 4. Lock down RTDB rules

Deny all direct client access (all writes go through Cloud Functions with the Admin SDK):

```json
{
  "rules": {
    ".read": false,
    ".write": false
  }
}
```

### 5. Set the relay base URL in AURA

```bash
# For CI (GitHub Actions secret)
RELAY_BASE_URL=https://us-central1-your-project.cloudfunctions.net

# For local dev (.env or gradle.properties — never commit this)
RELAY_BASE_URL=https://us-central1-your-project.cloudfunctions.net
```

The app constructs slot URLs as:
```
POST {RELAY_BASE_URL}/relayPost/{uuid}
GET  {RELAY_BASE_URL}/relayGet/{uuid}
```

---

## Option B — Cloudflare Workers + KV (zero-ops, global edge)

Cloudflare Workers free plan: 100,000 requests/day, KV included. No cold starts.

### 1. Create a KV namespace

```bash
npx wrangler kv:namespace create RELAY_SLOTS
# Note the namespace ID from the output
```

### 2. `wrangler.toml`

```toml
name = "aura-relay"
main = "src/worker.js"
compatibility_date = "2024-01-01"

[[kv_namespaces]]
binding = "RELAY_SLOTS"
id = "<your-namespace-id>"
```

### 3. `src/worker.js`

```js
const SLOT_TTL_SECONDS = 300; // 5 minutes
const MAX_BODY_BYTES = 4096;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    // Expected paths: /relay/{slotId}
    const match = url.pathname.match(/^\/relay\/([a-zA-Z0-9_-]{8,128})$/);
    if (!match) return new Response("Not Found", { status: 404 });
    const slotId = match[1];

    if (request.method === "POST") {
      const body = await request.arrayBuffer();
      if (body.byteLength > MAX_BODY_BYTES)
        return Response.json({ error: "payload_too_large" }, { status: 413 });

      const existing = await env.RELAY_SLOTS.get(slotId);
      if (existing !== null)
        return Response.json({ error: "slot_occupied" }, { status: 409 });

      // Store as base64; KV values are strings
      const b64 = btoa(String.fromCharCode(...new Uint8Array(body)));
      await env.RELAY_SLOTS.put(slotId, b64, { expirationTtl: SLOT_TTL_SECONDS });
      return Response.json({ ok: true });
    }

    if (request.method === "GET") {
      const b64 = await env.RELAY_SLOTS.get(slotId);
      if (b64 === null)
        return Response.json({ error: "not_found" }, { status: 404 });

      // One-time pickup — delete immediately
      await env.RELAY_SLOTS.delete(slotId);

      const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
      return new Response(bytes, {
        headers: { "Content-Type": "application/octet-stream" },
      });
    }

    return new Response("Method Not Allowed", { status: 405 });
  },
};
```

### 4. Deploy

```bash
npx wrangler deploy
```

Your relay will be live at `https://aura-relay.<your-subdomain>.workers.dev`.

### 5. Set `RELAY_BASE_URL`

```bash
RELAY_BASE_URL=https://aura-relay.<your-subdomain>.workers.dev
```

The app appends `/relay/{uuid}` for both POST and GET.

---

## Configuring `RELAY_BASE_URL`

### GitHub Actions

Add a repository secret:

```
Settings → Secrets and variables → Actions → New repository secret
Name:  RELAY_BASE_URL
Value: https://aura-relay.your-subdomain.workers.dev
```

The `ci.yml` workflow reads it via:

```yaml
env:
  RELAY_BASE_URL: ${{ secrets.RELAY_BASE_URL }}
```

And it flows into the app via `BuildConfig.RELAY_BASE_URL` (configured in `app/build.gradle.kts`).

### Local development

Create `local.properties` (already in `.gitignore`) and add:

```properties
relay.base.url=https://aura-relay.your-subdomain.workers.dev
```

Or set an environment variable before running Gradle:

```bash
export RELAY_BASE_URL=https://aura-relay.your-subdomain.workers.dev
./gradlew assembleDebug
```

---

## Privacy notes

- Slot IDs are random UUIDs generated client-side — not tied to any user identity
- The relay stores ciphertext only; the key never leaves the device
- Even if the relay is compromised, an attacker cannot decrypt the payload without breaking AES-256-GCM
- SAS verification (6-digit PIN shown on both devices) provides a second, out-of-band MITM check independent of the relay
- Slot TTL of 5 minutes limits the window for offline dictionary attacks on slot IDs (effectively impossible at 2^122 entropy)

---

## Relay server contract summary

| Property | Value |
|---|---|
| Transport | HTTPS only |
| Slot ID format | UUID v4 (128 bits random) |
| Max payload | 4 096 bytes |
| Slot TTL | 5 minutes |
| Pickup semantics | One-time (deleted on first GET) |
| Relay sees | Ciphertext only |
| Auth required | None (slot ID is the capability) |
| MITM protection | SAS PIN (client-side, not relay-provided) |


---

## Phase 5.7 — TLS Certificate Pinning

AURA pins the relay server's TLS leaf certificate via `network_security_config.xml`.
This section documents the pin rotation process.

### Current pin configuration

File: `app/src/main/res/xml/network_security_config.xml`
- `domain`: `relay.example.com` (replace with your actual relay hostname)
- `pin-set expiration`: `2027-06-01`
- `BuildConfig.RELAY_PIN_EXPIRY_EPOCH_MS`: `1780300800000L` (in `app/build.gradle.kts`)

### How to get the current certificate's pin hash

```bash
openssl s_client -connect relay.example.com:443 2>/dev/null </dev/null \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform DER \
  | openssl dgst -sha256 -binary \
  | base64
```

### Pin rotation process

1. **Obtain the new certificate pin** using the command above against the new server cert.
2. **Update `network_security_config.xml`**: replace the `<pin>` values with the new hash.
3. **Update the expiry date**: set `pin-set expiration` to the new certificate's expiry date.
4. **Update `build.gradle.kts`**: set `RELAY_PIN_EXPIRY_EPOCH_MS` to the new expiry epoch in milliseconds.
5. **Verify**: run `./gradlew test` — `RelayClientPinTest.relayPinExpiryEpoch_isInFuture()` must pass.
6. **Ship**: build a release APK/AAB and verify relay connectivity before distributing.

### CI warning system

`RelayClient` logs a `Timber.w` warning when the current time is within 30 days of
`BuildConfig.RELAY_PIN_EXPIRY_EPOCH_MS`. Monitor CI logs for this warning.
