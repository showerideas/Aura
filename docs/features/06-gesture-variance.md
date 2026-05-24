# PR-06 — Gesture variance gate

> A user can record an essentially-flat hand pose and the cosine-similarity matcher would give a high score to any other near-zero embedding — because all flat 42-float vectors collapse toward the same direction. PR-06 rejects low-variance feature vectors at **record** *and* **match** time.

---

## How it scores

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
    R[42-float landmark<br/>embedding] --> V["variance σ² = mean((xᵢ - x̄)²)"]
    V --> T{"σ² ≥ τ_min?"}
    T -- no --> X[Reject:<br/>"too flat — try a bigger motion"]
    T -- yes --> A[Accept]
```

`τ_min` is a small positive float, picked empirically so that a deliberate wrist-flick passes and a still phone fails.

---

## Implementation

- `GestureAuthManager.computeVariance(vector: FloatArray): Float` — `internal` for unit-testability.
- Used both in `savePattern()` (refuses to store) and in `match()` (refuses to compare).
- Threshold is a single `private const val MIN_VARIANCE` so it can be tuned in one place.

---

## Tests

`GestureMatchTest.kt` covers:

- A flat constant vector returns `variance ≈ 0`.
- A real-looking sin-wave vector returns a variance above the threshold.
- `match()` rejects both candidates and references when either is below `τ_min`.

---

## Connection to the strength meter

The same variance value powers the 5-bar **gesture-strength indicator** added in PR-11. See [`features/11-gesture-strength.md`](11-gesture-strength.md).
