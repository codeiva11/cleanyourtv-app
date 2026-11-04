package tv.cleanyour.core.flows

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.Tv
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.store.DocumentStore

// 0.4.8 (research/release-0.4.8/PLAN.md item 1): the app counts the restarts itself. Testers sent "it works" as loose
// text, which the graduation rule (2 or 3 confirmed restarts per brand and Android version) cannot count. So each time
// the app connects to a TV whose home screen it switched, or whose Home button helper it switched on, it reads two
// things it reads anyway: the TV's boot_id (/proc/sys/kernel/random/boot_id, a new one after every boot) and what Home
// answers to (the same read as Manage's Home check). A new boot_id with the new home screen still answering Home is
// one held restart; the TV's own back after a new boot_id is a failed one. Nothing else is read and nothing is written
// to the TV. Kept per TV on the phone, never sent: the person's tap on "Tell us it worked" (Confirmation) is the only
// way the count leaves it.

// How the new home screen reaches the TV, for the count and the confirmation: the switch with an exact (or candidate)
// list, or the switch with the setup wizard left on (R0, a brand set or no list).
enum class HomePath(val word: String) {
    LIST("list"), R0("R0");

    companion object {
        fun of(word: String?): HomePath? = entries.firstOrNull { it.word == word }

        // The path for this TV, or null when this app has not put the new home screen in front of it (never switched,
        // switched back with "Use the TV's own home screen", or put back with Undo everything): then nothing is read.
        // Stock-only: a TV this build switched has no new home screen, so its path is null and nothing is counted;
        // only a TV an older build switched still counts.
        fun of(tv: Tv, latest: Snapshot?, decision: HomeDecision?): HomePath? = when {
            tv.launcher == null -> null
            tv.profileId != null || decision?.profile?.let { it.startsWith("exact ") || it.startsWith("candidate ") } == true -> LIST
            else -> R0
        }
    }
}

// What this phone counted for one TV. `bootId`: the boot of the last read; `answered`: whether the new home screen (for
// the helper, the TV's own Google home screen it sends on from) answered Home at that read; `path`: the path of that
// read. `held` and `failed` count the restarts seen since this phone first read the TV on a path; `firstAtMs` and
// `lastAtMs` are when the first and the last of them were counted, and `build` the app (version, flavour and build
// kind) that counted the last. `toldAtMs` and `toldHeld`: when the person last opened the confirmation, and the held
// count it carried.
data class RestartTally(
    val bootId: String? = null,
    val answered: Boolean = false,
    val path: HomePath? = null,
    val held: Int = 0,
    val failed: Int = 0,
    val firstAtMs: Long? = null,
    val lastAtMs: Long? = null,
    val build: String? = null,
    val lastFailed: Boolean = false,
    val toldAtMs: Long? = null,
    val toldHeld: Int = 0,
) {
    val counted: Int get() = held + failed
}

// What one read said, for Done and the log.
enum class RestartRead {
    FIRST,       // the first read on this path: the baseline, nothing counted
    SAME_BOOT,   // no restart since the last read
    HELD,        // a restart, and the new home screen still answers Home
    FAILED,      // a restart, and the TV's own (or another) answers Home now
    NOT_COUNTED, // a restart after a read where the new home screen was not answering: nothing to hold
}

data class RestartObservation(val read: RestartRead, val tally: RestartTally)

// tvs/<id>/restarts.txt, one key=value per line. Forget this TV and Delete everything remove it with the TV's folder.
class Restarts(private val store: DocumentStore) {
    private val lock = Mutex()
    private fun path(id: TvId) = "tvs/${id.value}/restarts.txt"

    suspend fun load(id: TvId): RestartTally? = store.read(path(id)) { bytes ->
        val lines = bytes.decodeToString().lines()
        fun value(key: String) = lines.firstOrNull { it.startsWith("$key=") }?.removePrefix("$key=")?.trim()?.ifEmpty { null }
        RestartTally(
            bootId = value("boot"), answered = value("answered") == "true", path = HomePath.of(value("path")),
            held = value("held")?.toIntOrNull() ?: 0, failed = value("failed")?.toIntOrNull() ?: 0,
            firstAtMs = value("first")?.toLongOrNull(), lastAtMs = value("last")?.toLongOrNull(), build = value("build"),
            lastFailed = value("lastfailed") == "true", toldAtMs = value("told")?.toLongOrNull(), toldHeld = value("toldheld")?.toIntOrNull() ?: 0,
        )
    }

    private suspend fun save(id: TvId, t: RestartTally) {
        fun one(s: String) = s.replace('\n', ' ').replace('\r', ' ').trim()
        val lines = listOfNotNull(
            t.bootId?.let { "boot=${one(it)}" }, "answered=${t.answered}", t.path?.let { "path=${it.word}" },
            "held=${t.held}", "failed=${t.failed}", t.firstAtMs?.let { "first=$it" }, t.lastAtMs?.let { "last=$it" },
            t.build?.let { "build=${one(it)}" }, "lastfailed=${t.lastFailed}", t.toldAtMs?.let { "told=$it" }, "toldheld=${t.toldHeld}",
        )
        store.write(path(id), lines.joinToString("") { "$it\n" }.encodeToByteArray())
    }

    // One read of the TV on `path`: `bootId` and `home` as the TV answered them now, `build` the app that read them.
    // A restart counts only across two reads on the same path with the new home screen answering at the first: held
    // when it still answers, failed when it does not. A read on another path (Setup run again with the switch after
    // the helper) starts a new count from that read: a confirmation is for one path.
    suspend fun observe(id: TvId, bootId: String, home: HomeResolution, path: HomePath, build: String, nowMs: Long): RestartObservation = lock.withLock {
        val before = attempt { load(id) }.getOrNull() ?: RestartTally()
        val answers = answers(path, home)
        val (read, next) = when {
            before.bootId == null -> RestartRead.FIRST to before.copy(bootId = bootId, answered = answers, path = path)
            before.path != path -> RestartRead.FIRST to RestartTally(bootId = bootId, answered = answers, path = path)
            before.bootId == bootId -> RestartRead.SAME_BOOT to before.copy(answered = answers)
            !before.answered -> RestartRead.NOT_COUNTED to before.copy(bootId = bootId, answered = answers)
            answers -> RestartRead.HELD to before.copy(bootId = bootId, answered = true, held = before.held + 1,
                firstAtMs = before.firstAtMs ?: nowMs, lastAtMs = nowMs, build = build, lastFailed = false)
            else -> RestartRead.FAILED to before.copy(bootId = bootId, answered = false, failed = before.failed + 1,
                firstAtMs = before.firstAtMs ?: nowMs, lastAtMs = nowMs, build = build, lastFailed = true)
        }
        if (next != before) save(id, next)
        RestartObservation(read, next)
    }

    // The person opened the confirmation (copied and opened GitHub, or shared it): when, and the count it carried.
    suspend fun told(id: TvId, nowMs: Long): RestartTally? = lock.withLock {
        val t = attempt { load(id) }.getOrNull() ?: return@withLock null
        t.copy(toldAtMs = nowMs, toldHeld = t.held).also { save(id, it) }
    }

    companion object {
        // Whether the TV's Home answer is one to count: right after a boot Home resolves to the framework's FallbackHome
        // (or to nothing) until the TV is up, which says nothing about the restart. Such a read is left for the next one
        // in the same boot, so the boot it saw is not used up. Seen on the TV emulator: the known-TV reconnect answers
        // half a minute before sys.boot_completed.
        fun settled(home: HomeResolution): Boolean {
            val c = home.resolved ?: return false
            return c.pkg.value != "android" && "FallbackHome" !in c.cls
        }

        // Whether the new home screen answers Home on `path`. The switch: Home resolves to ours.
        fun answers(path: HomePath, home: HomeResolution): Boolean = when (path) {
            HomePath.LIST, HomePath.R0 -> home.resolved?.pkg == Launcher.pkg
        }
    }
}
