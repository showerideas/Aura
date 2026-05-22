# PR-22 — Release config + ProGuard + CI

> PR-22 (and its hot-fix string PR-22a … PR-22i) made AURA *shippable*: env-driven signing, ProGuard rules that survive R8 + resource shrinking, a network-security config, manifest hardening flags, the Play-Store-listing draft, the privacy-policy draft, and a GitHub Actions pipeline that builds + tests + lints + assembles a release APK on every push.

---

## What landed across PR-22a … PR-22i

| Slice | Subject |
|---|---|
| **22a** | Release signing block + ProGuard rules + network security + manifest flags |
| **22b** | Privacy policy + Play Store listing docs |
| **22c** | `.github/workflows/ci.yml` |
| **22d** | Committed Gradle wrapper |
| **22e** | AAPT errors in launcher icons |
| **22f** | Kotlin compile errors blocking CI |
| **22g** | Unit-test failures |
| **22h** | Lint errors |
| **22i** | `assembleRelease` crash when `KEYSTORE_PATH` is the empty string (CI) |

After 22i, **CI run [#26297620334](https://github.com/showerideas/Aura/actions/runs/26297620334) ✅** is the first green build that exits with a release APK artifact attached.

---

## CI pipeline diagram

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
    Push[Push / PR] --> Checkout
    Checkout --> JDK[setup-java@v4<br/>JDK 17 temurin]
    JDK --> Gradle[setup-gradle@v3]
    Gradle --> Unit[testDebugUnitTest]
    Unit -- ✅ --> Lint[lintDebug]
    Lint -- ✅ --> Assemble[assembleRelease<br/>KEYSTORE_* blank]
    Assemble -- ✅ --> Upload[upload-artifact:<br/>aura-release-unsigned.apk]
    Unit -- ❌ --> Fail[upload test reports]
    Lint -- ❌ --> Fail
    Assemble -- ❌ --> Fail
```

The full workflow is at [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml).

---

## Release-signing contract

`app/build.gradle.kts` reads four env vars:

```
KEYSTORE_PATH            ← absolute path to .keystore file
KEYSTORE_STORE_PASSWORD
KEYSTORE_KEY_ALIAS
KEYSTORE_KEY_PASSWORD
```

The PR-22i fix matters because `System.getenv()` returns `""` (not `null`) for env vars that are set-but-empty — which is exactly what CI does. The `?:` operator alone won't help. The script now does:

```kotlin
val storePath = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
if (storePath != null) {
    storeFile = file(storePath)
}
```

→ blank env var ⇒ no `storeFile` ⇒ no signing config wired into `release` ⇒ Gradle produces an unsigned APK without crashing.

---

## Manifest hardening flags

From PR-22a:

- `android:allowBackup="false"` — no Auto-Backup of Room / EncryptedSharedPreferences.
- `android:dataExtractionRules="@xml/data_extraction_rules"` — explicit per-file exclusion list for Device-to-Device transfer.
- `android:fullBackupContent="@xml/backup_rules"` — legacy backup-rules equivalent.
- `android:hasFragileUserData="true"` — Play Console warning on uninstall.
- `android:networkSecurityConfig="@xml/network_security_config"` — cleartext denied everywhere.

---

## Release artifacts

- **Unsigned APK** is uploaded by the CI as `aura-release-unsigned` (14-day retention).
- The **first tagged GitHub Release** (`v1.0.0`) attaches this exact APK as a downloadable asset, with a sha-256 published in the release notes.
