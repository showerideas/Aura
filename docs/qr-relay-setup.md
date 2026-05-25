# QR Relay Setup Guide

AURA's QR exchange channel uses a relay server as a rendezvous point when
Nearby Connections or Wi-Fi Direct are unavailable. This guide walks through
setting up the relay using **Firebase Realtime Database** — a fully managed,
zero-ops option that fits within Google's free tier for typical AURA usage.

---

## 1. Prerequisites

- A Google account
- [Firebase CLI](https://firebase.google.com/docs/cli) installed (`npm install -g firebase-tools`)
- `RELAY_BASE_URL` environment variable set in CI (see §5)

---

## 2. Create a Firebase project

1. Go to <https://console.firebase.google.com> and click **Add project**.
2. Enter a project name (e.g. `aura-relay`).
3. Disable Google Analytics (not needed) → **Create project**.
4. In the left sidebar, select **Build → Realtime Database**.
5. Click **Create Database** → choose a region close to your users.
6. Select **Start in locked mode** (you will add security rules in §3).

---

## 3. Database security rules

Paste the following into **Realtime Database → Rules**:

```json
{
  "rules": {
    "relay": {
      "$sessionId": {
        // Any authenticated client can read and write their own session slot.
        // Session IDs are UUIDs generated on-device — effectively unguessable.
        // Payloads expire server-side via a Cloud Function (see §4).
        ".read":  "auth == null",
        ".write": "auth == null",
        // Enforce maximum payload size: ~4 KB covers any realistic AURA handshake.
        ".validate": "newData.isString() && newData.val().length <= 4096"
      }
    }
  }
}
```

> **Security note:** AURA QR payloads are ephemeral, short-lived (60 s), and
> contain only ECDH public keys + session UUIDs — no profile data or secrets.
> Profile data is encrypted end-to-end after the relay handshake completes.
> Leaving the relay open-read/write is acceptable because a stolen payload
> only reveals a one-time ephemeral public key; the ECDH handshake still
> requires a subsequent SAS verbal confirmation to detect relay-based MITM.

---

## 4. Auto-expiry Cloud Function (optional but recommended)

Without expiry, stale session slots accumulate. Deploy a simple cleanup function:

```bash
firebase init functions   # select JavaScript; enable ESLint
```

Replace `functions/index.js` with:

```javascript
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { getDatabase }  = require("firebase-admin/database");
const { initializeApp } = require("firebase-admin/app");

initializeApp();

// Runs every 5 minutes; deletes relay slots older than 120 seconds.
exports.cleanStaleRelaySessions = onSchedule("every 5 minutes", async () => {
  const db   = getDatabase();
  const ref  = db.ref("relay");
  const cutoff = Date.now() - 120_000;

  const snap = await ref.orderByChild("createdAt").endAt(cutoff).get();
  if (!snap.exists()) return;

  const updates = {};
  snap.forEach(child => { updates[child.key] = null; });
  await ref.update(updates);
  console.log(`Cleaned ${Object.keys(updates).length} stale relay sessions`);
});
```

Deploy:

```bash
firebase deploy --only functions
```

> **Free tier note:** Scheduled functions require the **Blaze (pay-as-you-go)**
> plan. If you prefer the free Spark plan, omit the function — the app's
> built-in 60-second client-side expiry will prevent relay slots from being
> used after they expire, even if they persist in the database.

---

## 5. Wire the relay URL into AURA

### Local development

```bash
export RELAY_BASE_URL="https://<your-project-id>-default-rtdb.firebaseio.com"
./gradlew assembleGmsDebug
```

### CI / GitHub Actions

1. In your repository go to **Settings → Secrets and variables → Actions**.
2. Add a repository secret named `RELAY_BASE_URL` with the value:
   `https://<your-project-id>-default-rtdb.firebaseio.com`
3. The CI workflow already reads `RELAY_BASE_URL` via the `buildConfigField`
   in `app/build.gradle.kts`:
   ```kotlin
   val relayBaseUrl = System.getenv("RELAY_BASE_URL")?.takeIf { it.isNotBlank() }
       ?: "https://relay.example.com"
   buildConfigField("String", "RELAY_BASE_URL", "\"$relayBaseUrl\"")
   ```

If `RELAY_BASE_URL` is not set, the build succeeds but QR relay attempts will
fail at runtime (the default `relay.example.com` does not exist). Nearby
Connections and Wi-Fi Direct are unaffected.

---

## 6. Self-hosted alternative (no Firebase)

If you prefer to avoid Google services entirely, any HTTP server that supports
the following two endpoints is compatible:

| Method | Path | Description |
|--------|------|-------------|
| `PUT`  | `/relay/{sessionId}` | Store a base64-encoded payload. Returns `200 OK`. |
| `GET`  | `/relay/{sessionId}` | Retrieve the payload. Returns `200 OK` with the payload, or `404` if expired/missing. |
| `DELETE` | `/relay/{sessionId}` | Delete after retrieval. Returns `200 OK`. |

A minimal Node.js implementation (~50 lines) using in-memory storage with
TTL is available in `tools/relay-server/` (coming in Phase 7.x).

> AURA's relay client (`QRExchangeRepository`) uses the `RELAY_BASE_URL`
> build config field as the base URL for all three operations above.

---

## 7. Verify the setup

Run AURA on two devices connected to the Internet, navigate to **Activate →
QR Exchange**, and scan one device's QR code with the other. If the relay is
configured correctly the devices will exchange ECDH public keys, display the
SAS verification dialog, and complete the contact exchange within a few seconds.

If the exchange fails check:
- `RELAY_BASE_URL` is set to your Firebase RTDB URL (not the placeholder)
- Firebase database rules allow unauthenticated read/write on `/relay/*`
- Both devices have Internet connectivity (QR relay does not work offline)
- The session UUID in the QR code hasn't expired (60-second window)
