# Gesture Credential — Entropy Analysis (Prompt 3)

> **Verdict: ergonomic gate, not a biometric credential.**
> The 42-float hand-landmark embedding + cosine similarity ≥ 0.88 provides
> meaningful friction against casual misuse, but it does not provide
> per-person uniqueness guarantees. Any person who can produce the same
> broad gesture class as the enrolled user will likely be accepted.

---

## 1. How the credential works

`CameraHandEmbedder` extracts 21 hand landmarks from MediaPipe
`GestureRecognizer` and normalises them:
- Wrist centred at origin.
- All coordinates scaled by wrist-to-middle-MCP distance.
- Output: 42 floats (21 × x,y).

`GestureAuthManager.match()` accepts any candidate whose cosine similarity
to the stored template is ≥ 0.88.

---

## 2. Why inter-person similarity is high for the same gesture

The normalisation deliberately **removes** hand size and position as
distinguishing factors. After wrist-centring and MCP-scaling:
- Two people making the same "thumbs up" produce vectors that differ mainly
  by their finger-length ratios.
- Finger-length ratios across adult hands vary by roughly 10–20%.
- After normalisation, the resulting cosine similarity between two different
  people's same-gesture embeddings is typically **0.88–0.97** — right at or
  above the acceptance threshold.

This is the fundamental design tension: the normalisation that makes the same
person's gesture consistent across sessions also makes different people's same
gesture consistent with each other.

---

## 3. Synthetic entropy measurements

`HandEmbeddingEntropyTest.kt` models the three key scenarios with synthetic
embeddings whose noise levels match reported MediaPipe output distributions.
Run with: `./gradlew :app:testDebugUnitTest --tests "*.HandEmbeddingEntropyTest"`

| Scenario | Expected cosine similarity | FAR at threshold 0.88 |
|---|---|---|
| **Intra-person, same gesture** (enrollment vs. same person's repetition) | 0.93–0.99 | >95% (correct accept) |
| **Inter-person, same gesture** (enrollment vs. different person, same gesture class) | 0.85–0.97 | **30–70%** (false accept) |
| **Inter-person, different gesture** (enrollment vs. completely different gesture) | 0.3–0.75 | <5% (correct reject) |

The inter-person same-gesture FAR of **30–70%** is the critical number:
it means that, in a room where everyone is making a "thumbs up", roughly
every other person will be accepted as the enrolled user.

---

## 4. What this means in practice

For AURA's use case (face-to-face exchange, both parties present):

- The gesture gate **prevents accidental exchanges** — you must deliberately
  perform a specific hand shape, which prevents pocket-dial style events.
- The gesture gate **does not prevent a determined nearby person** from
  performing the same gesture class and triggering an exchange.
- The **real security credential** is the long-lived Android Keystore ECDSA
  identity key — not the gesture. The gesture is a UX gate before the crypto
  layer, not a replacement for it.

---

## 5. Measured false-accept rate (note on synthetic vs. real data)

The numbers above are synthetic model estimates. To measure real FAR:

```
1. Enrol 10 different subjects performing "thumbs up" (or another gesture).
2. For each enrolled template, test the other 9 subjects' same-gesture attempts.
3. Count how many score ≥ 0.88.
4. FAR = (accepted cross-person pairs) / (total cross-person attempts).
```

A Pixel 8 running MediaPipe 0.10.14 with this measurement protocol is expected
to produce FAR in the 40–65% range for same-gesture cross-person pairs.
This needs real-device verification — add to the manual QA checklist.

---

## 6. Recommended framing

Replace any marketing copy that implies the gesture is a biometric credential
with language like:

> "Your recorded hand shape is an ergonomic gate that prevents accidental
> exchanges. For strong per-person authentication, AURA's long-lived device
> identity key (stored in the Android Keystore) is the actual security anchor."

---

## 7. Proposed second factor (SAS PIN)

For first-meet exchanges where device identity keys have not been seen before
(TOFU), neither the gesture nor the crypto alone prevents impersonation.
Prompt-8 adds a **Short Authentication String** (SAS) derived from both
parties' ECDH public keys. Both phones display the same 6-digit number; the
user confirms visually. This closes the first-meet MITM gap that the gesture
alone cannot close.

See `docs/SECURITY.md` (updated in Prompt-8) and `SasVerifier.kt`.
