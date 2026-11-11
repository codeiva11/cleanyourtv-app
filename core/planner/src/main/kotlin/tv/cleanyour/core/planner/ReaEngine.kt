package tv.cleanyour.core.planner

import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.GroupDefault
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageEntry
import tv.cleanyour.core.model.PackageGroup
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.ops.Denylist
import tv.cleanyour.core.spec.FactsTable

/**
 * REA (Reverse-Engineering Analysis) Engine:
 * Autonomous heuristic analyzer for Android TV & Google TV.
 * Detects hidden ad-providers, telemetry daemons, partner bloatware, and RAM hogs
 * on ANY TV device (even unlisted/unknown TV models) without requiring hardcoded profiles.
 */
object ReaEngine {

    val STOCK_GOOGLE_LAUNCHER = PackageName("com.google.android.tvlauncher")
    val STOCK_TV_RECOMMENDATIONS = PackageName("com.google.android.tvrecommendations")

    // Heuristic signatures for Ads & Recommendation daemons
    private val ADS_SIGNATURES = listOf(
        "recommendation",
        "adssignals",
        "promos",
        "autoinstalls",
        ".ads.",
        ".ads",
        "adservice",
        "sponsor",
        "banner",
        "epop",
        "e-pop",
        "commercial",
        "freepay",
        "advertising",
        "advertisement",
        "adv."
    )

    // Heuristic signatures for Telemetry & Analytics trackers
    private val TELEMETRY_SIGNATURES = listOf(
        "analytics",
        "analytic",
        "telemetry",
        "diagnosing",
        "crashlog",
        "userbehavior",
        "metrics",
        "usagestats",
        "tracking",
        "datacollect",
        "userfeedback"
    )

    // Heavy RAM use floor: on a 1-2 GB TV a single background system app holding 50 MB+
    // is heavy enough to matter. Only measured RSS nominates.
    const val HEAVY_RAM_KB = 50 * 1024

    // Core Android and SoC packages that must NEVER be nominated purely by RAM heuristic
    private val SAFE_SYSTEM_PREFIXES = listOf(
        "com.android.",
        "android",
        "com.google.android.ext.",
        "com.google.android.modulemetadata",
        "com.google.android.overlay.",
        "com.google.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.google.android.webview",
        "com.google.android.inputmethod.",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.mediatek.",
        "com.droidlogic."
    )

    // Known vendor partner bloatware and unnecessary background apps across 47+ TV brands.
    // RFC-SHOP-1: expanded with additional Google TV ad/content packages that feed the Shop tab
    // and Discover row, plus more OEM bloatware discovered across reports.
    private val VENDOR_BLOAT_PREFIXES = listOf(
        "com.google.android.videos",
        "com.google.android.youtube.tvmusic",
        "com.google.android.play.games",
        "com.google.android.partnersetup",
        "com.google.android.leanbacklauncher.partnercustomizer",
        "com.google.android.tvrecommendations",
        "com.google.android.tv.frameworkpackagestubs",
        "com.google.android.katniss",
        "com.google.android.pano.discover",
        "com.smartdevice.leanbacklauncher.customizer",
        "com.smartdevice.acr.recommendation",
        "com.smartdevice.epopplayer",
        "com.smartdevice.emanual",
        "com.smartdevice.tvworkwithalexa",
        "com.smartdevice.dtv.icast",
        "com.mstar.android.tv.disclaimercustomization",
        "fusion.android.tv.demo",
        "com.graymatrix.did",
        "com.xiaomi.mitv.upgrade",
        "com.xiaomi.mitv.shop",
        "com.xiaomi.mitv.media.vibes",
        "com.xiaomi.tv.gallery",
        "com.xiaomi.mitv.advertise",
        "com.xiaomi.mitv.tvpush",
        "com.xiaomi.mitv.payment",
        "com.miui.tv.analytics",
        "com.miui.bugreport",
        "com.tcl.waterfall",
        "com.tcl.partnercustomizer",
        "com.tcl.tvmanager",
        "com.tcl.useragreement",
        "com.tcl.messagebox",
        "com.tcl.fota",
        "com.tcl.guard",
        "com.oneplus.tv.experience",
        "com.oneplus.tv.usagestats",
        "com.sony.dtv.bravialife",
        "com.sony.dtv.promotions",
        "com.sony.dtv.samba",
        "com.sony.dtv.oemsettings",
        "com.hisense.tv.recommendation",
        "com.hisense.synccast",
        "com.hisense.cloudsearch",
        "com.jamdeo.tv.vod",
        "com.realme.tv.experience",
        "com.android.tv.partnercustomizer",
        "com.panasonic.tv.commercial",
        "com.lg.tv.lgsmartthinq",
    )

    data class Candidate(
        val pkg: PackageName,
        val friendlyName: String,
        val description: String,
        val category: String,
        val estimatedRamMb: Int = 35
    )

    /**
     * Inspects all installed packages on the connected TV and discovers
     * all bloatware candidates using static facts and dynamic heuristic reverse-engineering.
     */
    fun findDebloatCandidates(
        snapshot: Snapshot,
        factsTable: FactsTable,
        stockLaunchers: List<Component>,
        ramByPackage: Map<PackageName, Int> = emptyMap()
    ): List<Candidate> {
        val candidates = mutableListOf<Candidate>()
        val stockPkgs = stockLaunchers.map { it.pkg }.toSet()
        val protectedPkgs = setOf(STOCK_GOOGLE_LAUNCHER) + stockPkgs

        for (pkg in snapshot.packages.keys) {
            val state = snapshot.packages[pkg] ?: continue
            // SAFETY: NEVER touch user-installed (third-party) apps!
            // Only manufacturer preloaded system packages can be candidates for bloatware removal.
            if (!state.system) continue

            val pkgStr = pkg.value.lowercase()

            // 1. Absolute Safety Check: Never target protected packages, system UI, inputs, or active launchers
            if (pkg in protectedPkgs) continue
            if (Denylist.staticReason(pkg.value) != null) continue
            if (snapshot.home.resolved?.pkg == pkg) continue
            if (snapshot.ime?.pkg == pkg || snapshot.screensaver.any { it.pkg == pkg }) continue

            // 2. Facts Table Match
            val facts = factsTable.lookup(pkg)
            if (facts != null) {
                val cat = facts.category.lowercase()
                if (cat in setOf("ads", "telemetry", "analytics", "bloat", "unwanted", "streaming")) {
                    candidates += Candidate(
                        pkg = pkg,
                        friendlyName = facts.friendlyName,
                        description = facts.description,
                        category = facts.category,
                        estimatedRamMb = ramMb(pkg, ramByPackage)
                    )
                    continue
                }
            }

            // 3. Dynamic Heuristic Reverse Engineering (Handles unknown / unlisted TVs)
            if (ADS_SIGNATURES.any { pkgStr.contains(it) }) {
                candidates += Candidate(
                    pkg = pkg,
                    friendlyName = formatName(pkg.value),
                    description = "Ad and recommendation provider eating TV RAM",
                    category = "ads",
                    estimatedRamMb = ramMb(pkg, ramByPackage)
                )
                continue
            }

            if (TELEMETRY_SIGNATURES.any { pkgStr.contains(it) }) {
                candidates += Candidate(
                    pkg = pkg,
                    friendlyName = formatName(pkg.value),
                    description = "Background telemetry and tracking service",
                    category = "telemetry",
                    estimatedRamMb = ramMb(pkg, ramByPackage)
                )
                continue
            }

            if (VENDOR_BLOAT_PREFIXES.any { pkgStr.startsWith(it) }) {
                candidates += Candidate(
                    pkg = pkg,
                    friendlyName = formatName(pkg.value),
                    description = "Preloaded manufacturer bloatware",
                    category = "bloat",
                    estimatedRamMb = ramMb(pkg, ramByPackage)
                )
                continue
            }

            // 4. Heavy RAM use: a non-critical SYSTEM app running right now whose measured RSS >= HEAVY_RAM_KB.
            // Core Android OS services (SAFE_SYSTEM_PREFIXES) are strictly excluded from RAM nomination
            // to ensure TV OS boot stability (avoiding RescueParty recovery).
            val rssKb = ramByPackage[pkg]
            if (snapshot.packages[pkg]?.system == true &&
                rssKb != null &&
                rssKb >= HEAVY_RAM_KB &&
                SAFE_SYSTEM_PREFIXES.none { pkgStr.startsWith(it) }) {
                candidates += Candidate(
                    pkg = pkg,
                    friendlyName = formatName(pkg.value),
                    description = "Heavy RAM use: about ${rssKb / 1024} MB in RAM right now",
                    category = "ram",
                    estimatedRamMb = rssKb / 1024
                )
                continue
            }
        }

        // Heaviest first, so the worst RAM hogs sit at the top of their group; the name
        // breaks ties, so the order is stable when nothing is measured.
        return candidates.distinctBy { it.pkg }
            .sortedWith(compareByDescending<Candidate> { it.estimatedRamMb }.thenBy { it.pkg.value })
    }

    /**
     * Synthesizes PackageGroups from REA candidates so the Planner and UI
     * can display and switch off all bloatware on ANY unlisted TV.
     */
    fun synthesizeReaGroups(candidates: List<Candidate>): List<PackageGroup> {
        val ads = candidates.filter { it.category == "ads" }
        val telemetry = candidates.filter { it.category == "telemetry" }
        val ram = candidates.filter { it.category == "ram" }
        val bloat = candidates.filter { it.category !in setOf("ads", "telemetry", "ram") }

        val groups = mutableListOf<PackageGroup>()
        if (ads.isNotEmpty()) {
            groups += PackageGroup(
                id = "rea_ads",
                title = "Adverts and Recommendations (REA Auto-Found)",
                default = GroupDefault.DISABLE,
                packages = ads.map { PackageEntry(it.pkg, it.description) }
            )
        }
        if (telemetry.isNotEmpty()) {
            groups += PackageGroup(
                id = "rea_telemetry",
                title = "Tracking and Telemetry (REA Auto-Found)",
                default = GroupDefault.DISABLE,
                packages = telemetry.map { PackageEntry(it.pkg, it.description) }
            )
        }
        if (ram.isNotEmpty()) {
            groups += PackageGroup(
                id = "rea_ram",
                title = "Heavy RAM use (REA Auto-Found)",
                default = GroupDefault.ASK,
                packages = ram.map { PackageEntry(it.pkg, it.description) }
            )
        }
        if (bloat.isNotEmpty()) {
            groups += PackageGroup(
                id = "rea_bloat",
                title = "Background Bloatware (REA Auto-Found)",
                default = GroupDefault.DISABLE,
                packages = bloat.map { PackageEntry(it.pkg, it.description) }
            )
        }
        return groups
    }

    /**
     * Checks if Option A: Clean Stock Launcher is applicable.
     */
    fun isStockGoogleTvLauncher(snapshot: Snapshot): Boolean {
        return snapshot.packages.containsKey(STOCK_GOOGLE_LAUNCHER)
    }

    /**
     * Builds the exact operations for Option A: Clean Stock Home.
     * Permanently uninstalls tvrecommendations (the central ad signals provider), blocks every
     * known ad/shop setting so the Shop tab, Discover row and sponsored content cannot return
     * after the stock launcher restarts, and restarts the launcher with a cleared cache.
     *
     * RFC-SHOP-1: earlier builds only disabled tvrecommendations and set two settings. The
     * launcher re-fetched its ad configuration on the next cold start and the Shop row came back.
     * This version uninstalls tvrecommendations (system app; instant undo with pm install-existing),
     * clears the launcher's cached ad data and notification provider, and writes every known
     * Google TV ad/shop secure setting to its off value.
     */
    fun buildCleanStockHomeOps(snapshot: Snapshot): List<Op> {
        val ops = mutableListOf<Op>()

        // 1. Permanently remove tvrecommendations (the central Ad Signals provider).
        //    pm uninstall removes it for the user; pm install-existing brings it back instantly.
        ops += Op.Uninstall(STOCK_TV_RECOMMENDATIONS)
        ops += Op.ClearPackage(STOCK_TV_RECOMMENDATIONS)
        ops += Op.DeleteContent("content://com.google.android.tvrecommendations.NotificationContentProvider/notifications")

        // 2. Block every known ad/shop setting so the Shop tab and sponsored content stay gone.
        ops += Op.SettingsPut(SettingKey.FREEPLAY_ENABLED, "0")
        ops += Op.SettingsPut(SettingKey.LIMIT_AD_TRACKING, "1")
        ops += Op.SettingsPut(SettingKey.PERSONALIZED_ADS, "0")
        ops += Op.SettingsPut(SettingKey.DISCOVER_ENABLED, "0")
        ops += Op.SettingsPut(SettingKey.SPONSORED_CONTENT, "0")

        // 3. Clear the launcher's cached ads and Shop tab data, then restart it so the
        //    ad-free stock home screen shows immediately.
        if (isStockGoogleTvLauncher(snapshot)) {
            ops += Op.ClearPackage(STOCK_GOOGLE_LAUNCHER)
            ops += Op.RestartHome(Component(STOCK_GOOGLE_LAUNCHER, ".MainActivity"))
        }

        return ops
    }

    // RFC-RAM-1: measured RSS (KB) beats the name-guess; a package that is not running
    // (absent from the map) keeps the guess. RSS counts shared pages per process, so the
    // sum ranks heavy apps but is never an exact "uses X MB" claim.
    private fun ramMb(pkg: PackageName, ramByPackage: Map<PackageName, Int>): Int =
        ramByPackage[pkg]?.let { (it / 1024).coerceAtLeast(1) } ?: estimateRam(pkg.value)

    private fun estimateRam(pkg: String): Int = when {
        pkg.contains("recommendation") -> 55
        pkg.contains("analytic") -> 40
        pkg.contains("partner") -> 35
        else -> 30
    }

    private fun formatName(pkg: String): String {
        val last = pkg.substringAfterLast('.')
        return last.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
