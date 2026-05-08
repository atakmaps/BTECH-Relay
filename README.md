# UV-PRO — Open-Source BTECH Radio ↔ ATAK Bridge Plugin

A free, open-source ATAK plugin that connects UV-PRO radios to the Android Team Awareness Kit (ATAK) over Bluetooth. Team members with radios can share positions, chat, and situational awareness data entirely off-grid — no cell service or internet required.

## Features

| Feature | Status | Description |
|---------|--------|-------------|
| **Position Sharing (PLI)** | ✅ Working | Your ATAK position is beaconed over radio at a configurable interval. Incoming positions appear as contacts on the map. |
| **Smart Beaconing** | ✅ Working | Dynamically adjusts beacon rate based on speed and heading change. Fast/turning = frequent beacons; slow/straight = less frequent. All six parameters configurable in Settings → Manage Smart Beacon Settings. |
| **Ping Reply** | ✅ Working | Automatically replies to incoming pings with your current GPS position. Toggle on/off in Settings. |
| **Bluetooth Scan & Connect** | ✅ Working | Instant picker showing previously-connected radios with live green/gray availability dots. Auto-connects to last used radio on ATAK startup. |
| **Radio Connection Status Overlay** | ✅ Working | Persistent icon in the lower-right ATAK map corner showing connection state. Green box = connected; dark box = disconnected. |
| **GeoChat over RF (contact-centric)** | ✅ Working | Chat to radio peers using ATAK's native Contacts/GeoChat UI (plugin contacts route via RF transport). |
| **GeoChat delivery receipts (checkmarks)** | ✅ Working | ATAK's native single-checkmark (delivered) and double-checkmark (read) appear on the sender's chat window. |
| **Retry on no ACK + delivery failure alert** | ✅ Working | If no delivered ACK within the configured interval, message is retransmitted up to max retries. If all retries exhausted, a persistent alert appears. Retry interval and max retries adjustable in Settings. |
| **Contact-targeted CoT over RF** | ✅ Working | Any CoT item sendable to a contact in ATAK — waypoints, routes, casevac/9-line, drawings, markers — is intercepted, compressed, and relayed over RF. |
| **SA Relay (opt-in)** | ✅ Working | Network-to-radio bridge: broadcasts received SA over RF to radio-only users. Configurable in Settings. |
| **AES-256 Encryption** | ✅ Working | Optional shared-secret AES-256-GCM for all radio traffic. All nodes must use the same secret. |
| **Contact Tracking** | ✅ Working | Radios in range tracked as contacts with callsign, last-seen time, and position. |
| **Map Repeater Load/Tune (KML)** | ✅ Working | Tap a repeater placemark from imported KML, then use **Load Selected Repeater** to program channel 1 and tune the radio (TX/RX + CTCSS/DCS). |
| **Bluetooth Auto-Reconnect** | ✅ Working | Three-strategy SPP connection with exponential backoff reconnect (up to 5 attempts). |
| **Send Ping** | ✅ Working | Lightweight keepalive — lets other nodes know you're active even without GPS. |
## How It Works

```
┌─────────────────────────────────────┐
│           ATAK Application          │
│  ┌───────────────────────────────┐  │
│  │      UV-PRO Plugin        │  │
│  │                                │  │
│  │  Bluetooth ─► KISS TNC ─►    │  │
│  │  AX.25 frames ─► Packet      │  │
│  │  Router ─► CoT / Chat /      │  │
│  │  GPS / Encryption             │  │
│  └───────────────────────────────┘  │
└──────────────┬──────────────────────┘
               │ Bluetooth SPP (Data)
               ▼
┌─────────────────────────────────────┐
│       BTECH Radio (KISS TNC)        │
└──────────────┬──────────────────────┘
               │ RF (VHF/UHF)
               ▼
┌─────────────────────────────────────┐
│     Other Radios + EUDs in Range    │
└─────────────────────────────────────┘
```

The plugin talks to the radio over Bluetooth SPP using the KISS TNC protocol. Data is encapsulated in AX.25 frames with a compact binary format. On the ATAK side, incoming packets become CoT events, map contacts, and chat messages; outgoing ATAK data is serialized, optionally encrypted, and transmitted over radio.

## Requirements

### To Use the Plugin
- **ATAK-CIV 5.5.1** (or compatible version) installed on your Android device
- **UV-PRO** radio (UV-PRO, GMRS-PRO, or UV-50X series with KISS TNC support)
- **Bluetooth** pairing between the Android device and radio

### To Build from Source
- **JDK 17** — [Eclipse Temurin](https://adoptium.net/) recommended. Other JDK 17 distributions work too.
- **Android SDK** with API level 35 — install via [Android Studio](https://developer.android.com/studio) or the [command-line tools](https://developer.android.com/studio#command-line-tools-only)
- **ATAK-CIV 5.5.1 SDK** — available from the [atak-civ GitHub repo](https://github.com/TAK-Product-Center/atak-civ)
- **Git** — to clone the repo

> **Note:** You do _not_ need to install Gradle. The included Gradle wrapper (`gradlew` / `gradlew.bat`) downloads the correct version automatically.

## Quick Install (Pre-built APK)

If you just want to install the plugin without building it:

1. Download the latest APK from the [Releases](../../releases) page.
2. Transfer it to your Android device.
3. Install with: `adb install -r UVPro-*.apk`
4. Open ATAK → Menu → Tools → **UV-PRO**.

APK filenames look like `ATAK-Plugin-UVPro-*-civ-release.apk` (or `civ-debug` for debug builds).

### Upgrading from a debug or self-signed build

If you previously installed a **debug** or **self-signed** (non-TPC) build, Android will block the upgrade with an "App not installed" or "incompatible" error because the signing certificate changed. This is a one-time issue — all TPC-signed releases share the same certificate going forward.

**Fix (your ATAK data is safe):**

> Uninstalling the plugin APK does **not** affect the `/atak` directory, your maps, contacts, tracks, or any other ATAK data. That data belongs to the ATAK application, not this plugin.

```bash
# 1. Remove the old plugin (ATAK data is untouched)
adb uninstall com.uvpro.plugin

# 2. Install the new TPC-signed APK
adb install ATAK-Plugin-UVPro-*-tpc-*-civ-release.apk
```

After reinstalling, open ATAK → Menu → Tools → **UV-PRO** and reconnect your radio. All previous settings are stored in ATAK's shared preferences and will be restored automatically.

## GitHub releases and signing

- **Third-party (TPC) signing:** The APK that is fully aligned with **stock ATAK-CIV** and the usual install rules is the one built and signed on the **TAK Product Center third-party pipeline** (takrepo). It may show the standard indicator that the plugin was signed with the third-party service. No extra code is required in this repo for that — trust comes from the **pipeline signature**, not a flag in Java.
- **GitHub Releases:** Each [release](https://github.com/atakmaps/UV-PRO/releases) can attach the **same civ-release APK** produced for that version (ideally the TPC output). You can also build `assembleCivRelease` yourself (see below); for **public distribution**, prefer the **TPC-signed** binary when you have it.
- **Local `assembleCivRelease`:** ProGuard/R8 needs an **ATAK apply-mapping** file. This repo sets `atak.proguard.mapping` automatically: if you place the real `proguard-civ-release-mapping.txt` from a TPC/takrepo build in `app/libs/atak-civ/`, that is used; otherwise a **placeholder empty mapping** (`tools/empty-atak-applymapping.txt`) is used so the build completes. A build with the placeholder is fine for **CI smoke tests**; for **field use**, prefer a release built with the **official ATAK mapping** and/or the **TPC APK**.
- The `android` block in `app/build.gradle` sets `bundle { storeArchive { enable = false } }` as required by **atak-takdev** `takdevLint` for release signing.

## Building from Source

### 1. Download the ATAK-CIV SDK

Go to the [atak-civ GitHub repo](https://github.com/TAK-Product-Center/atak-civ), download the **ATAK-CIV 5.5.1 SDK**, and extract these files into `app/libs/atak-civ/`:

```
app/libs/atak-civ/
├── main.jar
├── atak-gradle-takdev.jar
├── android_keystore
└── proguard-release-keep.txt
```

> The SDK zip contains more files — you only need these four.

### 2. Configure `gradle.properties`

The repository includes a committed `gradle.properties` with shared flags. If Gradle does not pick up **JDK 17** automatically, add a line to that file (or copy from the template and merge):

```bash
# optional if JAVA_HOME is not JDK 17:
# cp gradle.properties.example gradle.properties   # only if you need a fresh file
```

Open `gradle.properties` and set `org.gradle.java.home` to your JDK 17 if needed:

| OS | Typical JDK 17 Path |
|----|---------------------|
| **Windows** | `C:\\Program Files\\Eclipse Adoptium\\jdk-17.x.x-hotspot` |
| **macOS** | `/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home` |
| **Linux** | `/usr/lib/jvm/temurin-17-jdk-amd64` |

> **Tip:** If your system `JAVA_HOME` already points to JDK 17, you can delete the `org.gradle.java.home` line entirely.

### 3. Build the APK

```bash
# Clone the repo
git clone https://github.com/atakmaps/UV-PRO.git
cd UV-PRO

# Linux/macOS
./gradlew assembleCivDebug

# Windows (Command Prompt or PowerShell)
gradlew.bat assembleCivDebug
```

The APK will be at:
```
app/build/outputs/apk/civ/debug/ATAK-Plugin-UVPro-*.apk
```

### 4. Install

```bash
adb install -r app/build/outputs/apk/civ/debug/ATAK-Plugin-UVPro-*.apk
```

Then open ATAK → Menu → Tools → **UV-PRO**.

### 5. Release (minified) build — `assembleCivRelease`

For a **R8/ProGuard** release build (smaller, obfuscated) matching the TPC `civRelease` variant:

```bash
./gradlew :app:assembleCivRelease
# Windows: gradlew.bat :app:assembleCivRelease
```

Output:

```
app/build/outputs/apk/civ/release/ATAK-Plugin-UVPro-*-civ-release.apk
```

Use the **official ProGuard apply-mapping** from the ATAK/takrepo pipeline when you need a **production-equivalent** binary (see [GitHub releases and signing](#github-releases-and-signing) above).

### Troubleshooting

| Problem | Fix |
|---------|-----|
| `Android Gradle plugin requires Java 17` | Your `gradle.properties` is missing or `org.gradle.java.home` points to the wrong JDK. See step 2. |
| `Could not find main.jar` | The ATAK SDK files aren't in `app/libs/atak-civ/`. See step 1. |
| `AAPT: error: resource not found` | Run `./gradlew clean` and rebuild. |
| Build succeeds but plugin doesn't appear in ATAK | Make sure you're running **ATAK-CIV 5.5.1** — the plugin is compiled against this specific version. |

## Usage

1. **Pair your radio** with your Android device via Bluetooth settings.
2. Open the **UV-PRO** plugin in ATAK.
3. Tap **Scan** to find your radio, then tap it to connect.
4. The status dot turns green when connected.

### Plugin Controls

| Control | What It Does |
|---------|-------------|
| **AES-256-GCM switch** | Enable encryption (enter the shared secret first) |
| **Send Beacon** | Immediately broadcast your current position |
| **Send Ping** | Send a lightweight keepalive with your callsign |
| **Load Selected Repeater** | Program/tune selected KML repeater into radio channel 1 (includes TX/RX and CTCSS/DCS tone) |
| **Settings** | Configure beacon interval, SA Relay, and other plugin options (team color is controlled by ATAK core settings) |

### Repeater workflow (KML)

1. Import repeater KML into ATAK.
2. Tap a repeater placemark on the map (must contain TX/RX and tone metadata).
3. Open **UV-PRO** tool and confirm **Selected Repeater** is populated.
4. Tap **Load Selected Repeater** to write/tune the radio.

Current implementation writes/tunes **channel 1** by design for deterministic testing. Future updates may add channel selection.

### Contact-centric routing (important)

Radio peers are represented as **native ATAK Contacts** (UIDs look like `ANDROID-<CALLSIGN>`). Use the ATAK Contacts UI to:

- open GeoChat with a radio contact (messages route over RF via the plugin).

Waypoints, routes, casevac/9-line, drawings, and other CoT items can all be sent to a radio contact using the native ATAK "Send to Contact" UI. The plugin intercepts the outbound CoT, compresses it, fragments it if needed, and transmits it over RF.

### SA Relay

Enable **SA Relay** in Settings to automatically broadcast received network SA (team positions, waypoints, routes) over RF to all radio users on frequency. This is designed for a single designated relay node — **do not enable unless instructed by your team leader.** A per-contact 30-second throttle prevents channel flooding.

For deeper implementation details and a full “new agent” handoff (logic trees, key files, known ATAK gotchas), see `HANDOFF.md`.

### Encryption

When enabled, all outgoing packets are encrypted with AES-256-CBC using a key derived from your passphrase (PBKDF2). **All radios in your group must use the same passphrase.** If a packet fails to decrypt on the receiving end, it is silently dropped.

## Project Structure

```
app/src/main/java/com/uvpro/plugin/
├── UVProLifecycle.java       # Plugin entry point
├── UVProTool.java            # Tool registration
├── UVProMapComponent.java    # Core component — wires everything together
├── UVProDropDownReceiver.java # UI panel
├── bluetooth/
│   └── BtConnectionManager.java  # Bluetooth SPP + KISS TNC connection
├── kiss/
│   ├── KissConstants.java        # KISS protocol constants
│   ├── KissFrameEncoder.java     # Encode AX.25 → KISS frames
│   └── KissFrameDecoder.java     # Decode KISS frames → AX.25
├── ax25/
│   ├── Ax25Frame.java            # AX.25 frame builder/parser
│   └── AprsParser.java           # APRS position parser
├── protocol/
│   ├── UVProPacket.java      # Binary packet format
│   ├── PacketRouter.java         # Routes incoming packets to subsystems
│   └── PacketFragmenter.java     # Fragment/reassemble large packets
├── cot/
│   ├── CotBridge.java            # CoT ↔ radio relay
│   └── CotBuilder.java           # Build CoT events from radio data
├── chat/
│   └── ChatBridge.java           # GeoChat ↔ radio relay
├── crypto/
│   └── EncryptionManager.java    # AES-256-GCM + PBKDF2 (envelope v3)
├── contacts/
│   ├── ContactTracker.java       # Track radios in range
│   └── RadioContact.java         # Contact data model
├── voice/
│   └── (legacy PTT scaffolding; not shipped as a feature in this fork)
└── ui/
    └── SettingsFragment.java     # Preference constants and helpers
```

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for build instructions and guidelines.

## License

MIT + Commons Clause — free to use, modify, and distribute, but commercial sale rights are reserved. See [LICENSE](LICENSE).
