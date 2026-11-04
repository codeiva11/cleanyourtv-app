package tv.cleanyour.core.planner

import tv.cleanyour.core.ops.HelperLines

import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.DeviceFacts
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.planner.PlannerFixtures.change
import tv.cleanyour.core.planner.PlannerFixtures.installedLauncher
import tv.cleanyour.core.planner.PlannerFixtures.launcher
import tv.cleanyour.core.planner.PlannerFixtures.snapshot
import tv.cleanyour.core.planner.PlannerFixtures.stock
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.core.spec.ProfileSource
import tv.cleanyour.core.store.SnapshotCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// WP4 G4 review: Undo everything (and Before a factory reset) on TVs the earlier builds and the
// 0.4.5 update path leave behind.
class UndoSafetyTest {
    private val mic = PackageName("com.google.android.tv.remote.service")
    private val chooser = Component(PackageName("android"), "com.android.internal.app.ResolverActivity")
    private val helpers = "com.gathin.tvhome/com.gathin.tvhome.InputKeyService"

    private fun Snapshot.with(pkg: PackageName, state: EnabledState) =
        copy(packages = packages + (pkg to PackageState(state, true, 1, null, false, false, null, false))).let { it.copy(contentHash = SnapshotCodec.hash(it)) }

    private fun undo(s: Snapshot, changes: List<ChangeRecord>, planner: DefaultPlanner = PlannerFixtures.planner()): Plan {
        val r = planner.plan(PlanRequest.UndoEverything(changes, removeLauncher = false), s, PlannerFixtures.facts, changes)
        return assertIs<PlanResult.Ok>(r, r.toString()).plan
    }

    // BLOCKING: an earlier build switched off TCL G10's remote mic service, which the static
    // denylist protects now. Its DefaultState inverse is a write the renderer refuses, which used
    // to refuse the whole Undo everything. Off now: one Enable. On now (put back since): no step.
    // An inverse that would switch it off is never planned.
    @Test fun aProtectedPackageOnlyEverGoesBackOn() {
        val off = listOf(change(0, Op.Disable(mic), Prior(packageState = EnabledState.DEFAULT)))
        assertEquals(listOf<Op>(Op.Enable(mic)), undo(snapshot().with(mic, EnabledState.DISABLED_USER), off).steps.map { it.op })
        assertTrue(undo(snapshot().with(mic, EnabledState.ENABLED), off).steps.none { DefaultPlanner.packageOf(it.op) == mic })
        // Our record that switched it on from off: the inverse would be a Disable; never planned.
        val on = listOf(change(0, Op.Enable(mic), Prior(packageState = EnabledState.DISABLED_USER)))
        assertTrue(undo(snapshot().with(mic, EnabledState.ENABLED), on).steps.isEmpty())
        // A package that is not protected keeps its exact inverse.
        val feedback = PackageName("com.google.android.feedback")
        val plain = listOf(change(0, Op.Disable(feedback), Prior(packageState = EnabledState.DEFAULT)))
        assertEquals(listOf<Op>(Op.DefaultState(feedback)), undo(snapshot().with(feedback, EnabledState.DISABLED_USER), plain).steps.map { it.op })
    }

    // (1) The helper put back journals accessibility_enabled 1 and the service list as priors.
    // Undo everything never writes 1 nor a non-empty list, whatever the TV has now.
    @Test fun undoNeverSwitchesAccessibilityOn() {
        val putBack = listOf(
            change(0, Op.SettingsPut(SettingKey.ENABLED_ACCESSIBILITY, null), Prior(settingValue = helpers)),
            change(1, Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "0"), Prior(settingValue = "1")),
            change(2, Op.SettingsPut(SettingKey.ENABLED_ACCESSIBILITY, helpers), Prior(settingValue = helpers)),
            change(3, Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "1"), Prior(settingValue = "1")),
        )
        fun tv(enabled: String?, list: String?) = snapshot().withSettings(snapshot().settings + mapOf(SettingKey.ACCESSIBILITY_ENABLED to enabled, SettingKey.ENABLED_ACCESSIBILITY to list))
        // Switched off by the person, list kept or cleared: nothing is written.
        for (list in listOf(helpers, null)) {
            assertTrue(undo(tv("0", list), putBack).steps.none { it.op is Op.SettingsPut }, "accessibility off, list $list")
        }
        // On now with the list cleared or changed: the list is not written back either.
        for (list in listOf(null, "com.example.reader/.Reader")) {
            assertTrue(undo(tv("1", list), putBack).steps.none { it.op is Op.SettingsPut }, "accessibility on, list $list")
        }
        for (s in listOf(tv("0", helpers), tv("0", null), tv("1", null), tv(null, null))) {
            assertTrue(undo(s, putBack).steps.none { it.op == Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "1") })
        }
    }

    // RFC-HELPER-1 (test build): Undo everything after the Home button helper. Whatever the TV has
    // now, the undo never adds the helper and never writes accessibility_enabled 1 or a list back; it
    // takes out exactly our entry, every other entry byte for byte and in order, and switches
    // accessibility off only when this app switched it on and nothing else is left.
    @Test fun undoTakesOutOnlyTheHelperAndNeverSwitchesItOn() {
        val service = HelperLines.SERVICE.flat
        val reader = "com.example.reader/.Reader"
        val added = listOf(
            change(0, Op.AccessibilityAdd(null, HelperLines.SERVICE), Prior(settingValue = null)),
            change(1, Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "1"), Prior(settingValue = "0")),
        )
        fun tv(enabled: String?, list: String?) = snapshot().withSettings(snapshot().settings + mapOf(SettingKey.ACCESSIBILITY_ENABLED to enabled, SettingKey.ENABLED_ACCESSIBILITY to list))
        val p = PlannerFixtures.planner()   // a release planner: Undo of a test build's records works in any build
        for (enabled in listOf("1", "0", null)) for (list in listOf(service, "$reader:$service", "$service:$reader", reader, null)) {
            val ops = undo(tv(enabled, list), added, p).steps.map { it.op }
            val label = "accessibility $enabled, list $list: $ops"
            assertTrue(ops.none { it is Op.AccessibilityAdd || it == Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "1") }, label)
            assertTrue(ops.none { it is Op.SettingsPut && it.key == SettingKey.ENABLED_ACCESSIBILITY && it.value != null }, label)
            val remove = ops.filterIsInstance<Op.AccessibilityRemove>()
            if (list?.contains(service) == true) assertEquals(listOf(Op.AccessibilityRemove(list, HelperLines.SERVICE)), remove, label)
            else assertTrue(remove.isEmpty(), label)
            val others = list?.split(':')?.filter { it != service }.orEmpty()
            val off = Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "0") in ops
            if (off) assertTrue(others.isEmpty() && enabled != "0", label)
            if (enabled == "1" && others.isEmpty()) assertTrue(off, "accessibility goes back off with nothing left: $label")
        }
        // A removal of ours (Manage's Switch off) is never undone.
        val switchedOff = added + change(5, Op.AccessibilityRemove(service, HelperLines.SERVICE), Prior(settingValue = service))
        assertTrue(undo(tv("1", reader), switchedOff, p).steps.none { it.op is Op.AccessibilityAdd })
    }

    // (3) A journal from before 0.4.5 on a chooser TV holds the chooser as the SetHome prior. Undo
    // everything and Before a factory reset hand HOME to the stock home the switch armed its
    // watchdog with (or the profile's stock launcher), never to the chooser.
    @Test fun aPlaceholderHomePriorGoesBackToTheStockHome() {
        val token = RunToken("00000000000000aa")
        val takeover = listOf(
            change(0, Op.ArmHomeWatchdog(stock, 150, token), null),
            change(1, Op.Disable(stock.pkg), Prior(packageState = EnabledState.ENABLED)),
            change(2, Op.SetHome(launcher), Prior(home = chooser)),
        )
        // Ours is Home, the stock home is off, so it claims nothing.
        val ours = snapshot(disabled = setOf(stock.pkg.value), home = launcher, claimants = listOf(launcher), extra = mapOf(installedLauncher()))
        for (records in listOf(takeover, takeover.drop(1))) {
            val source = if (records === takeover) null else object : ProfileSource {
                override suspend fun all() = listOf(PlannerFixtures.profile())
                override suspend fun resolve(facts: DeviceFacts) = ProfileResolution.Exact(PlannerFixtures.profile())
            }
            val planner = PlannerFixtures.planner(profiles = source)
            val ops = undo(ours, records, planner).steps.map { it.op }
            assertTrue(Op.SetHome(stock) in ops, "${records.size} records: $ops")
            assertTrue(ops.none { (it as? Op.SetHome)?.component == chooser })
            val reset = planner.plan(PlanRequest.BeforeFactoryReset, ours, PlannerFixtures.facts, records)
            val resetOps = assertIs<PlanResult.Ok>(reset, reset.toString()).plan.steps.map { it.op }
            assertTrue(Op.SetHome(stock) in resetOps && Op.Enable(stock.pkg) in resetOps, "$resetOps")
        }
    }

    // A brand-set TV (the generic KnownLaunchers as its stock launchers) whose generic launcher the
    // factory switched off, and a pre-0.4.5 placeholder prior with no watchdog record: neither Undo
    // everything nor Before a factory reset ever hands HOME to that launcher or switches it on.
    @Test fun aFactoryOffGenericLauncherIsNeverTheHomeToGoBackTo() {
        val generic = tv.cleanyour.core.spec.KnownLaunchers.TVLAUNCHER
        val source = object : ProfileSource {
            override suspend fun all() = emptyList<tv.cleanyour.core.model.Profile>()
            override suspend fun resolve(facts: DeviceFacts) = ProfileResolution.BrandSureSet("Xiaomi", emptyList(), tv.cleanyour.core.spec.KnownLaunchers.generic)
        }
        val planner = PlannerFixtures.planner(profiles = source)
        // Ours is Home; the generic launcher is installed but off from the factory: not a claimant, no record of ours.
        val tv = snapshot(home = launcher, claimants = listOf(launcher), extra = mapOf(installedLauncher())).with(generic.pkg, EnabledState.DISABLED_USER)
        val records = listOf(change(0, Op.SetHome(launcher), Prior(home = chooser)))
        val undo = planner.plan(PlanRequest.UndoEverything(records, removeLauncher = false), tv, PlannerFixtures.facts, records)
        val reset = planner.plan(PlanRequest.BeforeFactoryReset, tv, PlannerFixtures.facts, records)
        for ((name, r) in listOf("undo" to undo, "reset" to reset)) {
            val ops = assertIs<PlanResult.Ok>(r, "$name: $r").plan.steps.map { it.op }
            assertTrue(ops.none { (it as? Op.SetHome)?.component?.pkg == generic.pkg || it == Op.Enable(generic.pkg) || (it as? Op.SetHome)?.component == chooser }, "$name: $ops")
        }
        // Asked for directly, it is not a home screen this TV had either.
        val asked = planner.plan(PlanRequest.UseStockHome(generic), tv, PlannerFixtures.facts, records)
        assertIs<PlanResult.Refused>(asked)
    }
}
