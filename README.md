# 📱 ScrollMeter

**ScrollMeter** is an open-source, privacy-first Android application that automatically tracks your Instagram Reels consumption in real-time — directly on your device.

It features a live **Floating Notch Pill** that sits unobtrusively under your front camera cutout while browsing Reels, detailed session history, daily watch time analytics, and creator tracking.

<p align="center">
  <a href="https://drive.google.com/file/d/1hq96hf2PB2DZgkczRYtqbeRbZAAku6Lt/view?usp=drive_link">
    <img src="https://img.shields.io/badge/Download_APK-Google_Drive-brightgreen?style=for-the-badge&logo=google-drive&logoColor=white" alt="Download APK" />
  </a>
  <img src="https://img.shields.io/badge/Platform-Android-blue?style=for-the-badge&logo=android" alt="Platform Android" />
  <img src="https://img.shields.io/badge/License-MIT-purple?style=for-the-badge" alt="License MIT" />
</p>

### 📥 [**Click Here to Download ScrollMeter APK (Google Drive)**](https://drive.google.com/file/d/1hq96hf2PB2DZgkczRYtqbeRbZAAku6Lt/view?usp=drive_link)
> Tap the link above to download the latest `.apk` file directly to your phone.

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
- **Detection Engine**: Pure **Computer Vision Pipeline** (`MediaProjection` + `OpticalFlowAnalyzer` + `PerceptualHashAnalyzer` + `ReelTransitionDetector`) operating on captured screen frames without requiring Accessibility permissions.
- **Overlay Engine**: **WindowManager** system overlay (`NotchOverlayManager`) rendered with translucent gradient glassmorphism (`TYPE_APPLICATION_OVERLAY`).
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

### Method B: Install via APK Download (Recommended for Users)
1. Download the APK directly from Google Drive:
   👉 [**Download ScrollMeter.apk**](https://drive.google.com/file/d/1hq96hf2PB2DZgkczRYtqbeRbZAAku6Lt/view?usp=drive_link)
2. On your phone, open the downloaded APK file.
3. If prompted, toggle **Allow from this source** / **Install unknown apps**.
4. Tap **Install**.

---

## ⚙️ Initial Setup on Device

Once installed, open **ScrollMeter** and grant the required permissions:

1. **Screen Capture Permission (Required for Computer Vision Reel Detection)**:
   - Tap the top card (**"Computer Vision Inactive"**).
   - In the system prompt, grant **Screen Recording / Capture** permission.
2. **Display Over Other Apps (Required for the Floating Notch Pill)**:
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
│   │   └── java/com/scrollmeter/app/
│   │       ├── MainActivity.kt                # Jetpack Compose UI (Dashboard, History, Vision)
│   │       ├── NotchOverlayManager.kt          # Floating notch pill overlay window
│   │       ├── ReelTrackerState.kt             # In-memory reactive state holder
│   │       ├── capture/                        # MediaProjection Screen Capture Service
│   │       ├── vision/                         # Optical flow, dHash, motion analyzers
│   │       ├── detector/                       # Transition state machine & verification
│   │       ├── context/                        # Visual Reels context detector
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
