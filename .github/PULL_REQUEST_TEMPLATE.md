<!--
Thanks for contributing to AURA! Please fill the sections below.
Read docs/CONTRIBUTING.md if this is your first PR.
-->

## What & why

<!-- One paragraph: what does this PR change, and why is the change needed? -->

## How

<!-- Bullet list of the implementation approach. Link to design notes or
     features/ docs if relevant. -->

## Linked issues / PRs

<!-- e.g. "Closes #42", "Follows up on #38" -->

## Screenshots / demos

<!-- For UI changes, paste before/after screenshots or a short GIF.
     Delete this section for code-only PRs. -->

## Test plan

- [ ] `./gradlew testDebugUnitTest` — green locally
- [ ] `./gradlew lintDebug` — green locally
- [ ] `./gradlew assembleRelease` — green locally
- [ ] Manual smoke test on a real device or emulator (which?)

## Checklist

- [ ] Branch name follows `type/short-slug` (e.g. `feat/qr-fallback`, `fix/race-cond`)
- [ ] Commit message follows the body-style used by [`docs/CONTRIBUTING.md`](../docs/CONTRIBUTING.md)
- [ ] Touched a feature with a dossier? Updated [`docs/features/`](../docs/features/) accordingly
- [ ] Touched a public claim? Updated [`docs/AUDIT.md`](../docs/AUDIT.md)
- [ ] No outbound network call added (AURA's core promise)
- [ ] No new permission added without a [permission rationale](../app/src/main/java/com/showerideas/aura/ui/PermissionRationaleBottomSheet.kt) entry
