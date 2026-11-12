# REA deep scan — what it runs, and what it can (and cannot) prove

The deep scan is the first step of the fix run's task 1: before anything is changed or
removed, the app asks the TV itself what brings the Shop tab and the sponsored banners
back after a reboot. It runs on the person's TV, from the phone app, over the same
wireless ADB session the Setup probe already uses. There is no pre-set profile: the scan
reads the TV's own state and reports evidence, and only evidence nominates a package.

## What runs, in order

Setup's `replan()` gathers a suspect list first (`ReaEngine.deepScanSuspects(snapshot)`) —
packages whose name already looks like an ad/shop provider (they are in
`STOCK_AD_AND_SHOP_PACKAGES`, or their name matches an ad/telemetry signature or a vendor
bloat prefix), system apps only, denylisted and stock-launcher packages excluded. Then
`AdbProber.deepScan(session, suspects)` runs three read-only commands:

1. **Boot receivers** — `cmd package query-receivers --brief -a android.intent.action.BOOT_COMPLETED`
   Every component that wakes at boot. A package that registers a boot receiver is one that
   can re-register its home-screen rows after a reboot.
2. **Job scheduler** — `dumpsys jobscheduler`
   Every scheduled job, counted per package. A package with a scheduled job can re-download
   Shop config or banner content on a schedule, long after the app is opened.
3. **One package dump per suspect** (at most 40, alphabetical) — `dumpsys package <pkg>`
   The dump's `Receivers:` section, `Providers:` section and `signatures:` line. The
   providers section is where an ad/content provider lives; the signature digest is the 64
   hex characters that identify who signed the package — the same package name signed by
   different keys (vendor vs Google) is a different thing.

Every section is best-effort: a section that fails simply yields no evidence for it, and
the scan never throws into the Setup flow.

## The APK scan (RFC-SHOP-5, 2026-11-12)

After the dumps, the same deep scan pulls at most four APKs from the TV and scans them
on the phone (`AdbProber.deepScan` → `ApkScanner.scan`) — the resolved home launcher
first, then the suspects in name order. Each APK is copied under the fixed TMP name
`apkscan.apk` (`ProbeScripts.copyApk`, 40 MiB cap — launcherx is 35 MB), pulled over the
ADB sync service, scanned, and removed whatever happened (`signerCleanup`). A copy, pull
or parse that fails is evidence-absent: the package simply gets no finding, and Setup
never fails for it.

The scan is deliberately lite — no decompilation, no desktop, no network beyond this ADB
session, no profile downloads. It reads three things from the APK, all pure Kotlin:

- **Binary AndroidManifest.xml** — a small hand-rolled string-pool + chunk walk (the
  same offsets apktool uses, validated against its output on tvlauncher, launcherx,
  tvrecommendations and the Fire OS launcher): components (activities, services,
  receivers, providers) with their class names, provider authorities, and intent-filter
  deep links (scheme + host + path).
- **resources.arsc** — the global string pool only; a package's ad strings appear there
  when compiled normally.
- **dex string and type tables** — string literals and class descriptors; code is never
  executed or disassembled.

The signatures (`ApkScanSignatures`) are data derived by REA analysis of the public
APKs in the sandbox — tvlauncher 6.4.6, launcherx 1.0.616930155, tvrecommendations 4.1.8
and the Fire OS launcher 7251003.1 — and only what REA actually found is listed. A
component matches when its class is a REA-proven Shop/ad component or provider, when its
provider authority serves ad signals, when it opens the Shop deep link
(`tv.google.com`), or when its class name matches a signature substring at a word
boundary (so "ads" does not fire on "Downloads" and "shop" does not fire on
"Workshop"). A dex or resources string naming an ad network
(`pagead2.googlesyndication.com`, `doubleclick.net`, `googlesyndication.com`) is strong
evidence the APK serves ads; a bare "shop" or "sponsored" word is corroborating only
and never nominates alone. Native `.so` names are recorded as a footprint note, never
evidence.

What the findings feed, with the same safety as the rest of the plan:

- **Section 3.5** — a launcher's scanned Shop/ad components join the hardcoded
  `TVLAUNCHER_SHOP_COMPONENTS` (which stays as the fallback) and are switched off with
  `Op.DisableComponent`. The home entry point is refused twice (the scanner, then
  `ReaEngine.allowed()`), and a component already off stays off.
- **Section 2 / REA groups** — a system package whose APK shows strong ad/telemetry/
  bloat evidence nominates even when its name matches nothing. The loop's guards apply
  first: system apps only, denylist, the resolved home, the keyboard and the
  screensaver are never nominated, and a launcher never appears here. APK size alone
  never nominates — a big APK is a weak signal only; the measured RSS (rule 4) stays
  the authority.

What it cannot see: obfuscated logic (a MainActivity tab gate, a Phenotype server
flag), native-code tabs, and any APK it did not pull (at most four pulls per scan, and
only the base APK — split APKs' config splits are not pulled). Full decompilation
stays desktop-REA work. And an app the person installed themselves is never nominated
or switched off by any of this — the engine skips non-system apps first, and the
planner refuses one with `REASON_USER_INSTALLED` wherever a profile, a Custom choice,
a SwitchOff request, or Clean Stock Home section 2 names it.

## What the scan can reveal on the Skyworth SWTV

The banners Sunil reported — Diwali Celebration, Top Selling Movies, Trending on Google,
Kickstart Khushiyon ka Safar — are rows the stock launcher draws. The scan answers, for
each suspect:

- Does it wake at boot? (`bootReceivers` — e.g. a Coocaa or Skyworth customizer with a
  `.BootReceiver` is the thing that re-registers the Shop rows after a reboot.)
- Does it keep scheduled jobs? (`jobs` — a job that periodically fetches new banner
  content explains why a banner returns hours later, not only at boot.)
- Does it expose a content provider? (`providers` — a provider is how a launcher pulls
  rows: the tvrecommendations provider is what the content-delete step removes before
  the uninstall, and a vendor provider doing the same job is the next suspect.)
- Who signed it? (`signatureHex` — a vendor-signed customizer is the vendor's, whatever
  its name claims.)

A package is a candidate only when at least one of those is present
(`DeepScanEvidence.hasRevivalEvidence`). A name alone is never enough: a suspect with an
empty scan is left alone. The candidate lands in its own group,
"Deep scan found (REA Auto-Found)", default ASK, and the person chooses it like any other
row.

## What the scan cannot do

- **It cannot name a package that is not on the TV.** If the Shop tab comes back from a
  server-side config the launcher downloads (no local package involved), the scan shows
  the launcher's own boot receiver and jobs — and the fix is the launcher clear + restart
  and the secure settings, not a package removal.
- **It can now block one single broadcast receiver, service, activity or provider —
  RFC-SHOP-2 (2026-11-12).** The old note that the plan's vocabulary (`Op`) has no
  per-component verb was true until 2026-11-12: `Op.DisableComponent` /
  `Op.EnableComponent` now render `pm disable-user --user 0 <pkg>/<cls>` /
  `pm enable --user 0 <pkg>/<cls>` through the renderer, with inverse, codec, journal
  and restore handling. Clean Stock Home uses them (section 3.5) for the launcher's own
  ad components — the four manifest-proven tvlauncher ones, plus whatever the scan finds
  in a launcher package — so the Shop tab cannot come back after a reboot. The launcher
  itself is still never touched, and a component of a package the person must keep is
  only switched off when it is a launcher ad component, never its home entry point.
  **The Secure keys are not the fix:** `PERSONALIZED_ADS` / `DISCOVER_ENABLED` /
  `SPONSORED_CONTENT` are not AOSP-standard keys and no consumer of them is proven in
  the launcher — the plan still sets them (harmless), but the reboot-proof lever is the
  component disable, not the keys.
- **It cannot prove the phone-side timing.** The scan runs before the plan is built, so
  the Found screen can already show the deep-scan group on the first run — no second
  connect is needed.

## Files

- `core/model/.../DeepScan.kt` — `DeepScanEvidence`, `DeepScanResult`, `ApkScanFinding`.
- `core/probe/.../ProbeScripts.kt` — `DEEP_BOOT_RECEIVERS`, `DEEP_JOBS`, `deepPackageDump(pkg)`, `apkScanPath()`.
- `core/probe/.../Parsers.kt` — `DeepScanParser` (boot receivers, jobs, package dump).
- `core/probe/.../ApkScanner.kt`, `ApkScanSignatures.kt` — the on-phone APK scan and its REA-derived signatures.
- `core/probe/.../Probe.kt`, `AdbProber.kt` — `Prober.deepScan`.
- `core/planner/.../ReaEngine.kt` — `deepScanSuspects`, `findDeepScanCandidates`, the merge in
  `findDebloatCandidates`, the deep-scan group in `synthesizeReaGroups`.
- `core/flows/.../PlanRunner.kt`, `SetupMachine.kt` — the scan runs inside `replan()`.
