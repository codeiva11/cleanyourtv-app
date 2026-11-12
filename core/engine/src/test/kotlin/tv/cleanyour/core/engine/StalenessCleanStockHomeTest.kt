package tv.cleanyour.core.engine

import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.ops.ShellRenderer
import tv.cleanyour.core.planner.Choices
import tv.cleanyour.core.planner.DefaultPlanner
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.testing.fixtures.FakeClock
import tv.cleanyour.testing.fixtures.FakeIdGen
import tv.cleanyour.testing.fixtures.MapFactsTable
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 2026-11-12 stall (video): "Make it clean" spun on "Restore point saved" at 0% and came
// back to Found, twice. The pre-plan staleness check paused the run STALE before step 0
// because Clean Stock Home recorded the installed Play Store as absent. The plan the
// planner builds must pass that same check against the snapshot it was planned on —
// otherwise no run on that TV can ever start.
class StalenessCleanStockHomeTest {
    private val vending = PackageName("com.android.vending")
    private val vendingState = PackageState(EnabledState.ENABLED, true, 1, null, false, false, "Play Store", false)

    private fun snapshot(withVending: Boolean) = Samples.snapshot(
        packages = if (withVending) Samples.miTvPackages() + (vending to vendingState)
        else Samples.miTvPackages() - vending,
    )

    private fun setupPlan(withVending: Boolean) =
        (DefaultPlanner(FakeClock(), FakeIdGen(), ShellRenderer(), "0.1.0").plan(
            PlanRequest.Setup(
                ProfileResolution.None(listOf(Samples.stockLauncher)),
                Choices(emptySet(), emptySet(), keepDebuggingOn = false, cleanStockHome = true, uninstallBloat = true),
            ),
            snapshot(withVending), MapFactsTable(), emptyList(),
        ) as PlanResult.Ok).plan

    @Test fun cleanStockHomePlanWithPlayStoreInstalledIsNotStale() {
        val snap = snapshot(withVending = true)
        assertEquals(emptyList(), Staleness.diff(setupPlan(withVending = true).steps, snap))
    }

    @Test fun cleanStockHomePlanWithPlayStoreAbsentIsNotStale() {
        val snap = snapshot(withVending = false)
        assertEquals(emptyList(), Staleness.diff(setupPlan(withVending = false).steps, snap))
    }

    @Test fun aGenuinelyRemovedPlayStoreIsStillStale() {
        val planned = setupPlan(withVending = true)
        val diff = Staleness.diff(planned.steps, snapshot(withVending = false))
        assertTrue(diff.any { it.startsWith(vending.value) }, "removing the Play Store between plan and run is a real change: $diff")
    }
}
