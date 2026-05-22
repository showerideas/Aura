# PR-02 — ECDH race-condition fix

> Two devices that both call `requestConnection` at the same instant used to deadlock: each side would `acceptConnection` before its own ECDH key had been generated, so the first `MSG_TYPE_PUBLIC_KEY` arrived into a `null` private-key slot.

---

## The race

```mermaid
sequenceDiagram
    participant A
    participant B
    A->>A: onConnectionInitiated
    B->>B: onConnectionInitiated
    par symmetric
        A->>B: acceptConnection
    and
        B->>A: acceptConnection
    end
    Note over A,B: Both onConnectionResult(OK) fire<br/>~at the same time
    par dangerous
        A->>B: MSG_PUBLIC_KEY (A's eph pub)
    and
        B->>A: MSG_PUBLIC_KEY (B's eph pub)
    end
    Note over A,B: Pre-fix: if A hadn't finished<br/>generateEphemeralECDHKeyPair() yet,<br/>B's key arrives with no private key to pair it with → NPE.
```

---

## The fix

`NearbyExchangeService` now **generates the ephemeral keypair synchronously in `onConnectionInitiated`**, *before* calling `acceptConnection`. The first thing sent on the channel afterwards is our public key, and the field that receives the peer's public key is guarded by a `lateinit` plus a small `Mutex` so concurrent calls from the Nearby callback thread can't observe a half-initialised state.

```kotlin
override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
    sessionState.ourEphemeralKeyPair = CryptoUtils.generateEphemeralECDHKeyPair() // ← moved here
    connectionsClient.acceptConnection(endpointId, payloadCallback)
}
```

---

## Tests

`app/src/test/.../NearbyExchangeServiceGateTest.kt` covers the symmetric-init case by driving both callbacks on separate threads and asserting that the resulting AES key matches on both sides.
