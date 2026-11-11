package tv.cleanyour.core.planner

import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.GroupDefault
import tv.cleanyour.core.model.InstallFlag
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageGroup
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.Tier
import kotlinx.coroutines.runBlocking
import tv.cleanyour.core.ops.Denylist
import tv.cleanyour.core.ops.FailurePolicy
import tv.cleanyour.core.ops.HelperLines
import tv.cleanyour.core.ops.HomePlaceholder
import tv.cleanyour.core.ops.OpRenderer
import tv.cleanyour.core.ops.RenderRefused
import tv.cleanyour.core.ops.Rendered
import tv.cleanyour.core.ops.UndoOrder
import tv.cleanyour.core.ops.Watchdog
import tv.cleanyour.core.spec.AppNames
import tv.cleanyour.core.spec.FactsTable
import tv.cleanyour.core.spec.KnownLaunchers
import tv.cleanyour.core.spec.ProfileCategory
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.core.spec.ProfileSource
import tv.cleanyour.core.spec.resolveFor
import tv.cleanyour.core.store.Clock
import tv.cleanyour.core.store.IdGen

// The planner (PRODUCT-SPEC 5.8): pure, one plan per request, every op rendered through the one
// renderer so the plan screen, the hash and the executor see the same lines. Protected packages
// never reach a step (Denylist, applied here and again by the executor with live facts).
//
// Idempotence: a request whose effect the snapshot already shows yields a plan with no steps.
// Refusals carry plain reasons for the UI. Stock-only build (2026-11-04): the HOME takeover is
// gone, so no plan carries a GuardedSection anymore; the section type stays for the engine's
// generic handling of old plans. See docs/history/STOCK-ONLY.md.
//
// `profiles` (R1 R-3) resolves this TV's profile for the Manage requests (SwitchOff, Leftovers,
// PutBack), so the profile's never_disable list and the wizard quirk hold at plan time and the plan
// screen never offers what the executor's live check would then refuse. Setup takes its resolution
// from the request. The parameter is last and defaulted, so the R0: 15 constructor still compiles.
// `keepUpdatesOn` (STORES-PLAN 1, the Google Play build): every software updater stays on, found by
// its name (ProfileCategory.isUpdater, D22) or by the profile's "updates" group. Setup shows it as
// protected ("We keep these", with REASON_UPDATES), SwitchOff refuses it, and Setup and Leftovers
// skip one an earlier full build had switched off. Off (the default), updates stay a choice (D22).
class DefaultPlanner(
    private val clock: Clock,
    private val idGen: IdGen,
    private val renderer: OpRenderer,
    private val appVersion: String,
    private val launcher: Component = Component(SwitchOffRule.OUR_LAUNCHER, ".MainActivity"),
    private val profiles: ProfileSource? = null,
    private val keepUpdatesOn: Boolean = false,
) : Planner {

    override fun plan(request: PlanRequest, snapshot: Snapshot, facts: FactsTable, journals: List<ChangeRecord>): PlanResult {
        val id = idGen.planId()   // first, so a plan's id does not depend on how many run tokens it drew
        val d = Draft(snapshot, facts, journals)
        val kind = when (request) {
            is PlanRequest.Setup -> { d.setup(request); PlanKind.SETUP }
            is PlanRequest.PutBack -> { d.putBack(request.pkg); PlanKind.PUT_BACK }
            is PlanRequest.SwitchOff -> { d.switchOff(request.pkgs); PlanKind.SWITCH_OFF }
            is PlanRequest.Leftovers -> { d.leftovers(request.expectedDisabled); PlanKind.LEFTOVERS }
            is PlanRequest.AddModule -> { d.addModule(request); PlanKind.ADD_MODULE }
            is PlanRequest.RemoveModule -> { d.removeModule(request.pkg); PlanKind.REMOVE_MODULE }
            is PlanRequest.UseStockHome -> { d.useStockHome(request.stock, null); PlanKind.USE_STOCK_HOME }
            is PlanRequest.UndoEverything -> { d.undoEverything(request.changes, request.removeLauncher); PlanKind.UNDO_EVERYTHING }
            PlanRequest.BeforeFactoryReset -> { d.beforeFactoryReset(); PlanKind.BEFORE_FACTORY_RESET }
            PlanRequest.DebugOff -> { d.debugOff(); PlanKind.DEBUG_OFF }
            is PlanRequest.WriteRestorePoint -> { d.writeRestorePoint(request.restorePointJson, request.restoreTxt); PlanKind.WRITE_RESTORE_POINT }
            PlanRequest.Reboot -> { d.reboot(); PlanKind.REBOOT }
        }
        if (d.reasons.isNotEmpty()) return PlanResult.Refused(d.reasons.toList())
        val steps = d.steps.toList()
        val plan = Plan(
            id = id, tvId = snapshot.tvId, kind = kind, baseSnapshot = snapshot.contentHash,
            steps = steps, sections = d.sections.toList(), summary = d.summary(),
            hash = renderer.planHash(steps.map { it.rendered }), createdAtMs = clock.nowMs(), appVersion = appVersion,
        )
        return PlanResult.Ok(plan)
    }

    private inner class Draft(val snapshot: Snapshot, val facts: FactsTable, val journals: List<ChangeRecord>) {
        val steps = mutableListOf<Step>()
        val sections = mutableListOf<GuardedSection>()
        val reasons = mutableListOf<String>()
        var groups = mutableListOf<GroupSummary>()
        var keepNames = listOf<String>()

        fun summary() = PlanSummary(groups.toList(), keepNames)

        // The accessibility settings as the snapshot reports them, so Undo's setting filter knows
        // what the plan leaves behind (an undo never writes "1" or a non-empty list back).
        var accList: String? = snapshot.settings[SettingKey.ENABLED_ACCESSIBILITY]
        var accOn: String? = snapshot.settings[SettingKey.ACCESSIBILITY_ENABLED]

        // This TV's resolution for the Manage requests (R1 R-3), with the snapshot's Home (issue #114).
        // The bundled source answers without suspending, so the blocking call returns at once; the
        // planner stays a plain function.
        val resolution: ProfileResolution? by lazy { profiles?.let { src -> runBlocking { src.resolveFor(snapshot) } } }
        val managedProfile: Profile? get() = (resolution as? ProfileResolution.Exact)?.profile
        val managedStockLaunchers: List<Component> get() = when (val r = resolution) {
            is ProfileResolution.Exact -> r.profile.stockLaunchers
            is ProfileResolution.BrandSureSet -> r.stockLaunchers
            is ProfileResolution.None -> r.stockLaunchers
            null -> emptyList()
        }

        fun name(pkg: PackageName): String = AppNames.of(pkg, facts, snapshot.packages[pkg]?.label)
        // Issue #111: an owner's-choice row says what switching it off costs.
        fun description(pkg: PackageName): String {
            val what = facts.lookup(pkg)?.description ?: ""
            val cost = ProfileCategory.OWNERS_CHOICE[pkg] ?: return what
            return if (what.isEmpty()) cost else "$what. $cost"
        }

        // RFC-RAM-1: a running app's row says "about X MB in RAM" when we measured it.
        // RSS over-counts shared pages, so the number is approximate by design and says so.
        fun ramNote(pkg: PackageName, base: String, ramByPackage: Map<PackageName, Int>): String {
            val kb = ramByPackage[pkg] ?: return base
            val note = "about ${kb / 1024} MB in RAM"
            return if (base.isEmpty()) note.replaceFirstChar { it.titlecase() } else "$base · $note"
        }
        // An uninstall's line on the progress screen. The old home screen app (an earlier build's
        // install, removed by Undo) has no label on the TV, so AppNames would make its name from
        // the package ("Gathin Home", QA 0.4.5-beta1).
        fun removing(pkg: PackageName): String = if (pkg == launcher.pkg) NARRATION_REMOVE_LAUNCHER else "Removing ${name(pkg)} from the TV"
        fun state(pkg: PackageName): PackageState? = snapshot.packages[pkg]
        fun isOff(pkg: PackageName): Boolean = state(pkg)?.enabled.let { it == EnabledState.DISABLED_USER || it == EnabledState.DISABLED }
        fun installed(pkg: PackageName): Boolean = pkg in snapshot.packages

        // The one place a step is made: render, or record the refusal as a reason.
        fun step(op: Op, prior: Prior?, group: String?, narration: String, policy: FailurePolicy): Int {
            val rendered: Rendered = try {
                renderer.render(op)
            } catch (e: RenderRefused) {
                reasons += "cannot render ${op::class.simpleName}: ${e.reason}"
                return -1
            }
            steps += Step(steps.size, op, rendered, prior, group, narration, policy)
            return steps.size - 1
        }

        fun refuse(reason: String) { reasons += reason }
        fun planned(op: Op): Boolean = steps.any { it.op == op }
        fun putBackPlanned(pkg: PackageName): Boolean = planned(Op.Enable(pkg)) || planned(Op.DefaultState(pkg)) || planned(Op.InstallExisting(pkg))

        // WP4 (C6, G4): a package the static denylist protects now (an earlier build switched it off,
        // like TCL G10's remote mic service) only ever takes the write towards on. The renderer
        // refuses a DefaultState for it, so one that is off goes back on with Enable, and one that is
        // on needs nothing; an inverse that would switch it off is never planned. Null: no step.
        fun protectedInverse(pkg: PackageName, inverse: Op): Op? {
            if (Denylist.staticReason(pkg.value) == null) return inverse
            return when (inverse) {
                is Op.DefaultState -> if (isOff(pkg)) Op.Enable(pkg) else null
                is Op.Disable -> null
                else -> inverse
            }
        }

        // WP4 (G4): a journal from before 0.4.5 may hold the chooser or another placeholder as the
        // SetHome prior on a TV whose HOME was undecided. The home screen to go back to is then the
        // stock home the switch armed its watchdog with, else the TV's stock home claimant, else a
        // stock launcher of the exact profile (never a brand set's or the generic KnownLaunchers) that
        // is installed and either claims HOME or has a Disable record of ours: a launcher the factory
        // switched off is never handed HOME.
        fun homeToRestore(recorded: Component?, records: List<ChangeRecord>): Component? {
            if (recorded == null || !StockHome.isPlaceholder(recorded)) return recorded
            val all = journals + records
            fun claims(pkg: PackageName) = snapshot.home.claimants.any { it.component.pkg == pkg }
            fun ourDisable(pkg: PackageName) = all.any { (it.op as? Op.Disable)?.pkg == pkg }
            return HomePlaceholder.stockFromRecords(records) ?: StockHome.of(snapshot, managedStockLaunchers)
                ?: managedProfile?.stockLaunchers.orEmpty().firstOrNull { installed(it.pkg) && (claims(it.pkg) || ourDisable(it.pkg)) }
        }

        // The rule the engine applies again with live facts (SwitchOffRule, 0.3.3 issue #10), outside
        // the home screen switch, plus this build's own choice: keepUpdatesOn keeps the updaters.
        fun protectedReason(pkg: PackageName, profile: Profile?, stockLaunchers: List<Component>, groups: List<PackageGroup> = profile?.groups.orEmpty()): String? {
            SwitchOffRule.reason(pkg, state(pkg), profile, snapshot, stockLaunchers)?.let { return it }
            if (keptUpdater(pkg, groups)) return REASON_UPDATES
            return null
        }

        // beta4 (PatchWallFeeds): never PatchWall's feeds off while PatchWall stays on. While PatchWall
        // is on and the plan keeps it on (`patchWallGoesOff` false), a feed is kept on with this
        // reason; with PatchWall switched off by the same plan, or off already, its feeds may go off too.
        fun patchWallFeedKept(pkg: PackageName, patchWallGoesOff: Boolean): Boolean =
            pkg in PATCHWALL_FEEDS && PatchWallFeeds.staysOn(snapshot, patchWallGoesOff)

        // keepUpdatesOn: a software updater by its name (D22) or by its profile group.
        fun keptUpdater(pkg: PackageName, groups: List<PackageGroup>): Boolean = keepUpdatesOn && ProfileCategory.isUpdater(pkg, groups)

        fun disable(pkg: PackageName, group: String?, policy: FailurePolicy = FailurePolicy.SKIP_IF_REFUSED, narration: String = "Switching off ${name(pkg)}") =
            step(Op.Disable(pkg), Prior(packageState = state(pkg)?.enabled), group, narration, policy)

        // RFC-UNINSTALL-1: the planner's uninstall. Prior.present == true: the app was on the
        // TV before us, so Undo everything can put it back with Op.InstallExisting.
        fun uninstall(pkg: PackageName, group: String?, policy: FailurePolicy = FailurePolicy.SKIP_IF_REFUSED, narration: String = "Uninstalling ${name(pkg)}") =
            step(Op.Uninstall(pkg), Prior(packageState = state(pkg)?.enabled, present = true), group, narration, policy)

        // Bloat the plan switches off is permanently uninstalled, never only disabled — but only
        // a system app: `pm install-existing` brings a system app back, a deleted user-installed
        // app is gone for good, so that one is only ever disabled.
        fun switchOff(pkg: PackageName, group: String?, uninstallBloat: Boolean, policy: FailurePolicy = FailurePolicy.SKIP_IF_REFUSED) {
            if (uninstallBloat && snapshot.packages[pkg]?.system == true) {
                step(Op.ClearPackage(pkg), null, group, "Clearing cache for ${name(pkg)}", FailurePolicy.CONTINUE)
                uninstall(pkg, group, policy)
            } else {
                disable(pkg, group, policy)
            }
        }

        fun enable(pkg: PackageName, group: String?, narration: String = "Switching ${name(pkg)} back on", policy: FailurePolicy = FailurePolicy.STOP_AND_ROLLBACK) =
            step(Op.Enable(pkg), Prior(packageState = state(pkg)?.enabled), group, narration, policy)

        // ---- Setup -------------------------------------------------------------------------------

        fun setup(req: PlanRequest.Setup) {
            val profile = (req.resolution as? ProfileResolution.Exact)?.profile
            val rawPackageGroups: List<PackageGroup> = when (val r = req.resolution) {
                is ProfileResolution.Exact -> r.profile.groups
                is ProfileResolution.BrandSureSet -> r.sureSet
                is ProfileResolution.None -> emptyList()
            }
            val stockLaunchers: List<Component> = when (val r = req.resolution) {
                is ProfileResolution.Exact -> r.profile.stockLaunchers
                is ProfileResolution.BrandSureSet -> r.stockLaunchers
                is ProfileResolution.None -> r.stockLaunchers
            }
            val choices = req.choices
            // REA default on (2026-11-04): when autoDebloat/cleanStockHome is set, REA runs even
            // when the TV has a profile — its groups join the profile's, profile rows first, so the
            // profile wins a package both know (the `covered` set skips the REA row for it).
            val packageGroups: List<PackageGroup> = rawPackageGroups + if (choices.autoDebloat || choices.cleanStockHome) {
                val candidates = ReaEngine.findDebloatCandidates(snapshot, facts, stockLaunchers, ramByPackage = req.ramByPackage)
                ReaEngine.synthesizeReaGroups(candidates)
            } else {
                emptyList()
            }
            val covered = mutableSetOf<PackageName>()
            var disabled = 0
            fun wanted(pkg: PackageName, default: GroupDefault): Boolean = when {
                pkg in choices.kept -> false
                // RFC-SHOP-1: ad and Shop tab providers (like com.google.android.videos) are always
                // debloated when autoDebloat or cleanStockHome is on, even if a community profile
                // put them into an "ask" category.
                (choices.autoDebloat || choices.cleanStockHome) && pkg in ReaEngine.STOCK_AD_AND_SHOP_PACKAGES -> true
                default == GroupDefault.DISABLE -> true
                default == GroupDefault.ASK || default == GroupDefault.KEEP -> pkg in choices.extraOff
                else -> false
            }
            // Whether this plan switches PatchWall off: its first row decides, as in the loop below,
            // else the Custom list.
            val patchWallGoesOff = protectedReason(PATCHWALL, profile, stockLaunchers, packageGroups) == null &&
                PatchWallFeeds.goesOff(snapshot, packageGroups, choices.kept, choices.extraOff)
            fun feedReason(pkg: PackageName): String? = if (patchWallFeedKept(pkg, patchWallGoesOff)) REASON_PATCHWALL_FEED else null

            for (group in packageGroups) {
                val rows = mutableListOf<RowSummary>()
                for (entry in group.packages) {
                    val pkg = entry.name
                    if (!installed(pkg) || pkg in covered) continue
                    covered += pkg
                    val why = protectedReason(pkg, profile, stockLaunchers, packageGroups) ?: feedReason(pkg)
                    val wanted = wanted(pkg, group.default)
                    // Already asleep: nothing to switch off and nothing to keep, so the row would
                    // only make S3 count what the plan never does (emulator run, round 3). The same
                    // for an updater keepUpdatesOn protects that is off already (an earlier full
                    // build switched it off): "We keep these" would be untrue, and Manage's Switched
                    // off still lists it with Put back (RF-STORES-030 S1).
                    // The same for a PatchWall feed that is off already: the plan leaves it as it is.
                    if ((why == null || why == REASON_UPDATES || why == REASON_PATCHWALL_FEED) && isOff(pkg)) continue
                    val off = why == null && wanted
                    rows += RowSummary(pkg, name(pkg), ramNote(pkg, description(pkg), req.ramByPackage), off, why != null, why)
                    if (off) { switchOff(pkg, group.id, choices.uninstallBloat); disabled++ }
                }
                groups += GroupSummary(group.id, group.title, rows.count { it.off }, rows.size, rows)
            }
            // Custom: packages switched off outside the groups.
            val custom = mutableListOf<RowSummary>()
            for (pkg in choices.extraOff.sortedBy { it.value }) {
                if (pkg in covered || !installed(pkg) || pkg in choices.kept) continue
                if (keptUpdater(pkg, packageGroups)) continue   // keepUpdatesOn: a choice an earlier full build kept
                val why = protectedReason(pkg, profile, stockLaunchers, packageGroups) ?: feedReason(pkg)
                if (why != null) { refuse("${name(pkg)} cannot be switched off: $why"); continue }
                if (isOff(pkg)) continue
                custom += RowSummary(pkg, name(pkg), ramNote(pkg, description(pkg), req.ramByPackage), off = true, protected = false, whyProtected = null)
                switchOff(pkg, GROUP_CUSTOM, choices.uninstallBloat); disabled++
            }
            if (custom.isNotEmpty()) groups += GroupSummary(GROUP_CUSTOM, "Also switched off", custom.size, custom.size, custom)
            // beta4 (issue #945, review of beta4): a 0.4.5 Setup on the Xiaomi brand set switched Mi TV+
            // and the web content off with PatchWall kept on. The feeds this app switched off come
            // back on first; a feed off for another reason stays off.
            val back = PatchWallFeeds.off(snapshot, patchWallGoesOff).filter { PatchWallFeeds.ours(journals, it) }
            if (back.isNotEmpty()) {
                val rows = back.map { RowSummary(it, name(it), description(it), off = false, protected = true, whyProtected = REASON_PATCHWALL_FEED_BACK) }
                for (pkg in back) { enable(pkg, GROUP_PATCHWALL_FEEDS, "Switching ${name(pkg)} back on: PatchWall needs it"); disabled++ }
                if (rows.isNotEmpty()) groups += GroupSummary(GROUP_PATCHWALL_FEEDS, TITLE_PATCHWALL_FEEDS, 0, rows.size, rows)
            }
            keepNames = choices.kept.filter { installed(it) }.map { name(it) }.sorted()

            if (choices.cleanStockHome && ReaEngine.isStockGoogleTvLauncher(snapshot)) {
                cleanStockHome(stockLaunchers, choices.kept, req.ramByPackage)
            }
        }

        fun cleanStockHome(stockLaunchers: List<Component>, kept: Set<PackageName> = emptySet(), ramByPackage: Map<PackageName, Int> = emptyMap()) {
            val stockRows = mutableListOf<RowSummary>()

            // 0. Ensure Google Play Store and external storage stay installed, enabled and safe
            val vending = PackageName("com.android.vending")
            val extStorage = PackageName("com.android.externalstorage")
            step(Op.InstallExisting(vending), Prior(present = false), GROUP_STOCK_CLEAN, "Ensuring Google Play Store is installed", FailurePolicy.CONTINUE)
            step(Op.Enable(vending), Prior(packageState = state(vending)?.enabled), GROUP_STOCK_CLEAN, "Ensuring Google Play Store is enabled", FailurePolicy.CONTINUE)
            step(Op.Enable(extStorage), Prior(packageState = state(extStorage)?.enabled), GROUP_STOCK_CLEAN, "Ensuring external storage is enabled", FailurePolicy.CONTINUE)

            // 1. Permanently uninstall every ad, recommendation, and Shop tab provider.
            //    RFC-SHOP-1: com.google.android.videos (Google Play Movies & TV) is the direct
            //    provider of the "Shop" tab in com.google.android.tvlauncher and com.google.android.apps.tv.launcherx.
            //    Unless it is uninstalled via pm uninstall -k --user 0, the stock launcher queries it on reboot
            //    and restores the Shop tab! All packages in STOCK_AD_AND_SHOP_PACKAGES that exist on this TV
            //    (plus core Google ad/shop providers) are permanently uninstalled here.
            for (pkg in ReaEngine.STOCK_AD_AND_SHOP_PACKAGES) {
                if (pkg in kept) continue
                val isCore = pkg == ReaEngine.STOCK_TV_RECOMMENDATIONS || pkg == ReaEngine.GOOGLE_PLAY_MOVIES
                val isPresent = installed(pkg)
                if (!isPresent && !isCore) continue

                if (isPresent) {
                    stockRows += RowSummary(pkg, name(pkg), ramNote(pkg, description(pkg), ramByPackage), off = true, protected = false, whyProtected = null)
                    step(Op.ClearPackage(pkg), null, GROUP_STOCK_CLEAN, "Clearing ${name(pkg)} cache", FailurePolicy.CONTINUE)
                    uninstall(pkg, GROUP_STOCK_CLEAN)
                } else if (isCore) {
                    // Core package not reported currently installed: ensure it is cleared and uninstalled so it cannot revive on reboot.
                    step(Op.ClearPackage(pkg), null, GROUP_STOCK_CLEAN, "Clearing ${name(pkg)} cache", FailurePolicy.CONTINUE)
                    step(Op.Uninstall(pkg), Prior(present = true), GROUP_STOCK_CLEAN, "Uninstalling ${name(pkg)}", FailurePolicy.CONTINUE)
                }
            }

            if (stockRows.isNotEmpty()) {
                groups += GroupSummary(GROUP_STOCK_CLEAN, TITLE_STOCK_CLEAN, stockRows.size, stockRows.size, stockRows)
            }

            // 2. Clear pending ad notifications
            step(Op.DeleteContent("content://com.google.android.tvrecommendations.NotificationContentProvider/notifications"), null, GROUP_STOCK_CLEAN, "Clearing pending ad notifications", FailurePolicy.CONTINUE)

            // 3. Block every known ad/shop setting so the Shop tab, Discover row and video ads stay gone permanently
            step(Op.SettingsPut(SettingKey.FREEPLAY_ENABLED, "0"), Prior(settingValue = snapshot.settings[SettingKey.FREEPLAY_ENABLED]), GROUP_STOCK_CLEAN, "Permanent block: auto-playing video ads", FailurePolicy.CONTINUE)
            step(Op.SettingsPut(SettingKey.LIMIT_AD_TRACKING, "1"), Prior(settingValue = snapshot.settings[SettingKey.LIMIT_AD_TRACKING]), GROUP_STOCK_CLEAN, "Permanent block: background ad tracking", FailurePolicy.CONTINUE)
            step(Op.SettingsPut(SettingKey.PERSONALIZED_ADS, "0"), Prior(settingValue = snapshot.settings[SettingKey.PERSONALIZED_ADS]), GROUP_STOCK_CLEAN, "Permanent block: personalized ad targeting", FailurePolicy.CONTINUE)
            step(Op.SettingsPut(SettingKey.DISCOVER_ENABLED, "0"), Prior(settingValue = snapshot.settings[SettingKey.DISCOVER_ENABLED]), GROUP_STOCK_CLEAN, "Permanent block: Discover & Shop tab injection", FailurePolicy.CONTINUE)
            step(Op.SettingsPut(SettingKey.SPONSORED_CONTENT, "0"), Prior(settingValue = snapshot.settings[SettingKey.SPONSORED_CONTENT]), GROUP_STOCK_CLEAN, "Permanent block: sponsored promotional channels", FailurePolicy.CONTINUE)

            // Speed up TV performance by disabling UI animation lag
            step(Op.SettingsPut(SettingKey.WINDOW_ANIMATION_SCALE, "0"), Prior(settingValue = snapshot.settings[SettingKey.WINDOW_ANIMATION_SCALE]), GROUP_STOCK_CLEAN, "Speeding up window animations", FailurePolicy.CONTINUE)
            step(Op.SettingsPut(SettingKey.TRANSITION_ANIMATION_SCALE, "0"), Prior(settingValue = snapshot.settings[SettingKey.TRANSITION_ANIMATION_SCALE]), GROUP_STOCK_CLEAN, "Speeding up transition animations", FailurePolicy.CONTINUE)
            step(Op.SettingsPut(SettingKey.ANIMATOR_DURATION_SCALE, "0"), Prior(settingValue = snapshot.settings[SettingKey.ANIMATOR_DURATION_SCALE]), GROUP_STOCK_CLEAN, "Speeding up animator duration", FailurePolicy.CONTINUE)

            // 4. CRITICAL: Clear launcher's cached ads and Shop/Discover tabs from its SQLite database,
            //    then restart it so the clean layout takes effect immediately.
            val stock = ReaEngine.resolveStockLauncherComponent(snapshot, stockLaunchers)
            step(Op.ClearPackage(stock.pkg), null, GROUP_STOCK_CLEAN, "Clearing launcher cached ads and Shop tab", FailurePolicy.CONTINUE)
            step(Op.RestartHome(stock), null, GROUP_STOCK_CLEAN, "Restarting clean stock home screen", FailurePolicy.CONTINUE)
        }

        // D39 ladder: streaming install when the TV advertises `cmd`, else push to tmp and pm install.
        // Used only by modules now: the launcher and the curtain are gone.
        fun installApk(blob: BlobRef, sha256: Sha256, pkg: PackageName, versionCode: Long, signer: Sha256, present: Boolean, group: String, narration: String, quiet: Boolean = false) {
            val prior = Prior(present = present)
            fun helper(text: String) = if (quiet) "" else text
            if ("cmd" in snapshot.facts.adbFeatures) {
                step(Op.InstallStream(blob, sha256, pkg, versionCode, signer), prior, group, narration, FailurePolicy.CONTINUE)
            } else {
                val apk = RemotePath(RemoteDir.TMP, listOf(sha256.hex.take(APK_NAME_HEX) + ".apk"))   // a FileName is at most 64 characters
                step(Op.Mkdir(RemoteDir.TMP), null, group, helper("Preparing the TV"), FailurePolicy.CONTINUE)
                step(Op.PushAsset(blob, apk, sha256), Prior(present = false), group, helper("Copying the app to the TV"), FailurePolicy.CONTINUE)
                val flags = if (present) setOf(InstallFlag.REPLACE) else emptySet()
                step(Op.Install(apk, sha256, pkg, versionCode, signer, flags), prior, group, narration, FailurePolicy.CONTINUE)
                step(Op.RemoveFile(apk), null, group, helper("Tidying up"), FailurePolicy.CONTINUE)
            }
        }

        // ---- Manage ------------------------------------------------------------------------------

        fun putBack(pkg: PackageName) {
            if (!installed(pkg)) {
                // RFC-UNINSTALL-1: a system app we uninstalled comes back with
                // `pm install-existing`; anything else that is not on the TV is not ours.
                if (journals.any { (it.op as? Op.Uninstall)?.pkg == pkg && it.prior?.present == true }) {
                    step(Op.InstallExisting(pkg), Prior(present = false), GROUP_PUT_BACK, "Putting back ${name(pkg)}", FailurePolicy.STOP_AND_ROLLBACK)
                    return
                }
                refuse("${pkg.value} is not on this TV"); return
            }
            if (!isOff(pkg)) return   // already on
            // The wizard quirk (R1 R-3): on a TV whose wizard takes the Home button back (issue #49),
            // the wizard comes back on only with the TV's own home screen, never under the new one.
            val profile = managedProfile
            if (profile != null && Denylist.isWizardAllowed(pkg, profile) && snapshot.home.resolved == launcher) {
                refuse("${name(pkg)} cannot be switched back on while the new home screen is in charge: $REASON_WIZARD_WHILE_OURS"); return
            }
            // beta4 (PatchWallFeeds): PatchWall comes back on only with its feeds, which go first.
            if (pkg == PATCHWALL) for (feed in PATCHWALL_FEEDS.sortedBy { it.value }) {
                if (!installed(feed) || !isOff(feed)) continue
                if (!putBackStep(feed, "Switching ${name(feed)} back on: PatchWall needs it")) {
                    refuse("${name(pkg)} cannot be switched back on while ${name(feed)} stays off: $REASON_PATCHWALL_FEED_BACK"); return
                }
            }
            putBackStep(pkg, "Switching ${name(pkg)} back on")
        }

        // The step that switches a package back on: the inverse of this app's last Disable of it, else
        // Enable, through protectedInverse. False when there is none to make.
        fun putBackStep(pkg: PackageName, narration: String): Boolean {
            val change = journals.filter { it.op is Op.Disable && (it.op as Op.Disable).pkg == pkg }.maxByOrNull { it.atMs }
            val inverse = change?.let { renderer.inverse(it.op, it.prior ?: Prior()) } ?: Op.Enable(pkg)
            val op = protectedInverse(pkg, inverse) ?: return false
            return step(op, Prior(packageState = state(pkg)?.enabled), GROUP_PUT_BACK, narration, FailurePolicy.STOP_AND_ROLLBACK) >= 0
        }

        // 0.4.6: a package that may answer Home once it is on again (a switched off package is no
        // claimant, so the snapshot cannot say): Google's and Amazon's TV home screens, the stock
        // launchers of this TV's resolution, the TV's own home the switch armed its watchdog with, a
        // vendor home, or a claimant the TV still lists. Issue #1944: or a factory test app that
        // answers Home (HomePlaceholder.isFactoryHome), which ties with the new home screen.
        fun mayClaimHome(pkg: PackageName): Boolean =
            pkg in KnownLaunchers.byPackage || managedStockLaunchers.any { it.pkg == pkg } || HomePlaceholder.stockFromRecords(journals)?.pkg == pkg ||
                pkg.value in Denylist.vendorHomes || snapshot.home.claimants.any { it.component.pkg == pkg } || HomePlaceholder.isFactoryHome(pkg)

        fun switchOff(pkgs: Set<PackageName>) {
            val rows = mutableListOf<RowSummary>()
            // beta4: a PatchWall feed only with PatchWall in the same request (patchWallFeedKept).
            val patchWallGoesOff = PATCHWALL in pkgs && protectedReason(PATCHWALL, managedProfile, managedStockLaunchers) == null
            for (pkg in pkgs.sortedBy { it.value }) {
                if (!installed(pkg)) { refuse("${pkg.value} is not on this TV"); continue }
                val why = protectedReason(pkg, managedProfile, managedStockLaunchers)   // R1 R-3: never_disable and the wizard quirk
                    ?: REASON_PATCHWALL_FEED.takeIf { patchWallFeedKept(pkg, patchWallGoesOff) }
                if (why != null) { refuse("${name(pkg)} cannot be switched off: $why"); continue }
                rows += RowSummary(pkg, name(pkg), description(pkg), off = true, protected = false, whyProtected = null)
                if (!isOff(pkg)) disable(pkg, GROUP_SWITCH_OFF)
            }
            if (rows.isNotEmpty()) groups += GroupSummary(GROUP_SWITCH_OFF, "Switched off", rows.size, rows.size, rows)
        }

        fun leftovers(expectedDisabled: Set<PackageName>) {
            // beta4: a PatchWall feed that came back on stays on while PatchWall is on and stays on.
            val patchWallGoesOff = PATCHWALL in expectedDisabled && protectedReason(PATCHWALL, managedProfile, managedStockLaunchers) == null
            for (pkg in expectedDisabled.sortedBy { it.value }) {
                if (!installed(pkg) || isOff(pkg)) continue
                if (keptUpdater(pkg, managedProfile?.groups.orEmpty())) continue   // keepUpdatesOn: an updater that came back on stays on
                if (patchWallFeedKept(pkg, patchWallGoesOff)) continue
                // After a TV update the stock launcher may be enabled and HOME again (R1): a leftover
                // that is now a home screen is never switched off outside the home screen switch.
                val why = protectedReason(pkg, managedProfile, managedStockLaunchers)   // R1 R-3: never_disable and the wizard quirk
                if (why != null) { refuse("${name(pkg)} cannot be switched off: $why"); continue }
                disable(pkg, GROUP_LEFTOVERS)
            }
        }

        fun addModule(req: PlanRequest.AddModule) {
            val module = req.module; val version = req.version; val apk = req.apk
            if (module.tier != Tier.VERIFIED && module.tier != Tier.COMMUNITY) { refuse("${module.name} is ${module.tier.name.lowercase()} in the index"); return }
            if (apk.sha256 != version.artifact.sha256 || apk.size != version.artifact.size) { refuse("the downloaded file does not match the index entry"); return }
            if (version.minSdk > snapshot.facts.sdk) { refuse("${module.name} needs Android ${version.minSdk}+ and this TV has ${snapshot.facts.sdk}") ; return }
            if (version.abis.isNotEmpty() && version.abis.none { it in snapshot.facts.abis }) { refuse("${module.name} has no build for this TV's processor"); return }
            if (snapshot.facts.installRefusal) { refuse("this TV refuses app installs"); return }
            val current = state(module.pkg)
            if (current != null && current.signer != null && current.signer != version.artifact.signer) { refuse("${module.name} is already on the TV, installed with another key"); return }
            if (current != null && current.versionCode >= version.versionCode) return   // already there
            // postInstall is parsed and ignored in 0.1 (R0: no VERIFIED module exists yet).
            installApk(apk, version.artifact.sha256, module.pkg, version.versionCode, version.artifact.signer,
                present = current != null, group = GROUP_MODULE, narration = "Adding ${module.name} to the TV")
            step(Op.PressHome, null, GROUP_MODULE, "Showing the home screen", FailurePolicy.CONTINUE)
        }

        fun removeModule(pkg: PackageName) {
            val current = state(pkg) ?: return   // already gone
            if (current.system) { refuse("${name(pkg)} is part of the TV's system and cannot be removed"); return }
            val ours = current.installedByUs || journals.any { (it.op as? Op.Install)?.pkg == pkg || (it.op as? Op.InstallStream)?.pkg == pkg }
            if (!ours) { refuse("${name(pkg)} was not installed by this app"); return }
            if (pkg == launcher.pkg && snapshot.home.resolved == launcher) { refuse("use the TV's own home screen first, then remove the new one"); return }
            step(Op.Uninstall(pkg), Prior(present = true), GROUP_MODULE, removing(pkg), FailurePolicy.STOP_AND_ROLLBACK)
        }

        // Also the core of UndoEverything and BeforeFactoryReset: the TV's own home screen back.
        // `records`: change records besides the journals that may name it (Undo everything's own).
        // WP4 (G4): the stock home the switch armed its watchdog with, and an installed stock
        // launcher of this TV's exact profile (not a brand set's, not the generic KnownLaunchers), are
        // home screens this TV had too (a placeholder prior is mapped to one of them by homeToRestore).
        fun useStockHome(stock: Component, wizardFromJournal: PackageName?, records: List<ChangeRecord> = emptyList()) {
            if (stock == launcher) { refuse("that is the new home screen, not the TV's own"); return }
            val all = journals + records
            val known = snapshot.home.claimants.any { it.component == stock } ||
                all.any { it.op is Op.SetHome && it.prior?.home == stock } ||
                all.any { (it.op as? Op.ArmHomeWatchdog)?.stock == stock } ||
                (stock in managedProfile?.stockLaunchers.orEmpty() && installed(stock.pkg))
            if (!known) { refuse("${stock.flat} is not a home screen this TV had"); return }
            // A package the undo already puts back (Enable, or DefaultState for an original DEFAULT)
            // is not enabled a second time: that would leave it ENABLED instead of DEFAULT (R1 R-10).
            if (isOff(stock.pkg) && !putBackPlanned(stock.pkg)) enable(stock.pkg, GROUP_HOME, "Switching the TV's own home screen back on")
            val wizard = wizardFromJournal ?: journals.firstOrNull { it.op is Op.Disable && (it.op as Op.Disable).pkg.value in Denylist.setupWizards }?.let { (it.op as Op.Disable).pkg }
            if (wizard != null && installed(wizard) && isOff(wizard) && !putBackPlanned(wizard)) enable(wizard, GROUP_HOME, "Switching the setup wizard back on")
            if (snapshot.home.resolved != stock) {
                step(Op.SetHome(stock), Prior(home = snapshot.home.resolved), GROUP_HOME, "Making the TV's own home screen the default", FailurePolicy.STOP_AND_ROLLBACK)
                step(Op.VerifyHome(stock), null, GROUP_HOME, "Checking the home screen", FailurePolicy.STOP_AND_ROLLBACK)
                step(Op.PressHome, null, GROUP_HOME, "Showing the home screen", FailurePolicy.CONTINUE)
            }
        }

        // Computed from the change records (every change this app made), never from "enable
        // everything disabled". UndoOrder (shared with the laptop restore scripts) picks the
        // earliest record of each item, which holds the state before this app touched it, and
        // orders them: package states, settings, the home screen, our apps, our files.
        fun undoEverything(changes: List<ChangeRecord>, removeLauncher: Boolean) {
            val order = UndoOrder.undoOrder(changes)
            val originalHome: Component? = homeToRestore(order.firstOrNull { it.op is Op.SetHome }?.prior?.home, changes + journals)
            val ourApps = order.filter { UndoOrder.target(it.op) is UndoOrder.Target.App && it.prior?.present == false }
                .mapNotNull { packageOf(it.op) }.toSet()
            var clearedLook = false
            for (c in order) {
                when (val t = UndoOrder.target(c.op)) {
                    // Undo-only helper removal: the helper itself is gone with the launcher
                    // removal, but a stored 0.4.7 plan may still have switched it on, so
                    // Undo everything still switches it back off (was helperOut in HEAD).
                    is UndoOrder.Target.Helper -> {
                        val list = accList
                        if (c.op !is Op.AccessibilityAdd || !HelperLines.listed(list) || list == null) continue
                        if (!SettingKey.ENABLED_ACCESSIBILITY.valueShape.matches(list)) continue
                        step(Op.AccessibilityRemove(list, HelperLines.SERVICE), Prior(settingValue = list), GROUP_UNDO, "Switching off the Home button helper", FailurePolicy.CONTINUE)
                        accList = HelperLines.without(list)
                        val switchedOnByUs = (changes + journals).any { it.op == Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "1") && it.prior?.settingValue != "1" } &&
                            (changes + journals).any { it.op is Op.AccessibilityAdd }
                        if (accList == null && accOn == "1" && switchedOnByUs) {
                            step(Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "0"), Prior(settingValue = "1"), GROUP_UNDO, "Switching accessibility back off", FailurePolicy.CONTINUE)
                            accOn = "0"
                        }
                    }
                    is UndoOrder.Target.PackageEnabled -> {
                        val current = state(t.pkg) ?: continue
                        val prior = c.prior ?: continue
                        val original = prior.packageState ?: continue
                        if (current.enabled == original) continue
                        val inverse = renderer.inverse(c.op, prior)?.let { protectedInverse(t.pkg, it) } ?: continue
                        step(inverse, Prior(packageState = current.enabled), GROUP_UNDO, undoNarration(inverse, t.pkg), FailurePolicy.CONTINUE)
                    }
                    is UndoOrder.Target.Setting -> {
                        val original = c.prior?.settingValue
                        val current = snapshot.settings[t.key]
                        if (t.key !in snapshot.settings || current == original) continue
                        if (t.key == SettingKey.ADB_ENABLED || t.key == SettingKey.ADB_WIFI_ENABLED) continue   // never switched back on by the app
                        // WP4 (G4, RF-LAUNCHER-240 F7): nor is accessibility. The helper put back journals
                        // accessibility_enabled 1 and the service list as priors; after the person switched
                        // accessibility off or changed the list, writing them back would switch a service
                        // on again. So an undo writes neither "1" nor a non-empty list: every accessibility
                        // write is null, "0" or what the TV has right now (AccessibilityPutBackPropertyTest).
                        if (t.key == SettingKey.ACCESSIBILITY_ENABLED && original == "1") continue
                        if (t.key == SettingKey.ENABLED_ACCESSIBILITY && !original.isNullOrEmpty()) continue
                        // Accessibility the plan left on goes back off only with nothing else left in
                        // the list; a non-empty list is never written back.
                        if (t.key == SettingKey.ACCESSIBILITY_ENABLED && ((accList?.split(':').orEmpty().any { it.isNotEmpty() } == true) || accOn == "0")) continue
                        step(Op.SettingsPut(t.key, original), Prior(settingValue = current), GROUP_UNDO, "Putting back the TV's ${t.key.key.replace('_', ' ')} setting", FailurePolicy.CONTINUE)
                    }
                    UndoOrder.Target.Home -> if (originalHome != null && originalHome != launcher) useStockHome(originalHome, null, changes)
                    is UndoOrder.Target.App -> {
                        val pkg = t.pkg
                        if (c.op is Op.Uninstall) {
                            // RFC-UNINSTALL-1: we uninstalled bloat the TV had — put it back.
                            // `pm install-existing` restores a system app the shell removed; the
                            // planner never uninstalls a user-installed app, so there is always
                            // something to bring back.
                            if (c.prior?.present == true && !installed(pkg)) {
                                step(Op.InstallExisting(pkg), Prior(present = false), GROUP_UNDO, "Putting back ${name(pkg)}", FailurePolicy.CONTINUE)
                            }
                            continue
                        }
                        if (pkg !in ourApps || !installed(pkg)) continue
                        if (pkg == launcher.pkg && !removeLauncher) continue
                        if (pkg == launcher.pkg && snapshot.home.resolved == launcher && (originalHome == null || originalHome == launcher)) {
                            refuse("the new home screen is still in charge; the TV's own home screen must come back first"); continue
                        }
                        val removed = step(Op.Uninstall(pkg), Prior(present = true), GROUP_UNDO, removing(pkg), FailurePolicy.CONTINUE)
                        if (pkg == launcher.pkg && removed >= 0) tidyWatchdogFiles(changes + journals)
                    }
                    is UndoOrder.Target.File -> {
                        // Only files in the launcher's folder that did not exist before us; a file that
                        // existed cannot be rebuilt in 0.1, and nothing is left to tidy when the launcher goes.
                        // The earliest record says whether it existed (a push, or the Setup's wallpaper
                        // removal on a fresh launcher, RFC-DOCK-1); it is on the TV now only when our
                        // last record of it is a push.
                        val launcherStays = installed(launcher.pkg) && !(removeLauncher && launcher.pkg in ourApps)
                        if (!launcherStays || t.path.dir != RemoteDir.OVERLAY || c.prior?.present != false) continue
                        val last = changes.filter { UndoOrder.target(it.op) == t }.maxWithOrNull(UndoOrder.CHRONOLOGICAL)
                        val pushed = last?.op as? Op.PushAsset ?: continue
                        // RFC-THEME-1: a file the provider wrote is the launcher's, so the provider removes it too.
                        step(Op.RemoveFile(t.path, pushed.route), null, GROUP_UNDO, "Clearing the look", FailurePolicy.CONTINUE)
                        clearedLook = true
                    }
                    null -> {}
                }
            }
            if (clearedLook) {
                step(Op.RestartHome(launcher), null, GROUP_UNDO, "Restarting the home screen", FailurePolicy.CONTINUE)
            }
        }

        // QA 0.4.5-beta1: the home switch leaves two small files of ours in /data/local/tmp/cleanyourtv,
        // the token file that defused the watchdog and the watchdog's log (empty unless it fired).
        // When the undo removes the new home screen they have no use left, so they go with it: one
        // RemoveFile (`rm -f` of that one file, nothing broader) per file name the watchdog template
        // writes, for the run tokens of our ArmHomeWatchdog and ConfirmHome records, skipping a file
        // an earlier undo already removed. The folder itself stays (there is no op that removes one).
        fun tidyWatchdogFiles(records: List<ChangeRecord>) {
            val tokens = records.sortedWith(UndoOrder.CHRONOLOGICAL).mapNotNull { c ->
                when (val op = c.op) { is Op.ArmHomeWatchdog -> op.token; is Op.ArmHelperWatchdog -> op.token; is Op.ConfirmHome -> op.token; else -> null }
            }.distinct()
            val gone = records.mapNotNull { (it.op as? Op.RemoveFile)?.path }.toSet()
            var first = true
            for (token in tokens) {
                for (file in listOf(Watchdog.tokenFileName(token), Watchdog.logFileName(token))) {
                    val path = RemotePath(RemoteDir.TMP, listOf(file))
                    if (path in gone) continue
                    if (step(Op.RemoveFile(path), null, GROUP_UNDO, if (first) "Tidying up" else "", FailurePolicy.CONTINUE) >= 0) first = false
                }
            }
        }

        fun beforeFactoryReset() {
            val originalHome = homeToRestore(journals.filter { it.op is Op.SetHome }.minByOrNull { it.atMs }?.prior?.home, journals)
            val wizard = journals.firstOrNull { it.op is Op.Disable && (it.op as Op.Disable).pkg.value in Denylist.setupWizards }?.let { (it.op as Op.Disable).pkg }
            if (originalHome != null && originalHome != launcher) {
                useStockHome(originalHome, wizard)
            } else if (wizard != null && installed(wizard) && isOff(wizard)) {
                enable(wizard, GROUP_HOME, "Switching the setup wizard back on")
            }
        }

        fun debugOff() {
            if (snapshot.facts.sdk >= 30) {
                step(Op.SettingsPut(SettingKey.ADB_WIFI_ENABLED, "0"), Prior(settingValue = snapshot.settings[SettingKey.ADB_WIFI_ENABLED]),
                    GROUP_SESSION_END, "Switching wireless debugging off", FailurePolicy.CONTINUE)
            }
            step(Op.DebugOff, Prior(settingValue = snapshot.settings[SettingKey.ADB_ENABLED]), GROUP_SESSION_END, "Switching debugging off", FailurePolicy.CONTINUE)
        }

        fun writeRestorePoint(json: BlobRef, txt: BlobRef) {
            step(Op.Mkdir(RemoteDir.RESTORE), null, GROUP_RESTORE, "Preparing the restore point folder", FailurePolicy.CONTINUE)
            step(Op.PushAsset(json, RemotePath(RemoteDir.RESTORE, listOf("restore-point.json")), json.sha256), Prior(present = null), GROUP_RESTORE, "Saving the restore point on the TV", FailurePolicy.CONTINUE)
            step(Op.PushAsset(txt, RemotePath(RemoteDir.RESTORE, listOf("restore.txt")), txt.sha256), Prior(present = null), GROUP_RESTORE, "Saving the restore list on the TV", FailurePolicy.CONTINUE)
        }

        // RFC-LAUNCHER-1 (D4): "Update the home screen". The pin installed over our older launcher in
        // place (same package and signer, newer versionCode): the launcher's files, the dock and the
        // look in config.json, its card answers and its Launcher settings all stay, since only an
        // uninstall removes them. Anything else is not an update and is refused: another key is
        // Setup's replace (D36), an unknown key is never guessed, a newer one is never downgraded.
        fun reboot() {
            step(Op.Reboot, null, GROUP_REBOOT, "Restarting the TV", FailurePolicy.CONTINUE)
        }

        private fun undoNarration(op: Op, pkg: PackageName) = when (op) {
            is Op.Enable, is Op.DefaultState -> "Switching ${name(pkg)} back on"
            else -> "Switching ${name(pkg)} back off"
        }
    }

    companion object {
        const val APK_NAME_HEX = 40
        const val GROUP_CUSTOM = "custom"
        const val GROUP_STOCK_CLEAN = "stock_clean"
        const val TITLE_STOCK_CLEAN = "Clean Stock Home"
        const val GROUP_HOME = "home"
        // Legacy ids, kept so the engine can still read stored plans from before the stock-only
        // build: their guarded sections (SECTION_HOME, SECTION_HELPER) and curtain steps
        // (GROUP_CURTAIN). The planner never makes them anymore.
        const val SECTION_HOME = "home_takeover"
        const val SECTION_HELPER = "home_helper"
        const val GROUP_CURTAIN = "curtain"
        const val GROUP_THEME = "theme"
        const val GROUP_PUT_BACK = "put_back"
        const val GROUP_SWITCH_OFF = "switch_off"
        const val GROUP_LEFTOVERS = "leftovers"
        const val GROUP_MODULE = "module"
        const val GROUP_UNDO = "undo"
        const val GROUP_SESSION_END = "session_end"
        const val GROUP_RESTORE = "restore"
        const val GROUP_REBOOT = "reboot"
        const val WATCHDOG_SECONDS = 150
        const val REASON_WIZARD_WHILE_OURS = "on this TV the first-run setup wizard takes the Home button back; use the TV's own home screen first"
        const val REASON_HOME_SCREEN = "it is the TV's own home screen; only the home screen switch changes it"
        const val REASON_FIRE_OS_HOME = "it is Fire OS's own home screen, which Fire OS never lets anyone switch off"
        const val REASON_WIZARD_SECTION_ONLY = "the first-run setup wizard is only switched off as part of the home screen switch"
        // Undo of an earlier build's home screen: the uninstall erases everything it kept.
        const val NARRATION_REMOVE_LAUNCHER = "Removing the new home screen from the TV"
        // STORES-PLAN 1 (keepUpdatesOn): the Google Play build keeps the TV's updaters on.
        const val REASON_UPDATES = "it brings the TV's software and security updates, which this version of the app always keeps on"
        // beta4 (PatchWallFeeds): PatchWall (Xiaomi's home screen app) and the feeds it shows.
        val PATCHWALL = PatchWallFeeds.PATCHWALL
        val PATCHWALL_FEEDS: Set<PackageName> = PatchWallFeeds.FEEDS
        const val REASON_PATCHWALL_FEED = "PatchWall stays on and needs it, so it stays on too; switch PatchWall off as well to switch this off"
        const val REASON_PATCHWALL_FEED_BACK = "PatchWall stays on and needs it, so it is switched back on"
        const val GROUP_PATCHWALL_FEEDS = "patchwall_feeds"
        const val TITLE_PATCHWALL_FEEDS = "Back on for PatchWall"

        fun packageOf(op: Op): PackageName? = when (op) {
            is Op.Disable -> op.pkg
            is Op.Enable -> op.pkg
            is Op.DefaultState -> op.pkg
            is Op.Uninstall -> op.pkg
            is Op.InstallExisting -> op.pkg
            is Op.ClearPackage -> op.pkg
            is Op.Install -> op.pkg
            is Op.InstallStream -> op.pkg
            else -> null
        }
    }
}
