package tv.cleanyour.core.flows

import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.ops.ShellRenderer
import tv.cleanyour.core.planner.Choices
import tv.cleanyour.core.planner.DefaultPlanner
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.planner.disabledPackages
import tv.cleanyour.core.planner.switchOffCount
import tv.cleanyour.core.planner.switchOffPackages
import tv.cleanyour.core.spec.BundledFactsTable
import tv.cleanyour.core.spec.BundledProfileSource
import tv.cleanyour.core.spec.DockRanking
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.testing.fixtures.FakeClock
import tv.cleanyour.testing.fixtures.FakeIdGen
import tv.cleanyour.testing.fixtures.Fixtures
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Emulator run, round 3: on the emulator TV S3 said nothing was found to switch off while the
// plan log said "9 steps, 1 to switch off". Here S3's numbers (the numeral, the group rows,
// "n of m off" in the list), the list's Off rows, "Show commands" and the plan "Make it clean" runs
// must agree exactly, with the bundled profiles and facts the app ships, for the reference Mi TV
// and an unknown model.
class WhatWeFoundMatchesThePlanTest {
    private val facts = BundledFactsTable()
    private val profiles = BundledProfileSource()
    private val planner = DefaultPlanner(FakeClock(), FakeIdGen(), ShellRenderer(), "0.1.0")
    // The Setup request SetupMachine.replan sends for a first visit in Simple: nothing kept,
    // nothing extra. Stock-only: the plan switches apps off and cleans the stock home screen's
    // ads; it never installs, switches or themes a home screen.
    private fun plan(snapshot: Snapshot): Pair<Plan, ProfileResolution> {
        val res = profiles.resolveNow(snapshot.facts)
        val choices = Choices(emptySet(), emptySet(), false)
        val r = planner.plan(PlanRequest.Setup(res, choices), snapshot, facts, emptyList())
        return assertIs<PlanResult.Ok>(r, r.toString()).plan to res
    }

    // Every rule the screen and the plan share, in one place.
    private fun assertS3AgreesWithThePlan(plan: Plan) {
        val summary = plan.summary
        val disabled = plan.disabledPackages
        // S3's numeral and headline.
        assertEquals(disabled.size, summary.switchOffCount, "S3 counts every switch-off of the plan")
        // The summary card's group rows add up to the same number.
        assertEquals(disabled.size, summary.groups.filter { it.off > 0 }.sumOf { it.off })
        // "See the list": the Off rows are exactly the packages the plan switches off, once each.
        assertEquals(disabled.sortedBy { it.value }, summary.switchOffPackages.sortedBy { it.value })
        assertEquals(disabled.size, disabled.toSet().size)
        // Each group's count is its own Off rows.
        for (g in summary.groups) {
            assertEquals(g.rows.count { it.off }, g.off, g.id)
            assertEquals(g.rows.size, g.total, g.id)
        }
        // A row the list shows as kept is never switched off; nothing protected and kept is either.
        val keptRows = summary.groups.flatMap { it.rows }.filter { !it.off }.map { it.pkg }
        assertTrue(keptRows.none { it in disabled }, "a kept row is switched off: ${keptRows.filter { it in disabled }}")
        // "Show commands": every step of the plan, in order, nothing else, so every write is shown.
        val commands = SetupMachine.commandsOf(plan)
        val expected = plan.steps.flatMap { s -> s.rendered.lines.map { it.text } + listOfNotNull(s.rendered.transfer?.let { "" }) }
        assertEquals(expected.size, commands.size, "one line per rendered line and transfer")
        for (step in plan.steps) for (line in step.rendered.lines) assertTrue(line.text in commands, line.text)
        for (pkg in disabled) assertTrue(commands.any { pkg.value in it && "disable" in it }, "$pkg missing from Show commands")
    }

    @Test fun referenceMiTv() {
        val snapshot = Samples.snapshot(packages = Samples.miTvPackages(emptySet()))
        val (plan, res) = plan(snapshot)
        assertIs<ProfileResolution.Exact>(res)
        assertS3AgreesWithThePlan(plan)
        val dock = DockRanking.pick(res.profile, snapshot.packages.keys, snapshot.hdmi.isNotEmpty(), res.profile.liveTv, "en")
        assertTrue(dock.isNotEmpty())
        assertTrue(plan.summary.switchOffCount > 1)
    }

    @Test fun referenceMiTvAfterItsHandDebloat() {
        // The capture's own state: most of the list already off. Only what is still on is counted.
        val (plan, _) = plan(Samples.snapshot())
        assertS3AgreesWithThePlan(plan)
        val alreadyOff = Fixtures.miTv().disabledAfter.map(::PackageName).toSet()
        assertTrue(plan.summary.switchOffPackages.none { it in alreadyOff })
    }

    // RF-SAFETY-250: a TV no profile knows keeps its switch only without a setup wizard that is on.
    private val wizard = PackageName("com.google.android.tungsten.setupwraith")
    private fun Snapshot.withoutWizard(): Snapshot = copy(packages = packages - wizard).let { it.copy(contentHash = tv.cleanyour.core.store.SnapshotCodec.hash(it)) }
    private fun assertTheCleanUpAlone(snapshot: Snapshot) {
        assertTrue(wizard in snapshot.packages)
        val (plan, _) = plan(snapshot)
        assertS3AgreesWithThePlan(plan)
        assertTrue(plan.sections.isEmpty(), "no home screen switch while the wizard stays on")
        assertTrue(plan.steps.none { it.op is Op.SetHome || it.op == Op.Disable(Samples.stockLauncher.pkg) })
    }

    @Test fun unknownModel() {
        val facts = Samples.miTvFacts().copy(brand = "Acme", manufacturer = "Acme", model = "ACME-55", device = "acme55", fingerprint = "acme/acme55:9/x")
        val withWizard = Samples.snapshot(facts = facts, packages = Samples.miTvPackages(emptySet()))
        assertTheCleanUpAlone(withWizard)
        val snapshot = withWizard.withoutWizard()
        val (plan, res) = plan(snapshot)
        assertIs<ProfileResolution.None>(res)
        assertS3AgreesWithThePlan(plan)
        // Nothing is sure on an unknown model: nothing is switched off at all.
        assertTrue(plan.disabledPackages.isEmpty())
        assertEquals(0, plan.summary.switchOffCount)
    }
}
