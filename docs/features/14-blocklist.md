# PR-14 — Endpoint blocklist (DB schema v2)

> Users can mark a contact as "block" — that peer's identity-key fingerprint is then auto-rejected on any future discovery. This is the user-facing reason the DB schema bumped to v2.

---

## Data shape

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
erDiagram
    BlockedEndpoint {
        string idPubFingerprint PK "SHA-256(idPub) hex"
        long blockedAt
        string displayName "snapshot at block-time"
    }
```

The fingerprint is the same SHA-256 we stamp on every received `Contact` (see [`features/13-device-challenge.md`](13-device-challenge.md)), so the join is on a stable identifier even if the peer changes their display name.

---

## Enforcement point

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
    EP[onEndpointFound] --> Q{idPub<br/>fingerprint<br/>blocked?}
    Q -- yes --> Reject[disconnect endpoint<br/>no UI noise]
    Q -- no --> Continue[normal flow → challenge]
```

Crucially, the blocked endpoint is dropped **after** the challenge so we *know* it's really the same device — endpoint IDs from Nearby Connections rotate per session and can't be trusted directly.

---

## UI

- **Block:** "Block this person" from `ContactDetailBottomSheet`. Toast: "Won't appear in future exchanges."
- **Unblock:** `BlockedDevicesFragment` lists every blocked entry with the snapshotted name and the date; tap to unblock.

---

## File pointers

- Entity: `app/src/main/java/com/showerideas/aura/model/BlockedEndpoint.kt`
- DAO: `app/src/main/java/com/showerideas/aura/data/local/BlockedEndpointDao.kt`
- Repository: `app/src/main/java/com/showerideas/aura/data/BlocklistRepository.kt`
- UI: `ui/settings/BlockedDevicesFragment.kt` + `BlockedDevicesAdapter.kt`
- Migration: see [`features/04-room-migrations.md`](04-room-migrations.md)

---

## Tests

`app/src/androidTest/.../BlockedEndpointDaoTest.kt`:

- Insert + `isBlocked()` round-trip.
- Delete by primary key.
- Flow emits updates after insert / delete.
