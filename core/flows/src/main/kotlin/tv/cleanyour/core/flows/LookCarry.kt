package tv.cleanyour.core.flows

import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.spec.LauncherConfigDoc
import tv.cleanyour.core.spec.LiveTvKeys

// What a Manage push adds to a look's doc on this TV, after the look itself: the TV's Live TV app
// and no placed app hidden (LiveTvKeys), from the newest snapshot and the TV's profile. Without a
// snapshot the Live TV app is not known, so live_tv_package is not written; the hidden list still
// never hides a placed app.
internal object LookCarry {
    suspend fun of(d: ManageDeps, tvId: TvId): (LauncherConfigDoc) -> LauncherConfigDoc {
        val snap = attempt { d.snapshots.latest(tvId) }.getOrNull()
        val profile = d.tv(tvId)?.let { attempt { d.profileOf(it) }.getOrNull() }
        val liveTv = snap?.let { LiveTvKeys.pick(profile, it.packages) }
        return { doc -> LiveTvKeys.apply(doc, liveTv) }
    }
}
