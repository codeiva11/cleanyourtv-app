package tv.cleanyour.core.engine

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.GroupDefault
import tv.cleanyour.core.model.LauncherArtifact
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageEntry
import tv.cleanyour.core.model.PackageGroup
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Platform
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.ProfileMatch
import tv.cleanyour.core.model.Quirk
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.ops.ShellRenderer
import tv.cleanyour.core.planner.Choices
import tv.cleanyour.core.model.CurtainGroup
import tv.cleanyour.core.model.CurtainStage
import tv.cleanyour.core.ops.FailurePolicy
import tv.cleanyour.core.model.FileRoute
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.ops.Denylist
import tv.cleanyour.core.ops.HelperLines
import tv.cleanyour.core.planner.GroupSummary
import tv.cleanyour.core.planner.GuardedSection
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.core.planner.PlanSummary
import tv.cleanyour.core.planner.RowSummary
import tv.cleanyour.core.planner.StockHome
import tv.cleanyour.core.planner.DefaultPlanner
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.Step
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.core.store.GatePass
import tv.cleanyour.testing.fixtures.FakeCredentialGate
import tv.cleanyour.testing.fixtures.FakeIdGen
import tv.cleanyour.testing.fixtures.FakeProfileSource
import tv.cleanyour.testing.fixtures.MapFactsTable
import tv.cleanyour.testing.fixtures.MemoryDocumentStore
import tv.cleanyour.testing.fixtures.MemoryJournal
import tv.cleanyour.testing.fixtures.MemorySnapshotStore
import tv.cleanyour.testing.fixtures.Samples
import tv.cleanyour.testing.fixtures.sha256Of

// Everything an engine test needs: the scripted TV, in-memory stores, the real renderer and planner,
// a clock on the test scheduler and a gate that mints passes.
class Harness(val scope: TestScope, val tv: ScriptedTv = ScriptedTv(Samples.snapshot(packages = Samples.miTvPackages(emptySet())))) {
    val prober = ScriptedProber(tv)
    val journal = MemoryJournal()
    val snapshots = MemorySnapshotStore()
    val documents = MemoryDocumentStore()
    val plans = DocumentPlanStore(documents)
    val clock = SchedulerClock(scope.testScheduler)
    val renderer = ShellRenderer()
    val gate = FakeCredentialGate(clock)
    val idGen = FakeIdGen()
    val facts = MapFactsTable()
    val launcher = Samples.launcherComponent
    val stock = Samples.stockLauncher
    val wizard = PackageName("com.google.android.tungsten.setupwraith")
    val signer = Sha256("b".repeat(64))
    val apkBytes = "fake launcher apk".encodeToByteArray()
    val apkSha: Sha256 = tv.acceptApk(apkBytes, launcher.pkg, 1, signer)
    val artifact = LauncherArtifact(launcher.pkg, 1, "0.1.0", apkSha, signer, BlobRef(apkSha, apkBytes.size.toLong(), "bundled:launcher.apk"))
    val debloat = listOf("com.miui.tv.analytics", "com.xiaomi.statistic", "com.google.android.feedback", "com.google.android.videos", "com.google.android.play.games").map { PackageName(it) }

    fun profile(quirks: Set<Quirk> = emptySet(), setupWraith: PackageName? = null) = Profile(
        "xiaomi-mitv-axso0", ProfileMatch(listOf(tv.facts.model), emptyList(), null, null, null, null), Platform.ANDROID_TV, listOf(stock), null, setupWraith, quirks,
        listOf(PackageGroup("ads", "Adverts and telemetry", GroupDefault.DISABLE, debloat.map { PackageEntry(it, null) })), emptySet(), emptyList(), null, Sha256("1".repeat(64)),
    )

    var profiles = FakeProfileSource(listOf(profile()))
    val transport = FileOverlayTransport(prober)
    val planner = DefaultPlanner(clock, idGen, renderer, "0.1.0")

    fun engine(
        liveCheckEveryMs: Long = 2_000, thumbnailEveryMs: Long = 5_000, journal: Journal = this.journal, log: (String) -> Unit = {},
        onWrongFocus: (tv.cleanyour.core.model.TvId, Component) -> Unit = { _, _ -> },
    ) = DefaultEngine(renderer, prober, journal, snapshots, plans, clock, scope.backgroundScope, profiles, liveCheckEveryMs, thumbnailEveryMs, log = log, onWrongFocus = onWrongFocus)

    fun pass(): GatePass = gate.mint(tv.tvId)

    fun plan(request: PlanRequest, planner: DefaultPlanner = this.planner): Plan {
        val snap = tv.snapshot(SnapshotReason.BASELINE)
        return when (val r = planner.plan(request, snap, facts, emptyList())) {
            is PlanResult.Ok -> r.plan
            is PlanResult.Refused -> throw AssertionError("refused: ${r.reasons}")
        }
    }

    // The plan a 0.4.7 build made for this TV, built directly below: the planner no longer plans
    // a home screen switch, but the engine still has to run (and roll back) a stored one, so the
    // takeover form is the stored plan and the clean-up form is the live planner's Setup.
    fun setupPlan(takeover: Boolean = true, installLauncher: Boolean = true, profile: Profile = profile(), planner: DefaultPlanner = this.planner,
        watchdogSeconds: Int = DefaultPlanner.WATCHDOG_SECONDS, confirmWindowMs: Long = 90_000): Plan =
        if (!takeover) storedCleanupPlan(profile, installLauncher)
        else storedTakeoverPlan(profile, watchdogSeconds, confirmWindowMs)

    fun storedCleanupPlan(profile: Profile = profile(), installLauncher: Boolean = true): Plan {
        val snap = tv.snapshot(SnapshotReason.BASELINE)
        val id = idGen.planId()
        val steps = mutableListOf<Step>()
        fun step(op: Op, prior: Prior?, group: String?, narration: String, policy: FailurePolicy): Int {
            steps += Step(steps.size, op, renderer.render(op), prior, group, narration, policy)
            return steps.size - 1
        }
        for (pkg in debloat) step(Op.Disable(pkg), Prior(packageState = snap.packages[pkg]?.enabled), "ads",
            "Switching off ${pkg.value}", FailurePolicy.SKIP_IF_REFUSED)
        if (installLauncher) {
            step(Op.Settle(15), null, "launcher", "Giving the TV 15 seconds to settle", FailurePolicy.CONTINUE)
            step(Op.InstallStream(artifact.blob, artifact.sha256, launcher.pkg, 1, signer), Prior(present = false),
                "launcher", "Installing the new home screen", FailurePolicy.CONTINUE)
        }
        val rows = debloat.map { RowSummary(it, it.value, "", off = true, protected = false, whyProtected = null) }
        val summary = PlanSummary(listOf(GroupSummary("ads", "Adverts and telemetry", debloat.size, debloat.size, rows)), emptyList())
        return Plan(id, tv.tvId, PlanKind.SETUP, snap.contentHash, steps, emptyList(), summary,
            renderer.planHash(steps.map { it.rendered }), Samples.NOW_MS, "0.1.0")
    }

    fun storedTakeoverPlan(profile: Profile = profile(), watchdogSeconds: Int = DefaultPlanner.WATCHDOG_SECONDS, confirmWindowMs: Long = 90_000): Plan {
        val snap = tv.snapshot(SnapshotReason.BASELINE)
        val id = idGen.planId()
        val token = idGen.runToken()
        val steps = mutableListOf<Step>()
        fun step(op: Op, prior: Prior?, group: String?, narration: String, policy: FailurePolicy): Int {
            steps += Step(steps.size, op, renderer.render(op), prior, group, narration, policy)
            return steps.size - 1
        }
        for (pkg in debloat) step(Op.Disable(pkg), Prior(packageState = snap.packages[pkg]?.enabled), "ads",
            "Switching off ${pkg.value}", FailurePolicy.SKIP_IF_REFUSED)
        step(Op.Settle(15), null, "launcher", "Giving the TV 15 seconds to settle", FailurePolicy.CONTINUE)
        step(Op.InstallStream(artifact.blob, artifact.sha256, launcher.pkg, 1, signer), Prior(present = false),
            "launcher", "Installing the new home screen", FailurePolicy.CONTINUE)
        step(Op.StartActivity(launcher, wait = true), null, "home", "Starting the new home screen", FailurePolicy.CONTINUE)
        step(Op.Mkdir(RemoteDir.TMP), null, "home", "Preparing the safety timer", FailurePolicy.CONTINUE)
        val from = step(Op.ArmHomeWatchdog(stock, watchdogSeconds, token), null, "home",
            "Arming the safety timer", FailurePolicy.STOP_AND_ROLLBACK)
        step(Op.Disable(stock.pkg), Prior(packageState = snap.packages[stock.pkg]?.enabled), "home",
            "Switching off the TV's own home screen", FailurePolicy.STOP_AND_ROLLBACK)
        // D3 quirk: a profile whose setup wizard may own the saved Wi-Fi goes off inside the switch.
        val wizard = profile.setupWraith?.takeIf { Quirk.HOME_TAKEOVER_SETUPWRAITH in profile.quirks && it in snap.packages }
        if (wizard != null) step(Op.Disable(wizard), Prior(packageState = snap.packages[wizard]?.enabled), "home",
            "Switch off the first-run setup wizard so the new home screen can take over", FailurePolicy.STOP_AND_ROLLBACK)
        step(Op.SetHome(launcher), Prior(home = StockHome.recorded(snap, stock)), "home",
            "Making the new home screen the default", FailurePolicy.STOP_AND_ROLLBACK)
        val to = step(Op.VerifyHome(launcher), null, "home", "Checking the home screen", FailurePolicy.STOP_AND_ROLLBACK)
        step(Op.ConfirmHome(token), null, "home", "Confirming the new home screen", FailurePolicy.CONTINUE)
        step(Op.KillWatchdog(token), null, "home", "Disarming the safety timer", FailurePolicy.CONTINUE)
        val rollbackIfFocus = (profile.stockLaunchers.map { it.pkg } + snap.home.claimants.map { it.component.pkg } +
            (Denylist.setupWizards + Denylist.vendorSetupWizards + Denylist.FRAMEWORK).map(::PackageName) +
            PackageName("com.android.settings")).toSet()
        val rollback: List<Op> = listOfNotNull(
            Op.ConfirmHome(token), Op.KillWatchdog(token), Op.Enable(stock.pkg), wizard?.let { Op.Enable(it) },
            Op.SetHome(stock), Op.VerifyHome(stock), Op.PressHome,
        )
        val section = GuardedSection(DefaultPlanner.SECTION_HOME, from, to, token, confirmWindowMs, launcher, rollbackIfFocus, rollback)
        val rows = debloat.map { RowSummary(it, it.value, "", off = true, protected = false, whyProtected = null) }
        val summary = PlanSummary(listOf(GroupSummary("ads", "Adverts and telemetry", debloat.size, debloat.size, rows)), emptyList())
        return Plan(id, tv.tvId, PlanKind.SETUP, snap.contentHash, steps, listOf(section), summary,
            renderer.planHash(steps.map { it.rendered }), Samples.NOW_MS, "0.1.0")
    }

    // The 0.4.7 helper plan (RFC-HELPER-1): the look, then the guarded helper section. The snapshot
    // carries no accessibility settings, so the list is empty and accessibility goes on here.
    fun storedHelperPlan(config: BlobRef): Plan {
        val snap = tv.snapshot(SnapshotReason.BASELINE)
        val id = idGen.planId()
        val token = idGen.runToken()
        val service = HelperLines.SERVICE
        val steps = mutableListOf<Step>()
        fun step(op: Op, prior: Prior?, group: String?, narration: String, policy: FailurePolicy): Int {
            steps += Step(steps.size, op, renderer.render(op), prior, group, narration, policy)
            return steps.size - 1
        }
        for (pkg in debloat) step(Op.Disable(pkg), Prior(packageState = snap.packages[pkg]?.enabled), "ads",
            "Switching off ${pkg.value}", FailurePolicy.SKIP_IF_REFUSED)
        step(Op.Settle(15), null, "launcher", "Giving the TV 15 seconds to settle", FailurePolicy.CONTINUE)
        step(Op.InstallStream(artifact.blob, artifact.sha256, launcher.pkg, 1, signer), Prior(present = false),
            "launcher", "Installing the new home screen", FailurePolicy.CONTINUE)
        for (op in transport.writePlanSteps(config, null, FileRoute.SYNC)) {
            val (prior, narration, policy) = when (op) {
                is Op.PushAsset -> Triple(Prior(present = false), "Sending the look to the TV", FailurePolicy.CONTINUE)
                is Op.RestartHome -> Triple(null, "Restarting the home screen", FailurePolicy.CONTINUE)
                is Op.RemoveFile -> Triple(Prior(present = false), "Clearing the old wallpaper", FailurePolicy.CONTINUE)
                else -> Triple(null, "Preparing the home screen", FailurePolicy.CONTINUE)
            }
            step(op, prior, "theme", narration, policy)
        }
        step(Op.StartActivity(launcher, wait = true), null, "helper", "Starting the new home screen", FailurePolicy.CONTINUE)
        step(Op.Mkdir(RemoteDir.TMP), null, "helper", "Preparing the safety timer", FailurePolicy.CONTINUE)
        val from = step(Op.ArmHelperWatchdog(service, DefaultPlanner.WATCHDOG_SECONDS, token, offIfEmpty = true), null, "helper",
            "Arming the safety timer", FailurePolicy.STOP_AND_ROLLBACK)
        val list: String? = null
        step(Op.AccessibilityAdd(list, service), Prior(settingValue = list), "helper",
            "Switching on the Home button helper", FailurePolicy.STOP_AND_ROLLBACK)
        step(Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "1"), Prior(settingValue = snap.settings[SettingKey.ACCESSIBILITY_ENABLED]), "helper",
            "Switching on accessibility for the helper", FailurePolicy.STOP_AND_ROLLBACK)
        step(Op.Settle(3), null, "helper", "Letting the helper start", FailurePolicy.CONTINUE)
        step(Op.PressHome, null, "helper", "Pressing Home", FailurePolicy.CONTINUE)
        val to = step(Op.Settle(2), null, "helper", "Checking the home screen", FailurePolicy.CONTINUE)
        step(Op.ConfirmHome(token), null, "helper", "Confirming the new home screen", FailurePolicy.CONTINUE)
        step(Op.KillWatchdog(token), null, "helper", "Disarming the safety timer", FailurePolicy.CONTINUE)
        val after = HelperLines.appended(list, service)
        val rollback: List<Op> = listOf(
            Op.AccessibilityRemove(after, service), Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "0"),
            Op.ConfirmHome(token), Op.KillWatchdog(token), Op.PressHome,
        )
        val ownHomes = (profile().stockLaunchers.map { it.pkg } + stock.pkg +
            snap.home.claimants.map { it.component.pkg }.filter { it != launcher.pkg }).toSet()
        val section = GuardedSection(DefaultPlanner.SECTION_HELPER, from, to, token, 90_000, launcher, ownHomes, rollback)
        val rows = debloat.map { RowSummary(it, it.value, "", off = true, protected = false, whyProtected = null) }
        val summary = PlanSummary(listOf(GroupSummary("ads", "Adverts and telemetry", debloat.size, debloat.size, rows)), emptyList())
        return Plan(id, tv.tvId, PlanKind.SETUP, snap.contentHash, steps, listOf(section), summary,
            renderer.planHash(steps.map { it.rendered }), Samples.NOW_MS, "0.1.0")
    }

    // The 0.4.7 curtain weave (RFC-CURTAIN-1) over a plan: the curtain is installed and started,
    // cues are woven after each counted switch-off, and the last step removes the curtain again.
    fun storedCurtainPlan(base: Plan, curtain: LauncherArtifact): Plan {
        val curtainPkg = curtain.pkg
        val curtainActivity = Component(curtainPkg, ".CurtainActivity")
        val curtainReceiver = Component(curtainPkg, ".CueReceiver")
        val token = idGen.runToken()
        val body = base.steps.toList()
        val bodySections = base.sections.toList()
        fun inSection(i: Int) = bodySections.any { i in it.from..it.to }
        val counted = body.filter { it.op is Op.Disable && it.group != "home" && !inSection(it.index) }
        val countedIdx = counted.map { it.index }.toSet()
        val offTotal = counted.size
        val groupTotal = counted.groupingBy { it.group }.eachCount()
        val lastOfGroup = counted.groupBy { it.group }.mapValues { (_, v) -> v.last().index }.values.toSet()
        val installAt = body.firstOrNull { it.group == "launcher" && it.op !is Op.Settle }?.index
        val installedAt = body.firstOrNull { DefaultPlanner.packageOf(it.op) == launcher.pkg && (it.op is Op.Install || it.op is Op.InstallStream) }?.index
        val finaleAt = body.firstOrNull { it.op == Op.StartActivity(launcher, wait = true) }?.index
        val steps = mutableListOf<Step>()
        fun step(op: Op, prior: Prior?, group: String?, narration: String, policy: FailurePolicy): Int {
            steps += Step(steps.size, op, renderer.render(op), prior, group, narration, policy)
            return steps.size - 1
        }
        fun curtainGroup(groupId: String?): CurtainGroup = when (groupId?.removeSuffix("-optional")) {
            "ads" -> CurtainGroup.ADVERTS
            "tracking" -> CurtainGroup.TRACKING
            "preloads" -> CurtainGroup.NOBODY_ASKED
            "brand" -> CurtainGroup.BRAND_EXTRAS
            "demo" -> CurtainGroup.SHOP_DEMO
            else -> CurtainGroup.OTHER
        }
        fun n(x: Int) = x.coerceIn(0, ShellRenderer.CUE_MAX)
        fun cue(stage: CurtainStage, count: Int = 0, total: Int = 0, group: CurtainGroup = CurtainGroup.NONE, groupCount: Int = 0, groupTotal: Int = 0) =
            step(Op.CurtainCue(curtainReceiver, token, stage, n(count), n(total), group, n(groupCount), n(groupTotal)), null, "curtain", "", FailurePolicy.CONTINUE)
        fun finale() {
            cue(CurtainStage.FINALE)
            step(Op.Settle(3), null, "curtain", "Giving the TV a moment", FailurePolicy.CONTINUE)
        }
        step(Op.InstallStream(curtain.blob, curtain.sha256, curtainPkg, curtain.versionCode, curtain.signer), Prior(present = false),
            "curtain", "Showing the progress on the TV", FailurePolicy.CONTINUE)
        step(Op.StartActivity(curtainActivity, wait = false), null, "curtain", "", FailurePolicy.CONTINUE)
        cue(CurtainStage.SCAN, 0, offTotal)
        cue(CurtainStage.RESTORE_SAVED)
        val moved = IntArray(body.size)
        var k = 0
        val inGroup = mutableMapOf<String?, Int>()
        var finaled = false
        for (s in body) {
            if (s.index == finaleAt) { finale(); finaled = true }
            if (s.index == installAt) cue(CurtainStage.INSTALL)
            moved[s.index] = steps.size
            steps += s.copy(index = steps.size)
            if (s.index == installedAt) cue(CurtainStage.INSTALLED)
            if (s.index in countedIdx) {
                k++
                val g = (inGroup[s.group] ?: 0) + 1
                inGroup[s.group] = g
                val words = curtainGroup(s.group)
                cue(CurtainStage.OFF, k, offTotal, words, g, groupTotal.getValue(s.group))
                if (s.index in lastOfGroup) cue(CurtainStage.GROUP_DONE, k, offTotal, words, g, g)
            }
        }
        if (!finaled) finale()
        val sections = bodySections.map { it.copy(from = moved[it.from], to = moved[it.to]) }
        step(Op.Uninstall(curtainPkg), null, "curtain", "", FailurePolicy.CONTINUE)
        return base.copy(steps = steps, sections = sections, hash = renderer.planHash(steps.map { it.rendered }))
    }

    // A plan from explicit ops, rendered by the real renderer, like Samples.plan; its id comes from
    // the harness's IdGen like every planner plan, because a plan runs once (R1).
    fun opsPlan(vararg ops: Op): Plan = Samples.plan(ops.toList(), tvId = tv.tvId, renderer = renderer, id = idGen.planId(), base = tv.snapshot(SnapshotReason.BASELINE).contentHash)

    suspend fun records(plan: Plan): List<JournalRecord> = journal.read(plan.id)
    suspend fun types(plan: Plan): List<String> = records(plan).map { r ->
        when (r) {
            is JournalRecord.PlanStart -> "plan"
            is JournalRecord.Begin -> "begin${r.i}"
            is JournalRecord.Done -> "done${r.i}" + if (r.resumed) "r" else ""
            is JournalRecord.Failed -> "failed${r.i}"
            is JournalRecord.Section -> "section:${r.state.name}"
            is JournalRecord.Rollback -> "rollback${r.from}"
            is JournalRecord.Paused -> "paused:${r.reason.name}@${r.at}"
            is JournalRecord.End -> "end:${r.status.name}"
        }
    }

    // advanceUntilIdle stops when only background tasks remain, and the run lives in backgroundScope,
    // so tests drive the scheduler a second at a time until the run settles (or waits for the user).
    suspend fun settle(engine: Engine, maxSeconds: Int = 2_000): RunState {
        var guard = 0
        while (true) {
            scope.runCurrent()
            val s = engine.runState(tv.tvId).value
            if (s is RunState.Finished || s is RunState.Paused || s is RunState.Idle || s is RunState.AwaitingConfirm) return s
            scope.advanceTimeBy(1_000)
            check(guard++ < maxSeconds) { "run did not settle: $s" }
        }
    }

    companion object {
        fun sha(text: String) = sha256Of(text.encodeToByteArray())
        val fallbackHome = Component(PackageName("com.android.settings"), ".FallbackHome")
    }
}

// Every coroutine test gets an explicit wall-clock limit (the virtual clock skips the delays).
val TEST_TIMEOUT = kotlin.time.Duration.parse("30s")
