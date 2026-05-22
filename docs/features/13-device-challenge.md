# PR-13 — Device-identity challenge

> Even with ECDH, a MITM that sits between two phones and relays packets could in principle swap profiles. PR-13 adds a long-lived, hardware-backed identity key on each device and a challenge–response that proves the peer is the same device on every encounter.

---

## Key lifecycle

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
    First[First app launch] --> Gen[generate EC256 keypair<br/>alias: aura_device_identity<br/>Android Keystore<br/>non-extractable]
    Gen --> Disk[(Keystore)]
    Each[Every exchange] --> Use[sign challenges with idPriv]
    Use --> Send[send idPub + signature]
```

Because the private key is created `setUserAuthenticationRequired(false)` *and* `setIsStrongBoxBacked(true)` (where available), it is bound to the device — uninstalling AURA destroys it permanently.

---

## Handshake

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
sequenceDiagram
    participant A
    participant B
    A->>B: MSG_TYPE_CHALLENGE ‖ idPubA ‖ nonce_a (32 random bytes)
    B->>B: sig_b = ECDSA(idPrivB, nonce_a ‖ idPubB)
    B->>A: MSG_TYPE_CHALLENGE_RESPONSE ‖ idPubB ‖ sig_b
    A->>A: verify(sig_b, nonce_a ‖ idPubB, idPubB) ✅
    Note over A,B: mirror direction next
    B->>A: MSG_TYPE_CHALLENGE ‖ idPubB ‖ nonce_b
    A->>A: sig_a = ECDSA(idPrivA, nonce_b ‖ idPubA)
    A->>B: MSG_TYPE_CHALLENGE_RESPONSE ‖ idPubA ‖ sig_a
    B->>B: verify(sig_a, nonce_b ‖ idPubA, idPubA) ✅
```

Either side that fails to verify drops the connection and surfaces "Aborted: impersonation" in the UI.

---

## What this gets us

- **MITM resistance.** A relay cannot forge `sig_b` without `idPrivB`.
- **Cross-session continuity.** The `idPub` fingerprint is stored on every received `Contact` row, so re-encountering the same person yields a *match* even after their display name changes. This also feeds the blocklist ([`features/14-blocklist.md`](14-blocklist.md)) and the replay window ([`features/15-replay-protection.md`](15-replay-protection.md)).

---

## File pointers

- `CryptoUtils.getOrCreateDeviceIdentityKeyPair()` — Keystore generation.
- `CryptoUtils.sign()` / `verify()` — ECDSA.
- `NearbyExchangeService.sendChallenge()`, `handleIncomingChallenge()`, `handleChallengeResponse()`.

---

## Tests

`ReplayProtectionTest.kt` exercises the signing/verifying path as a side-effect of its own scenarios.
