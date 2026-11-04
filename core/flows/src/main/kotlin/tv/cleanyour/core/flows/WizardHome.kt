package tv.cleanyour.core.flows

import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.ops.HomePlaceholder
import tv.cleanyour.core.ops.UndoOrder
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.ops.Denylist

// 0.4.6: the safety net under the old R0 switch, for every TV. The switch may leave a setup
// wizard on when it claims no Home; should the wizard become a Home claimant later (a TV update, a
// firmware that turns it back on), it could take Home while the TV's own home screen is off, the
// RecoveryActivity risk of upstream #506 and #422. So each time Manage connects, it reads the TV's
// Home again, and when ours is Home, the TV's own home screen is off (no claimant) and a setup
// wizard is now a claimant, Manage says so and offers the way back: "Use the TV's own home screen"
// (PlanRequest.UseStockHome), the existing undo of the switch, which switches nothing else on
// (no accessibility, no Wi-Fi).
object WizardHome {
    // `home`: what the TV answers now; `latest`: the newest stored snapshot (its package states, so a
    // wizard the TV has switched off does not count); `stock`: the TV's own home screen (stockOf).
    fun applies(home: HomeResolution, latest: Snapshot?, profile: Profile?, stock: Component?): Boolean {
        if (home.resolved?.pkg != Launcher.pkg || stock == null || latest == null) return false
        if (home.claimants.any { it.component.pkg == stock.pkg }) return false   // the TV's own home screen is on
        return homeWizards(profile, latest.copy(home = home)).isNotEmpty()
    }

    // The Home claimants that are a setup wizard: the known wizards, the profile's own, or a package
    // named like Google TV's. One the snapshot shows switched off cannot take Home; one it does not
    // list counts, the safe side.
    private fun homeWizards(profile: Profile?, snapshot: Snapshot): Set<PackageName> =
        snapshot.home.claimants.map { it.component.pkg }.filter {
            it.value in Denylist.setupWizards || it == profile?.setupWraith || "setupwraith" in it.value.lowercase()
        }.filter { snapshot.packages[it]?.enabled.let { e -> e != EnabledState.DISABLED_USER && e != EnabledState.DISABLED } }.toSet()

    // The TV's own home screen the switch handed Home away from: the stock home its watchdog was
    // armed with (a brand set or no profile has no list of its own), else the exact profile's.
    fun stockOf(changes: List<ChangeRecord>, profile: Profile?): Component? =
        HomePlaceholder.stockFromRecords(changes) ?: profile?.stockLaunchers?.firstOrNull()
}

// 0.4.7 (xgimi-mogo-3-pro, which 0.4.6 switches; upstream TROUBLESHOOTING "Google TV comes back after a few remote
// presses"): firmware can switch the TV's own home screen back on behind this app. On the MoGo 3 Pro the remote's app
// buttons, its programmable and profile buttons and power send F keys to com.xgimi.xrmservice's
// XgimiGlobalKeyBroadcast, which runs `pm enable` on launcherx, and Google TV takes Home back. So each time Manage
// connects it also asks whether the TV's own home screen, which this app switched off (its last record of that
// package is our Disable), claims Home again; the card says so (TvCard.stockHomeBack) and the log has a line.
// Nothing is switched here: the Home button helper is the fix for such TVs.
object StockHomeBack {
    // What Manage's last connect read for each TV, one per graph (ManageDeps), so a problem report has the line too.
    class Reads {
        private val back = java.util.concurrent.ConcurrentHashMap<tv.cleanyour.core.model.TvId, Boolean>()
        operator fun get(id: tv.cleanyour.core.model.TvId): Boolean = back[id] == true
        // Records this read; true when it says "back" and the read before did not.
        fun record(id: tv.cleanyour.core.model.TvId, applies: Boolean): Boolean = back.put(id, applies) != true && applies
    }

    fun applies(home: HomeResolution, changes: List<ChangeRecord>, stock: Component?): Boolean {
        if (stock == null) return false
        val target = UndoOrder.Target.PackageEnabled(stock.pkg)
        val last = changes.filter { UndoOrder.target(it.op) == target }.maxWithOrNull(UndoOrder.CHRONOLOGICAL) ?: return false
        return last.op is Op.Disable && home.claimants.any { it.component.pkg == stock.pkg }
    }
}
