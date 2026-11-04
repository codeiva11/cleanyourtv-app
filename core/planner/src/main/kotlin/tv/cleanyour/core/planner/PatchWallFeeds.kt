package tv.cleanyour.core.planner

import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.GroupDefault
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageGroup
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Snapshot

// beta4, incident of 0.4.6-beta2 (issues #945, #1570): never PatchWall's feeds off while PatchWall
// stays on. A Xiaomi MiTV-AXSO2 on Android 11 kept PatchWall on (an owner's choice row since issue
// #111) with Mi TV+ and com.xm.webcontent switched off and its own home screen off, and was stuck
// loading after a restart; Xiaomi's Android 11 firmware opens PatchWall at boot (#1580). Every tested
// list switches PatchWall off together with its feeds. Shared by the planner (Setup, Switch off,
// Leftovers, Put back).
object PatchWallFeeds {
    val PATCHWALL = PackageName("com.mitv.tvhome.atv")
    val FEEDS: Set<PackageName> = setOf(PackageName("com.mitv.tvhome.mitvplus"), PackageName("com.xm.webcontent"))

    fun installed(s: Snapshot, p: PackageName): Boolean = p in s.packages
    fun isOff(s: Snapshot, p: PackageName): Boolean = s.packages[p]?.enabled.let { it == EnabledState.DISABLED_USER || it == EnabledState.DISABLED }

    // PatchWall is on and the plan keeps it on.
    fun staysOn(s: Snapshot, patchWallGoesOff: Boolean): Boolean = installed(s, PATCHWALL) && !isOff(s, PATCHWALL) && !patchWallGoesOff

    // Whether a Setup with these rows and choices switches PatchWall off: its first row decides (as
    // the planner's loop does), else the Custom list. The planner adds its own protection test.
    fun goesOff(s: Snapshot, groups: List<PackageGroup>, kept: Set<PackageName>, extraOff: Set<PackageName>): Boolean {
        if (!installed(s, PATCHWALL) || isOff(s, PATCHWALL) || PATCHWALL in kept) return false
        val group = groups.firstOrNull { g -> g.packages.any { it.name == PATCHWALL } }
        return when (group?.default) {
            GroupDefault.DISABLE -> true
            GroupDefault.ASK, GroupDefault.KEEP, null -> PATCHWALL in extraOff
        }
    }

    // A feed this app switched off (one of its own Disable records): the plan switches it back on.
    fun ours(journals: List<ChangeRecord>, p: PackageName): Boolean = journals.any { (it.op as? Op.Disable)?.pkg == p }

    // The feeds that are off while PatchWall stays on.
    fun off(s: Snapshot, patchWallGoesOff: Boolean): List<PackageName> =
        if (!staysOn(s, patchWallGoesOff)) emptyList() else FEEDS.filter { installed(s, it) && isOff(s, it) }.sortedBy { it.value }

    // Those the plan cannot switch back on (not switched off by this app): no home screen switch.
    fun stayingOff(s: Snapshot, journals: List<ChangeRecord>, patchWallGoesOff: Boolean): List<PackageName> =
        off(s, patchWallGoesOff).filterNot { ours(journals, it) }
}
