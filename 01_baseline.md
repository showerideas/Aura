# AURA — Baseline Assessment (Prompt 0)

> Repo: `showerideas/Aura` · Branch: `main` · Assessed: 2026-05-23
> Container: no JDK / Android SDK available — Gradle tasks cannot be executed
> directly in this environment. All findings below are from static source
> inspection. Reproduction commands are provided for local verification.

---

## Build environment gap

This container has no JDK, Android SDK, or Gradle wrapper binary resolution.
Run all verification commands on a machine with:
- JDK 17 (Temurin recommended)
- Android SDK API 35, build-tools 35.0.0, platform-tools
- `ANDROID_HOME` set

```bash
# Confirm the Gradle wrapper resolves
cd /path/to/Aura
./gradlew --version
```

---

## 1. downloadGestureModel task

### What the task does
`app/build.gradle.kts` registers a `downloadGestureModel` Gradle task that
fetches `gesture_recognizer.task` (~8 MB) from
`storage.googleapis.com/mediapipe-models/...` using `URL.openStream()` and
wires it as a `preBuild` dependency.

### Known problems (static analysis — no build required to confirm)

| Problem | Location |
|---|---|
| No connect / read timeout | `build.gradle.kts:227` — `url.openStream()` has no timeout; hangs forever on a slow network |
| No checksum verification | Model bytes are written to disk without any SHA-256 check; a MITM proxy can substitute a malicious model |
| No retry | Single-shot download; transient network errors fail the build |
| No CI cache | `.github/workflows/ci.yml` does not cache `gesture_recognizer.task`; every CI run re-downloads 8 MB |
| No ProGuard -keep for MediaPipe | `app/proguard-rules.pro` keeps Nearby, Hilt, Room, ZXing — **zero MediaPipe keeps**. Release R8 build will strip `com.google.mediapipe.**` and crash on first gesture |

### Reproduction (local)
```bash
./gradlew :app:downloadGestureModel
# Should succeed if network is available and GCS is reachable.

./gradlew assembleRelease
# Then: apkanalyzer classes list app/build/outputs/apk/release/*.apk | grep mediapipe
# Expected (current): empty — MediaPipe classes stripped by R8.
# Expected (after fix): classes present.
```

---

## 2. assembleDebug / assembleRelease

Cannot run in this container. Expected outcomes based on static analysis:

- **Debug**: builds successfully if `gesture_recognizer.task` is present in
  `app/src/main/assets/`. Model is excluded from git (`.gitignore`), so a
  fresh clone will fail with a `FileNotFoundException` at runtime when
  `CameraHandEmbedder.initRecognizer()` tries to load it.
- **Release**: builds successfully (R8 + resource shrinking configured
  correctly). However, the resulting APK will crash on first gesture attempt
  because MediaPipe classes are stripped (no `-keep` rules).

---

## 3. testDebugUnitTest — actual counts

Inspected all files in `app/src/test/` and `app/src/androidTest/`:

### Unit tests (7 files, 51 @Test methods)

| File | @Test count |
|---|---|
| `ContactsViewModelFilterTest` | 6 |
| `CryptoUtilsTest` | 11 |
| `GestureAuthTest` | 12 |
| `NearbyExchangeServiceGateTest` | 2 |
| `ProfileTest` | 5 |
| `ReplayProtectionTest` | 11 |
| `VCardUtilsTest` | 4 |
| **Total** | **51** |

### Instrumented tests (6 files, 20 @Test methods)

| File | @Test count |
|---|---|
| `BlockedEndpointDaoTest` | 5 |
| `ContactDaoTest` | 4 |
| `ExchangeFlowEspressoTest` | 1 |
| `KnownPeerDaoTest` | 3 |
| `MigrationTest` | 3 |
| `ProfileDaoTest` | 4 |
| **Total** | **20** |

**README claims "32 unit + 4 instrumentation tests" — reality is 51 unit + 20
instrumented.** The numbers are wrong in the safe direction (we have more),
but the exact count is still false.

```bash
# Reproduce locally:
./gradlew :app:testDebugUnitTest
# Look for: X tests executed, Y failed in the output.
```

---

## 4. lintDebug — expected findings

Cannot run in this container. Known issues from static analysis:

| Issue | Severity | Location |
|---|---|---|
| `MissingTranslation` disabled globally | WARN | `app/build.gradle.kts:51` — suppressed intentionally but without a linked tracking issue |
| Deprecated `MediaSession.setFlags()` API | WARN | `VolumeButtonListenerService.kt:109` — suppressed with `@Suppress("DEPRECATION")`; flags are still needed for API < 31 |
| No MediaPipe ProGuard keeps | not a lint issue — R8 concern | `proguard-rules.pro` — zero `com.google.mediapipe` keeps |

Translation status (actual, from file inspection):
- Base `values/strings.xml`: **197 string elements**
- All 7 locale files (`de`, `es`, `fr`, `hi`, `ja`, `ko`, `zh-rCN`): **162 strings each**
- Coverage: 162/197 = **82%** per locale — not 100%, hence MissingTranslation is suppressed

---

## 5. TODO / FIXME / XXX inventory

```bash
# Reproduce:
grep -rn "TODO\|FIXME\|XXX" app/src/main/ --include="*.kt"
```

Found (from static inspection):

| Comment | File | Line (approx) |
|---|---|---|
| `// DECISION(FIX-5): gesture verifies session start, not each peer — see git log` | `NearbyExchangeService.kt` | ~124 |
| `// FIX-6` through `// FIX-C` — past-tense fix annotations (not open TODOs) | `NearbyExchangeService.kt` | various |
| No open `TODO` or `FIXME` found in `app/src/main/` | — | — |

No open TODOs in main source. All `FIX-N` annotations are past-tense
documentation of already-applied fixes, not pending work.

---

## 6. What works (confirmed by static analysis)

| Component | Status |
|---|---|
| ECDH P-256 + AES-256-GCM crypto (`CryptoUtils.kt`) | Real and correct |
| ECDSA challenge / response (`CryptoUtils.signChallenge / verifyChallenge`) | Real |
| Room v2 schema + migrations (`Migrations.kt`, exported schemas) | Mature |
| Hilt DI graph | Fully wired |
| PayloadValidator replay / timestamp protection | Correct |
| 7-locale translations (162/197 strings per locale) | Shipped — partially complete |
| NearbyExchangeService per-endpoint ECDH in ROOM_HOST mode | Correct (`peerCtxByEndpoint`) |
| Avatar STREAM size enforcement on receive | Correct (streaming byte counter) |

## 7. What is broken or overstated (confirmed by static analysis)

| Issue | Severity |
|---|---|
| `docs/GESTURE_AUTH.md` describes accelerometer + DTW. Code is CameraX + MediaPipe + cosine similarity | Critical documentation lie |
| `README.md` tech stack: "Accelerometer + Dynamic Time Warping" | False |
| `docs/SECURITY.md` T6 references "DTW match threshold + variance check" | False |
| `docs/AUDIT.md` H3 references "accelerometer pipeline + DTW matcher" | False |
| MediaPipe classes have no ProGuard -keep rules — release APK crashes on gesture | Release-critical bug |
| `downloadGestureModel` has no timeout, checksum, or retry | Build reliability bug |
| README test count ("32 unit + 4 instrumentation") | Wrong — actual 51 + 20 |
| `gestureVerified` is a `companion object` field — process-wide, not per-instance | Known design limitation |
| `pendingChallengeByEndpoint` not cleaned up on guest disconnect in ROOM_HOST mode | Minor memory leak |
| `PayloadValidator` validates timestamps/nonces but not string lengths | Potential DoS vector |
| Volume button triple-press: STATE_PAUSED MediaSession trick unreliable on OEM devices | Undocumented limitation |
| `app/release/app-release.apk` committed to repo | Hygiene bug |

---

## Reproduction commands summary

```bash
cd /path/to/Aura

# 1. Download model
./gradlew :app:downloadGestureModel

# 2. Debug build
./gradlew :app:assembleDebug

# 3. Unit tests
./gradlew :app:testDebugUnitTest --tests "*"

# 4. Lint
./gradlew :app:lintDebug

# 5. Release build (to expose MediaPipe ProGuard issue)
./gradlew :app:assembleRelease
$ANDROID_HOME/tools/bin/apkanalyzer classes list \
  app/build/outputs/apk/release/app-release-unsigned.apk | grep mediapipe

# 6. Count test methods
grep -rc "@Test" app/src/test/
grep -rc "@Test" app/src/androidTest/

# 7. Check for DTW / accelerometer remnants in docs
grep -rni "dtw\|accelerometer\|variance\|resample" docs/
```
