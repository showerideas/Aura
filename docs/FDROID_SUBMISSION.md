# F-Droid Submission Guide — AURA

*Phase I2 | Last updated: 2026-05-26*

---

## Overview

AURA is distributed through F-Droid using the `foss` product flavor (no Google
Play Services). The build metadata lives at `fdroid/com.showerideas.aura.yml`.

---

## Pre-submission checklist

1. **Run the reproducible build test**

   ```bash
   bash fdroid/reproducible_build_test.sh
   ```

   Both builds must produce identical SHA-256 checksums. Copy the hash.

2. **Update `fdroid/com.showerideas.aura.yml`**

   - Add a new `Builds:` entry for the release version.
   - Paste the SHA-256 from step 1 as the `Hash:` field.
   - Update `CurrentVersion:` and `CurrentVersionCode:`.
   - Commit and tag: `git tag v<version>` (must match `commit:` in the YAML).

3. **Verify no proprietary dependencies**

   ```bash
   ./gradlew :app:dependencyInsight --configuration fossReleaseRuntimeClasspath \
     --dependency com.google 2>/dev/null | grep "com.google" || echo "No GMS deps"
   ```

   Expected: no `com.google.android.gms` artifacts in the `foss` flavor.

4. **Lint the metadata with fdroidserver**

   ```bash
   pip install fdroidserver
   fdroid lint fdroid/com.showerideas.aura.yml
   ```

   Zero warnings/errors required.

---

## Submitting to F-Droid data repository

F-Droid maintains application metadata in
[`gitlab.com/fdroid/fdroiddata`](https://gitlab.com/fdroid/fdroiddata).

### First-time submission

1. Fork `https://gitlab.com/fdroid/fdroiddata` on GitLab.

2. Copy `fdroid/com.showerideas.aura.yml` to the fork:
   ```bash
   cp fdroid/com.showerideas.aura.yml \
     /path/to/fdroiddata/metadata/com.showerideas.aura.yml
   ```

3. Open a merge request titled:
   > **New app: AURA - Gesture-biometric proximity contact exchange**

4. The F-Droid review bot will run automated checks. Address any `fdroid lint`
   failures reported in the MR.

5. A human reviewer will inspect the source code for compliance with the
   [F-Droid inclusion policy](https://f-droid.org/docs/Inclusion_Policy/) —
   no proprietary dependencies, no trackers, no anti-features.

### Subsequent releases

F-Droid's `AutoUpdateMode: Version v%v` + `UpdateCheckMode: Tags` handles
automatic version bumps once the app is accepted:

1. Push a tag `v<version>` to GitHub: `git tag v2.1.0 && git push origin v2.1.0`
2. F-Droid bot detects the new tag within ~24 hours.
3. F-Droid build server builds the `foss` flavor and publishes the APK.

No manual fdroiddata MR needed for routine releases after acceptance.

---

## Anti-features audit

F-Droid flags apps with certain "anti-features". AURA has **none**:

| Anti-feature | Present | Notes |
|---|---|---|
| `NonFreeNet` | No | All network comms use user-controlled relay or P2P |
| `NonFreeAdd` | No | MediaPipe model downloaded from GitHub Releases (MIT-licensed) |
| `NonFreeDep` | No | FOSS flavor has zero GMS/Firebase dependencies |
| `Tracking` | No | Zero analytics, no third-party SDKs |
| `Ads` | No | Ad-free |
| `UpstreamNonFree` | No | MIT license, full source on GitHub |
| `KnownVuln` | No | Security audit completed (see `docs/AUDIT.md`) |

---

## Verifying the published APK

After F-Droid builds and publishes:

```bash
# Download the published APK
curl -L -o aura-fdroid.apk \
  "https://f-droid.org/repo/com.showerideas.aura_<versionCode>.apk"

# Compare SHA-256 against the reproducible build test output
sha256sum aura-fdroid.apk
# Must match the hash in fdroid/com.showerideas.aura.yml
```

A match confirms the F-Droid build server produced the same binary as your
local reproducible build, end-to-end verifying the supply chain.

---

## Contacts / links

| Resource | URL |
|---|---|
| F-Droid data repository | https://gitlab.com/fdroid/fdroiddata |
| F-Droid forum (new app requests) | https://forum.f-droid.org/c/requests-for-packaging |
| F-Droid build metadata reference | https://f-droid.org/docs/Build_Metadata_Reference/ |
| F-Droid inclusion policy | https://f-droid.org/docs/Inclusion_Policy/ |
| AURA GitHub Issues | https://github.com/showerideas/Aura/issues |
