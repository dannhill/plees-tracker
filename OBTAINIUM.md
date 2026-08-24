# Installing & Updating Plees AutoSleep via Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) allows you to install and receive automatic, direct-from-GitHub updates for **Plees AutoSleep** without third-party app stores or proprietary trackers.

---

## 🚀 Quick Setup (One-Click)

If you already have Obtainium installed on your Android device:

1. Tap this deep link on your Android device:  
   👉 **[Add Plees AutoSleep to Obtainium](obtainium://add/https://github.com/dannhill/plees-tracker)**
2. In Obtainium, verify the settings and tap **Add**.
3. Tap **Install** to download and install the latest release.

---

## 📋 Manual Setup Instructions

If you prefer to configure Obtainium manually:

### Step 1: Install Obtainium
If you don't have Obtainium yet, download and install it from the [official Obtainium repository](https://github.com/ImranR98/Obtainium/releases).

### Step 2: Add Repository in Obtainium
1. Open **Obtainium**.
2. Tap the **+ (Add App)** button.
3. In the **App Source URL** field, enter:
   ```text
   https://github.com/dannhill/plees-tracker
   ```

### Step 3: Configure Filter Settings (Recommended)
Expand **Configuration Options** / **Filter Settings**:

| Setting Field | Recommended Value | Notes |
|---|---|---|
| **Filter release titles/tags** | `^v.*` | Tracks standard version tags (e.g., `v1.0.1`, `v1.0.42`) |
| **Filter APKs by regular expression** | `(?i)plees-autosleep.*\.apk` | Matches `plees-autosleep-v1.0.1.apk` and `Plees-AutoSleep-foss-release.apk` |
| **Version extraction** | Standard | Extracts version directly from GitHub tag / APK metadata |
| **Include pre-releases** | Disabled | Keeps your installation on stable releases |
| **Fallback to older releases** | Enabled | Ensures Obtainium finds valid releases if latest tag is filtered |

4. Tap **Add** at the bottom right.
5. Tap **Install** to download and install **Plees AutoSleep**.

---

## ⚙️ Post-Installation Setup: Grant Usage Access

Automatic sleep detection requires Android **Usage Access** permission to analyze device unlock timestamps (`KEYGUARD_HIDDEN`) locally on your device:

1. Open **Plees AutoSleep**.
2. Tap **Settings** (gear icon) in the top-right toolbar.
3. Scroll to **Automatic sleep detection** and switch it **ON**.
4. When prompted, tap **Open Settings** (or navigate to *Android Settings → Apps → Special app access → Usage access*).
5. Locate **Plees AutoSleep** and enable **Permit usage access**.
6. Return to Plees AutoSleep — automatic sleep detection is now active!

> 🔒 **Privacy Guarantee**: Plees AutoSleep does not have internet access (`android.permission.INTERNET` is omitted). All calculations are performed entirely on your device; no app usage history is ever logged, stored, or transmitted.

---

## 🔄 Automatic Background Updates

To enable seamless background updates in Obtainium:
1. In Obtainium, open **Settings → Background Updates**.
2. Enable **Periodic Background Checks** (e.g., Every 6 Hours or Daily).
3. If running Android 12+, enable **Unattended Updates** so Obtainium can update Plees AutoSleep automatically without prompting.

---

## 🛠️ Local Build & Manual Sideloading

If you prefer building and sideloading the APK manually:

```bash
# Clone the repository
git clone https://github.com/dannhill/plees-tracker.git
cd plees-tracker

# Build debug APK with AutoSleep package ID
./gradlew assembleFossDebug -Pautosleep=true

# Or build release APK with custom versioning
./gradlew assembleFossRelease -Pautosleep=true -PautosleepVersionCode=100000001 -PautosleepVersionName=1.0.1

# Install to connected device
adb install -r app/build/outputs/apk/foss/release/app-foss-release.apk
```

---

## ❓ Troubleshooting & FAQ

### "App not installed: Signature mismatch" / "Package conflicts"
- **Cause**: Android enforces that all package updates share the same signing certificate. If you previously installed an upstream build or a local debug APK, signature conflict occurs.
- **Solution**:
  - Plees AutoSleep uses package ID `hu.vmiklos.plees_tracker.autosleep`, so it installs **side-by-side** with upstream Plees Tracker (`hu.vmiklos.plees_tracker`).
  - If you previously sideloaded a local debug build of Plees AutoSleep, uninstall the debug build first before installing the official release from Obtainium.

### "No matching APK found"
- Verify that your **Filter APKs by regular expression** setting in Obtainium is set to `(?i)plees-autosleep.*\.apk` or left blank (the release packages `plees-autosleep-v${VERSION}.apk` and `Plees-AutoSleep-foss-release.apk`).

### Backups and Data Migration
- You can export your sleep history anytime via **Export to File** (CSV format) and import it on any device via **Import from File**.
