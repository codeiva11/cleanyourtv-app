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
- **It cannot block one single broadcast receiver or one single provider.** The plan's
  vocabulary (`Op`) has no per-receiver or per-provider verb, and adding one would mean
  inventing shell commands outside the renderer. What the plan does instead is remove the
  nominating package itself (uninstall for system apps when uninstallBloat is on, disable
  otherwise) — a package that is gone cannot wake at boot, run its jobs or serve its
  provider. If a receiver belongs to a package the person must keep, the scan still shows
  it, and the person decides.
- **It cannot prove the phone-side timing.** The scan runs before the plan is built, so
  the Found screen can already show the deep-scan group on the first run — no second
  connect is needed.

## Files

- `core/model/.../DeepScan.kt` — `DeepScanEvidence`, `DeepScanResult`.
- `core/probe/.../ProbeScripts.kt` — `DEEP_BOOT_RECEIVERS`, `DEEP_JOBS`, `deepPackageDump(pkg)`.
- `core/probe/.../Parsers.kt` — `DeepScanParser` (boot receivers, jobs, package dump).
- `core/probe/.../Probe.kt`, `AdbProber.kt` — `Prober.deepScan`.
- `core/planner/.../ReaEngine.kt` — `deepScanSuspects`, `findDeepScanCandidates`, the merge in
  `findDebloatCandidates`, the deep-scan group in `synthesizeReaGroups`.
- `core/flows/.../PlanRunner.kt`, `SetupMachine.kt` — the scan runs inside `replan()`.
