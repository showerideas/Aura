# Contributing to AURA

> This file describes the conventions the repo already follows.

---

## 1. Branch & commit style

Branch names follow `<type>/<short-slug>` (lowercase, hyphens), e.g. `fix/nfc-curve-validation`, `feat/room-mode`.

Commit messages use the imperative mood:

```
fix: reject oversized challenge bytes
feat: add room-mode host session
chore: bump Gradle to 8.4
```

Types: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`.

---

## 2. Required CI checks

A change cannot be merged until **all three** Gradle gates pass on GitHub Actions:

| Gate | Command | Why |
|---|---|---|
| Unit tests | `./gradlew testDebugUnitTest` | Catches gesture-matching / crypto / payload regressions cheaply. |
| Lint | `./gradlew lintDebug` | Stops new warnings; pre-existing ones are silenced by the baseline. |
| Release assembly | `./gradlew assembleRelease` | Validates ProGuard rules and R8 against every transitive dep. CI leaves the signing env vars blank → unsigned APK. |

Instrumentation tests (`connectedAndroidTest`) are **not** in CI yet (tracked in [`AUDIT.md`](AUDIT.md)). If your change touches Room or anything sensor-bound, run them locally and paste a screenshot of the result in the PR body.

---

## 3. Style

- **Kotlin first.** No Java unless you have a *very* good reason.
- **Two-space indentation** in XML, four-space in Kotlin.
- **No top-level `var`** — use `val` and immutable data classes.
- **`Timber` for logs**, gated on `BuildConfig.ENABLE_LOGGING`.
- **No new dependencies without a one-line justification.** Each transitive blob hurts R8 and increases supply-chain risk.
- **No reflection on user data.** Gson is fine; arbitrary Java reflection on `Profile` / `Contact` is not.

---

## 4. Where new code goes

| You are adding… | Put it in… |
|---|---|
| A new screen | `ui/<feature>/` — Fragment + ViewModel + a `nav_graph.xml` destination |
| A new entity | `model/` + DAO in `data/local/` + repository in `data/` + a `MigrationTest` row |
| A new crypto primitive | `utils/CryptoUtils.kt` — add a JVM unit test in `app/src/test/` |
| A new permission | `AndroidManifest.xml` + a bottom-sheet rationale in `PermissionRationaleBottomSheet` |
| A new string | `app/src/main/res/values/strings.xml` (English source of truth) + a TODO for translators |

---

## 5. Security-sensitive changes

If your change touches:

- `CryptoUtils.kt`
- `NearbyExchangeService.kt`
- `AndroidManifest.xml` permissions block
- anything in `app/src/main/res/xml/` (network / backup / data-extraction rules)

…request review from at least two maintainers. See [`SECURITY.md`](SECURITY.md).

---

## 6. Reporting bugs / vulnerabilities

- **Functional bug** → open an issue, include device model + Android version + redacted logcat.
- **Security issue** → **do not** open a public issue. Open a private [GitHub Security Advisory](https://github.com/showerideas/Aura/security/advisories/new).

---

## 7. Code of conduct

Be kind. Assume good faith.
