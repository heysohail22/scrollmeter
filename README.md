# 📱 ScrollMeter

**ScrollMeter** is an open-source, privacy-first Android application that automatically tracks your Instagram Reels consumption in real-time — directly on your device.

It features a live **Floating Notch Pill** that sits unobtrusively under your front camera cutout while browsing Reels, detailed session history, daily watch time analytics, and creator tracking.

---

## ✨ Features

- **⚡ Real-Time Reel Detection**: Detects Reel transitions instantly as you scroll, extracting creator handles, audio tracks, and captions without requiring account login or Instagram API access.
- **💊 Floating Notch Pill Overlay**: A sleek translucent glass pill below the camera notch displaying your current watch time (*e.g., `12m • 14 reels`*). Tapping the pill opens ScrollMeter directly.
- **📊 Detailed Dashboard**:
  - **Today's Stats**: Total Reels watched, total watch duration, and average watch time per Reel.
  - **This Month Overview**: Cumulative monthly Reel count and watch time.
  - **7-Day Trend**: Visual bar chart breakdown of the past week's screen time.
- **🕒 Sessions History**: Complete logs of every viewing session grouped by date, showing start/end timestamps, duration, and the individual Reels watched.
- **🔒 100% Private & On-Device**: Built on local Room database. Zero internet tracking, zero analytics, zero external servers. All data stays on your phone.

---

## 🛠️ Architecture & Tech Stack

- **UI Framework**: Modern declarative UI built with **Jetpack Compose** & **Material 3**.
- **Detection Engine**: Custom **Android AccessibilityService** (`InstagramAccessibilityService`) that monitors UI node hierarchy and content descriptions in real-time.
- **Overlay Engine**: **WindowManager** application/accessibility overlay (`NotchOverlayManager`) rendered with translucent gradient glassmorphism.
- **Local Persistence**: **Room Database** (SQLite) using Kotlin Coroutines & Flow for real-time UI updates.
- **Language**: 100% **Kotlin**.

---

## 🚀 How to Build and Create This App from Scratch

### 1. Prerequisites
- **Android Studio** (Hedgehog 2023.1.1 or newer recommended)
- **JDK 17** (configured in Gradle)
- **Android SDK** (API 34 / Android 14)

### 2. Clone the Repository
```bash
git clone https://github.com/heysohail22/scrollmeter.git
cd scrollmeter
```

### 3. Open in Android Studio
1. Open Android Studio ➔ Click **Open** ➔ Select the `scrollmeter` directory.
2. Wait for Gradle Sync to finish automatically.

### 4. Build the APK via Terminal
To build a debug APK without opening Android Studio:
```bash
./gradlew assembleDebug
```
The compiled APK will be generated at:
```text
app/build/outputs/apk/debug/app-debug.apk
```

---

## 📲 How to Install on Any Phone

### Method A: Install via USB / ADB (Direct)
1. Enable **Developer Options** and turn ON **USB Debugging** on the phone.
2. Connect the phone via USB cable (or via Wireless Debugging).
3. Run:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

### Method B: Install via APK File (Sharing with Anyone)
1. Copy `app/build/outputs/apk/debug/app-debug.apk` to your phone or share it via WhatsApp, Telegram, Quick Share, Google Drive, or Bluetooth.
2. On the recipient's phone, tap the APK file to install.
3. If prompted, toggle **Allow from this source** / **Install unknown apps**.

---

## ⚙️ Initial Setup on Device

Once installed, open **ScrollMeter** and grant the required permissions:

1. **Accessibility Permission (Required for Reel Detection)**:
   - Tap the top card (**"Service Inactive"**).
   - In Android Settings ➔ **Accessibility** ➔ Find **ScrollMeter** (under *Downloaded Apps*) ➔ Turn it **ON**.
2. **Display Over Other Apps (Required for the Floating Pill)**:
   - Toggle **Floating Notch Pill** inside the app.
   - When prompted, grant **Display over other apps** / **Appear on top** permission.

---

## 📂 Project Structure

```text
scrollmeter/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── res/
│   │   │   └── xml/accessibility_service_config.xml
│   │   └── java/com/scrollmeter/app/
│   │       ├── MainActivity.kt                # Jetpack Compose UI (Dashboard, History, Stats)
│   │       ├── InstagramAccessibilityService.kt # Core detection & tracking service
│   │       ├── NotchOverlayManager.kt          # Floating notch pill overlay window
│   │       ├── ReelTrackerState.kt             # In-memory reactive state holder
│   │       └── data/
│   │           ├── AppDatabase.kt             # Room DB instance
│   │           ├── ReelRecord.kt              # ReelRecord & ReelSession entities
│   │           └── ReelDao.kt                 # Reactive Room DAO queries
├── build.gradle.kts
└── settings.gradle.kts
```

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
