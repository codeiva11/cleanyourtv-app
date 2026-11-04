package tv.cleanyour.core.flows

import tv.cleanyour.core.backup.ChangeSummary
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.planner.StockHome
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// WP4 item 4 (B5): what counts as a placeholder home screen (never the TV's own home screen):
// the system chooser, setupwraith's RecoveryActivity and FallbackHome entries.
class RestorePointHomeTest {
    private val stock = Samples.stockLauncher
    private val chooser = Component(PackageName("android"), "com.android.internal.app.ResolverActivity")
    private val recovery = Component(PackageName("com.google.android.tungsten.setupwraith"), ".RecoveryActivity")
    private val fallback = Component(PackageName("com.android.tv.settings"), ".system.FallbackHome")
    private val vendorFallback = Component(PackageName("com.tcl.keycustomfunctionservice"), ".FallbackHome")

    // The rule itself: what counts as a placeholder, and what a real home screen records as.
    @Test fun placeholdersAreTheChooserFallbackHomesAndTheWizard() {
        for (c in listOf(chooser, recovery, fallback, vendorFallback, Component(PackageName("com.android.provision"), ".DefaultActivity"),
            Component(PackageName("com.xiaomi.mitv.settings"), ".entry.FallbackHome"))) assertTrue(StockHome.isPlaceholder(c), c.flat)
        for (c in listOf(stock, Component(PackageName("com.google.android.apps.tv.launcherx"), ".home.HomeActivity"), Component(PackageName("com.spocky.projengmenu"), ".ui.home.MainActivity")))
            assertFalse(StockHome.isPlaceholder(c), c.flat)
        val other = Component(PackageName("com.spocky.projengmenu"), ".ui.home.MainActivity")
        val s = Samples.miTvSnapshot()
        assertEquals(other, StockHome.recorded(s.copy(home = HomeResolution(other, s.home.claimants)), stock), "a real home screen is recorded as it is")
        assertEquals(stock, StockHome.recorded(s.copy(home = HomeResolution(chooser, s.home.claimants)), stock))
        assertEquals(stock, StockHome.recorded(s.copy(home = HomeResolution(null, s.home.claimants)), stock))
    }
}
