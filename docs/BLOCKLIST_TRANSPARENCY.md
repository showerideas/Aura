# AURA Blocklist Transparency Log

## Overview

Phase 8.4 introduces a distributed transparency log for the AURA remote blocklist.
Users can opt in to reporting abusive peers and receiving community-sourced warnings.

## Privacy principles

- **Opt-in only**: No data is submitted without explicit user action ("Submit this peer as abusive")
- **Opt-in for warnings**: Users must enable the "Show community warnings" toggle in Settings
- **No tracking**: Reports contain only the SHA-256 of the peer's identity public key
- **Verifiable**: All entries are published in a Merkle tree for independent audit

## Protocol

### Submit a report (POST /v1/report)
```json
{"hash": "<sha256-hex-of-identity-public-key>"}
```
Response: HTTP 200/204 on success

### Fetch Bloom filter (GET /v1/bloom)
Returns raw Bloom filter bytes. Used for fast local lookups (no individual hash queries).
- 3 hash functions
- False positive rate: ~1% at 10,000 entries

### Verify Merkle proof (GET /v1/proof/{hash})
Returns a serialized Merkle inclusion proof for audit purposes.
Each proof node: 32 bytes sibling hash + 1 byte side (0=left, 1=right)

## Client implementation

See `TransparencyLogClient.kt`:
- `submitHash(identityKeyHash)` — opt-in reporting
- `fetchBloomFilter()` — download for local use
- `verifyMerkleProof(hash, proof, root)` — audit verification
- `isInBloomFilter(hash, bloomBytes)` — O(k) local check

## WorkManager refresh

`BlocklistRefreshWorker` fetches the Bloom filter weekly and stores it in
`EncryptedSharedPreferences`. The worker is enqueued on app start if the
"Show community warnings" toggle is enabled.

## Exchange flow warning

When `isInBloomFilter(peer.identityKeyHash, cachedBloom)` returns true,
`ExchangeFragment` shows a `⚠️ This peer has been reported by other AURA users`
warning banner before completing the exchange.
