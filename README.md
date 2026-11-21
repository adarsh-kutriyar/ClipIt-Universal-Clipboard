# 📋 ClipIt — Universal BLE Clipboard for Mac & Android

**ClipIt** is a seamless, zero-cloud, peer-to-peer universal clipboard that synchronizes copied text instantly between macOS and Android over **Bluetooth Low Energy (BLE)**.

- **No HTTP servers, no cloud accounts, no Wi-Fi network dependency.**
- **One-time BLE pairing.**
- **macOS Menu Bar App** (native Swift + CoreBluetooth).
- **Native Android App** (Kotlin + Jetpack Compose + Companion Device Manager + Accessibility Service).

---

## ⚡ Architecture & How It Works

```
┌─────────────────────────────────┐                 ┌────────────────────────────────┐
│            macOS                │                 │            Android             │
│   (Menu Bar Application)        │                 │       (Kotlin / Compose)       │
├─────────────────────────────────┤                 ├────────────────────────────────┤
│ • CoreBluetooth Peripheral      │   BLE GATT      │ • BLE GATT Central Client      │
│ • Advertises ClipIt Service     │ <─────────────> │ • CompanionDeviceManager       │
│ • NSPasteboard Monitor          │  Notifications  │ • ClipItAccessibilityService   │
│ • Automatic Chunking / Framing  │    & Writes     │ • Quick Settings Tile          │
│ • Echo Prevention Engine        │                 │ • Foreground Service           │
└─────────────────────────────────┘                 └────────────────────────────────┘
```

### 1. BLE Protocol Specification
- **Service UUID**: `8D4F0001-3A5B-4C6D-9E8F-1B2C3D4E5F60`
- **Clipboard Characteristic UUID**: `8D4F0002-3A5B-4C6D-9E8F-1B2C3D4E5F60` (Properties: `READ`, `WRITE`, `NOTIFY`)
- **CCCD (Descriptor)**: `00002902-0000-1000-8000-00805F9B34FB`
- **Device Advertised Name**: `ClipIt-Mac`

#### Framing & Chunking Protocol
To support copying anything from single characters to long articles or code snippets (exceeding standard BLE MTU limits), both apps use a 5-byte header framing protocol:
```
[Byte 0: MessageID (1B)] [Bytes 1-2: TotalChunks (2B, BE)] [Bytes 3-4: ChunkIndex (2B, BE)] [Payload: UTF-8 Bytes]
```
Both sides reassemble chunks in memory and verify SHA-256 hashes to prevent infinite echo loops.

---

## 🔍 Addressing the Android Challenges

### 1. "Android kills apps in the background" — Auto-Wakeup via CompanionDeviceManager
We solve this using Android's native **Companion Device Manager (CDM)** framework:
1. When pairing, the app uses `CompanionDeviceManager.associate()` to bind with the Mac BLE peripheral.
2. The app registers `companionDeviceManager.startObservingDevicePresence(deviceAddress)` (and Android 14+ `ObservingDevicePresenceRequest`).
3. We declare `REQUEST_COMPANION_RUN_IN_BACKGROUND` and `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE`.
4. **Result**: When your Mac comes into BLE range, the Android OS automatically wakes up the app and binds to `ClipItCompanionService.onDeviceAppeared()`. Even if the app was swiped away or closed, Android elevates the process priority and reconnects BLE without getting killed by battery optimizations!

---

### 2. "Android doesn't allow background apps to access the clipboard"
Here is the exact breakdown of Android's clipboard security and how ClipIt handles both directions:

#### A. Mac ➔ Android (Writing to Clipboard)
* **Allowed natively in the background!** Any background service can call `clipboardManager.setPrimaryClip(ClipData)`. When you copy text on your Mac, it transmits over BLE and Android writes it directly to your phone's clipboard immediately (Android 12+ shows a brief system toast: *"ClipIt pasted from your clipboard"*).

#### B. Android ➔ Mac (Reading the Clipboard)
Starting with Android 10 (API 29), background apps are prevented from reading `clipboardManager.primaryClip`. Here is how ClipIt solves this:

1. **Solution 1: Quick Settings & Notification Actions (Recommended for Google Pay / Banking users)**
   - ClipIt includes a persistent notification with a 1-tap **"Send Clipboard"** action button and a **Quick Settings Tile ("ClipIt Sync")**.
   - Tapping it triggers instant clipboard reading with window focus and sends it to your Mac in milliseconds.
   - **100% safe with Google Pay, PhonePe, Paytm, and banking apps!** (Does not trigger any accessibility fraud warnings).

2. **Solution 2 (Optional Zero-Touch): `ClipItAccessibilityService`**
   - Automatically detects when you tap "Copy" or "Cut" in any app.
   - *Note on UPI/Banking:* NPCI and Indian banking apps (Google Pay, PhonePe, YONO) enforce security policies that pause UPI transactions if *any* third-party Accessibility Service is turned on. If you use UPI regularly, keep Accessibility **OFF** and use the 1-Tap Notification button instead!

3. **Solution 3 (Android Share Sheet)**
   - Tap "Share" on any text, tweet, or link and select **ClipIt** to send directly to your Mac.

4. **Solution 4 (Optional ADB Command for Purists)**
   - If you prefer not to use an Accessibility Service, you can optionally grant logcat permissions via ADB (the same method used by Tasker):
     ```bash
     adb shell appops set com.clipit.android SYSTEM_ALERT_WINDOW allow
     adb shell pm grant com.clipit.android android.permission.READ_LOGS
     ```

---

## 📦 Downloads & Pre-Built Releases

Pre-compiled, ready-to-run release binaries are available in the [`release/`](file:///Users/adarsh_kutriyar/Desktop/clip-it/release) directory:

| Platform | Download / Artifact | Description |
| :--- | :--- | :--- |
| **macOS** | [`ClipIt-macOS.dmg`](file:///Users/adarsh_kutriyar/Desktop/clip-it/release/ClipIt-macOS.dmg) | Standard macOS installer disk image. Open and drag to `/Applications`. |
| **macOS** | [`ClipIt-macOS.zip`](file:///Users/adarsh_kutriyar/Desktop/clip-it/release/ClipIt-macOS.zip) | Standalone portable zip of `ClipIt.app`. |
| **Android** | [`ClipIt.apk`](file:///Users/adarsh_kutriyar/Desktop/clip-it/release/ClipIt.apk) | Ready-to-install Android APK (supports Android 8.0 through 14+). |

---

## 🚀 Getting Started

### 1. Install the Mac Menu Bar App
To build, package, and install the Mac app directly to `/Applications`:

```bash
# Automatically builds, packages, and installs /Applications/ClipIt.app
./export_release.sh
```

Or open `release/ClipIt-macOS.dmg` and drag **ClipIt** into your **Applications** folder.

* **Menu Bar Only:** ClipIt runs as a lightweight, unobtrusive menu bar app (`LSUIElement = true`).
* **Launch at Login:** Click the ClipIt icon in your macOS menu bar and check **"Launch at Login"** to have it start automatically whenever your Mac boots!
* **Icon Styles:** Click **"Menu Bar Icon Style"** to choose between **Dual Sync Cards (Modern)**, **Paperclip (Minimal)**, or **Single Clipboard**.

---

### 2. Install the Android App
You can install the pre-compiled APK directly:

```bash
adb install -r release/ClipIt.apk
```

Or open the `/android` project in **Android Studio** and click **Run** (`Shift + F10`), or build via CLI:

```bash
cd android
./gradlew assembleDebug
```

3. Open **ClipIt** on your phone:
   - Tap **"Scan QR"** to scan your Mac's on-screen pairing QR code (`⌘P` on Mac), or tap **"Auto-search"**.
   - Your Mac and phone are now paired permanently with the auto-reconnect engine active!

---

## 🧪 Testing the Sync

1. **Mac ➔ Android**:
   - On your Mac, select any text and press `Cmd + C`.
   - Look at your phone: Android shows the clipboard toast. Tap paste (`Ctrl + V`) anywhere on your phone!
2. **Android ➔ Mac**:
   - On your phone, select any text in any app (Chrome, WhatsApp, Notes) and tap **Copy**.
   - On your Mac, press `Cmd + V`. The text is pasted instantly!
3. **Menu Bar Inspection**:
   - Click the ClipIt icon in your Mac's menu bar to see the live connection status (`🟢 Connected to Android`), last synced item preview, and timestamp.
