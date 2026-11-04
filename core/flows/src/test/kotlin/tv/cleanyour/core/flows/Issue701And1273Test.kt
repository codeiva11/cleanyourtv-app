package tv.cleanyour.core.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.spec.BundledProfileSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Issues #701 (TCL Percee TV, tcl_eu, Android 8, 0.4.6-beta2) and #1273 (Xiaomi MIBOX4, oneday,
// Android 12, 0.4.5): each report said "Profile: exact" and "Home switch: blocked WIZARD_STAYS_ON",
// with only tvlauncher and Settings' FallbackHome claiming Home and no wizard in the list. What
// remains of these reports now: they still parse as the app made them, and the decision file
// still records the version and the list a decision was made with.
@OptIn(ExperimentalCoroutinesApi::class)
class Issue701And1273Test {
    private val reports = mapOf("tv-701.txt" to "exact tcl-50dp660", "tv-1273.txt" to "exact xiaomi-mi-box-s-android12")
    private val source = BundledProfileSource()

    @Test fun theReportsAreReadAsTheAppMadeThem() {
        for ((name, profile) in reports) {
            val r = ReportText.parse(ReportText.fixture(name))
            assertEquals(profile, r.profileLine, name)
            assertEquals("blocked WIZARD_STAYS_ON", r.homeLine, name)
            assertEquals(listOf("com.google.android.tvlauncher", "com.android.tv.settings"), r.snapshot.home.claimants.map { it.component.pkg.value }, name)
            assertEquals(profile, LogCodes.profile(source.resolveNow(r.facts, home = r.snapshot.home, packages = r.snapshot.packages)), name)
        }
    }

    @Test fun theDecisionFileKeepsTheVersionAndTheList() = runTest {
        val h = Harness()
        val id = { Samples.miTvId() }
        val store = HomeDecisions(h.documents)
        store.save(id(), HomeDecision(false, "WIZARD_WIFI", app = "0.4.6-beta3", profile = "brand set Xiaomi"))
        assertEquals("home=blocked:WIZARD_WIFI\napp=0.4.6-beta3\nprofile=brand set Xiaomi\n", h.documents.read("tvs/${id().value}/decision.txt") { it.decodeToString() })
        assertEquals(HomeDecision(false, "WIZARD_WIFI", app = "0.4.6-beta3", profile = "brand set Xiaomi"), store.load(id()))
        store.save(id(), HomeDecision(true))
        assertEquals("home=planned\n", h.documents.read("tvs/${id().value}/decision.txt") { it.decodeToString() })
    }
}

private typealias Samples = tv.cleanyour.testing.fixtures.Samples
