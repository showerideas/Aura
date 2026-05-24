# Localisation — coverage and roadmap

> **Prompt-11 update (2026-05-23):** This document now reflects actual string counts
> and per-locale coverage, replacing earlier estimates.

---

## Current coverage (as of v1.1 + Prompt-11 additions)

| Locale | File | Translated | Total in `values/` | Coverage |
|---|---|---|---|---|
| English (base) | `values/strings.xml` | 202 | 202 | 100% |
| German | `values-de/strings.xml` | 162 | 202 | 80% |
| Spanish | `values-es/strings.xml` | 162 | 202 | 80% |
| French | `values-fr/strings.xml` | 162 | 202 | 80% |
| Hindi | `values-hi/strings.xml` | 162 | 202 | 80% |
| Japanese | `values-ja/strings.xml` | 162 | 202 | 80% |
| Korean | `values-ko/strings.xml` | 162 | 202 | 80% |
| Chinese (Simplified) | `values-zh-rCN/strings.xml` | 162 | 202 | 80% |

**40 strings added since the v1.1 stub bundles were created** — the new
strings cover: volume-button warning banner, gesture-entropy disclaimer,
SAS verification prompt, and settings improvements. These are not yet
translated in the locale bundles (Android falls back to English).

---

## Lint suppression status

`MissingTranslation` lint check is disabled in `app/build.gradle.kts` via:

```kotlin
lint {
    disable += setOf("MissingTranslation")
    ...
}
```

This is a deliberate coverage decision, not a lint bug. The 40 untranslated
strings all fall back gracefully to English. `ExtraTranslation` and
`InvalidTranslation` remain enabled to catch real mistakes.

**Tracking issue:** Re-enable `MissingTranslation` once all 7 locale bundles
reach 100% coverage. Targeted for v1.3.0.

---

## How translations work at runtime

Android resolves strings from the most-specific locale bundle available. A
device set to `de-AT` (Austrian German) will use `values-de/` where a string
exists, falling back to `values/` (English) for any key not present. The
user sees English copy for the 40 untranslated strings — the app does not
crash and layout does not break.

---

## How to add / update a translation

1. Copy the source string from `app/src/main/res/values/strings.xml`.
2. Paste into the appropriate `values-XX/strings.xml`.
3. Translate the *content*, leaving format specifiers (`%1$d`, `%1$s`) and
   Unicode escapes (`\u2019`) untouched.
4. Run `./gradlew lintDebug` — with `MissingTranslation` disabled, you'll only
   see `ExtraTranslation` (you added a key not in base) or `InvalidTranslation`.
5. Test by switching the device locale in Developer Options and re-launching.

---

## Priority strings for the next translation pass

The 40 untranslated strings include:

| String key | Surface | Why it matters |
|---|---|---|
| `settings_volume_wake_warning` | Settings | Shown prominently when BG activation is on |
| `settings_test_volume_press` | Settings | In-app test button |
| `settings_volume_test_*` | Settings | Test result messages |
| Gesture entropy disclaimer strings | Onboarding / gesture setup | User-visible FAR caveat |
| SAS verification prompt | Exchange flow | New first-meet security UX |

Completing these 40 strings would bring all 7 locales to 100% coverage.

---

## Tests

Translation is a content task, not a code task. The CI gate is:

```bash
./gradlew lintDebug
```

`ExtraTranslation` and `InvalidTranslation` are both enabled and fail CI.
`MissingTranslation` is suppressed until 100% coverage is reached.

To verify coverage counts:
```bash
grep -c '<string name=' app/src/main/res/values/strings.xml
grep -c '<string name=' app/src/main/res/values-de/strings.xml
# ... etc for each locale
```
