<p align="center">
  <img src="docs/images/banner-3-steps.png" width="800" alt="Clean your TV from your phone. No laptop. No coding agent. Open the app on your Android phone, tap Start, and the ads and bloat are gone in about five minutes. Three steps: connect, analyse, clean.">
</p>

<p align="center">
  <a href="https://github.com/codeiva11/cleanyourtv-app/releases/latest"><img src="https://img.shields.io/github/v/release/codeiva11/cleanyourtv-app?style=flat&color=E8A84C&labelColor=15181e&label=download%20APK" alt="Download the latest APK"></a>
  <img src="https://img.shields.io/badge/TVs-173%20lists%20%C2%B7%2047%20brands-E8A84C?style=flat&labelColor=15181e" alt="173 TV lists from 47 brands">
  <img src="https://img.shields.io/badge/phone-Android%209%2B-E8A84C?style=flat&labelColor=15181e" alt="Android 9 or newer">
  <a href="LICENSE"><img src="https://img.shields.io/badge/licence-MIT-E8A84C?style=flat&labelColor=15181e" alt="MIT licence"></a>
</p>

<h3 align="center">Clean your Android TV & Google TV directly from your phone.<br>No laptop, no cables, no coding agents, and <b>NO custom/third-party launcher</b> forced on your TV.</h3>

<p align="center">
  <a href="https://github.com/codeiva11/cleanyourtv-app/releases/latest"><b>Download APK</b></a> ·
  <a href="#how-it-works"><b>How it works</b></a> ·
  <a href="#rea-engine"><b>REA Autonomous Engine</b></a> ·
  <a href="#how-to-modify-and-extend"><b>How to Modify / Extend</b></a> ·
  <a href="#safe-by-design"><b>Is it safe?</b></a> ·
  <a href="#supported-tvs"><b>Supported TVs</b></a>
</p>

---

## 📺 100% Stock Launcher Only (No Custom Launcher)

**This app keeps your TV's original stock launcher, completely ad-free and bloat-free.**

Unlike earlier versions that replaced your home screen with a custom launcher (`cleanyourtv-launcher.apk`), this fork is strictly **Stock-Only** (see [docs/history/STOCK-ONLY.md](docs/history/STOCK-ONLY.md)):
- **Zero Third-Party Launchers:** Your TV keeps its official, familiar Google TV / Android TV home screen.
- **Progress Shows on Phone Only:** No intrusive curtain app is installed on the TV.
- **Safe & Native:** All hardware remote shortcuts, inputs, and OEM settings stay 100% functional.

---

## ⚡ How It Works (3 Simple Steps)

| 1. Connect | 2. Analyse | 3. Clean |
| :-: | :-: | :-: |
| <img src="docs/images/phone-connect.png" width="220" alt="Connect phone to TV over Wi-Fi"> | <img src="docs/images/phone-what-we-found.png" width="220" alt="Analyse bloatware and ads"> | <img src="docs/images/phone-done.png" width="220" alt="Clean stock launcher ready"> |
| The app discovers your TV on the local Wi-Fi (mDNS) and connects via ADB (Port 5555). | Scans installed packages using 173 community profiles + the REA heuristic engine. | 1-Tap "Make it clean". Restores stock launcher without ads, autoplay, or bloat. |

---

## 🧠 REA (Reverse-Engineering Analysis) Autonomous Engine

The **REA Engine** ([`core/planner/src/main/kotlin/tv/cleanyour/core/planner/ReaEngine.kt`](core/planner/src/main/kotlin/tv/cleanyour/core/planner/ReaEngine.kt)) inspects any TV dynamically at runtime without requiring an AI agent or prior database entry:

1. **Clean Stock Home (Option A):**
   - Deletes pending ad notifications first, while the provider is still on the TV (`content delete` on the tvrecommendations notification provider).
   - Permanently uninstalls the Shop tab and ad providers with `pm uninstall -k --user 0` — `com.google.android.tvrecommendations`, `com.google.android.videos` (Google Play Movies & TV, the Shop tab provider), Play Games, YouTube Music TV, the Discover row, and the vendor customizer packages (Skyworth/Coocaa, Mstar, TCL, Xiaomi and others). Uninstalled system apps come back with 1-tap Undo via `pm install-existing`.
   - Sets secure settings `freeplay_enabled = 0` (kills auto-playing video trailer ads), `limit_ad_tracking = 1` (blocks background ad tracking), `personalized_ads = 0`, `discover_enabled = 0` and `sponsored_content_enabled = 0`, so the Shop tab and sponsored rows cannot come back after a restart.
   - Force-stops the stock launcher, clears its cached ads and data (`pm clear`), and restarts its main activity — `com.google.android.tvlauncher/.MainActivity` on Android TV, `com.google.android.apps.tv.launcherx/.home.HomeActivity` on Google TV — so only Search, Home and Apps stay on top and no sponsored banner returns below.
   - Turns the window, transition and animator scales to 0, so the TV feels faster.
   - Google Play Store and external storage are always kept installed and enabled before any of this starts.
2. **Dynamic Heuristic Bloat Detection:**
   - **Ads & Promotions:** Scans package names matching `recommendation`, `adssignals`, `promos`, `autoinstalls`, `.ads.`, `adservice`, `sponsor`.
   - **Telemetry & Trackers:** Scans package names matching `analytic`, `telemetry`, `tracking`, `metrics`, `diagnosing`, `crashlog`.
   - **OEM Partner Bloatware:** Automatically catches preloaded partner apps from Xiaomi (`com.xiaomi.mitv.*`), TCL (`com.tcl.*`), Google Partner Setup, and others.
3. **RAM Profiler Ranking:**
   - Measures real per-process RSS RAM usage via `ps -A -o RSS,ARGS` and ranks the heaviest background hogs to the top.
4. **Permanent Bloatware Uninstall with Instant Undo:**
   - Performs `pm uninstall -k --user 0 <package>` for system apps, reclaiming device memory.
   - All uninstalled system apps can be restored instantly with 1-tap Undo via `pm install-existing --user 0 <package>`.

---

## 🛠️ How to Modify & Extend REA (For Developers)

Anyone can easily customize or add new detection rules:

### 1. Adding New Ad / Bloat Signatures
Open [`core/planner/src/main/kotlin/tv/cleanyour/core/planner/ReaEngine.kt`](core/planner/src/main/kotlin/tv/cleanyour/core/planner/ReaEngine.kt):
```kotlin
// Add keyword substrings to match new ad daemons:
private val ADS_SIGNATURES = listOf(
    "recommendation", "adssignals", "promos", "autoinstalls",
    ".ads.", "adservice", "sponsor", "your_new_keyword"
)

// Add new telemetry / tracking keywords:
private val TELEMETRY_SIGNATURES = listOf(
    "analytic", "telemetry", "tracking", "metrics", "logging"
)

// Add specific manufacturer packages:
private val VENDOR_BLOAT_PREFIXES = listOf(
    "com.xiaomi.mitv.", "com.tcl.partnercustomizer", "com.yourbrand.bloat"
)
```

### 2. Modifying Stock Launcher Cleaning Operations
The live Clean Stock Home is `DefaultPlanner.cleanStockHome()` in [`core/planner/src/main/kotlin/tv/cleanyour/core/planner/DefaultPlanner.kt`](core/planner/src/main/kotlin/tv/cleanyour/core/planner/DefaultPlanner.kt) — that is where the step order lives (Play Store kept safe, ad notifications deleted *before* the uninstalls, settings blocked, launcher cleared and restarted). The Shop/ad package list itself is `ReaEngine.STOCK_AD_AND_SHOP_PACKAGES` in [`ReaEngine.kt`](core/planner/src/main/kotlin/tv/cleanyour/core/planner/ReaEngine.kt):
```kotlin
// Add a package to the permanent uninstall list:
val STOCK_AD_AND_SHOP_PACKAGES = listOf(
    PackageName("com.google.android.tvrecommendations"),
    PackageName("com.google.android.videos"),
    // ...
)
```

### 3. Testing Your Changes Locally
Run the pure Kotlin unit test suite (no Android emulator required):
```bash
# If submodules are not yet initialized:
git submodule update --init --recursive

# Run core tests:
./gradlew :core:planner:test :core:ops:test :core:engine:test :core:flows:test :core:probe:test
```

---

## 🛠️ Local Android SDK & Submodule Setup

To build Android APKs (`assembleFullDebug` or `assembleFullRelease`), make sure you have:
1. Submodules checked out — the repo carries one submodule, `third_party/cleanyourtv` (the upstream launcher source). The `:core:spec` unit tests read it, so a build without it fails those tests:
   ```bash
   git submodule update --init --recursive
   ```
   (CI checks it out with `submodules: true`, so the release workflow always has it.)
2. Android SDK configured in `local.properties`:
   ```properties
   sdk.dir=C\:\\Users\\YourUser\\AppData\\Local\\Android\\Sdk
   ```
   (or on Linux/macOS: `sdk.dir=/home/user/Android/Sdk`)
3. JDK 17 or JDK 21 installed.

---

## 🛡️ Safe by Design

- **Automatic Restore Point:** Before changing anything, a full snapshot and restore point is created on your phone.
- **Never Touches Essential System Components:** Wi-Fi, Bluetooth, HDMI inputs, CEC, Audio, Play Store, and Active Stock Launchers are protected by a strict denylist ([`Denylist.kt`](core/ops/src/main/kotlin/tv/cleanyour/core/ops/Denylist.kt)).
- **Instant Rollback:** One-tap "Undo everything" reverses all uninstalls, re-enables disabled apps, and restores settings.
- **Exportable Shell Scripts:** You can export standalone `.sh` / `.bat` restore scripts from your phone to run from any PC with standard ADB.
- **Private & Offline:** Zero analytics, no account requirements, and no remote telemetry.

---

## 📺 Supported TVs

**173 TV profiles across 47 brands** (TCL, Sony, Xiaomi, Hisense, Philips, OnePlus, Panasonic, Motorola, Haier, Skyworth, onn, Google TV Streamer, and more). Even unlisted TVs are automatically protected and debloated via REA heuristics!

---

## 💻 Build from Source

```bash
git clone --recurse-submodules https://github.com/codeiva11/cleanyourtv-app.git
cd cleanyourtv-app
./gradlew assembleFullRelease
```

## 📄 Licence

MIT Licence — see [LICENSE](LICENSE).
Built upon the [Clean Your TV](https://github.com/Gathin23/cleanyourtv) community project and [dadb](https://github.com/mobile-dev-inc/dadb).
