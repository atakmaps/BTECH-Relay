# Contributing to UV-PRO

Thanks for your interest in contributing! UV-PRO is a free, open-source ATAK plugin
for off-grid team communication via UV-PRO radios.

## Building

### Prerequisites

- **JDK 17** (Eclipse Temurin recommended)
- **Android SDK** (API 35)
- **ATAK-CIV 5.5.1 SDK** — download from [TAK Product Center](https://tak.gov) (free registration)

### Setup

1. Clone the repo
2. Extract the ATAK SDK files into `app/libs/atak-civ/` (see README for details)
3. Build: `./gradlew assembleCivDebug`
4. Install the APK alongside ATAK-CIV on your device

### Merges, update-server TLS, and TPC zips

Branches that touch plugin startup or **`UVProMapComponent`** can break **HTTPS trust** for the plugin repo (`atakmaps.com`) without compile errors. **`UVProLifecycle`** must still run early trust setup. See **[AGENTS.md](AGENTS.md)** sections **“Merging branches (do not break update-server TLS)”** and **“TPC submission zips”** before merging or cutting a TPC submission.

### Merge gate (must pass before merge)

These are **blocking** requirements for feature merges. If any item fails or is unverified, **do not merge**.

- Keep trust chain intact in `UVProMapComponent`:
  `configureUpdateServerStatic` → `installUpdateServerTruststoreCompat` → `reloadCertificateManagerFromDatabase` → `registerUpdateServerCA` → deferred sync.
- Keep early trust hook in `UVProLifecycle` (`UVProMapComponent.applyUpdateServerTrustEarly` or equivalent early path).
- Keep trust assets and key material:
  - `app/src/main/assets/atakmaps-ca.p12`
  - `app/src/main/assets/isrg-root-x1.pem`
  - `app/src/main/res/values/strings.xml` key `uvpro_trust_bundle_p12_key`
- Do not rename update-server preference keys without ATAK 5.5.1 validation.
- Do not ship ProGuard/R8 changes that break reflection or strip `com.uvpro.plugin.**` keeps.
- Run and pass post-merge validation (below) on clean ATAK-CIV state.

#### Release-critical trust chain (do not simplify)

- In **`UVProMapComponent`**, keep the full chain intact:
  `configureUpdateServerStatic` → `installUpdateServerTruststoreCompat` → `reloadCertificateManagerFromDatabase` → `registerUpdateServerCA` → deferred sync.
- This flow also uses reflection on ATAK internals (`AtakCertificateDatabase`, `AtakCertificateDatabaseBase`, `CertificateManager`); treat merge conflicts in this area as high-risk.
- Do **not** move plugin startup so trust setup runs after ATAK already started repo HTTPS (`getCACerts ... 0 certs` / socket-closed race).
- In **`UVProLifecycle`**, keep `UVProMapComponent.applyUpdateServerTrustEarly` (or an equivalent early trust path).

#### Required trust assets and settings

- Required assets in APK:
  - `app/src/main/assets/atakmaps-ca.p12`
  - `app/src/main/assets/isrg-root-x1.pem`
- Required key material in `app/src/main/res/values/strings.xml`:
  - `uvpro_trust_bundle_p12_key` (Base64; decoded at runtime by design).
- `configureUpdateServerStatic` writes ATAK prefs such as `atakUpdateServerUrl`, `appMgmtUpdateServerUrl`, update-server toggles, and `updateServerCaLocation`; do not rename keys without testing on ATAK 5.5.1.

#### R8 / ProGuard + toolbar notes

- Keep root `app/proguard-gradle.txt` mapping flow (`-applymapping`) and do not strip `com.uvpro.plugin.**` keeps in a way that breaks reflection entry points.
- Toolbar icon and cert logic are separate; keep quick-launcher using `UVProTool.toolbarIcon` / `ic_uvpro_toolbar` unless you intentionally rework ATAK tint behavior.

#### TPC submission checklist

- Version: bump `ext.PLUGIN_VERSION` in root `build.gradle` only (`app/build.gradle` derives `versionCode`).
- Packaging: run `./tools/package-submission.sh` (uses `git archive` to produce `UV-PRO-<ver>-ATAK-5.5.1-source.zip`).
- Commit all files you want in the zip; uncommitted changes are excluded.
- Keep `gradle/takdev/atak-gradle-takdev.jar` in-repo for offline TPC builds.
- Run `./gradlew assembleCivRelease` first if you want local APK copied beside zip; ship the TPC-signed APK publicly.

#### Post-merge validation

- Run `./gradlew assembleCivRelease`.
- Install on clean ATAK-CIV (`pm clear com.atakmap.app.civ`).
- Verify logcat shows `UVPro`, `getCACerts for atakmaps.com`, and successful green sync in TAK Package Management.
- If any expected cert/sync signal is missing, treat as a merge blocker and fix before merge.

### Code Style

- Java 17 language level
- 4-space indentation
- Android `Log.d/i/w/e` with tag prefix `BTRelay`

## Areas Where Help Is Needed

- Bluetooth connection reliability across different Android versions and devices
- Voice PTT implementation and testing with real radio hardware
- UI improvements and dark mode support
- Testing with GMRS-PRO and UV-50X series radios
- Battery usage optimization for long-duration deployments

## Reporting Issues

Use GitHub Issues. Please include:

- Android version and device model
- ATAK-CIV version
- Radio model and firmware version
- Steps to reproduce
- Logcat output filtered by `BTRelay`

## License

MIT + Commons Clause — see [LICENSE](LICENSE)
