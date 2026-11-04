package tv.cleanyour.core.flows

import tv.cleanyour.core.spec.ProfileResolution
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Issue #945 and the review of beta4: PatchWall on while Mi TV+ or its web content is off, and not by
// this app (no Disable record of ours), so the plan cannot switch it back on. Setup offers no home
// screen switch and says why (PATCHWALL_FEEDS). The TV is the Mi TV 4i of issue #1119 (brand set
// Xiaomi, Android 9, R0's claimants) with PatchWall on and the web content off.
class Issue945SetupTest {

    // Review of beta4: a VENDOR_HOME block comes from the TV's own claimants, so an earlier Setup's
    // decision keeps its line after an app update.
    @Test fun aVendorHomeDecisionKeepsItsLine() {
        assertTrue("VENDOR_HOME" in HomeDecision.KEEPS_ITS_LINE)
        val d = HomeDecision(false, "VENDOR_HOME", app = "0.4.6-beta4", profile = "brand set Thomson")
        assertFalse(d.earlier("0.4.6", ProfileResolution.None(emptyList())))
        assertFalse(HomeDecision(false, "VENDOR_HOME").earlier("0.4.6", null))
        assertTrue(HomeDecision(false, "OTHER_HOME").earlier("0.4.6", null), "other blocks still tell an earlier decision")
    }
}
