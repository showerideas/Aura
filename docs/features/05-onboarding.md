# PR-05 — First-launch onboarding

> Three swipeable cards on first launch explain (a) what AURA is, (b) how the gesture works, (c) that the data never leaves the device. After the last card the user lands on the Profile screen to set up their card.

---

## Flow

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
    L[App launch] --> R{OnboardingPreferences.completed?}
    R -- false --> O1[Card 1: What is AURA]
    O1 --> O2[Card 2: Gesture & biometric]
    O2 --> O3[Card 3: Privacy]
    O3 --> Done[mark completed = true]
    Done --> P[ProfileFragment]
    R -- true --> H[HomeFragment]
```

---

## Implementation

- **UI:** `OnboardingFragment` + `ViewPager2`, with a custom indicator drawable (`onboarding_dot_selector.xml`).
- **State:** `OnboardingPreferences` (DataStore `Preferences`), single `completed: Boolean` key.
- **Hand-off:** `MainActivity` checks the flag in `onCreate()` and chooses the start destination of the nav graph.

---

## Strings

`onboarding_card1_title`, `onboarding_card1_body`, … `onboarding_card3_body`, plus `onboarding_skip`, `onboarding_done`.

---

## Tests

Manual QA only — no automated path yet (Espresso would need the instrumentation CI job, see [`AUDIT.md`](../AUDIT.md)).
