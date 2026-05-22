# Architecture

> AURA is a single-module Android app with a strict **UI → ViewModel → Repository → DAO** dependency flow, plus two long-lived foreground services for the hardware-coupled parts (volume-button listener and the Nearby Connections exchange).

---

## 1. Module / package map

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
    subgraph UI["🎨&nbsp;ui"]
        direction LR
        home["home"]:::ui
        profile["profile"]:::ui
        exchange["exchange"]:::ui
        contacts["contacts"]:::ui
        onboarding["onboarding"]:::ui
        qr["qr"]:::ui
        room["room"]:::ui
        settings["settings"]:::ui
        MA["Main<br/>Activity"]:::ui
        PRr["Permission<br/>Rationale"]:::ui
    end
    subgraph AUTH["🔐&nbsp;auth"]
        direction LR
        GAM["Gesture<br/>AuthMgr"]:::service
        BAH["Biometric<br/>Helper"]:::service
    end
    subgraph SVC["⚙️&nbsp;service"]
        direction LR
        VBLS["Volume<br/>Service"]:::service
        NES["Nearby<br/>Service"]:::service
    end
    subgraph DATA["💾&nbsp;data"]
        direction LR
        CR["Contact<br/>Repo"]:::data
        PRP["Profile<br/>Repo"]:::data
        BR["Blocklist<br/>Repo"]:::data
        AP["Auth<br/>Prefs"]:::data
        OP["Onboard<br/>Prefs"]:::data
        subgraph LOC["local"]
            direction LR
            AD["AppDb<br/>v2"]:::data
            CD["Contact<br/>Dao"]:::data
            PD["Profile<br/>Dao"]:::data
            BD["Blocked<br/>Dao"]:::data
            MIG["Migrations"]:::data
        end
    end
    subgraph MOD["📦&nbsp;model"]
        direction LR
        Mp["Profile"]:::muted
        Mc["Contact"]:::muted
        Mb["Blocked<br/>Endpoint"]:::muted
        Me["Exchange<br/>Session"]:::muted
        Mg["Gesture<br/>Pattern"]:::muted
    end
    subgraph UTILS["🔧&nbsp;utils"]
        direction LR
        CU["Crypto<br/>Utils"]:::crypto
        PV["Payload<br/>Validator"]:::crypto
        VU["VCard<br/>Utils"]:::crypto
        EU["Export<br/>Utils"]:::crypto
        AU["Avatar<br/>Utils"]:::crypto
        EX["Extensions"]:::crypto
    end
    subgraph DI["💉&nbsp;di"]
        DM["Database<br/>Module"]:::ok
    end

    UI --> AUTH
    UI --> DATA
    UI --> SVC
    SVC --> AUTH
    SVC --> DATA
    SVC --> UTILS
    DATA --> MOD
    DATA --> LOC
    LOC --> MOD
    AUTH --> UTILS
    DI --> DATA

    classDef ui fill:#8B5CF6,color:#FFFFFF,stroke:#5B21B6,stroke-width:2px
    classDef service fill:#0EA5E9,color:#FFFFFF,stroke:#075985,stroke-width:2px
    classDef data fill:#10B981,color:#FFFFFF,stroke:#065F46,stroke-width:2px
    classDef crypto fill:#EC4899,color:#FFFFFF,stroke:#9D174D,stroke-width:2px
    classDef muted fill:#64748B,color:#FFFFFF,stroke:#1E293B,stroke-width:2px
    classDef ok fill:#22C55E,color:#FFFFFF,stroke:#166534,stroke-width:2px
```

### Dependency-direction rules

1. **`ui` may depend on anything below it**, but never on another `ui/*` sub-package directly (use `Navigation`).
2. **`service` does not depend on `ui`** — it talks to `data` and emits events via Intents / `StateFlow`s exposed through repositories.
3. **`data/local` knows nothing about Android UI** and never imports `androidx.fragment` etc.
4. **`utils` is pure-Kotlin / JVM-testable** wherever possible. `CryptoUtils`, `VCardUtils`, `PayloadValidator` are exercised by `app/src/test` unit tests.
5. **`model` has zero outbound deps** — these are plain data classes / Room `@Entity`.

---

## 2. Runtime component diagram

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
    subgraph FG["⚙️&nbsp;Foreground&nbsp;services"]
        direction TB
        VBLS["Volume<br/>Service"]:::service
        NES["Nearby<br/>Service"]:::service
    end
    subgraph UIp["🎨&nbsp;UI&nbsp;process"]
        direction TB
        MA["Main<br/>Activity"]:::ui
        EF["Exchange<br/>Fragment"]:::ui
        HF["Home<br/>Fragment"]:::ui
    end
    subgraph OS["📱&nbsp;Android&nbsp;OS"]
        direction TB
        ME["Media<br/>Session"]:::muted
        NC["Nearby<br/>Conn API"]:::muted
        KS["Android<br/>Keystore"]:::muted
        SM["Sensor<br/>Manager"]:::muted
        BIO["Biometric<br/>Prompt"]:::muted
    end
    subgraph STO["💾&nbsp;On-device&nbsp;storage"]
        direction TB
        ROOM[("Room<br/>v2")]:::data
        ESP[("Encrypted<br/>Prefs")]:::data
        DSP[("Data<br/>Store")]:::data
    end

    ME -->|"key events"| VBLS
    VBLS -->|"ACTION_ACTIVATE"| MA
    MA --> EF
    HF --> NES
    EF -->|"verified gesture"| NES
    EF -->|"perform"| SM
    EF -->|"or biometric"| BIO
    NES <-->|"BLE / WiFi-P2P"| NC
    NES --> KS
    NES --> ROOM
    EF --> ESP
    MA --> DSP

    classDef service fill:#0EA5E9,color:#FFFFFF,stroke:#075985,stroke-width:2px
    classDef ui fill:#8B5CF6,color:#FFFFFF,stroke:#5B21B6,stroke-width:2px
    classDef muted fill:#64748B,color:#FFFFFF,stroke:#1E293B,stroke-width:2px
    classDef data fill:#10B981,color:#FFFFFF,stroke:#065F46,stroke-width:2px
```

### Why two services?

| Service | Why it must be foreground |
|---|---|
| `VolumeButtonListenerService` | Needs to receive `MediaSession` button events even when AURA is in the background. Without a foreground notification, Android 12+ will reap it within seconds. |
| `NearbyExchangeService` | Holds a BLE / Wi-Fi P2P connection during the exchange; Android requires a `foregroundServiceType="connectedDevice"` to keep BLE active. |

Both services are declared in [`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml) with the right `foregroundServiceType` and request `FOREGROUND_SERVICE_CONNECTED_DEVICE` on API 34+.

---

## 3. Class-level overview of the exchange service

`NearbyExchangeService` is the single largest class (~1 kLOC) and the security-critical hot path. Internally it is a small state machine plus a typed message protocol over the Nearby Connections `Payload` API.

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
stateDiagram-v2
    [*] --> Idle
    Idle --> Advertising: start()
    Idle --> AdvertisingHost: startRoomHost()
    Idle --> DiscoveringGuest: startRoomGuest()
    Advertising --> Connecting: onEndpointFound
    AdvertisingHost --> Connecting: onEndpointFound
    DiscoveringGuest --> Connecting: onEndpointFound
    Connecting --> KeyExchange: onConnectionResult(OK)
    KeyExchange --> Challenge: peer pub key received
    Challenge --> ChallengeVerify: response received
    ChallengeVerify --> ProfileExchange: signature valid
    ChallengeVerify --> Aborted: signature invalid<br/>(impersonation)
    ProfileExchange --> AvatarStream: profile saved
    ProfileExchange --> Completed: no avatar
    AvatarStream --> Completed: stream ingested
    Completed --> [*]
    Aborted --> [*]
    Connecting --> Aborted: timeout / disconnect
    KeyExchange --> Aborted: malformed
    AdvertisingHost --> AdvertisingHost: next guest
```

The wire format is one byte of `MSG_TYPE` followed by a body whose shape depends on the type:

| `MSG_TYPE` | Hex | Body |
|---|---|---|
| `PUBLIC_KEY` | `0x01` | SPKI-encoded ephemeral ECDH public key |
| `PROFILE` | `0x02` | `AES-GCM(profile JSON)` (IV ‖ ciphertext ‖ tag) |
| `AVATAR` | `0x03` | Base64(SPKI pub key) `\|` STREAM-payload-id |
| `CHALLENGE` | `0x04` | Base64(SPKI long-lived pub key) `\|` 32-byte nonce |
| `CHALLENGE_RESPONSE` | `0x05` | Base64(SPKI long-lived pub key) `\|` ECDSA signature |

See [`EXCHANGE_FLOW.md`](EXCHANGE_FLOW.md) for the full ordered walkthrough.

---

## 4. Navigation graph

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
    Splash(["app<br/>start"]):::user --> Onb{"First<br/>launch?"}:::gate
    Onb -- "yes" --> Onboard["Onboard<br/>Fragment"]:::ui
    Onb -- "no" --> Home["Home<br/>Fragment"]:::ui
    Onboard --> Home
    Home -->|"Activate"| Exchange["Exchange<br/>Fragment"]:::ui
    Home -->|"Edit"| Profile["Profile<br/>Fragment"]:::ui
    Home -->|"Contacts"| Contacts["Contacts<br/>Fragment"]:::ui
    Home -->|"QR"| QR["QR<br/>Fragment"]:::ui
    Home -->|"Room"| Room["Room<br/>Fragment"]:::ui
    Home -->|"Settings"| Settings["Settings<br/>Fragment"]:::ui
    Settings --> Blocked["Blocked<br/>Fragment"]:::ui
    Contacts -->|"tap"| Detail["Contact<br/>Detail"]:::ui

    classDef user fill:#6E56CF,color:#FFFFFF,stroke:#3D2C7A,stroke-width:2px
    classDef ui fill:#8B5CF6,color:#FFFFFF,stroke:#5B21B6,stroke-width:2px
    classDef gate fill:#F59E0B,color:#1F2937,stroke:#92400E,stroke-width:2px
```

The `nav_graph.xml` lives at [`app/src/main/res/navigation/nav_graph.xml`](../app/src/main/res/navigation/nav_graph.xml).

---

## 5. Dependency injection (Hilt)

A single `DatabaseModule` (`@InstallIn(SingletonComponent::class)`) provides:

- `AppDatabase` (Room) — built with `Migrations.MIGRATION_1_2` registered.
- Each DAO (`ContactDao`, `ProfileDao`, `BlockedEndpointDao`) is provided from the singleton database.
- `GestureAuthManager`, `BiometricAuthHelper`, and the three repositories (`ContactRepository`, `ProfileRepository`, `BlocklistRepository`) are constructor-`@Inject`ed.

ViewModels use `@HiltViewModel`. The `AuraApplication` class is annotated `@HiltAndroidApp`.

---

## 6. Build configuration in one glance

| Property | Value | Where |
|---|---|---|
| AGP | 8.4.0 | `gradle/libs.versions.toml` |
| Kotlin | 2.0.0 | `gradle/libs.versions.toml` |
| Compile / Target SDK | 35 | `app/build.gradle.kts` |
| Min SDK | 26 | `app/build.gradle.kts` |
| JVM target | 17 | `app/build.gradle.kts` |
| `applicationId` | `com.showerideas.aura` (`.debug` suffix on debug) | `app/build.gradle.kts` |
| `versionCode` / `versionName` | `1` / `1.0.0` | `app/build.gradle.kts` |
| `isMinifyEnabled` (release) | `true` | `app/build.gradle.kts` |
| ProGuard rules | `app/proguard-rules.pro` | linked in `release` block |
| Schema export dir | `app/schemas/` | annotation processor arg |
| Signing config | env-var driven (CI leaves blank → unsigned APK) | `app/build.gradle.kts` |

For the actual build invocation see [`BUILD.md`](BUILD.md).
