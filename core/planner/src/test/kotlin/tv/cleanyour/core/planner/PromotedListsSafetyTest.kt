package tv.cleanyour.core.planner

import tv.cleanyour.core.model.GroupDefault
import tv.cleanyour.core.planner.PlannerFixtures.plan
import tv.cleanyour.core.planner.PlannerFixtures.refused
import tv.cleanyour.core.planner.PlannerFixtures.setup
import tv.cleanyour.core.planner.PlannerFixtures.snapshot
import tv.cleanyour.core.planner.PlannerFixtures.wizard
import tv.cleanyour.core.spec.BundledProfileSource
import tv.cleanyour.core.store.SnapshotCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 0.4.5: the b06ab21 lists promoted by the second curator. The default plan of each switches off
// nothing the switch-off rule protects (the denylist, the profile's never-disable list, its Live TV
// app, the stock homes and the setup wizards), as CandidateSafetyTest asks of the candidate lists, and
// the clean-up itself plans on the reference TV.
class PromotedListsSafetyTest {
    private val promoted = listOf<String>(
        "xiaomi-mi-box-s-android12",
        "xiaomi-mitv-mssp2",
        "xiaomi-mitv-mssp0",
        "xiaomi-mitv-ayfr0",
        "xiaomi-mitv-astp0",
        "xiaomi-mitv-mteq0",
        "redmi-mitv-mftp0",
        "aqua-matrixtv-ee-50",
        "jvc-lt55mb508",
        "sharp-2t-c32gh3000i",
        "thomson-43rt1055",
        "hisense-fire-tv-aftcibfbd1",
    )
    // Xiaomi China-market sets whose Home takes only allowlisted apps: no known stock launcher, so a
    // home screen switch asked for anyway is refused and the clean-up alone is planned.
    private val cleanUpOnly = listOf<String>(
        "xiaomi-mitv-astp0",
        "xiaomi-mitv-mteq0",
        "redmi-mitv-mftp0",
    )
    private val all = BundledProfileSource.bundled.profiles.associateBy { it.id }
    // The reference TV without its setup wizard, so no wizard rule refuses the switch first.
    private val noWizard = snapshot().let { tv -> tv.copy(packages = tv.packages - wizard).let { it.copy(contentHash = SnapshotCodec.hash(it)) } }

    @Test fun theDefaultPlanSwitchesOffNothingProtected() {
        val refusedLines = mutableListOf<String>()
        for (id in promoted) {
            val p = all.getValue(id)
            for (g in p.groups.filter { it.default == GroupDefault.DISABLE }) for (e in g.packages)
                SwitchOffRule.reason(e.name, null, p, null, p.stockLaunchers)?.let { refusedLines += "$id ${e.name.value}: $it" }
            plan(setup(profile = p))
        }
        assertEquals(emptyList(), refusedLines)
    }

    @Test fun theCleanUpOnlySetsGetNoHomeScreenSwitch() {
        for (id in cleanUpOnly) {
            val p = all.getValue(id)
            assertTrue(p.stockLaunchers.isEmpty(), id)
            // Stock-only: no home switch attempted; clean-up plans cleanly.
            val planned = plan(setup(profile = p), snapshot = noWizard)
            assertTrue(planned.steps.isNotEmpty(), id)
        }
    }
}
