// FROZEN INTERFACE (PRODUCT-SPEC section 5.5, after gate R0). Names and signatures are binding;
// a change needs a one-paragraph RFC in the repo and Fable sign-off. Copied verbatim by W1; every
// edit to the copied text is listed in DECISIONS-W1.md. Owner: W4.

package tv.cleanyour.core.probe

import tv.cleanyour.core.model.*
import tv.cleanyour.core.session.TvSession

enum class ProbeStage { FACTS, PACKAGES, HOME, INPUTS, SETTINGS, LAUNCHER, DONE }

/**
 * Reads the TV's state over an ADB session. Every method is a read-only operation that
 * never changes anything on the TV. Implementations must be safe to call at any time.
 */
interface Prober {
    /** Device facts: brand, model, SDK, RAM, ABI, locale, GMS presence. */
    suspend fun facts(session: TvSession): DeviceFacts
    /** Full snapshot of the TV: every package, setting, home screen, input and screensaver. */
    suspend fun snapshot(session: TvSession, reason: SnapshotReason, onStage: (ProbeStage) -> Unit = {}): Snapshot
    /** The current home screen resolution (which launcher answers HOME). */
    suspend fun homeNow(session: TvSession): HomeResolution
    /** The resumed (foreground) activity on the TV, for the live home check. */
    suspend fun homeFocus(session: TvSession): Component?
    /** PNG screenshot of the TV, capped at 16 MiB; null on failure. */
    suspend fun screenshot(session: TvSession): ByteArray?
    /** SHA-256 of a file on the TV at the given path; null when the file is missing or unreadable. */
    suspend fun fileSha256(session: TvSession, path: RemotePath): Sha256?
    /** SHA-256 of the installed APK of a package; null when the package is not installed. */
    suspend fun installedSha256(session: TvSession, pkg: PackageName): Sha256?
    /** Free storage on /data in megabytes. */
    suspend fun freeDataMb(session: TvSession): Int
    /**
     * Per-app RAM usage (RSS in KB) summed by package name from `ps -A -o RSS,ARGS`.
     * RSS counts shared pages once per process, so a summed per-app RSS over-counts shared
     * libraries: it ranks heavy apps reliably but is never an exact "uses X MB" claim.
     * Best-effort: a firmware without the RSS column yields an empty map.
     */
    suspend fun ramByPackage(session: TvSession): Map<PackageName, Int>
    /** Whether the TV's firmware refuses app installs (some OEMs lock this down). */
    suspend fun installRefusal(session: TvSession, noopApk: BlobRef): Boolean
    /** Whether the home watchdog process for the given run token is still alive. */
    suspend fun watchdogAlive(session: TvSession, token: RunToken): Boolean
    /** The kernel's boot_id, which changes on every reboot. */
    suspend fun bootId(session: TvSession): String }
