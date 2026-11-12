// RFC-SHOP-5 (2026-11-12, fork): what the on-phone APK scan found inside one installed
// APK. The scanner (core:probe, ApkScanner) pulls the TV's own APK over wireless ADB and
// reads its manifest, resources and dex strings against REA-derived signatures
// (ApkScanSignatures) — no decompilation, no desktop, no network beyond this ADB session.
// A finding rides in DeepScanEvidence, so the planner sees it with the deep scan and no
// frozen interface changes. It is EVIDENCE, not a verdict: the planner still applies its
// own safety (system apps only, denylist, home/keyboard/screensaver, kept packages)
// before anything is planned, and a package with no finding is never nominated by the
// scan — what the scan cannot see (obfuscated logic, server flags, native-code tabs) is a
// limit of the evidence, never proof the package is clean.

package tv.cleanyour.core.model

/** Signature ids the scanner stamps on its matches. One id per kind of evidence, so the
 *  planner can tell a strong match (an ad-signal provider, a manifest component the
 *  signatures mark, an ad-network string) from a corroborating one (a bare "shop" or
 *  "sponsored" word, which says the APK talks about ads but never nominates alone). */
object ApkScanIds {
    const val SHOP_ACTIVITY = "shop-activity"
    const val AD_ACTIVITY = "ad-activity"
    const val AD_PROVIDER = "ad-provider"
    const val AD_NETWORK_STRING = "ad-network-string"
    const val TELEMETRY_COMPONENT = "telemetry-component"
    const val BLOAT_COMPONENT = "bloat-component"
    const val SHOP_DEEP_LINK = "shop-deep-link"
    const val CORROBORATING_STRING = "corroborating-string"
}

/** One matched signature inside an APK. [value] is the matched class, authority,
 *  deep-link host or string itself; [evidence] is the human line the plan shows, e.g.
 *  "manifest component .shop.library.LibraryPageActivity is a REA-proven Shop activity". */
enum class ApkScanMatchKind { COMPONENT, PROVIDER, STRING, DEEP_LINK }

data class ApkScanMatch(
    val signatureId: String,
    val kind: ApkScanMatchKind,
    val value: String,
    val evidence: String,
    val category: String, // "ads" | "telemetry" | "bloat" — the planner group this feeds
)

data class ApkScanComponent(
    val component: Component, // a manifest component the scanner nominated — never the home entry point
    val category: String,
    val matches: List<ApkScanMatch> = emptyList(),
)

data class ApkScanFinding(
    val pkg: PackageName,
    val apkSizeBytes: Long,
    val versionCode: Long = 0,
    val components: List<ApkScanComponent> = emptyList(),
    val matchedStrings: List<String> = emptyList(), // capped, matched strings only — never the whole table
    val matches: List<ApkScanMatch> = emptyList(), // every match, package-level ones included
    val nativeLibs: List<String> = emptyList(), // .so entry names: footprint note only, never evidence
    val stringsTruncated: Boolean = false, // a string table ran past the scan cap
    val scannedAtMs: Long = 0,
) {
    // A strong match is one that alone names ad machinery: an ad-network string, an
    // ad-signal provider, a manifest component the signatures mark, or a Shop deep link.
    // Corroborating strings ("shop", "sponsored" as bare words) never count on their own.
    val hasStrongAdEvidence: Boolean
        get() = matches.any { it.signatureId != ApkScanIds.CORROBORATING_STRING }

    // The category the strongest match feeds: ads first, then telemetry, then bloat.
    // Only strong matches are read; a package with corroborating strings alone has no
    // strongest category and is never nominated.
    val strongestCategory: String?
        get() {
            val strong = matches.filter { it.signatureId != ApkScanIds.CORROBORATING_STRING }
            return when {
                strong.any { it.category == "ads" } -> "ads"
                strong.any { it.category == "telemetry" } -> "telemetry"
                strong.any { it.category == "bloat" } -> "bloat"
                else -> null
            }
        }

    // One line for the planner's row: the strongest evidence first, capped so a row
    // stays readable.
    fun evidenceSummary(): String = matches.take(3).joinToString("; ") { it.evidence }
}
