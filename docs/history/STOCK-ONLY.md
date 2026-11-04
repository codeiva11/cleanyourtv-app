# Stock-only build (2026-11-04)

## Decision

This fork cleans a TV with the TV's own (stock) home screen only. It never installs a second
launcher on the TV and never installs the curtain (the TV-side progress app). Direction from the
fork owner, 2026-11-04: the app had installed its own launcher on his TV, which he does not
want. Progress during a run shows on the phone only. Safety (restore point, watchdog, rollback)
is unchanged.

## What was removed

- `:feature:launcher` and `:tv:curtain` modules, both bundled APKs
  (`cleanyourtv-launcher.apk`, `cleanyourtv-curtain.apk`), both pin checks.
- The takeover flow (install launcher → start it → arm watchdog → disable stock launcher →
  SetHome → verify), the helper (Home button helper), the look/dock push in Setup, the
  "Update the home screen" flow, `HomeScreenMachine`, `HomeHelperFlows`, `HomeKept`,
  `HomeSwitchRisk`, `HomeBlock`, `WizardWifi`, `LauncherPin`, `CurtainPin`.
- Setup no longer offers a dock, a look, or "Show the progress on the TV". Manage no longer
  has a Home screen section.

## Frozen-interface changes

- `core/planner/Planner.kt` (§5.8): `Choices` is now
  `(kept, extraOff, keepDebuggingOn, cleanStockHome=false, autoDebloat=false)`;
  `PlanRequest.Setup` is `(resolution, choices)` only; `ApplyTheme`, `UpdateLauncher` and
  `HomeHelperOff` requests are deleted; `PlanKind` lost `THEME`, `UPDATE_LAUNCHER` and
  `HOME_HELPER_OFF`; `PlanSummary` is `(groups, keepNames)` only; `AddModule` is
  `(module, version, apk)`.
- `core/trust/Trust.kt` (§5.11): the `LauncherPin` and `CurtainPin` interfaces are deleted;
  `Pins.kt` keeps only `TrustRoots`.

## What was deliberately kept

- The frozen `Op` vocabulary is untouched (`Install`, `InstallStream`, `Uninstall`,
  `CurtainCue`, `ArmHomeWatchdog` stay as types) — the engine still runs stored 0.4.7 plans
  that carry them.
- `undoEverything(removeLauncher)` still uninstalls `com.gathin.tvhome` from a TV cleaned by
  0.4.7, and the engine's guarded-section machinery still finishes or rolls back a stored
  0.4.7 plan mid-switch.
- The look/dock spec stack (`LauncherConfig`, `DockLayers`, `Looks`, `LookKeys`, `LookGates`,
  `LookExtras`, `HomeHelperKeys`, `DockRanking`, `BundledThemeSource`, `HomeFit`, `LauncherKeys`,
  `ThemeProviderLines`, `ConfigTransport`) stays: modules and old stored choices still read
  it. Deleting it is a separate later cleanup, not part of this removal.
- Modules (opt-in user-chosen add-ons) stay. They install apps, but only ones the person
  chose; the 0.1 release has no verified modules, so they are dormant in practice.
- `LauncherArtifact` in `core/model/IndexData.kt` and `HomeChoice`/`HomePrefs` in
  `ManageCommon.kt` stay for old stored data.

## What is NOT in this change (next steps)

- REA wiring: the REA engine and Clean Stock Home (Option A) code exists but never ran in the
  app flow. Setup still needs its 2 intents and the Customise toggles.
- Permanent uninstall: the engine already supports `Op.Uninstall` end to end, but no plan
  uses it for bloat yet. `pm uninstall --user 0` removes a system app for user 0 (APK stays
  in `/system`; factory reset restores it) and breaks restore/undo for that app, so the
  `Denylist` of protected packages matters more, not less.
- Real per-app RAM measurement: the probe reads only total RAM today. A new probe
  (`ps -A -o RSS,ARGS` or `dumpsys meminfo`) is needed before "heavy RAM" apps can be
  found by measurement rather than by name.
- Open decisions with the fork owner: uninstall scope (REA-found bloat only, or profile
  groups too?) and REA-when (only TVs with no profile/groups, or always?).

## Tests

The HomeScreenMachine-driven tests are deleted. The engine tests now build stored 0.4.7
takeover/helper/curtain plans directly (see `core/engine/src/test/.../Harness.kt`:
`storedTakeoverPlan`, `storedHelperPlan`, `storedCurtainPlan`) so the guarded-section
behaviour stays covered. The look-migration tests that do not need a TV push survive in
`LookMigrationTest.kt`.
