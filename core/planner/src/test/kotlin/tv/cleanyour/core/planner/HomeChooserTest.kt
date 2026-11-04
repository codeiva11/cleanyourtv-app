package tv.cleanyour.core.planner

import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.planner.PlannerFixtures.plan
import tv.cleanyour.core.planner.PlannerFixtures.profile
import tv.cleanyour.core.planner.PlannerFixtures.refused
import tv.cleanyour.core.planner.PlannerFixtures.snapshot
import tv.cleanyour.core.planner.PlannerFixtures.stock
import tv.cleanyour.core.spec.KnownLaunchers
import tv.cleanyour.core.store.SnapshotCodec
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// WP4 item 3 (B5): HOME answers with the system chooser (`resolve-activity` gives
// android/com.android.internal.app.ResolverActivity when two home screens tie) or with nothing at
// all. Then every HOME claimant, and the TV's stock home too, must still be protected outside the
// home screen switch: neither SwitchOff nor Leftovers may plan one, and the engine's live check
// (the same SwitchOffRule) refuses one.
class HomeChooserTest {
    private val chooser = Component(PackageName("android"), "com.android.internal.app.ResolverActivity")
    private val launcherx = KnownLaunchers.LAUNCHERX
    private val vendorHome = Component(PackageName("com.mitv.tvhome"), ".MainActivity")
    private val persons = Component(PackageName("com.spocky.projengmenu"), ".ui.home.MainActivity")
    private fun sys(label: String) = PackageState(EnabledState.ENABLED, true, 1, null, false, false, label, false)
    private val extra = mapOf(
        launcherx.pkg to sys("Google TV Home"), vendorHome.pkg to sys("PatchWall"),
        persons.pkg to PackageState(EnabledState.ENABLED, false, 1, null, false, false, "Projectivy", false),
    )

    private fun tv(resolved: Component?, claimants: List<Component>): Snapshot = snapshot(extra = extra).let { s ->
        s.copy(home = HomeResolution(resolved, claimants.mapIndexed { i, c -> HomeClaimant(c, 10 - i) })).let { it.copy(contentHash = SnapshotCodec.hash(it)) }
    }

    @Test fun everyClaimantAndTheStockHomeAreProtectedWhileHomeIsTheChooserOrNothing() {
        val claimants = listOf(stock, launcherx, vendorHome, persons)
        for (resolved in listOf(chooser, null)) {
            val s = tv(resolved, claimants)
            for (stockLaunchers in listOf(listOf(stock), emptyList())) {
                val p = profile().copy(stockLaunchers = stockLaunchers)
                for (c in claimants) {
                    assertNotNull(SwitchOffRule.reason(c.pkg, s.packages[c.pkg], p, s, stockLaunchers), "${c.flat} with HOME ${resolved?.flat}, stock $stockLaunchers")
                    assertNotNull(SwitchOffRule.reason(c.pkg, s.packages[c.pkg], null, s, emptyList()), "${c.flat} with no profile")
                }
                assertNotNull(SwitchOffRule.reason(stock.pkg, s.packages[stock.pkg], p, s, stockLaunchers), "the stock home")
            }
            // The planner (no profile source: no stock launchers known) refuses each one.
            for (c in claimants) assertTrue(refused(PlanRequest.SwitchOff(setOf(c.pkg)), snapshot = s).single().contains("cannot be switched off"), c.flat)
            // Leftovers skips nothing silently: a home screen there is a refusal, never a step.
            val r = PlannerFixtures.planner().plan(PlanRequest.Leftovers(claimants.map { it.pkg }.toSet()), s, PlannerFixtures.facts, emptyList())
            if (r is PlanResult.Ok) assertTrue(r.plan.steps.none { it.op is Op.Disable })
        }
    }

    // Only a claimant is known to be a home screen, so a known TV home screen that HOME does not
    // list (it lost the claim, or the list read short) is protected too while HOME is undecided.
    @Test fun aKnownStockHomeThatIsNotAClaimantIsProtectedWhileHomeIsUndecided() {
        for (resolved in listOf(chooser, null, Component(PackageName("com.android.tv.settings"), ".system.FallbackHome"))) {
            val s = tv(resolved, listOf(persons))
            for (known in listOf(KnownLaunchers.TVLAUNCHER, launcherx)) {
                assertNotNull(SwitchOffRule.reason(known.pkg, s.packages[known.pkg], null, s, emptyList()), "${known.flat} with HOME ${resolved?.flat}")
            }
        }
        // With a real answer the rule is as before: a known launcher HOME does not name stays a choice
        // only where the profile does not list it as stock.
        val decided = tv(persons, listOf(persons))
        assertNull(SwitchOffRule.reason(launcherx.pkg, decided.packages[launcherx.pkg], null, decided, emptyList()))
        assertNotNull(SwitchOffRule.reason(launcherx.pkg, decided.packages[launcherx.pkg], null, decided, listOf(launcherx)))
    }

    // Inside the switch the TV's own home screen still goes off as before, the chooser answering HOME.
    // WP4 item 4: the SetHome's prior (the recorded stock Home) is the stock home, never the chooser,
    // setupwraith's RecoveryActivity, a FallbackHome or nothing.
}
