package tv.cleanyour.core.planner

import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.DeepScanResult
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.GroupDefault
import tv.cleanyour.core.model.PackageEntry
import tv.cleanyour.core.model.PackageGroup
import tv.cleanyour.core.model.PackageName
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

    val STOCK_TV_LAUNCHER = PackageName("com.google.android.tvlauncher")
    val STOCK_GOOGLE_TV_LAUNCHER = PackageName("com.google.android.apps.tv.launcherx")
    val STOCK_GOOGLE_LAUNCHER = STOCK_TV_LAUNCHER   // Backward compatibility alias
    val STOCK_GOOGLE_LAUNCHERS = setOf(STOCK_TV_LAUNCHER, STOCK_GOOGLE_TV_LAUNCHER)
    val STOCK_TV_RECOMMENDATIONS = PackageName("com.google.android.tvrecommendations")
    val GOOGLE_PLAY_MOVIES = PackageName("com.google.android.videos")

    // The essential packages that power the Shop tab's content, video ads, and sponsored
    // recommendations on a stock launcher. Crucially includes com.google.android.videos
    // (Google Play Movies & TV) — the film store behind the Shop tab's pages (the
    // launcher's own strings say "Google Play Movies & TV is now the Shop tab").
    // The tab button itself is drawn by the launcher's own MainActivity (its
    // shop_tab_title resource, REA 2026-11-12 on the shipped tvlauncher APK), so
    // uninstalling videos removes the content source; the tab's pages are switched off
    // separately in TVLAUNCHER_SHOP_COMPONENTS below.
    // Disabling (pm disable-user --user 0) sets enabled="3" in package-restrictions.xml so Android OS
    // NEVER relaunches them on reboot, and pm clear wipes their cache completely.
    val STOCK_AD_AND_SHOP_PACKAGES = listOf(
        PackageName("com.google.android.tvrecommendations"),   // Central Ad signals & recommendations
        PackageName("com.google.android.videos"),              // Google Play Movies & TV (POWERS THE SHOP TAB!)
        PackageName("com.google.android.play.games"),          // Play Games TV
        PackageName("com.google.android.youtube.tvmusic"),     // YouTube Music TV
        PackageName("com.google.android.pano.discover"),       // Google TV Discover row & recommendations
        PackageName("com.google.android.tv.frameworkpackagestubs"), // Google TV partner promo stubs
        PackageName("com.google.android.partnersetup"),        // Partner setup attribution
        PackageName("com.smartdevice.leanbacklauncher.customizer"),
        PackageName("com.smartdevice.acr.recommendation"),
        PackageName("com.smartdevice.epopplayer"),
        PackageName("com.smartdevice.emanual"),
        PackageName("com.Smartdevice.emanual"),
        PackageName("fusion.android.tv.demo"),
        PackageName("com.mstar.android.tv.disclaimercustomization"),
        PackageName("com.coocaa.demonow.lightining"),
        PackageName("com.coocaa.sysreport"),
        PackageName("com.coocaa.tvpi"),
        PackageName("com.coocaa.messageprovider"),
        PackageName("com.tianci.ad"),
        PackageName("com.tianci.push"),
        PackageName("com.tianci.autoinstaller"),
        PackageName("com.coocaa.mall"),
    )

    // RFC-SHOP-2 (2026-11-12): the stock launcher's own ad components, proven from the
    // decompiled tvlauncher v7.7.15 manifest (tv_launcher_ad_analysis.md, Method 2 Step 3/4).
    // These four render or feed the sponsored rows and ad dialogs from inside the launcher,
    // so uninstalling tvrecommendations/videos alone cannot stop them: on boot the launcher
    // re-reads its own ad signals and re-draws them. They do NOT render the Shop tab
    // itself — that is the launcher's MainActivity, and its pages are the two Shop
    // activities in TVLAUNCHER_SHOP_COMPONENTS below. Switching them off with
    // `pm disable-user` (Op.DisableComponent) persists across reboot in the per-user
    // package-restrictions, and Undo switches them back on. Only tvlauncher's are hardcoded:
    // launcherx's component class names are not proven anywhere in this repo, so launcherx is
    // covered by the runtime discovery below, never by a guess.
    val TVLAUNCHER_AD_COMPONENTS: List<Component> = listOf(
        Component(STOCK_TV_LAUNCHER, "com.google.android.tvlauncher.home.featuredcarousel.WhyThisAdActivity"),
        Component(STOCK_TV_LAUNCHER, "com.google.android.tvlauncher.home.AdsPersonalizationAwarenessDialogActivity"),
        Component(STOCK_TV_LAUNCHER, "com.google.android.tvlauncher.targetedpromotions.RedeemTargetedPromotionActivity"),
        Component(STOCK_TV_LAUNCHER, "com.google.android.tvlauncher.home.AdsPersonalizationAwarenessNotificationReceiver"),
    )

    // RFC-SHOP-3 (2026-11-12): the stock launcher's own Shop content activities, proven
    // from the shipped tvlauncher v6.4.6 manifest by REA (decode-android-resources on the
    // APKPure APK; the same APK carries the photo's exact texts — shop_tab_title "Shop",
    // "Customize your Home screen", "Customize your recommendations", "Favorite Apps",
    // "Welcome to Shop" — so this IS the launcher drawing the Shop tab on the Skyworth
    // SWTV). The tab button is drawn by the launcher's MainActivity; its pages are these
    // two activities — the Library page (the tv.google.com/library deep link) and the
    // TVOD (buy/rent) player. The four ad components above never touch them, so a plan
    // that only switched those off left the Shop destination intact after every reboot.
    // Switching them off with `pm disable-user` persists across reboot in the per-user
    // package-restrictions, and Undo switches them back on. If a TV's tvlauncher version
    // renamed them, the disable fails and the step continues (CONTINUE) — the deep scan's
    // "shop"/"tvod" signatures below still catch whatever Shop components that version has.
    val TVLAUNCHER_SHOP_COMPONENTS: List<Component> = listOf(
        Component(STOCK_TV_LAUNCHER, "com.google.android.tvlauncher.shop.library.LibraryPageActivity"),
        Component(STOCK_TV_LAUNCHER, "com.google.android.tvlauncher.shop.tvodplayer.TvodPlayerActivity"),
    )

    // RFC-SHOP-2: substrings (lowercased) that mark a launcher component as ad machinery,
    // applied ONLY to the components of a launcher package (the resolved home plus the stock
    // Google launchers), never to the whole TV. "ima" is deliberately absent: it matches
    // "Image..." as well as the ads SDK, and every IMA class also contains "ads".
    private val AD_COMPONENT_SIGNATURES = listOf(
        "ads", "adssignals", "sponsor", "banner", "promos", "promotion", "recommend",
        "featuredcarousel", "infeed", "instream", "targetedpromotion", "adpersonalization",
        "doubleclick", "advertis", "commercial", "freepay", "whythisad",
        // RFC-SHOP-3: "shop" and "tvod" (transactional video-on-demand = buy/rent) mark a
        // launcher component as Shop machinery — tvlauncher's shop.* activities and
        // launcherx's KinetoscopeTvodActivity. Applied ONLY to launcher packages, never to
        // the whole TV.
        "shop", "tvod",
    )

    // Heuristic signatures for Ads & Recommendation daemons
    private val ADS_SIGNATURES = listOf(
        "recommendation",
        "recommender",
        "recommend",
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
        "adv.",
        "billboard",
        "customizer",
        "partnercustomizer",
        "demonow",
        "sysreport",
        "messageprovider",
        "tvpi",
        "autoinstaller"
        // NOTE (S9, 2026-11-12): "mall" was here and matched "small" too; com.coocaa.mall stays
        // caught by its explicit entry in STOCK_AD_AND_SHOP_PACKAGES, so the substring is gone.
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
        "userfeedback",
        "bugreport",
        "logcollector",
        "usagereport"
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
        "com.duokan.videodaily",
        "com.xiaomi.mitv.gamecenter",
        "com.xiaomi.statistic",
        "com.tcl.esticker",
        "com.tcl.loganalyzer",
        "com.tcl.tv.preload",
        "com.sony.dtv.demomode",
        "com.hisense.service.ad",
        "com.smartdevice.commercial",
        "com.panasonic.tv.partner",
        "com.amazon.device.sale",
        "com.amazon.kindle.otter.oobe",
        // Skyworth's preinstalled browser (verified 2026-11-12 from Sunil's own Skyworth
        // SWTV Settings screen: system app, v1.218, 635 kB). A TV browser nobody browses
        // on; small and low-RAM, so rule 4 never catches it — it needs the name.
        "com.qjy.browser",
        // Skyworth's Swaiot smart-home app (verified 2026-11-12 from Sunil's own SWTV
        // Settings screen: Settings shows "Enable", so it is already disabled — disabled
        // is not uninstalled, it still shows in Settings > Apps until pm uninstall
        // --user 0 removes it). The EXACT package only: a "com.security." prefix is too
        // broad and could match a security service another TV genuinely needs.
        "com.security.SwaiotTV",
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
        ramByPackage: Map<PackageName, Int> = emptyMap(),
        // RFC-DEEP-1: the deep scan's result, when Setup ran it. Its candidates join the static
        // ones below; null means the scan could not run and the static rules decide alone.
        deepScan: DeepScanResult? = null,
    ): List<Candidate> {
        val candidates = mutableListOf<Candidate>()
        val stockPkgs = stockLaunchers.map { it.pkg }.toSet()
        val protectedPkgs = STOCK_GOOGLE_LAUNCHERS + stockPkgs

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

            // 2. Facts Table Match. M1 (2026-11-12): "streaming" is NOT bloat — the facts table's
            // 172 streaming entries (Netflix, YouTube, Prime Video, Disney+...) are apps the person
            // watches. Nominating them would uninstall the person's own streaming apps on an
            // unlisted TV. Only ads, telemetry, analytics, bloat and unwanted nominate here.
            // S7: PatchWall (com.mitv.tvhome.atv) is excluded from automatic nomination too — the
            // planner's PatchWall machinery owns that decision and the person may switch it off
            // explicitly; REA must not uninstall a home screen on its own.
            val facts = factsTable.lookup(pkg)
            if (facts != null && pkg != PatchWallFeeds.PATCHWALL) {
                val cat = facts.category.lowercase()
                if (cat in setOf("ads", "telemetry", "analytics", "bloat", "unwanted")) {
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

            // Prefixes are matched against the lowercased package (pkgStr), so the entry
            // is lowercased too: a mixed-case package such as com.security.SwaiotTV would
            // otherwise never match its own entry.
            if (VENDOR_BLOAT_PREFIXES.any { pkgStr.startsWith(it.lowercase()) }) {
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

            // 4b. APK scan (RFC-SHOP-5): the TV's own APK, read on the phone, shows
            // ad/telemetry/bloat machinery the name never hints at — an ad-network
            // string, an ad-signal provider, or a manifest component the REA
            // signatures mark. The loop's guards above already apply: system apps
            // only, denylist, the resolved home, the keyboard and the screensaver
            // are never nominated, and a launcher never appears here (its components
            // are section 3.5's, never an uninstall). A corroborating string alone
            // ("shop", "sponsored" as bare words) never nominates — only a strong
            // match does. APK size alone never nominates either: a big APK is a weak
            // signal only, and the measured RSS (rule 4) stays the authority — size
            // is not even read here.
            val apkScan = deepScan?.evidence?.get(pkg)?.apkScan
            if (apkScan != null && apkScan.hasStrongAdEvidence) {
                candidates += Candidate(
                    pkg = pkg,
                    friendlyName = formatName(pkg.value),
                    description = "APK scan: ${apkScan.evidenceSummary()}",
                    category = apkScan.strongestCategory ?: "bloat",
                    estimatedRamMb = ramMb(pkg, ramByPackage)
                )
                continue
            }
        }

        // 5. Deep scan (RFC-DEEP-1): what the names cannot see. A suspect the scan caught with a
        // boot receiver, a scheduled job or a content provider nominates even when no static rule
        // above fired — that is the package that brings the Shop tab and sponsored banners back
        // after a reboot.
        if (deepScan != null) candidates += findDeepScanCandidates(deepScan, snapshot, stockLaunchers)

        // Heaviest first, so the worst RAM hogs sit at the top of their group; the name
        // breaks ties, so the order is stable when nothing is measured.
        return candidates.distinctBy { it.pkg }
            .sortedWith(compareByDescending<Candidate> { it.estimatedRamMb }.thenBy { it.pkg.value })
    }

    /**
     * RFC-DEEP-1: the packages the deep scan dumps one by one. Name evidence only — the Shop/ad
     * list, the ad and telemetry signatures, the vendor bloat prefixes — on system packages the
     * denylist and the stock launchers do not protect. The scan itself then decides, from what the
     * TV actually prints, which of these revive ads after a reboot.
     */
    fun deepScanSuspects(snapshot: Snapshot): Set<PackageName> {
        val suspects = LinkedHashSet<PackageName>()
        for (pkg in snapshot.packages.keys) {
            val state = snapshot.packages[pkg] ?: continue
            if (!state.system) continue
            if (pkg in STOCK_GOOGLE_LAUNCHERS) continue
            if (Denylist.staticReason(pkg.value) != null) continue
            val pkgStr = pkg.value.lowercase()
            if (pkg in STOCK_AD_AND_SHOP_PACKAGES ||
                ADS_SIGNATURES.any { pkgStr.contains(it) } ||
                TELEMETRY_SIGNATURES.any { pkgStr.contains(it) } ||
                VENDOR_BLOAT_PREFIXES.any { pkgStr.startsWith(it.lowercase()) }) {
                suspects += pkg
            }
        }
        return suspects
    }

    /**
     * RFC-DEEP-1: evidence-based nomination from a deep scan. A package nominates ONLY when the
     * scan shows it can revive ads: a BOOT_COMPLETED receiver (it wakes when the TV starts), a
     * scheduled JobScheduler job (it re-fetches ads and Shop config in the background), or a
     * content provider feeding the launcher. The same safety order as the static rules applies
     * first: system apps only, denylist, the resolved home, the keyboard and the screensaver are
     * never nominated — so the stock launcher itself, which has all three kinds of evidence,
     * never appears here. A partnercustomizer that only injects sponsored rows nominates; one the
     * denylist protects (an input-critical package such as com.tcl.dashboard) never does.
     */
    fun findDeepScanCandidates(
        scan: DeepScanResult,
        snapshot: Snapshot,
        stockLaunchers: List<Component>,
    ): List<Candidate> {
        val stockPkgs = stockLaunchers.map { it.pkg }.toSet()
        val protectedPkgs = STOCK_GOOGLE_LAUNCHERS + stockPkgs
        val out = mutableListOf<Candidate>()
        for ((pkg, evidence) in scan.evidence) {
            val state = snapshot.packages[pkg] ?: continue
            if (!state.system) continue
            if (pkg in protectedPkgs) continue
            // S7: PatchWall is excluded from automatic nomination — see rule 2 above.
            if (pkg == PatchWallFeeds.PATCHWALL) continue
            if (Denylist.staticReason(pkg.value) != null) continue
            if (snapshot.home.resolved?.pkg == pkg) continue
            if (snapshot.ime?.pkg == pkg || snapshot.screensaver.any { it.pkg == pkg }) continue
            if (!evidence.hasRevivalEvidence) continue
            val reasons = buildList {
                if (evidence.bootReceivers.isNotEmpty()) add("wakes at boot (${evidence.bootReceivers.size} receiver${if (evidence.bootReceivers.size == 1) "" else "s"})")
                if (evidence.jobs > 0) add("${evidence.jobs} scheduled job${if (evidence.jobs == 1) "" else "s"}")
                if (evidence.providers.isNotEmpty()) add("content provider (${evidence.providers.size})")
            }
            out += Candidate(
                pkg = pkg,
                friendlyName = formatName(pkg.value),
                description = "Deep scan: ${reasons.joinToString(", ")} — brings ads back after reboot",
                category = "ads",
                estimatedRamMb = 35,
            )
        }
        return out
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
     * RFC-SHOP-2: the launcher packages the deep scan must dump, so findLauncherAdComponents
     * can read their components: the resolved home (whatever its package is — a Coocaa
     * launcher on the Skyworth SWTV, a Google one elsewhere) plus any stock Google launcher
     * installed. The scan cap is 40 and these names sort early, so they are effectively
     * never cut.
     */
    fun launcherScanPackages(snapshot: Snapshot): Set<PackageName> {
        val out = LinkedHashSet<PackageName>()
        snapshot.home.resolved?.pkg?.let { if (it in snapshot.packages) out += it }
        for (pkg in STOCK_GOOGLE_LAUNCHERS) if (pkg in snapshot.packages) out += pkg
        return out
    }

    /**
     * RFC-SHOP-2: the launcher components to switch off so the Shop tab and sponsored banners
     * cannot come back after a reboot. Three sources, all evidence-only:
     *  - the manifest-proven tvlauncher components, when tvlauncher is installed — the four
     *    ad components plus the two Shop content activities (TVLAUNCHER_SHOP_COMPONENTS);
     *  - the deep scan's components of a launcher package whose class matches an ad or Shop
     *    signature (boot receivers, services, activities, providers the dump lists), when
     *    the scan actually saw them;
     *  - RFC-SHOP-5: the APK scan's components of a launcher package, read from the TV's
     *    own APK on the phone (manifest components the REA signatures mark), when the
     *    scan actually saw them.
     * Never: the resolved home component itself; any class with "mainactivity" or
     * "homeactivity" in it (the home screen's own entry points); a component the dump already
     * shows disabled (Undo must never switch on something that was off before the plan);
     * a component of a denylisted package. Sorted by flat name, so the plan order is stable.
     */
    fun findLauncherAdComponents(scan: DeepScanResult?, snapshot: Snapshot): List<Component> {
        val launchers = launcherScanPackages(snapshot)
        val home = snapshot.home.resolved
        val out = LinkedHashSet<Component>()
        fun isHomeComponent(c: Component): Boolean = c == home ||
            c.cls.lowercase().contains("mainactivity") || c.cls.lowercase().contains("homeactivity")
        fun allowed(c: Component): Boolean {
            if (isHomeComponent(c)) return false
            if (Denylist.staticReason(c.pkg.value) != null) return false
            return true
        }
        if (STOCK_TV_LAUNCHER in snapshot.packages) {
            // A hardcoded component the dump already shows disabled was off before us: never
            // plan it — Undo must never switch on something that was off before the plan.
            val disabled = scan?.evidence?.get(STOCK_TV_LAUNCHER)?.disabledComponents?.toSet().orEmpty()
            for (c in TVLAUNCHER_AD_COMPONENTS + TVLAUNCHER_SHOP_COMPONENTS) if (c !in disabled && allowed(c)) out += c
        }
        if (scan != null) {
            for (pkg in launchers) {
                val ev = scan.evidence[pkg] ?: continue
                val disabled = ev.disabledComponents.toSet()
                val seen = ev.bootReceivers + ev.services + ev.activities + ev.providers
                for (cls in seen) {
                    val c = Component(pkg, cls)
                    if (c in disabled) continue
                    if (!allowed(c)) continue
                    if (AD_COMPONENT_SIGNATURES.none { cls.lowercase().contains(it) }) continue
                    out += c
                }
                // RFC-SHOP-5: the APK scan's components of this launcher, read from the
                // TV's own APK on the phone (manifest components the REA signatures
                // mark — Shop activities, ad components, ad-signal providers, Shop
                // deep links). They join the dump's components — the hardcoded
                // TVLAUNCHER_SHOP_COMPONENTS above stay as the fallback, so a launcher
                // whose APK the scan could not pull still loses its Shop pages.
                // The scanner already refused the home entry point; allowed() re-checks
                // it, the denylist and the already-off list here.
                val apk = ev.apkScan
                if (apk != null) {
                    for (found in apk.components) {
                        val c = found.component
                        if (c in disabled) continue
                        if (!allowed(c)) continue
                        out += c
                    }
                }
            }
        }
        return out.sortedBy { it.flat }
    }

    /**
     * RFC-SHOP-2 (2026-11-12): Clean Stock Home is gated on the TV having a home screen the
     * plan knows how to clean. Fire OS is out first: its launcher is Amazon's, none of the
     * Shop/ad machinery applies, and the Fire OS tests pin the clean-up alone there. A home
     * screen the person installed is out too: Clean Stock Home clears the launcher's data
     * and restarts it, and a person's launcher is theirs to keep as it is. So is ours: our
     * launcher draws no Shop tab, so there is nothing to clean while it is home. Otherwise
     * a Google launcher installed qualifies (the old rule), or any OTHER resolved home — a
     * vendor launcher, whose ad packages section 2 uninstalls and whose ad components
     * section 3.5 disables. (The Skyworth SWTV's Shop tab is drawn by tvlauncher itself,
     * Google's Android TV home — REA 2026-11-12 decoded the photo's exact texts from the
     * shipped tvlauncher APK — not by the Coocaa launcher.) A TV whose HOME is undecided
     * (a placeholder or nothing) and has no Google launcher has no home to clean.
     */
    fun isCleanStockHomeEligible(snapshot: Snapshot): Boolean {
        if (FireOs.isFireOs(snapshot.facts, snapshot)) return false
        val resolved = snapshot.home.resolved
        if (resolved != null && !StockHome.isPlaceholder(resolved)) {
            if (SwitchOffRule.isPersonsLauncher(resolved.pkg, snapshot)) return false
            if (resolved.pkg == SwitchOffRule.OUR_LAUNCHER) return false
        }
        if (isStockGoogleTvLauncher(snapshot)) return true
        if (resolved == null || StockHome.isPlaceholder(resolved)) return false
        return true
    }

    /**
     * RFC-SHOP-2: the launcher Clean Stock Home clears and restarts: the TV's own resolved
     * home when it is a real, installed launcher (a vendor launcher on an unlisted TV as
     * well as a Google one), else the Google stock component when that is installed and on,
     * else null (no launcher to clear — the steps are skipped, not failed). The old code
     * always returned a Google component, even on a TV with no Google launcher at all, so
     * `pm clear` and the restart failed every run there. A person's launcher and ours are
     * never returned here: their data is not this plan's to clear.
     */
    fun stockHomeToClean(snapshot: Snapshot, stockLaunchers: List<Component>): Component? {
        val resolved = snapshot.home.resolved
        if (resolved != null && !StockHome.isPlaceholder(resolved) && resolved.pkg in snapshot.packages &&
            !SwitchOffRule.isPersonsLauncher(resolved.pkg, snapshot) && resolved.pkg != SwitchOffRule.OUR_LAUNCHER) {
            return resolved
        }
        val google = resolveStockLauncherComponent(snapshot, stockLaunchers)
        val state = snapshot.packages[google.pkg]?.enabled
        if (google.pkg in snapshot.packages && (state == EnabledState.ENABLED || state == EnabledState.DEFAULT)) return google
        return null
    }

    /**
     * Checks if Option A: Clean Stock Launcher is applicable for Android TV or Google TV.
     */
    fun isStockGoogleTvLauncher(snapshot: Snapshot): Boolean {
        return snapshot.packages.containsKey(STOCK_TV_LAUNCHER) ||
               snapshot.packages.containsKey(STOCK_GOOGLE_TV_LAUNCHER)
    }

    /**
     * Resolves the primary stock launcher component on the connected TV:
     * - Google TV launcherx -> com.google.android.apps.tv.launcherx/.home.HomeActivity
     * - Android TV tvlauncher -> com.google.android.tvlauncher/.MainActivity
     */
    fun resolveStockLauncherComponent(snapshot: Snapshot, stockLaunchers: List<Component> = emptyList()): Component {
        val resolved = snapshot.home.resolved
        if (resolved != null && resolved.pkg in STOCK_GOOGLE_LAUNCHERS && resolved.pkg in snapshot.packages) {
            return resolved
        }
        val fromStockList = stockLaunchers.firstOrNull { it.pkg in STOCK_GOOGLE_LAUNCHERS && it.pkg in snapshot.packages }
        if (fromStockList != null) return fromStockList

        return if (snapshot.packages.containsKey(STOCK_GOOGLE_TV_LAUNCHER)) {
            Component(STOCK_GOOGLE_TV_LAUNCHER, ".home.HomeActivity")
        } else {
            Component(STOCK_TV_LAUNCHER, ".MainActivity")
        }
    }

    // S8 (2026-11-12): buildCleanStockHomeOps lived here as dead code — no live caller, only
    // tests and the README named it. The live Clean Stock Home is DefaultPlanner.cleanStockHome,
    // which is also where the step order (content delete before uninstall) is enforced. Deleted;
    // the README now points at DefaultPlanner instead.

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
