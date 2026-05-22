# PR-15 — Replay-attack protection

> Even with ECDH per session, a recorded Nearby Connections payload could in principle be replayed against a victim by an attacker who can simulate a fresh Nearby session. PR-15 closes that window with a monotonically-advancing counter scoped to each peer.

---

## Counter window

```mermaid
%%{init: {'theme':'base','themeVariables':{
  'fontFamily':'ui-monospace, SFMono-Regular, Menlo, Monaco, monospace',
  'fontSize':'14px',
  'primaryColor':'#0EA5E9',
  'primaryTextColor':'#0F172A',
  'primaryBorderColor':'#075985',
  'lineColor':'#475569',
  'secondaryColor':'#F1F5F9',
  'tertiaryColor':'#FAFAF9',
  'clusterBkg':'#F8FAFC',
  'clusterBorder':'#CBD5E1'
},'flowchart':{'curve':'basis','nodeSpacing':40,'rankSpacing':50,'padding':12},'sequence':{'actorMargin':50,'boxMargin':10,'noteMargin':10,'messageMargin':35}}}%%
flowchart LR
    Recv[Encrypted profile<br/>arrives] --> Dec[AES-GCM decrypt]
    Dec --> Read[Read 8-byte counter<br/>from envelope]
    Read --> Cmp{counter > stored(idPub) ?}
    Cmp -- yes --> Save[Save contact &<br/>UPDATE stored(idPub) = counter]
    Cmp -- no --> Abort[Reject + log "replay"]
```

The counter is stored per peer identified by `idPub` fingerprint, so it survives across exchanges with the *same* person (each new exchange must use a larger counter) but is independent across different peers.

---

## Envelope shape

The encrypted profile body, before AES-GCM, is:

```
| version(1B) | counter(8B BE) | timestamp(8B BE) | profile JSON UTF-8 ... |
```

- `version` = `0x01` today; allows future format upgrades.
- `counter` is monotonic per device-pair — bumped from `lastSeenCounter` for the *sender's view of the peer*.
- `timestamp` is informational only (it does NOT gate replay; clock skew between two phones is unbounded).

---

## File pointers

- Encode / decode: `CryptoUtils.encryptProfileEnvelope()` and `decryptProfileEnvelope()`.
- Per-peer state: `Contact.lastSeenCounter` (column added in DB v2 alongside the blocklist).
- Service path: `NearbyExchangeService.handleIncomingProfile()` does the comparison and the row update inside a single Room transaction.

---

## Tests

`app/src/test/.../ReplayProtectionTest.kt`:

- Counter `5` then `6` accepted.
- Counter `6` then `5` rejected.
- Counter `5` then `5` rejected (must be strictly greater).
- AES-GCM tag failure also surfaces as a rejection.
- A different peer's counter is independent.
