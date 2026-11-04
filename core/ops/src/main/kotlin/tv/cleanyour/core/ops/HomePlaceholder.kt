package tv.cleanyour.core.ops

import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName

// A HOME answer that is not a home screen (WP4, B5): the system chooser (package android, its
// ResolverActivity), a FallbackHome placeholder the firmware shows while no launcher is on
// (com.android.tv.settings/.system.FallbackHome, com.tcl.keycustomfunctionservice/.FallbackHome),
// the first-run setup wizard (setupwraith's RecoveryActivity, com.android.provision) and, since
// issue #1944, a factory test app (isFactoryHome). Never recorded as the TV's own home screen, so
// neither step R nor Undo everything hands HOME to one.
// Here in :core:ops so the planner, the engine and the restore files (:core:backup) share it.
object HomePlaceholder {
    fun isPlaceholder(c: Component): Boolean {
        val pkg = c.pkg.value
        return pkg == Denylist.FRAMEWORK || "FallbackHome" in c.cls || pkg == "com.android.provision" ||
            pkg in Denylist.setupWizards || pkg in Denylist.vendorSetupWizards || "setupwraith" in pkg.lowercase() ||
            isFactoryHome(c.pkg)
    }

    // Issue #1944 (Xiaomi MiTV-MOOQ0, 0.4.6): with the TV's own home screen off and the new one gone,
    // Home resolved to com.duokan.factorytest/.FactoryLauncher, which answers Home at priority 0, the
    // same as an app the person installs. A factory test or factory menu app is never a home screen:
    // upstream's tools/disable.sh rule (TROUBLESHOOTING at b06ab21: a package named *factorytest*,
    // *factory_test* or *factorymode* does not count as something else that answers Home), and TCL's
    // factory view, whose FactoryTvActivity claims Home at priority 0 on a TCL 43S5K (issue #1838).
    // R0 still counts one as another home screen (the R0 placeholder rule), as before.
    private val FACTORY_WORDS = listOf("factorytest", "factory_test", "factorymode")
    private val FACTORY_VIEWS = setOf("com.tcl.factory.view")

    fun isFactoryHome(pkg: PackageName): Boolean =
        pkg.value in FACTORY_VIEWS || pkg.value.lowercase().let { name -> FACTORY_WORDS.any { it in name } }

    // The factory test apps that answer Home and that a tested list switches off: the MOOQ0 list's
    // "Home-screen claimants" group (upstream PR #57, firmware 5135 checked in PR #243), whose header
    // says to switch it off before the TV's own home screen, or Home can land on it. Only these may
    // go off as a Home claimant outside the switch (SwitchOffRule.isHomeScreen), and the switch takes
    // one that is still on off before the TV's own home screen (DefaultPlanner.takeover). TCL's
    // factory view is not one: the TCL lists say never to switch it off.
    val FACTORY_HOMES_OFF: Set<PackageName> = setOf(PackageName("com.duokan.factorytest"))

    // The TV's own home screen the home screen switch handed back to, from the change records: the
    // stock component of the earliest ArmHomeWatchdog (the takeover arms it with StockHome.of).
    // Journals from before 0.4.5 may hold a placeholder as a SetHome prior; this is what replaces it.
    fun stockFromRecords(changes: List<ChangeRecord>): Component? =
        changes.filter { it.op is Op.ArmHomeWatchdog }.minWithOrNull(UndoOrder.CHRONOLOGICAL)?.let { (it.op as Op.ArmHomeWatchdog).stock }
            ?.takeUnless { isPlaceholder(it) }
}
