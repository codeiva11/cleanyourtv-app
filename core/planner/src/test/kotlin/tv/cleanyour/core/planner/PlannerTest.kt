package tv.cleanyour.core.planner

import tv.cleanyour.core.model.ArtifactRef
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Module
import tv.cleanyour.core.model.ModuleVersion
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.Quirk
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.Tier
import tv.cleanyour.core.ops.Denylist
import tv.cleanyour.core.ops.FailurePolicy
import tv.cleanyour.core.ops.Privilege
import tv.cleanyour.core.ops.ShellRenderer
import tv.cleanyour.core.planner.PlannerFixtures.artifact
import tv.cleanyour.core.planner.PlannerFixtures.change
import tv.cleanyour.core.planner.PlannerFixtures.choices
import tv.cleanyour.core.planner.PlannerFixtures.debloatList
import tv.cleanyour.core.planner.PlannerFixtures.installedLauncher
import tv.cleanyour.core.planner.PlannerFixtures.launcher
import tv.cleanyour.core.planner.PlannerFixtures.plan
import tv.cleanyour.core.planner.PlannerFixtures.profile
import tv.cleanyour.core.planner.PlannerFixtures.refused
import tv.cleanyour.core.planner.PlannerFixtures.setup
import tv.cleanyour.core.planner.PlannerFixtures.snapshot
import tv.cleanyour.core.planner.PlannerFixtures.stock
import tv.cleanyour.core.planner.PlannerFixtures.wizard
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.testing.fixtures.FakeProfileSource
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlannerTest {
    private val renderer = ShellRenderer()

    private fun ops(p: Plan) = p.steps.map { it.op }
    private inline fun <reified T : Op> Plan.find(): T = steps.map { it.op }.filterIsInstance<T>().single()


    @Test fun keptAndExtraOffChangeExactlyThoseOps() {
        val videos = PackageName("com.google.android.videos")
        val netflix = PackageName("com.netflix.ninja")
        val p = plan(setup(choices = choices(kept = setOf(videos), extraOff = setOf(netflix))))
        assertTrue(ops(p).none { it == Op.Disable(videos) })
        assertTrue(ops(p).contains(Op.Disable(netflix)))
        assertEquals(17, p.summary.groups.first().off)
        assertEquals(listOf("Netflix").map { "com.netflix.ninja" }, p.summary.groups.single { it.id == DefaultPlanner.GROUP_CUSTOM }.rows.map { it.pkg.value })
        assertEquals(p.disabledPackages.toSet(), p.summary.switchOffPackages.toSet())
        // No facts in the fixture and no label from the probe: the name is read from the package
        // (round 3), never the raw package.
        assertEquals(listOf("Videos"), p.summary.keepNames)
    }

    @Test fun protectedRowsAreShownNotPlanned() {
        val extra = tv.cleanyour.core.model.PackageGroup("sys", "System", tv.cleanyour.core.model.GroupDefault.DISABLE, listOf(
            tv.cleanyour.core.model.PackageEntry(PackageName("com.android.systemui"), null),
            tv.cleanyour.core.model.PackageEntry(PackageName("com.google.android.backdrop"), null),
            tv.cleanyour.core.model.PackageEntry(stock.pkg, null),
            tv.cleanyour.core.model.PackageEntry(wizard, null),
            tv.cleanyour.core.model.PackageEntry(PackageName("com.droidlogic.tvinput"), null),
        ))
        val snap = snapshot().let { s -> s.copy(screensaver = listOf(Component(PackageName("com.google.android.backdrop"), ".Backdrop"))).let { it.copy(contentHash = tv.cleanyour.core.store.SnapshotCodec.hash(it)) } }
        val p = plan(setup(profile = profile(extraGroups = listOf(extra))), snapshot = snap)
        val rows = p.summary.groups.first { it.id == "sys" }.rows
        // The stock launcher is protected now: the TV keeps its own home screen, so it stays
        // under "We keep these" as off (never switched off), with the other protected rows.
        assertEquals(5, rows.size)
        assertTrue(rows.all { it.protected && !it.off && it.whyProtected != null }, rows.toString())
        assertEquals(0, p.summary.groups.first { it.id == "sys" }.off)
        assertTrue(ops(p).filterIsInstance<Op.Disable>().none { it.pkg.value == "com.android.systemui" || it.pkg == wizard })
        assertTrue(ops(p).filterIsInstance<Op.Disable>().none { it.pkg == stock.pkg }, "the stock launcher is never switched off")
        assertEquals(p.disabledPackages.toSet(), p.summary.switchOffPackages.toSet())
    }


    @Test fun idempotentReplan() {
        // Everything already done: nothing to switch off, the launcher installed and in charge.
        val snap = snapshot(disabled = debloatList.map { it.value }.toSet(), home = launcher, claimants = listOf(launcher, stock), extra = mapOf(installedLauncher()))
        val p = plan(setup(), snapshot = snap)
        assertEquals(emptyList<Step>(), p.steps)
        assertTrue(p.sections.isEmpty())
        // Nothing is counted that the plan does not do: the already-off packages are not rows.
        assertEquals(0, p.summary.switchOffCount)
        assertTrue(p.summary.groups.flatMap { it.rows }.none { it.pkg in debloatList })
        assertEquals(renderer.planHash(emptyList()), p.hash)
    }





    @Test fun putBackUsesTheJournalsPrior() {
        val pkg = PackageName("com.google.android.videos")
        val snap = snapshot(disabled = setOf(pkg.value))
        assertEquals(listOf(Op.Enable(pkg)), ops(plan(PlanRequest.PutBack(pkg), snap)))
        val j = listOf(change(3, Op.Disable(pkg), Prior(packageState = EnabledState.DEFAULT)))
        assertEquals(listOf(Op.DefaultState(pkg)), ops(plan(PlanRequest.PutBack(pkg), snap, j)))
        assertEquals(emptyList<Op>(), ops(plan(PlanRequest.PutBack(pkg), snapshot())))
        assertTrue(refused(PlanRequest.PutBack(PackageName("com.not.here"))).single().contains("not on this TV"))
        assertEquals(PlanKind.PUT_BACK, plan(PlanRequest.PutBack(pkg), snap).kind)
    }

    @Test fun switchOffAndLeftovers() {
        val a = PackageName("com.google.android.videos"); val b = PackageName("com.netflix.ninja")
        val p = plan(PlanRequest.SwitchOff(setOf(a, b)))
        assertEquals(listOf(Op.Disable(a), Op.Disable(b)), ops(p))
        assertEquals(PlanKind.SWITCH_OFF, p.kind)
        assertTrue(refused(PlanRequest.SwitchOff(setOf(a, PackageName("com.android.systemui")))).single().contains("system UI"))
        assertTrue(refused(PlanRequest.SwitchOff(setOf(stock.pkg))).single().contains("home screen"))
        val left = plan(PlanRequest.Leftovers(setOf(a, b)), snapshot(disabled = setOf(a.value)))
        assertEquals(listOf(Op.Disable(b)), ops(left))
        assertEquals(PlanKind.LEFTOVERS, left.kind)
        // R1: a leftover that is HOME again after a TV update (the stock launcher re-enabled and
        // resolving HOME) is refused; only the home screen switch may switch a home screen off.
        assertTrue(refused(PlanRequest.Leftovers(setOf(stock.pkg, b))).single().contains("home screen"))
        // The current HOME is protected even when the claimant list does not carry it (R5-W4 O5).
        val patchwall = Component(PackageName("com.mitv.tvhome.atv"), ".MainActivity")
        val pw = snapshot(home = patchwall, claimants = emptyList(), extra = mapOf(patchwall.pkg to PackageState(EnabledState.ENABLED, true, 1, null, false, false, "PatchWall", false)))
        assertTrue(refused(PlanRequest.SwitchOff(setOf(patchwall.pkg)), pw).single().contains("home screen"))
        assertTrue(refused(PlanRequest.Leftovers(setOf(patchwall.pkg)), pw).single().contains("home screen"))
        val extra = tv.cleanyour.core.model.PackageGroup("ads", "Adverts", tv.cleanyour.core.model.GroupDefault.DISABLE, listOf(tv.cleanyour.core.model.PackageEntry(patchwall.pkg, null)))
        val setupPlan = plan(setup(profile = profile(extraGroups = listOf(extra))), pw)
        assertTrue(setupPlan.steps.none { DefaultPlanner.packageOf(it.op) == patchwall.pkg })
        // The Mi TV debloat list names PatchWall itself, so the row lands in whichever group lists it first.
        val row = setupPlan.summary.groups.flatMap { it.rows }.single { it.pkg == patchwall.pkg }
        assertTrue(row.protected && !row.off && row.whyProtected!!.contains("home screen"))
    }


    private fun module(tier: Tier = Tier.COMMUNITY, sha: Sha256 = Sha256("d".repeat(64)), size: Long = 5000, minSdk: Int = 21): Pair<Module, ModuleVersion> {
        val v = ModuleVersion(3, "1.2", "2026-01-01", ArtifactRef("https://example/retro.apk", size, sha, Sha256("e".repeat(64))), emptyList(), "none", minSdk, emptyList(), emptyList())
        val m = Module("retro", "Retro", "one", "desc", PackageName("tv.retro.app"), "card", tier, "pub", "src", "MIT", null, null, listOf(v))
        return m to v
    }

    @Test fun addAndRemoveModule() {
        val (m, v) = module()
        val apk = BlobRef(v.artifact.sha256, v.artifact.size, "cache/retro")
        val p = plan(PlanRequest.AddModule(m, v, apk))
        assertEquals(listOf("InstallStream", "PressHome"), ops(p).map { it::class.simpleName })
        assertEquals(PlanKind.ADD_MODULE, p.kind)
        assertTrue(p.steps.first().rendered.privilege == Privilege.COMMUNITY)
        assertTrue(refused(PlanRequest.AddModule(m, v, apk.copy(size = 1))).single().contains("does not match"))
        val (q, qv) = module(tier = Tier.REVOKED)
        assertTrue(refused(PlanRequest.AddModule(q, qv, apk)).single().contains("revoked"))
        val (n, nv) = module(minSdk = 30)
        assertTrue(refused(PlanRequest.AddModule(n, nv, apk)).single().contains("Android 30"))
        // Remove: system refused, not ours refused, ours removed, the launcher only when not HOME.
        val installed = m.pkg to PackageState(EnabledState.ENABLED, false, 3, v.artifact.signer, false, false, null, false)
        assertTrue(refused(PlanRequest.RemoveModule(m.pkg), snapshot(extra = mapOf(installed))).single().contains("not installed by this app"))
        val j = listOf(change(0, Op.InstallStream(apk, apk.sha256, m.pkg, 3, v.artifact.signer), Prior(present = false)))
        assertEquals(listOf(Op.Uninstall(m.pkg)), ops(plan(PlanRequest.RemoveModule(m.pkg), snapshot(extra = mapOf(installed)), j)))
        assertEquals(emptyList<Op>(), ops(plan(PlanRequest.RemoveModule(m.pkg))))
        assertTrue(refused(PlanRequest.RemoveModule(PackageName("mitv.service"))).single().contains("system"))
        assertTrue(refused(PlanRequest.RemoveModule(launcher.pkg), snapshot(home = launcher, claimants = listOf(launcher, stock), extra = mapOf(installedLauncher()))).single().contains("own home screen first"))
        assertEquals(listOf(Op.Uninstall(launcher.pkg)), ops(plan(PlanRequest.RemoveModule(launcher.pkg), snapshot(extra = mapOf(installedLauncher())))))
    }

    @Test fun useStockHomeAndBeforeFactoryReset() {
        val after = snapshot(disabled = setOf(stock.pkg.value, wizard.value), home = launcher, claimants = listOf(launcher, stock), extra = mapOf(installedLauncher()))
        val p = plan(PlanRequest.UseStockHome(stock), after)
        assertEquals(listOf(Op.Enable(stock.pkg), Op.SetHome(stock), Op.VerifyHome(stock), Op.PressHome), ops(p))
        assertEquals(PlanKind.USE_STOCK_HOME, p.kind)
        assertEquals(Prior(home = launcher), p.steps.first { it.op is Op.SetHome }.prior)
        assertTrue(refused(PlanRequest.UseStockHome(launcher), after).single().contains("not the TV's own"))
        assertTrue(refused(PlanRequest.UseStockHome(Component(PackageName("com.x.y"), ".Z")), after).single().contains("not a home screen"))
        val journals = listOf(change(5, Op.Disable(wizard), Prior(packageState = EnabledState.ENABLED)), change(6, Op.SetHome(launcher), Prior(home = stock)))
        val reset = plan(PlanRequest.BeforeFactoryReset, after, journals)
        assertEquals(listOf(Op.Enable(stock.pkg), Op.Enable(wizard), Op.SetHome(stock), Op.VerifyHome(stock), Op.PressHome), ops(reset))
        assertEquals(PlanKind.BEFORE_FACTORY_RESET, reset.kind)
        assertEquals(emptyList<Op>(), ops(plan(PlanRequest.BeforeFactoryReset, snapshot())))
    }

    @Test fun undoEverythingIsComputedFromTheChangesInOrder() {
        val a = PackageName("com.miui.tv.analytics"); val b = PackageName("com.xiaomi.statistic")
        val changes = listOf(
            change(0, Op.Disable(a), Prior(packageState = EnabledState.ENABLED)),
            change(1, Op.Disable(b), Prior(packageState = EnabledState.DEFAULT)),
            change(2, Op.InstallStream(artifact().blob, artifact().sha256, launcher.pkg, 1, artifact().signer), Prior(present = false)),
            change(3, Op.Disable(stock.pkg), Prior(packageState = EnabledState.ENABLED)),
            change(4, Op.SetHome(launcher), Prior(home = stock)),
            change(5, Op.SettingsPut(SettingKey.SCREENSAVER_ENABLED, "0"), Prior(settingValue = "1")),
            change(6, Op.PushAsset(BlobRef(Sha256("c".repeat(64)), 5, "cfg"), RemotePath(RemoteDir.OVERLAY, listOf("config.json")), Sha256("c".repeat(64))), Prior(present = false)),
            change(7, Op.Enable(a), Prior(packageState = EnabledState.DISABLED_USER)),   // put back later; the original state is what counts
            change(8, Op.Disable(a), Prior(packageState = EnabledState.ENABLED)),
        )
        val now = snapshot(disabled = setOf(a.value, b.value, stock.pkg.value), home = launcher, claimants = listOf(launcher, stock), extra = mapOf(installedLauncher()))
            .withSettings(mapOf(SettingKey.SCREENSAVER_ENABLED to "0"))
        val p = plan(PlanRequest.UndoEverything(changes, removeLauncher = false), now)
        assertEquals(listOf(
            Op.Enable(stock.pkg), Op.DefaultState(b), Op.Enable(a),
            Op.SettingsPut(SettingKey.SCREENSAVER_ENABLED, "1"),
            Op.SetHome(stock), Op.VerifyHome(stock), Op.PressHome,
            Op.RemoveFile(RemotePath(RemoteDir.OVERLAY, listOf("config.json"))), Op.RestartHome(launcher),
        ), ops(p))
        assertEquals(PlanKind.UNDO_EVERYTHING, p.kind)
        val gone = plan(PlanRequest.UndoEverything(changes, removeLauncher = true), now)
        assertTrue(Op.Uninstall(launcher.pkg) in ops(gone))
        assertTrue(ops(gone).none { it is Op.RemoveFile || it is Op.RestartHome })
        assertTrue(ops(gone).indexOf(Op.SetHome(stock)) < ops(gone).indexOf(Op.Uninstall(launcher.pkg)))
        // Already undone: nothing to do.
        assertEquals(emptyList<Op>(), ops(plan(PlanRequest.UndoEverything(changes.take(1), false), snapshot())))
    }

    // QA 0.4.5-beta1: the undo's progress said "Removing Gathin Home from the TV". The probe reads
    // no labels, so the launcher's name came from its package; the app calls it the new home screen.
    @Test fun removingOurLauncherSaysTheNewHomeScreen() {
        val unlabelled = installedLauncher().let { (pkg, st) -> pkg to st.copy(label = null) }
        val changes = listOf(
            change(0, Op.InstallStream(artifact().blob, artifact().sha256, launcher.pkg, 1, artifact().signer), Prior(present = false)),
            change(1, Op.Disable(stock.pkg), Prior(packageState = EnabledState.ENABLED)),
            change(2, Op.SetHome(launcher), Prior(home = stock)),
        )
        val now = snapshot(disabled = setOf(stock.pkg.value), home = launcher, claimants = listOf(launcher, stock), extra = mapOf(unlabelled))
        val undo = plan(PlanRequest.UndoEverything(changes, removeLauncher = true), now, changes)
        assertEquals("Removing the new home screen from the TV", undo.steps.single { it.op == Op.Uninstall(launcher.pkg) }.narration)
        assertTrue(undo.steps.none { "Gathin" in it.narration }, undo.steps.map { it.narration }.toString())
        // Removing it from Manage (the TV's own home screen already back) says the same.
        val stockBack = snapshot(extra = mapOf(unlabelled))
        assertEquals("Removing the new home screen from the TV", plan(PlanRequest.RemoveModule(launcher.pkg), stockBack, changes).steps.single().narration)
        // Another app of ours keeps its own name.
        val (m, v) = module()
        val apk = BlobRef(v.artifact.sha256, v.artifact.size, "cache/retro")
        val installed = m.pkg to PackageState(EnabledState.ENABLED, false, 3, v.artifact.signer, false, false, "Retro Games", false)
        val j = listOf(change(0, Op.InstallStream(apk, apk.sha256, m.pkg, 3, v.artifact.signer), Prior(present = false)))
        assertEquals("Removing Retro Games from the TV", plan(PlanRequest.RemoveModule(m.pkg), snapshot(extra = mapOf(installed)), j).steps.single().narration)
    }

    @Test fun debugOffAndRestorePoint() {
        val p = plan(PlanRequest.DebugOff)
        assertEquals(listOf(Op.DebugOff), ops(p))
        assertEquals(PlanKind.DEBUG_OFF, p.kind)
        assertTrue(p.steps.all { it.rendered.privilege == Privilege.SESSION_END && it.onFailure == FailurePolicy.CONTINUE })
        val eleven = Samples.snapshot(facts = Samples.miTvFacts().copy(sdk = 30), packages = Samples.miTvPackages()).withSettings(emptyMap())
        assertEquals(listOf(Op.SettingsPut(SettingKey.ADB_WIFI_ENABLED, "0"), Op.DebugOff), ops(plan(PlanRequest.DebugOff, eleven)))
        assertTrue(plan(PlanRequest.DebugOff, eleven).steps.all { it.rendered.privilege == Privilege.SESSION_END })
        val json = BlobRef(Sha256("1".repeat(64)), 10, "j"); val txt = BlobRef(Sha256("2".repeat(64)), 10, "t")
        val w = plan(PlanRequest.WriteRestorePoint(json, txt))
        assertEquals(listOf(Op.Mkdir(RemoteDir.RESTORE), Op.PushAsset(json, RemotePath(RemoteDir.RESTORE, listOf("restore-point.json")), json.sha256),
            Op.PushAsset(txt, RemotePath(RemoteDir.RESTORE, listOf("restore.txt")), txt.sha256)), ops(w))
        assertEquals(PlanKind.WRITE_RESTORE_POINT, w.kind)
    }

    @Test fun plansHaveIdsTimesAndRenderedLinesFromTheOneRenderer() {
        val p = plan(setup())
        assertEquals("01FAKEPLAN0000000000000001", p.id.value)
        assertEquals(PlannerFixtures.clock.nowMs(), p.createdAtMs)
        assertEquals("0.1.0", p.appVersion)
        for (s in p.steps) assertEquals(renderer.render(s.op).lines.map { it.text }, s.rendered.lines.map { it.text })
        assertTrue(p.steps.none { it.rendered.lines.any { l -> l.text.startsWith("fake") } })
    }

    // RFC-W1-2 ruling: the planner never emits an op on `android`, and refuses a request naming it.
    @Test fun theFrameworkPackageIsRefusedByThePlanner() {
        val android = PackageName("android")
        assertEquals(Denylist.FRAMEWORK, android.value)
        val state = PackageState(EnabledState.ENABLED, true, 28, null, true, false, "Android System", false)
        val snap = snapshot(extra = mapOf(android to state))
        assertTrue(refused(PlanRequest.SwitchOff(setOf(android)), snap).single().contains("framework"))
        assertTrue(refused(PlanRequest.Leftovers(setOf(android)), snap).single().contains("framework"))
        assertTrue(refused(PlanRequest.RemoveModule(android), snap).isNotEmpty())
        val extra = tv.cleanyour.core.model.PackageGroup("bad", "Bad list", tv.cleanyour.core.model.GroupDefault.DISABLE, listOf(tv.cleanyour.core.model.PackageEntry(android, null)))
        val p = plan(setup(profile = profile(extraGroups = listOf(extra)), choices = choices(extraOff = emptySet())), snap)
        assertTrue(p.steps.none { s -> DefaultPlanner.packageOf(s.op) == android })
        val row = p.summary.groups.first { it.id == "bad" }.rows.single()
        assertTrue(row.protected && !row.off && row.whyProtected!!.contains("framework"))
        assertNull(ops(p).filterIsInstance<Op.Disable>().firstOrNull { it.pkg == android })
        // Named by the user as an extra: refused with the reason, never planned.
        assertTrue(refused(setup(choices = choices(extraOff = setOf(android))), snap).any { it.contains("framework") })
        // As a stock launcher in a bad profile: the claimant is never switched off.
        val bad = profile().copy(stockLaunchers = listOf(Component(android, ".ResolverActivity")))
        val r = PlannerFixtures.planner().plan(setup(profile = bad), snapshot(claimants = listOf(Component(android, ".ResolverActivity")), home = null, extra = mapOf(android to state)), PlannerFixtures.facts, emptyList())
        if (r is PlanResult.Ok) assertTrue(r.plan.steps.none { s -> DefaultPlanner.packageOf(s.op) == android })
    }

    // R1 R-3: the Manage requests see this TV's profile, so never_disable and the wizard quirk hold
    // at plan time, not only in the executor's live check.
    @Test fun manageRequestsApplyTheProfile() {
        val a = PackageName("com.google.android.videos")
        val quirky = profile(quirks = setOf(Quirk.HOME_TAKEOVER_SETUPWRAITH), setupWraith = wizard, neverDisable = setOf(a))
        val source = FakeProfileSource(listOf(quirky)).also { it.resolver = { _, all -> ProfileResolution.Exact(all.single()) } }
        val withProfile = PlannerFixtures.planner(profiles = source)
        fun refusedBy(req: PlanRequest, snap: Snapshot = snapshot()): List<String> = (withProfile.plan(req, snap, PlannerFixtures.facts, emptyList()) as PlanResult.Refused).reasons
        assertTrue(refusedBy(PlanRequest.SwitchOff(setOf(a))).single().contains(Denylist.REASON_PROFILE))
        assertTrue(refusedBy(PlanRequest.Leftovers(setOf(a))).single().contains(Denylist.REASON_PROFILE))
        // Without a source the planner cannot know (the executor's live check still refuses it).
        assertEquals(listOf<Op>(Op.Disable(a)), ops(plan(PlanRequest.SwitchOff(setOf(a)))))
        // The wizard with the quirk: only the home screen switch switches it off.
        assertTrue(refusedBy(PlanRequest.SwitchOff(setOf(wizard))).single().contains(DefaultPlanner.REASON_WIZARD_SECTION_ONLY))
        assertTrue(refusedBy(PlanRequest.Leftovers(setOf(wizard))).single().contains(DefaultPlanner.REASON_WIZARD_SECTION_ONLY))
        // And it comes back on only under the TV's own home screen (issue #49).
        val ours = snapshot(disabled = setOf(wizard.value, stock.pkg.value), home = launcher, claimants = listOf(launcher), extra = mapOf(installedLauncher()))
        assertTrue(refusedBy(PlanRequest.PutBack(wizard), ours).single().contains(DefaultPlanner.REASON_WIZARD_WHILE_OURS))
        assertEquals(listOf<Op>(Op.Enable(wizard)), ops(plan(PlanRequest.PutBack(wizard), snapshot(disabled = setOf(wizard.value)), planner = withProfile)))
        // The R0: 15 constructor still compiles and plans without a profile.
        assertEquals(listOf<Op>(Op.Enable(wizard)), ops(plan(PlanRequest.PutBack(wizard), ours)))
    }

    // R1 R-10: a stock launcher whose state before this app was DEFAULT ends DEFAULT, not ENABLED.
    @Test fun undoEverythingLeavesAnOriginallyDefaultStockLauncherDefault() {
        val changes = listOf(
            change(0, Op.Disable(stock.pkg), Prior(packageState = EnabledState.DEFAULT)),
            change(1, Op.SetHome(launcher), Prior(home = stock)),
        )
        val now = snapshot(disabled = setOf(stock.pkg.value), home = launcher, claimants = listOf(launcher, stock), extra = mapOf(installedLauncher()))
        val p = plan(PlanRequest.UndoEverything(changes, removeLauncher = false), now)
        assertEquals(listOf(Op.DefaultState(stock.pkg), Op.SetHome(stock), Op.VerifyHome(stock), Op.PressHome), ops(p))
    }

    // RFC-W8-5 and R1 R-1.
    @Test fun rebootIsItsOwnPlan() {
        val r = plan(PlanRequest.Reboot)
        assertEquals(listOf<Op>(Op.Reboot), ops(r))
        assertEquals(PlanKind.REBOOT, r.kind)
        assertEquals("Restarting the TV", r.steps.single().narration)
    }
    // Issue #2 (A1): Fire OS's own home screen is never switched off, and nothing of the new home
    // screen is installed for it: an unlisted Fire TV Edition set on the Amazon sure set, with the
    // TV's facts naming nothing (here the reference TV's), is caught by its packages.
    // RFC-SHOP-2 (2026-11-12): a vendor home with no Google launcher at all — the old gate
    // skipped the whole clean-up there and the Shop tab came back after every reboot. On a
    // vendor home the clean-up runs: the vendor ad packages are uninstalled, the
    // launcher's ad components are switched off (section 3.5), and the vendor launcher itself is
    // cleared and restarted — never uninstalled or disabled as a package.
    @Test fun cleanStockHomeRunsOnAVendorHomeAndSwitchesOffItsAdComponents() {
        val vendor = Component(PackageName("com.vendor.launcher"), ".MainActivity")
        val snap = snapshot(home = vendor, claimants = listOf(vendor),
            extra = mapOf(vendor.pkg to PackageState(EnabledState.ENABLED, true, 1, null, false, false, "Home", false)))
            .let { it.copy(packages = it.packages - PackageName("com.google.android.tvlauncher")) }
            .let { it.copy(contentHash = tv.cleanyour.core.store.SnapshotCodec.hash(it)) }
        val scan = tv.cleanyour.core.model.DeepScanResult(1, mapOf(
            vendor.pkg to tv.cleanyour.core.model.DeepScanEvidence(
                bootReceivers = listOf(".AdsBootReceiver"),
                services = listOf("com.vendor.launcher.ads.AdService"),
                activities = listOf(".MainActivity"),
            ),
        ))
        val req = PlanRequest.Setup(tv.cleanyour.core.spec.ProfileResolution.Exact(profile()),
            choices(cleanStockHome = true, uninstallBloat = true), deepScan = scan)
        val p = plan(req, snapshot = snap)
        assertEquals(
            listOf(
                Op.DisableComponent(Component(vendor.pkg, ".AdsBootReceiver")),
                Op.DisableComponent(Component(vendor.pkg, "com.vendor.launcher.ads.AdService")),
            ),
            ops(p).filterIsInstance<Op.DisableComponent>(),
        )
        assertTrue(ops(p).none { it is Op.Disable && it.pkg == vendor.pkg }, "the launcher itself is never disabled")
        assertTrue(ops(p).none { it is Op.Uninstall && it.pkg == vendor.pkg }, "the launcher itself is never uninstalled")
        assertTrue(Op.ClearPackage(vendor.pkg) in ops(p), "the launcher's cached ads are cleared")
        assertTrue(Op.RestartHome(vendor) in ops(p), "the launcher is restarted with them off")
    }

    // RFC-SHOP-3 (2026-11-12): Clean Stock Home switches off the launcher's own Shop
    // content activities (REA: the Shop tab is drawn by tvlauncher's MainActivity and its
    // pages are LibraryPageActivity + TvodPlayerActivity) alongside the four ad
    // components — and the launcher itself is never disabled or uninstalled.
    @Test fun cleanStockHomeSwitchesOffTheLauncherShopActivities() {
        val tvlauncher = PackageName("com.google.android.tvlauncher")
        val snap = snapshot(extra = mapOf(
            tvlauncher to PackageState(EnabledState.ENABLED, true, 1, null, false, false, "Android TV Home", false),
        ))
        val p = plan(setup(choices = choices(cleanStockHome = true, uninstallBloat = true)), snapshot = snap)
        val shop = listOf(
            Component(tvlauncher, "com.google.android.tvlauncher.shop.library.LibraryPageActivity"),
            Component(tvlauncher, "com.google.android.tvlauncher.shop.tvodplayer.TvodPlayerActivity"),
        )
        val disabled = ops(p).filterIsInstance<Op.DisableComponent>().map { it.component }
        assertTrue(shop.all { it in disabled }, disabled.toString())
        assertTrue(ReaEngine.TVLAUNCHER_AD_COMPONENTS.all { it in disabled }, disabled.toString())
        assertTrue(ops(p).none { it is Op.Disable && it.pkg == tvlauncher }, "the launcher itself is never disabled")
        assertTrue(ops(p).none { it is Op.Uninstall && it.pkg == tvlauncher }, "the launcher itself is never uninstalled")
    }

    // RFC-SHOP-4 (2026-11-12): the post-0.4.7-37 Skyworth SWTV — every Shop/ad package
    // already uninstalled, only the launcher's own Shop components left to switch off.
    // Before this fix the plan held the Shop DisableComponent steps but the Found screen
    // counted off == 0 (section 3.5 made no group row) and showed "Nothing to switch off"
    // with no "Make it clean" button, so the steps could never run.
    @Test fun cleanStockHomeCountsLauncherShopComponentsWhenEveryPackageIsGone() {
        val tvlauncher = PackageName("com.google.android.tvlauncher")
        val snap = snapshot(extra = mapOf(
            tvlauncher to PackageState(EnabledState.ENABLED, true, 1, null, false, false, "Android TV Home", false),
        )).let { s ->
            val keep = setOf(tvlauncher, PackageName("com.android.vending"), PackageName("com.android.externalstorage"))
            s.copy(packages = s.packages.filterKeys { it in keep })
                .let { it.copy(contentHash = tv.cleanyour.core.store.SnapshotCodec.hash(it)) }
        }
        val p = plan(setup(profile = null, choices = choices(cleanStockHome = true, uninstallBloat = true)), snapshot = snap)
        val shop = listOf(
            Component(tvlauncher, "com.google.android.tvlauncher.shop.library.LibraryPageActivity"),
            Component(tvlauncher, "com.google.android.tvlauncher.shop.tvodplayer.TvodPlayerActivity"),
        )
        val disabled = ops(p).filterIsInstance<Op.DisableComponent>().map { it.component }
        assertTrue(shop.all { it in disabled }, disabled.toString())
        // The Found screen's count: one aggregate row for the launcher's components.
        val stock = p.summary.groups.single { it.id == DefaultPlanner.GROUP_STOCK_CLEAN }
        assertEquals(1, stock.off, "the launcher's components count as switch-offs")
        assertEquals(1, stock.rows.size)
        assertEquals(1, p.summary.groups.sumOf { it.off })
        assertEquals(tvlauncher, stock.rows.single().pkg)
    }

    // RFC-SHOP-4: the LIST sheet keeps a launcher by package — a kept launcher's
    // components stay on, and its row reads off = false, so "Make it clean" is not
    // offered for work the person asked to keep.
    @Test fun cleanStockHomeKeepsALauncherWhoseComponentsAreKept() {
        val tvlauncher = PackageName("com.google.android.tvlauncher")
        val snap = snapshot(extra = mapOf(
            tvlauncher to PackageState(EnabledState.ENABLED, true, 1, null, false, false, "Android TV Home", false),
        )).let { s ->
            val keep = setOf(tvlauncher, PackageName("com.android.vending"), PackageName("com.android.externalstorage"))
            s.copy(packages = s.packages.filterKeys { it in keep })
                .let { it.copy(contentHash = tv.cleanyour.core.store.SnapshotCodec.hash(it)) }
        }
        val p = plan(setup(profile = null, choices = choices(kept = setOf(tvlauncher), cleanStockHome = true, uninstallBloat = true)), snapshot = snap)
        assertTrue(ops(p).none { it is Op.DisableComponent }, "a kept launcher's components stay on")
        val stock = p.summary.groups.single { it.id == DefaultPlanner.GROUP_STOCK_CLEAN }
        assertEquals(0, stock.off)
        assertEquals(0, p.summary.groups.sumOf { it.off })
    }

    // RFC-SHOP-3 (2026-11-12): Undo everything puts a switched-off launcher Shop activity
    // back on too — the Shop destination comes back with the rest of the clean-up.
    @Test fun undoEverythingPutsALauncherShopActivityBackOn() {
        val c = Component(PackageName("com.google.android.tvlauncher"),
            "com.google.android.tvlauncher.shop.tvodplayer.TvodPlayerActivity")
        val changes = listOf(change(0, Op.DisableComponent(c), Prior()))
        val p = plan(PlanRequest.UndoEverything(changes, removeLauncher = false), snapshot())
        assertEquals(listOf(Op.EnableComponent(c)), ops(p))
    }

    // 2026-11-12 stall (video): "Make it clean" spun on "Restore point saved" at 0% and
    // came back to Found, twice. Clean Stock Home recorded the installed Play Store as
    // absent, so the pre-plan staleness check saw "was absent, now installed" and paused
    // the run STALE before step 0. The prior records the actual presence (S3).
    @Test fun cleanStockHomeRecordsThePlayStoreAsInstalledWhenItIs() {
        val vending = PackageName("com.android.vending")
        val snap = snapshot(extra = mapOf(
            vending to PackageState(EnabledState.ENABLED, true, 1, null, false, false, "Play Store", false),
        ))
        val p = plan(setup(profile = null, choices = choices(cleanStockHome = true, uninstallBloat = true)), snapshot = snap)
        val step = p.steps.single { it.op is Op.InstallExisting && (it.op as Op.InstallExisting).pkg == vending }
        assertEquals(true, step.prior?.present, "an installed Play Store is present before us")
    }

    // RFC-SHOP-2 (2026-11-12): Undo everything puts a switched-off launcher ad component
    // back on — a component disable is a lasting change, so it is undone like a package
    // switch, in the package-states phase, with the enable as its inverse.
    @Test fun undoEverythingPutsALauncherAdComponentBackOn() {
        val c = Component(PackageName("com.google.android.tvlauncher"),
            "com.google.android.tvlauncher.home.featuredcarousel.WhyThisAdActivity")
        val changes = listOf(change(0, Op.DisableComponent(c), Prior()))
        val p = plan(PlanRequest.UndoEverything(changes, removeLauncher = false), snapshot())
        assertEquals(listOf(Op.EnableComponent(c)), ops(p))
        assertEquals("Switching the launcher ad component back on",
            p.steps.single { it.op == Op.EnableComponent(c) }.narration)
    }

    @Test fun aFireOsHomeIsProtectedAndTheCleanUpAloneIsPlanned() {
        val amazon = Component(PackageName("com.amazon.tv.launcher"), ".ui.HomeActivity_vNext")
        val starter = PackageName("com.amazon.firehomestarter")
        val fire = mapOf(
            amazon.pkg to PackageState(EnabledState.ENABLED, true, 1, null, false, false, "Home", false),
            starter to PackageState(EnabledState.ENABLED, true, 1, null, false, false, null, false),
        )
        val snap = snapshot(home = amazon, claimants = listOf(amazon), extra = fire).let { it.copy(packages = it.packages - PlannerFixtures.wizard) }
            .let { it.copy(contentHash = tv.cleanyour.core.store.SnapshotCodec.hash(it)) }
        val groups = listOf(tv.cleanyour.core.model.PackageGroup("ads", "Adverts", tv.cleanyour.core.model.GroupDefault.DISABLE,
            listOf(debloatList.first(), amazon.pkg, starter).map { tv.cleanyour.core.model.PackageEntry(it, null) }))
        fun sure(c: Choices) = PlanRequest.Setup(tv.cleanyour.core.spec.ProfileResolution.BrandSureSet("Amazon", groups, listOf(amazon)), c)
        // The clean-up alone: no install, no SetHome, and Fire OS's home screens are shown as kept.
        val p = plan(sure(choices()), snapshot = snap)
        assertTrue(p.sections.isEmpty())
        assertTrue(ops(p).none { it is Op.Install || it is Op.InstallStream || it is Op.SetHome })
        assertEquals(listOf<Op>(Op.Disable(debloatList.first())), ops(p))
        val rows = p.summary.groups.flatMap { it.rows }.filter { FireOs.isHome(it.pkg) }
        assertEquals(setOf(amazon.pkg, starter), rows.map { it.pkg }.toSet())
        assertTrue(rows.all { it.protected && !it.off && it.whyProtected == DefaultPlanner.REASON_FIRE_OS_HOME })
        // Nor can Manage switch one off.
        assertTrue(refused(PlanRequest.SwitchOff(setOf(amazon.pkg)), snapshot = snap).single().contains(DefaultPlanner.REASON_FIRE_OS_HOME))
    }

    // 0.4.6: Manage's wizard Home row ("Switch it back on") is this same plan. On an R0 TV (the
    // switch left the wizard on, so no record switches it back on) it switches the TV's own home
    // screen on and hands it Home, and nothing else: no setting (so no accessibility, no Wi-Fi).
    @Test fun useTheTvsOwnHomeScreenOnAnR0TvTouchesNothingElse() {
        val ours = snapshot(disabled = setOf(stock.pkg.value), home = launcher, claimants = listOf(launcher, Component(wizard, ".RecoveryActivity")), extra = mapOf(installedLauncher()))
            .withSettings(mapOf(SettingKey.ACCESSIBILITY_ENABLED to "0", SettingKey.ADB_ENABLED to "1"))
        val journals = listOf(change(1, Op.ArmHomeWatchdog(stock, 150, tv.cleanyour.core.model.RunToken("00000000000000aa")), null),
            change(2, Op.Disable(stock.pkg), Prior(packageState = EnabledState.ENABLED)), change(3, Op.SetHome(launcher), Prior(home = stock)))
        val p = plan(PlanRequest.UseStockHome(stock), ours, journals)
        assertEquals(listOf(Op.Enable(stock.pkg), Op.SetHome(stock), Op.VerifyHome(stock), Op.PressHome), ops(p))
    }
}

