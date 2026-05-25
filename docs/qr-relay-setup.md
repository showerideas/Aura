# QR Relay Self-Hosting Guide

> Set this up once. It takes ~10 minutes. Zero ongoing cost on Firebase free tier.

AURA's QR relay lets two devices swap encrypted profiles when they can't reach each
other over BLE or Wi-Fi Direct (enterprise firewalls, different subnets, etc.).

**Security guarantee:** The relay server only ever stores ciphertext. Your profile
data is encrypted client-side with AES-256-GCM before it leaves your phone. The relay
server cannot read it, cannot correlate sender and receiver (they use randomly-generated
slot IDs), and each slot self-expires after 60 seconds.

---

## Option A — Firebase Realtime Database (recommended, zero-ops)

### 1. Create a Firebase project

1. Go to [console.firebase.google.com](https://console.firebase.google.com)
2. Click **Add project** → name it `aura-relay` (or anything you like)
3. Disable Google Analytics (not needed) → **Create project**

### 2. Create a Realtime Database

1. In the left sidebar: **Build → Realtime Database**
2. Click **Create Database**
3. Choose a region close to your users (e.g. `us-central1`)
4. Start in **locked mode** (we'll set rules next)

### 3. Set security rules

Replace the default rules with these — they allow any client to write to a slot once
and read from it, but expire slots server-side via the 60-second TTL in the app:

```json
{
  "rules": {
    "slots": {
      "$slotId": {
        ".read": true,
        ".write": "!data.exists()"
      }
    }
  }
}
```

> **Why `!data.exists()`?** This prevents overwriting an existing slot. A slot is
> written once (the sender's encrypted profile) and read once (by the peer). Once the
> app reads the slot it deletes it client-side. The 60-second QR expiry in AURA's UI
> also limits the exposure window.

### 4. Get your database URL

From the Realtime Database page, copy the URL — it looks like:

```
https://aura-relay-default-rtdb.firebaseio.com
```

### 5. Configure AURA to use your relay

#### For local development

Add to your shell profile (`~/.zshrc` or `~/.bashrc`):

```bash
export RELAY_BASE_URL="https://aura-relay-default-rtdb.firebaseio.com"
```

Then rebuild:

```bash
./gradlew assembleDebug
```

#### For CI (GitHub Actions)

In your repo: **Settings → Secrets and variables → Actions → Variables** (not Secrets —
the relay URL is not sensitive):

| Name | Value |
|---|---|
| `RELAY_BASE_URL` | `https://aura-relay-default-rtdb.firebaseio.com` |

Then reference it in `.github/workflows/ci.yml`:

```yaml
env:
  RELAY_BASE_URL: ${{ vars.RELAY_BASE_URL }}
```

The `buildConfigField` in `app/build.gradle.kts` picks it up automatically:

```kotlin
val relayBaseUrl = System.getenv("RELAY_BASE_URL")?.takeIf { it.isNotBlank() }
    ?: "https://relay.example.com"
buildConfigField("String", "RELAY_BASE_URL", "\"$relayBaseUrl\"")
```

### 6. Verify it works

```bash
# Write a test slot
curl -X PUT \
  "https://aura-relay-default-rtdb.firebaseio.com/slots/test123.json" \
  -d '"hello"'

# Read it back
curl "https://aura-relay-default-rtdb.firebaseio.com/slots/test123.json"
# → "hello"

# Delete it
curl -X DELETE \
  "https://aura-relay-default-rtdb.firebaseio.com/slots/test123.json"
```

---

## Option B — Cloudflare Workers (alternative, also zero-ops)

Cloudflare Workers free tier: 100,000 requests/day. Sufficient for personal use.

### 1. Install Wrangler

```bash
npm install -g wrangler
wrangler login
```

### 2. Create a KV namespace for slot storage

```bash
wrangler kv:namespace create AURA_SLOTS
# Note the id from the output
```

### 3. Create the worker

`aura-relay/src/index.ts`:

```typescript
export interface Env {
  AURA_SLOTS: KVNamespace;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    // Expect path: /slots/<slotId>
    const match = url.pathname.match(/^\/slots\/([a-zA-Z0-9_-]{8,64})$/);
    if (!match) {
      return new Response("Bad request", { status: 400 });
    }
    const slotId = match[1];

    if (request.method === "PUT" || request.method === "POST") {
      const existing = await env.AURA_SLOTS.get(slotId);
      if (existing !== null) {
        return new Response("Slot already occupied", { status: 409 });
      }
      const body = await request.text();
      // TTL: 120 seconds (double the 60s QR expiry — allows for clock skew)
      await env.AURA_SLOTS.put(slotId, body, { expirationTtl: 120 });
      return new Response("OK", { status: 201 });

    } else if (request.method === "GET") {
      const value = await env.AURA_SLOTS.get(slotId);
      if (value === null) {
        return new Response("Not found", { status: 404 });
      }
      return new Response(value, {
        headers: { "Content-Type": "application/json" }
      });

    } else if (request.method === "DELETE") {
      await env.AURA_SLOTS.delete(slotId);
      return new Response("Deleted", { status: 200 });

    } else {
      return new Response("Method not allowed", { status: 405 });
    }
  }
};
```

`wrangler.toml`:

```toml
name = "aura-relay"
main = "src/index.ts"
compatibility_date = "2024-01-01"

[[kv_namespaces]]
binding = "AURA_SLOTS"
id = "<your-kv-namespace-id>"
```

### 4. Deploy

```bash
wrangler deploy
```

Your relay URL will be: `https://aura-relay.<your-account>.workers.dev`

Set `RELAY_BASE_URL` to this URL as in Option A step 5.

---

## Relay contract (for third-party relay implementations)

AURA's `RelayClient.kt` expects the following REST contract:

| Operation | Method | Path | Body | Success |
|---|---|---|---|---|
| Post profile | `POST` | `/slots/<slotId>` | `{"payload":"<base64url>"}` | `201` |
| Poll for peer | `GET` | `/slots/<slotId>` | — | `200` with JSON body, or `404` |
| Delete slot | `DELETE` | `/slots/<slotId>` | — | `200` or `204` |

### Slot ID format

A random UUID (v4) without hyphens, 32 hex characters. Generated by
`QRExchangeViewModel` from the local ephemeral session UUID.

### Payload format

The body is a JSON object with a single `payload` key containing the
Base64url-encoded (no padding) AES-256-GCM ciphertext of the sender's profile.
The GCM nonce is prepended to the ciphertext (first 12 bytes). No plaintext
profile data ever reaches the relay.

### TTL

The relay should expire unclaimed slots after at least 60 seconds (the QR code
rotation period). 120 seconds is recommended to account for clock skew and slow
networks.

### CORS

If the relay is accessed from a browser context (e.g. for the GitHub Pages landing
page), add:

```
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET, POST, DELETE
```

---

## Privacy notes

- The relay IP logs show two requests to the same slot ID from two different IP addresses.
  This leaks the timing and rough location of the exchange to the relay operator.
- For higher privacy: route relay requests through a VPN or Tor (opt-in, see
  `Settings → Privacy → Advanced → Anonymous QR relay` — Phase 8.3 roadmap item).
- The relay operator **cannot** decrypt the profile payload — it is AES-256-GCM
  encrypted before it reaches the relay.
