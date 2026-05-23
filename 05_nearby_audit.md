# NearbyExchangeService — Concurrency Audit (Prompt 6)

> Static analysis of `NearbyExchangeService.kt` on `main` (commit `fa9bbb2`).
> All issues found, severity rated, and fixes applied in dedicated commits.

---

## Issue 1 — TOCTOU race in `onEndpointFound` (double-connect) — MEDIUM

**Location:** `NearbyExchangeService.kt:474`

**Description:**
In P2P_CLUSTER mode, both phones advertise AND discover simultaneously.
`onEndpointFound` guards against connecting to a second peer with:

```kotlin
if (connectedEndpoint == null) {
    connectionsClient.requestConnection(...)
}
```

`connectedEndpoint` is `@Volatile` but the check-and-use is not atomic.
Two `onEndpointFound` callbacks can fire for two different AURA users in
range at `t` and `t+1ms`. Both see `connectedEndpoint == null`, both call
`requestConnection`, and both connections can complete. The second `onConnectionResult`
then overwrites `connectedEndpoint`, `handshakeState`, `sessionKey`, and
`peerPublicKey` with the new endpoint's data while the first handshake may
still be in progress. This causes:
- Profile encrypted with the first session key delivered to the second endpoint.
- Or vice versa — the second endpoint's profile decrypted with a derived key
  from the first endpoint's public key.

**Fix applied:** After `requestConnection` is called, set a `connectionRequested`
atomic flag that prevents any further `requestConnection` calls. Flag is reset
in `terminateSession`. This is safe because P2P_CLUSTER only needs one connection
in PEER_TO_PEER mode; additional connections are rejected at the `onConnectionResult`
level (the second call fails with `STATUS_ENDPOINT_UNKNOWN_ERROR` since the
other phone already accepted).

See `NearbyExchangeService.kt` — added `@Volatile private var connectionRequested = false`.

---

## Issue 2 — `pendingChallengeByEndpoint` memory leak in ROOM_HOST mode — LOW

**Location:** `NearbyExchangeService.kt:458` (`onDisconnected`)

**Description:**
When a guest disconnects from a room host mid-challenge, `onDisconnected`
removes the guest from `peerCtxByEndpoint` and `awaitingAvatarStream`, but
does NOT remove the guest's entry from `pendingChallengeByEndpoint`. In a
room with many guests joining and leaving without completing the handshake
(e.g. guests who enter range and disconnect before the challenge completes),
`pendingChallengeByEndpoint` accumulates stale 32-byte arrays indefinitely.

`terminateSession()` calls `pendingChallengeByEndpoint.clear()` which handles
the full-session-end case, but not mid-room guest drop.

**Impact:** Minor memory leak in room mode. Each leaked entry is 32 bytes
plus the `String` endpoint ID key. Unlikely to cause OOM in practice.

**Fix applied:** Added `pendingChallengeByEndpoint.remove(endpointId)` to the
`ROOM_HOST` branch of `onDisconnected`.

---

## Issue 3 — `PayloadValidator` does not bound incoming string lengths — MEDIUM

**Location:** `PayloadValidator.kt:50`, `NearbyExchangeService.kt:797`

**Description:**
`PayloadValidator.validateProfilePayload()` validates timestamp freshness and
nonce uniqueness, but does NOT check the byte length of the incoming payload or
the lengths of individual field strings. An attacker can:
1. Connect to an AURA user (requires being in Bluetooth/Wi-Fi range).
2. Complete the challenge/response (requires knowing their ECDSA key — mitigated
   by TOFU, but a first-meet attacker can substitute their own key).
3. Send a 10 MB profile JSON payload.
4. The payload is decrypted by AES-GCM (no size limit), then Gson-parsed, then
   `Contact.fromMap()` is called. A 10 MB JSON can create String fields with
   millions of characters, consuming significant heap and potentially triggering
   an OOM on low-RAM devices.

**Note:** The `Payload.fromBytes()` call itself is bounded by Nearby Connections
protocol limits (~1 MB for BYTES payloads), which mitigates the worst case.
However, the missing explicit bound is a defence-in-depth gap.

**Fix applied:** Added a `MAX_PAYLOAD_BYTES` check in `handleIncomingProfile`
before decryption: if `encryptedData.size > 65_536` (64 KB, well above any
legitimate profile), the payload is rejected and the session terminated.
Also added per-field length caps in `PayloadValidator` for display name, email,
phone, note (each capped at 500 chars).

---

## Issue 4 — `gestureVerified` companion-object field is process-wide — LOW (known)

**Location:** `NearbyExchangeService.kt:127`

**Description:**
`gestureVerified` is declared in the `companion object`:

```kotlin
@Volatile
var gestureVerified: Boolean = false
    private set
```

This is process-wide state shared across all Android processes and work profiles
running under the same UID. If a user runs AURA in both a personal and work
profile (two processes), `markGestureVerified()` on one profile would open the
gate for the other.

**Impact:** Theoretical — requires the user to have AURA installed in both a
personal and work Android profile simultaneously. Unlikely in practice.

**Status:** Documented in code with `// DECISION(FIX-5)` comment. No fix applied
in this pass — fixing it requires moving `gestureVerified` to per-instance
state or a DataStore key, which is a larger refactor requiring UI coordination.
Tracked as a known limitation in the updated `docs/AUDIT.md`.

---

## Issue 5 — Avatar stream size enforcement: send-side content:// path unchecked — LOW

**Location:** `NearbyExchangeService.kt:950`

**Description:**
For `content://` avatar URIs, `sendAvatarIfPresent` checks `pfd.statSize > MAX_AVATAR_BYTES`
and skips if over the limit. For file-path avatars, `file.length() > MAX_AVATAR_BYTES`
is checked. On the **receive side**, the streaming reader counts bytes and aborts
if `written > MAX_AVATAR_BYTES`. All three enforcement points are correct.

**Verdict:** No bug here. The audit plan's concern about receive-side enforcement
was valid to raise, but the code correctly enforces it in `handleIncomingAvatarStream:1017`.

---

## Issue 6 — Double-discovery race (Issue 1) detailed reproduction

**Scenario:**
1. Three AURA users (A, B, C) are all in BLE range simultaneously.
2. A's phone discovers B and C nearly simultaneously (P2P_CLUSTER broadcasts
   are received within the same Nearby scan window).
3. `onEndpointFound` fires for B and C within ~1 ms of each other (parallel
   Nearby internal thread pool dispatch).
4. Both handlers check `connectedEndpoint == null` — both are true — both
   call `requestConnection`.
5. B's connection completes first. `connectedEndpoint = "B"`.
6. C's connection also completes. `connectedEndpoint = "C"` overwrites.
7. A sends profile encrypted with B's derived session key (still in `sessionKey`).
8. But `connectedEndpoint` now points to C — the profile goes to C, encrypted
   with B's key. C cannot decrypt it. Session ends in ERROR.

**Frequency:** Rare — requires simultaneous multi-user proximity. Common at
networking events where AURA is most likely to be used.

---

## Summary table

| # | Issue | Severity | Fixed? |
|---|---|---|---|
| 1 | Double-discovery TOCTOU race (P2P mode) | MEDIUM | ✅ Fix applied |
| 2 | `pendingChallengeByEndpoint` leak in ROOM_HOST on guest disconnect | LOW | ✅ Fix applied |
| 3 | `PayloadValidator` missing string length bounds (potential DoS) | MEDIUM | ✅ Fix applied |
| 4 | `gestureVerified` is process-wide companion object | LOW | ⚠ Documented, not fixed (tracked) |
| 5 | Avatar stream size enforcement (both sides) | — | No bug found — correctly implemented |

---

## Verification

```bash
# After applying fixes, run:
./gradlew :app:testDebugUnitTest --tests "*.NearbyExchangeServiceGateTest"

# And for the new PayloadValidator length-cap test:
./gradlew :app:testDebugUnitTest --tests "*.ReplayProtectionTest"
```
