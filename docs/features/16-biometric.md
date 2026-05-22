# PR-16 — Biometric unlock as an alternative gate

> The gesture is the default unlock, but many users would rather use the fingerprint scanner or face unlock they already trust. PR-16 lets either method pass the exchange gate.

---

## Decision tree

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
    Start[Activate exchange] --> Pref{AuthPreferences.method}
    Pref -- gesture --> G[Perform gesture]
    Pref -- biometric --> B[BiometricPrompt]
    G --> Ok[Continue]
    B --> Ok
    Pref -- gesture, no gesture set --> Modal[Unprotected modal]
    Pref -- biometric, none enrolled --> Fallback[Fall back to gesture]
```

The user picks gesture vs biometric in **Settings → Authentication method**. Biometric defaults to *off* — gesture is what AURA was designed for, biometric is a courtesy.

---

## Implementation

- `BiometricAuthHelper.authenticate(activity, onSuccess, onError)` wraps `androidx.biometric.BiometricPrompt`.
- Strong, weak, and device-credential authenticators are all allowed (`BIOMETRIC_STRONG or DEVICE_CREDENTIAL`) so users without enrolled biometrics can fall back to PIN/pattern.
- The "verified" signal is the same `NearbyExchangeService.markGestureVerified()` call — the service does not care which mechanism cleared the gate.

---

## Why not strictly STRONG?

`BIOMETRIC_STRONG` excludes face unlock on many older devices. Forcing it would lock those users out of the alternative entirely. The privacy/security trade-off here is local-only — the biometric is just a gate to *our* exchange flow, not a key in the keystore-backed crypto.

---

## File pointers

- `app/src/main/java/com/showerideas/aura/auth/BiometricAuthHelper.kt`
- Preference: `AuthPreferences.method` (DataStore)
- Settings UI: `ui/settings/SettingsFragment.kt`

---

## Tests

Wired but **not** in CI yet — the BiometricPrompt cannot be driven from a JVM unit test, and the instrumentation emulator job is still a follow-up (see [`AUDIT.md`](../AUDIT.md)). Manual QA on Pixel + Samsung devices.
