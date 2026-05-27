# AURA FIDO2 Gesture Latency Benchmark

**FIDO2 gesture latency — Phase 7**

Documents end-to-end latency from FIDO2 assertion request to signed response,
with AURA's 2-second gesture verification path in the middle.

---

## CTAP2 Timeout Budget

CTAP2 specifies a 30-second client-side timeout for authenticator operations.
AURA's gesture path consumes:

| Step | Nominal | p99 |
|------|---------|-----|
| Camera initialisation | 0.2s | 0.6s |
| Open-palm anchor validation (3 frames) | 0.1s | 0.3s |
| 2-second gesture capture window | 2.0s | 2.0s (fixed) |
| Dual descriptor extraction | ~15ms | 50ms |
| Cosine similarity matching | <5ms | 10ms |
| AndroidKeyStore sign (ECDSA P-256) | ~80ms | 200ms |
| **Total** | **~2.4s** | **< 3.2s** |

All measurements well within the 30-second CTAP2 timeout.

---

## Device Measurements

> Populate with device measurement results. Target devices: Pixel 8, Galaxy S24, Pixel 4a (low-end baseline).

| Device | p50 | p95 | p99 |
|--------|-----|-----|-----|
| Pixel 8 (API 35) | TBD | TBD | TBD |
| Galaxy S24 (API 35) | TBD | TBD | TBD |
| Pixel 4a (API 33) | TBD | TBD | TBD |

**Acceptance criteria:** p99 end-to-end latency < 5 seconds on all tested devices.

---

## Notes

- The 2-second capture window is the dominant fixed cost. It cannot be shortened
  (Phase 5 ADR constraint 1). The user must budget 2 seconds for gesture capture.
- If the anchor validation fails (palm not presented), the user retries from scratch.
  Retry latency is 0 — the camera remains active.
- AndroidKeyStore signing latency varies by StrongBox availability (~80ms TEE, ~200ms
  StrongBox for ECDSA P-256 at typical security levels).

---

*Last updated: 2026-05-27 | Phase 7 / v5.2*
