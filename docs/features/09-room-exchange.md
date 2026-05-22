# PR-09 — Room mode (1 host : N guests)

> The default exchange is 1-to-1. For meetups, weddings, and small conferences PR-09 adds a "Room" mode where one device is the **host** and any number of guests can stream their cards to it in sequence — perfect for an organiser collecting attendees.

---

## Topology

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
flowchart TB
    H((Host))
    G1((Guest 1))
    G2((Guest 2))
    G3((Guest 3))
    G1 -- "P2P_STAR" --> H
    G2 -- "P2P_STAR" --> H
    G3 -- "P2P_STAR" --> H
    H -- "secure session per guest" --> H
```

The host calls `NearbyExchangeService.startRoomHost(context)`, which starts the service in `P2P_STAR` strategy and accepts every incoming guest until the host taps **Stop**.

Each guest still does its **own** ECDH and challenge with the host, so the host gets *N* independent secure sessions, not a broadcast. The host's profile is delivered to each guest as they connect.

---

## UI

- Host: `RoomExchangeFragment` with a live counter ("3 guests onboarded") and a Stop button.
- Guest: same fragment, switched to guest mode → searches for a host name, then walks the same gesture/biometric gate before sending its profile.

---

## File pointers

- `app/src/main/java/com/showerideas/aura/ui/room/RoomExchangeFragment.kt`
- `app/src/main/java/com/showerideas/aura/ui/room/RoomExchangeViewModel.kt`
- `NearbyExchangeService.startRoomHost()` / `startRoomGuest()` companion functions.

---

## Tests

Manual QA. A full instrumentation harness for room mode would need at least three connected devices, which the current emulator-runner plans do not cover.
