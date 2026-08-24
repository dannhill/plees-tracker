# Plees AutoSleep

[![Release](https://img.shields.io/github/v/release/dannhill/plees-tracker?include_prereleases&style=flat-square&color=blue)](https://github.com/dannhill/plees-tracker/releases)
[![Obtainium Compatible](https://img.shields.io/badge/Obtainium-Compatible-3DDC84?style=flat-square&logo=android&logoColor=white)](OBTAINIUM.md)
[![License: BSD-3-Clause](https://img.shields.io/badge/License-BSD_3--Clause-orange.svg?style=flat-square)](LICENSE)
[![No Internet Permission](https://img.shields.io/badge/Internet-Zero_Permissions-success?style=flat-square)](#-privacy--offline-guarantee)

**Plees AutoSleep** is an intelligent, privacy-first automatic sleep tracker for Android. It extends the minimalist open-source sleep tracker [plees-tracker](https://github.com/vmiklos/plees-tracker) by automatically detecting sleep sessions from device unlock events—without background listeners, without battery drain, and without internet access.

---

## 🌟 Key Features

* **🔄 Automatic Sleep Detection**: Detects your overnight sleep intervals retrospectively by analyzing device unlock timestamps (`KEYGUARD_HIDDEN`).
* **🔋 Zero Nighttime Battery Drain**: Does not run continuous background listeners, audio/accelerometer sensors, or wake-locks during the night. Detections are evaluated on-demand and via periodic background workers.
* **🛡️ 100% Private & Offline**: Strictly local execution. Contains **no `INTERNET` permission**, no telemetry, and no third-party analytics. Raw application usage is never persisted.
* **🤝 Overlap & Conflict Suppression**: Automatically recognizes manual sleep entries and suppresses duplicate suggestions if coverage is $\ge 50\%$.
* **📱 Side-by-Side Installation**: Distributed under package ID `hu.vmiklos.plees_tracker.autosleep`, allowing parallel coexistence with upstream Plees Tracker.
* **🔄 Seamless Obtainium Updates**: Automated CI/CD releases signed with a persistent release key for frictionless one-click updates.

---

## 📥 Installation

### Option 1: Obtainium (Recommended)
Install and receive automatic updates directly from GitHub Releases:
* 📲 **[Click to Add to Obtainium](obtainium://add/https://github.com/dannhill/plees-tracker)**
* Or read our complete [Obtainium Setup Guide (OBTAINIUM.md)](OBTAINIUM.md).

### Option 2: Direct APK Download
Download the latest `Plees-AutoSleep-foss-release.apk` or `plees-autosleep-v*.apk` from the [GitHub Releases Page](https://github.com/dannhill/plees-tracker/releases/latest).

---

## 🧠 How AutoSleep Works

Plees AutoSleep uses a deterministic gap analysis engine (`AutoSleepDetector`):

1. **Event Sourcing**: Queries system unlock events over a rolling 72-hour window.
2. **Anchor Window**: Evaluates candidate gaps between 3 hours and 16 hours that intersect the local 00:00–06:00 overnight anchor on the wake date.
3. **Save Modes**:
   - **Suggest Mode (Default)**: Displays a clean `MaterialAlertDialog` upon opening the app, letting you **Save**, **Discard**, or review **Later**.
   - **Auto-Save Mode**: Automatically saves detected sleep sessions directly to your history.
4. **Health Connect & Backup Integration**: Saved sessions immediately trigger optional Health Connect synchronization and automated backups.

---

## 🔒 Privacy & Offline Guarantee

Plees AutoSleep is engineered from the ground up for strict privacy:
- The Android manifest omits the `android.permission.INTERNET` permission entirely.
- The `PACKAGE_USAGE_STATS` permission is used strictly to read system `KEYGUARD_HIDDEN` events.
- No app usage data, notification content, or user interactions are ever accessed, logged, or stored.

---

## 🛠️ Building From Source

### Prerequisites
* **Java**: OpenJDK 21 (Temurin or Zulu)
* **Android SDK**: `compileSdk 37`, `targetSdk 36`, `minSdk 24`
* **Build System**: Gradle 9.x (wrapper provided)

### Build Commands

```bash
# Clone the repository
git clone https://github.com/dannhill/plees-tracker.git
cd plees-tracker

# Run all unit tests (343+ tests)
./gradlew testFossDebugUnitTest

# Build debug APK (FOSS flavor with AutoSleep ID)
./gradlew assembleFossDebug -Pautosleep=true
# Output: app/build/outputs/apk/foss/debug/app-foss-debug.apk

# Build release APK (FOSS flavor with custom versioning)
./gradlew assembleFossRelease -Pautosleep=true -PautosleepVersionCode=100000001 -PautosleepVersionName=1.0.1
# Output: app/build/outputs/apk/foss/release/app-foss-release.apk
```

---

## 🔐 Repository Secrets & CI/CD Release Setup

Automated release builds on GitHub Actions require the following repository secrets (`Settings → Secrets and variables → Actions`):

| Secret Name | Description | Example / Format |
|---|---|---|
| `AUTOSLEEP_KEYSTORE_B64` | Base64-encoded `.jks` release keystore file | `base64 -w 0 plees_keystore.jks` |
| `SIGNING_STORE_PASSWORD` | Password protecting the keystore | Plain text password |
| `SIGNING_KEY_ALIAS` | Key alias in the keystore | `plees-autosleep` |
| `SIGNING_KEY_PASSWORD` | Password protecting the private key | Plain text password |

### Generating a Release Keystore
```bash
keytool -genkeypair -v \
  -keystore plees_keystore.jks \
  -alias plees-autosleep \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass "<STORE_PASSWORD>" \
  -keypass "<KEY_PASSWORD>" \
  -dname "CN=Plees AutoSleep, O=dannhill, C=US"

# Encode to Base64 for GitHub Secret
base64 -w 0 plees_keystore.jks > keystore_b64.txt
```

---

## 🌿 Repository Branching Structure

* `master`: Clean mirror tracking upstream [vmiklos/plees-tracker](https://github.com/vmiklos/plees-tracker).
* `feature/autosleep`: Core feature implementation under `hu.vmiklos.plees_tracker` for clean upstream Pull Requests.
* `main`: Distribution branch with custom package name `hu.vmiklos.plees_tracker.autosleep`, app name "Plees AutoSleep", and GitHub Actions release workflows.

---

## 📄 License & Credits

* Original **Plees Tracker** is copyright © Miklos Vajna and contributors, licensed under the **BSD 3-Clause License**.
* **Plees AutoSleep** enhancements are licensed under the same **BSD 3-Clause License** ([LICENSE](LICENSE)).