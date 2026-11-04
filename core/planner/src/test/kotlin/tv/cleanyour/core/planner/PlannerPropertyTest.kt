package tv.cleanyour.core.planner

import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.GroupDefault
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageEntry
import tv.cleanyour.core.model.PackageGroup
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Quirk
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.ops.Denylist
import tv.cleanyour.core.ops.Protected
import tv.cleanyour.core.planner.PlannerFixtures.choices
import tv.cleanyour.core.planner.PlannerFixtures.facts
import tv.cleanyour.core.planner.PlannerFixtures.launcher
import tv.cleanyour.core.planner.PlannerFixtures.planner
import tv.cleanyour.core.planner.PlannerFixtures.profile
import tv.cleanyour.core.planner.PlannerFixtures.stock
import tv.cleanyour.core.planner.PlannerFixtures.wizard
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.core.store.SnapshotCodec
import tv.cleanyour.testing.fixtures.Samples
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ARCHITECTURE 6.3 planner invariants over seeded random TVs and choices: no protected package in
// any plan; apply(plan) then apply(UndoEverything) returns the TV to its baseline; planning again
// against an applied TV yields an empty plan.
class PlannerPropertyTest {
    private val rnd = Random(7)
    private val protectedNames = Denylist.exact.keys.filter { it != Denylist.FRAMEWORK } + listOf("com.android.networkstack.tethering", "com.android.wifi.resources")
    private val ordinary = (1..40).map { "com.vendor.app$it" } + listOf("com.google.android.videos", "com.netflix.ninja", "com.miui.tv.analytics")

    private fun randomTv(): Triple<Snapshot, List<PackageGroup>, Set<PackageName>> {
        val packages = linkedMapOf<PackageName, PackageState>()
        fun put(name: String, persistent: Boolean = false, tvInput: Boolean = false, enabled: EnabledState = EnabledState.ENABLED) {
            packages[PackageName(name)] = PackageState(enabled, rnd.nextBoolean(), 1, null, persistent, tvInput, null, false)
        }
        for (n in ordinary) if (rnd.nextInt(4) != 0) put(n, persistent = rnd.nextInt(10) == 0, tvInput = rnd.nextInt(12) == 0, enabled = if (rnd.nextInt(6) == 0) EnabledState.DISABLED_USER else EnabledState.ENABLED)
        for (n in protectedNames) if (rnd.nextBoolean()) put(n)
        put(stock.pkg.value); put(wizard.value); put("com.google.android.inputmethod.latin")
        val ime = Component(PackageName("com.google.android.inputmethod.latin"), ".LatinIME")
        val claimants = listOf(stock) + if (rnd.nextBoolean()) listOf(Component(wizard, ".RecoveryActivity")) else emptyList()
        val snap = Samples.snapshot(packages = packages, home = HomeResolution(stock, claimants.mapIndexed { i, c -> HomeClaimant(c, 2 - i) }))
            .copy(ime = ime).let { it.copy(contentHash = SnapshotCodec.hash(it)) }
        // Profile groups that deliberately include protected names and the home screen.
        val pool = packages.keys.toList()
        val groups = (1..rnd.nextInt(1, 4)).map { g ->
            PackageGroup("g$g", "Group $g", GroupDefault.entries[rnd.nextInt(3)], pool.shuffled(rnd).take(rnd.nextInt(0, pool.size + 1)).map { PackageEntry(it, null) })
        }
        val never = pool.shuffled(rnd).take(rnd.nextInt(0, 3)).toSet()
        return Triple(snap, groups, never)
    }

    private fun expectedProtected(snap: Snapshot, prof: Profile): Set<PackageName> =
        snap.packages.keys.filter { Denylist.reason(it, snap, prof) != null || it == stock.pkg || it == wizard }.toSet()

    @Test fun noProtectedPackageInAnyPlanAndApplyThenUndoIsTheBaseline() {
        var plans = 0; var disables = 0
        repeat(150) {
            val (snap, groups, never) = randomTv()
            val quirk = rnd.nextBoolean()
            val prof = profile(quirks = if (quirk) setOf(Quirk.HOME_TAKEOVER_SETUPWRAITH) else emptySet(), setupWraith = if (quirk) wizard else null, neverDisable = never)
                .copy(groups = groups)
            val pool = snap.packages.keys.toList()
            val ch = choices(
                kept = pool.shuffled(rnd).take(rnd.nextInt(0, 5)).toSet(),
                extraOff = pool.shuffled(rnd).take(rnd.nextInt(0, 5)).filter { Denylist.reason(it, snap, prof) == null && it != stock.pkg && it != wizard }.toSet(),
            )
            val tv = ModelTv(snap)
            val result = planner().plan(PlanRequest.Setup(ProfileResolution.Exact(prof), ch), snap, facts, emptyList())
            val plan = (result as? PlanResult.Ok)?.plan ?: return@repeat
            plans++
            // Stock-only: the stock launcher and the wizard are never switched off, so they stay
            // in the protected set (no section carves them out any more).
            val protectedHere = expectedProtected(snap, prof)
            for (s in plan.steps) {
                val pkg = DefaultPlanner.packageOf(s.op) ?: continue
                if (s.op is Op.Disable || s.op is Op.Uninstall || s.op is Op.DefaultState) {
                    assertTrue(pkg !in protectedHere, "protected ${pkg.value} in plan: ${s.op}")
                    assertTrue(Protected.isProtected(pkg, snap.packages[pkg], if (pkg == wizard) prof else null) == null, "Protected says no: ${pkg.value}")
                    assertTrue(pkg != snap.ime?.pkg)
                }
                if (s.op is Op.Disable) disables++
            }
            // The stock launcher is never switched off, whatever never_disable says (D25).
            if (stock.pkg in never) assertTrue(plan.steps.none { it.op == Op.Disable(stock.pkg) }, "never_disable keeps the stock launcher enabled (D25)")
            // Apply, then undo everything from the change records: back to the baseline.
            val baseline = tv.state()
            tv.apply(plan)
            val applied = tv.snapshot()
            val again = planner().plan(PlanRequest.Setup(ProfileResolution.Exact(prof), ch), applied, facts, tv.changes)
            val replan = (again as? PlanResult.Ok)?.plan ?: throw AssertionError("re-plan refused: $again")
            assertEquals(emptyList<Op>(), replan.steps.map { it.op }, "planning against an applied TV must be empty")
            val undo = planner().plan(PlanRequest.UndoEverything(tv.changes, removeLauncher = true), applied, facts, tv.changes)
            val undoPlan = (undo as? PlanResult.Ok)?.plan ?: throw AssertionError("undo refused: $undo")
            tv.apply(undoPlan)
            assertEquals(baseline, tv.state(), "apply then undo differs from the baseline")
            assertTrue(launcher.pkg !in tv.packages)
        }
        assertTrue(plans > 100 && disables > 200, "plans=$plans disables=$disables")
    }

}
