# ScrollMeter

ScrollMeter is an Android application that tracks actual Instagram Reels consumption directly on-device.

## Milestone 1: Core Reel Detection & Live Counter

This milestone focuses purely on the minimum functionality required to detect Instagram Reel-to-Reel transitions and display a live count:
- **No unnecessary abstractions**: No Room, SQLite, repositories, ViewModels, or network dependencies.
- **Dwell Time State Machine**: Skips under **1.2 seconds** are ignored to prevent counting rapid doomscroll flickers.
- **Live UI**: Built with Jetpack Compose, showing live counter, service status toggle, and real-time detection diagnostics.

---

### How to Run on Android Phone

1. **Open the project** in **Android Studio** (or connect your phone via USB with USB debugging enabled).
2. **Build and install** onto your device:
   ```bash
   ./gradlew installDebug
   ```
3. **Open ScrollMeter** on your phone.
4. Tap **Enable** next to "Service Disabled", which opens Android's Accessibility Settings.
5. In Accessibility Settings, find **ScrollMeter Reels Tracker** under *Downloaded Apps / Services* and switch it **ON**.
6. Return to ScrollMeter — the indicator will turn green: **Service Active**.
7. Tap **Open Instagram** (or open Instagram manually), go to the **Reels** tab, and scroll through several Reels.
8. Switch back to **ScrollMeter** to see your live Reel count and detection details.
