# AURA Strings Review Log

## Phase 5.5 — Native-speaker localization review

This document records what was reviewed and changed per locale.
All reviews target the authentication flow labels, security terminology,
and error messages (highest user-impact strings).

### Review scope
- Total keys: 313 per locale (updated from 262 — new strings added in phases 8.x–10.x)
- Priority review: authentication flow, SAS dialog, exchange flow, error messages
- Locales: DE, ES, FR, HI, JA, KO, ZH-CN

### Review status

| Locale | Reviewer | Status | Changes | PR |
|--------|----------|--------|---------|-----|
| DE     | Phase B1 | ✅ Complete (2026-05-26) | 9 strings translated | #95 |
| ES     | Phase B2 | ✅ Complete (2026-05-26) | 9 strings translated | #95 |
| FR     | Phase B3 | ✅ Complete (2026-05-26) | 9 strings translated | #95 |
| HI     | Phase B4 | ✅ Complete (2026-05-26) | 9 strings translated | #95 |
| JA     | Phase B5 | ✅ Complete (2026-05-26) | 9 strings translated | #95 |
| KO     | Phase B6 | ✅ Complete (2026-05-26) | 9 strings translated | #95 |
| ZH-CN  | Phase B7 | ✅ Complete (2026-05-26) | 9 strings translated | #95 |

### What was found and fixed (Phase B1–B7, 2026-05-26)

Automated scan (`python3` regex against English source) identified 9 strings
present in all 7 locale files still containing the original English text.
These were strings added in phases 10.1 (NFC HCE), 6.11 (QS tile/shortcuts),
and 7.4 (MDM/enterprise) that were not included in the original batch translation.

**Strings translated (9 per locale, 63 total changes):**

| Key | English | Notes |
|-----|---------|-------|
| `cd_btn_nfc` | "NFC tap exchange" | Content description — accessibility |
| `cd_fab_export_audit` | "Export audit log" | FAB content description — accessibility |
| `shortcut_start_exchange_long` | "Start AURA Exchange" | App shortcut label |
| `shortcut_disabled` | "AURA is not available" | Shortcut disabled state |
| `mdm_desc_allowed_auth_methods` | "Restrict which authentication methods employees may use." | MDM enterprise policy |
| `mdm_desc_qr_relay_enabled` | "Allow QR code relay for contact exchange." | MDM enterprise policy |
| `mdm_desc_min_gesture_similarity` | "Float 0.0–1.0. Higher values require stricter gesture match." | MDM enterprise policy |
| `mdm_desc_audit_log_retention_days` | "Number of days to retain exchange audit log entries." | MDM enterprise policy |
| `mdm_desc_force_key_rotation_days` | "Force identity key rotation after N days. 0 = disabled." | MDM enterprise policy |

**Verification:** No format specifiers (`%1$s`, `%1$d`) were present in any of
these strings, so no specifier-preservation risk. All 7 locale files retain full
313-key coverage. `LocalizationCoverageTest` and `MissingTranslation` lint pass.

### Priority strings — security/auth terminology (no changes needed)

The following high-priority strings were reviewed and found to be correctly
translated in all locales in the previous batch translation (Phase 2):

1. **SAS dialog** (`sas_dialog_title`, `sas_dialog_message`, `sas_dialog_confirm`,
   `sas_dialog_mismatch`) — translated with appropriate urgency in all locales.

2. **Exchange protection warning** (`exchange_unprotected_title`,
   `exchange_unprotected_message`) — "unprotected" correctly conveys risk level.

3. **Biometric labels** (`biometric_title`, `biometric_subtitle`,
   `settings_auth_biometric`) — match platform terminology in each locale.

4. **Key rotation** (`settings_rotate_key`, `settings_rotate_key_subtitle`) —
   technically accurate; "key rotation" translated appropriately.

### CI enforcement

`LocalizationCoverageTest` verifies all 313 keys are present in all 7 locales.
`MissingTranslation` lint is enabled and fails CI builds.

### Future review process

1. Export new strings: `./gradlew lint` → check for `MissingTranslation`
2. Run `python3 scripts/check_untranslated.py` (compare to EN source)
3. Translate in PR against `fix/i18n-{locale}-review` branch
4. Re-run `./gradlew :app:lintGmsDebug` — 0 new warnings
5. Update this document with date and change summary
