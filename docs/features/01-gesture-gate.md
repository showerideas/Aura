# PR-01 — Gesture-gate enforcement

> Before this PR, anybody who triggered AURA could exchange profiles immediately. PR-01 makes the gesture (or biometric) a hard gate that must pass *before* `NearbyExchangeService` is allowed to advertise.

---

## What the user sees

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
    A[Triple-press vol ▼ <br/>or tap Activate] --> B[Exchange screen]
    B --> C{Gesture<br/>set?}
    C -- no --> D[Modal:<br/>"unprotected — continue?"]
    C -- yes --> E[Hold + perform gesture]
    E --> F{Match?}
    F -- yes --> G[NearbyExchangeService.start]
    F -- no, retries left --> H["Didn't match. N attempt(s) left."]
    H --> E
    F -- no, retries=0 --> I["Too many attempts. Cancelled."]
    D -- continue --> G
    D -- cancel --> J[back to Home]
```

---

## Implementation pointers

- `ExchangeFragment` requests gesture verification through `GestureAuthManager.match()`.
- `NearbyExchangeService` exposes `markGestureVerified()` from its `companion object`; the service refuses to leave the `Idle` state until that flag is set within the last 60 s.
- The 3-strike counter lives in `ExchangeViewModel.failedAttempts`.

---

## Tests

- `app/src/test/.../NearbyExchangeServiceGateTest.kt` — the service cannot start without a verified gesture.
- `app/src/test/.../GestureMatchTest.kt` — match/no-match cases against synthetic feature vectors.

---

## Strings touched

`exchange_gesture_hint`, `exchange_gesture_confirmed`, `exchange_gesture_failed_retry`, `exchange_gesture_failed_max`, `exchange_unprotected_title`, `exchange_unprotected_message`.
