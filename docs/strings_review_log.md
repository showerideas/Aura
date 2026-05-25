# AURA Strings Review Log

## Phase 5.5 — Native-speaker localization review

This document records what was reviewed and changed per locale.
All reviews target the authentication flow labels, security terminology,
and error messages (highest user-impact strings).

### Review scope
- Total keys: 262 per locale
- Priority review: authentication flow, SAS dialog, exchange flow, error messages
- Locales: DE, ES, FR, HI, JA, KO, ZH-CN

### Review status

| Locale | Reviewer | Status | Changes | PR |
|--------|----------|--------|---------|-----|
| DE     | Pending  | ⏳ Review needed | — | — |
| ES     | Pending  | ⏳ Review needed | — | — |
| FR     | Pending  | ⏳ Review needed | — | — |
| HI     | Pending  | ⏳ Review needed | — | — |
| JA     | Pending  | ⏳ Review needed | — | — |
| KO     | Pending  | ⏳ Review needed | — | — |
| ZH-CN  | Pending  | ⏳ Review needed | — | — |

### Key strings requiring expert review

1. **Authentication terms** — "gesture authentication", "short authentication string",
   "identity verification" must be technically accurate in each locale.

2. **Security warnings** — "Your exchange is not protected" (unprotected exchange dialog),
   "Key mismatch — possible interception" (SAS mismatch) — must convey urgency accurately.

3. **Biometric labels** — "Biometric authentication", "fingerprint", "face recognition" —
   must match OS-standard terminology in each locale.

### Process per locale

1. Export strings needing review: `./gradlew lint` → check for `MissingTranslation`
2. Commission review (native speaker with technical background preferred)
3. Implement fixes in a single PR per locale: `fix/i18n-{locale}-review`
4. Re-run `./gradlew :app:lintGmsDebug` — 0 new `MissingTranslation` warnings
5. Update this document with reviewer name, date, and change summary

### CI enforcement

`LocalizationCoverageTest` verifies all 262 keys are present in all 7 locales.
`MissingTranslation` lint is enabled and fails CI builds.
