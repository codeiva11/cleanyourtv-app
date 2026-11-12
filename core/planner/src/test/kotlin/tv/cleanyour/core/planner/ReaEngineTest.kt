package tv.cleanyour.core.planner

import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.DeepScanEvidence
import tv.cleanyour.core.model.DeepScanResult
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.spec.FactsTable
import tv.cleanyour.core.spec.PackageFacts
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaEngineTest {

    private fun state(system: Boolean = true) = PackageState(
        enabled = EnabledState.ENABLED,
        system = system,
        versionCode = 1L,
        signer = null,
        persistent = false,
        tvInput = false,
        label = "App",
        installedByUs = false
    )

    private val fakeFacts = object : FactsTable {
        override fun lookup(pkg: PackageName): PackageFacts? = when (pkg.value) {
            "com.google.android.tvrecommendations" -> PackageFacts("TV Recommendations", "Adverts and recommendations", "ads")
            "com.xiaomi.mitv.analytic" -> PackageFacts("Xiaomi Analytics", "Usage and analytics tracker", "telemetry")
            "com.example.streamer" -> PackageFacts("Streamer", "A streaming app the person watches", "streaming")
            else -> null
        }
    }

    @Test
    fun findsBloatwareAndAdvertsAutomatically() {
        val packages = mapOf(
            PackageName("com.google.android.tvlauncher") to state(),
            PackageName("com.google.android.tvrecommendations") to state(),
            PackageName("com.xiaomi.mitv.analytic") to state(),
            PackageName("com.mediatek.wwtv.tvrecommendation") to state(),
            PackageName("com.tcl.partnercustomizer") to state(),
            PackageName("com.android.settings") to state() // System protected!
        )
        val snap = Samples.snapshot(packages = packages)
        val stockLaunchers = listOf(Component(PackageName("com.google.android.tvlauncher"), ".MainActivity"))

        val candidates = ReaEngine.findDebloatCandidates(snap, fakeFacts, stockLaunchers)

        val candidateNames = candidates.map { it.pkg.value }
        // Protected package com.android.settings must NEVER be targeted
        assertFalse("com.android.settings" in candidateNames)
        // Stock launcher must NEVER be targeted
        assertFalse("com.google.android.tvlauncher" in candidateNames)

        // Ad and telemetry bloatware must be discovered
        assertTrue("com.google.android.tvrecommendations" in candidateNames)
        assertTrue("com.xiaomi.mitv.analytic" in candidateNames)
        assertTrue("com.mediatek.wwtv.tvrecommendation" in candidateNames)
        assertTrue("com.tcl.partnercustomizer" in candidateNames)
    }

    @Test
    fun synthesizesPackageGroupsCorrectly() {
        val candidates = listOf(
            ReaEngine.Candidate(PackageName("com.google.android.tvrecommendations"), "TV Recs", "Ads", "ads", 50),
            ReaEngine.Candidate(PackageName("com.xiaomi.mitv.analytic"), "Analytics", "Telemetry", "telemetry", 40)
        )
        val groups = ReaEngine.synthesizeReaGroups(candidates)
        assertEquals(2, groups.size)
        assertEquals("rea_ads", groups[0].id)
        assertEquals("rea_telemetry", groups[1].id)
    }

    // S8 (2026-11-12): buildCleanStockHomeOps is deleted — the live Clean Stock Home is
    // DefaultPlanner.cleanStockHome. What those two tests pinned (the stock launcher's correct
    // activity per launcher) is pinned here instead, on resolveStockLauncherComponent.
    @Test
    fun resolvesTheRightMainActivityPerStockLauncher() {
        val launcherx = PackageName("com.google.android.apps.tv.launcherx")
        val tvlauncher = PackageName("com.google.android.tvlauncher")
        val snapX = Samples.snapshot(
            packages = mapOf(launcherx to state(), PackageName("com.google.android.tvrecommendations") to state()),
            home = HomeResolution(Component(launcherx, ".home.HomeActivity"), listOf(HomeClaimant(Component(launcherx, ".home.HomeActivity"), 2))),
        )
        assertTrue(ReaEngine.isStockGoogleTvLauncher(snapX))
        assertEquals(Component(launcherx, ".home.HomeActivity"), ReaEngine.resolveStockLauncherComponent(snapX))
        val snapTv = Samples.snapshot(
            packages = mapOf(tvlauncher to state(), PackageName("com.google.android.tvrecommendations") to state()),
            home = HomeResolution(Component(tvlauncher, ".MainActivity"), listOf(HomeClaimant(Component(tvlauncher, ".MainActivity"), 2))),
        )
        assertTrue(ReaEngine.isStockGoogleTvLauncher(snapTv))
        assertEquals(Component(tvlauncher, ".MainActivity"), ReaEngine.resolveStockLauncherComponent(snapTv))
    }

    // M1 (2026-11-12): a "streaming" facts entry is an app the person watches, not bloat.
    // It must never nominate, even on an unlisted TV with no profile.
    @Test
    fun doesNotNominateStreamingApps() {
        val streamer = PackageName("com.example.streamer")
        val packages = mapOf(
            streamer to state(),
            PackageName("com.google.android.tvrecommendations") to state(),
        )
        val snap = Samples.snapshot(packages = packages)
        val stockLaunchers = listOf(Component(PackageName("com.google.android.tvlauncher"), ".MainActivity"))

        val candidates = ReaEngine.findDebloatCandidates(snap, fakeFacts, stockLaunchers)
        val candidateNames = candidates.map { it.pkg.value }
        assertFalse("com.example.streamer" in candidateNames)
        assertTrue("com.google.android.tvrecommendations" in candidateNames)
    }

    // RFC-DEEP-1: a suspect the scan caught waking at boot and keeping scheduled jobs nominates,
    // with the evidence in its description.
    @Test
    fun deepScanNominatesPackagesWithBootReceiversAndJobs() {
        val customizer = PackageName("com.vendor.customizer")
        val packages = mapOf(
            customizer to state(),
            PackageName("com.google.android.tvlauncher") to state(),
        )
        val snap = Samples.snapshot(packages = packages)
        val stockLaunchers = listOf(Component(PackageName("com.google.android.tvlauncher"), ".MainActivity"))
        val scan = DeepScanResult(1, mapOf(
            customizer to DeepScanEvidence(bootReceivers = listOf(".BootReceiver"), jobs = 2),
        ))

        val candidates = ReaEngine.findDeepScanCandidates(scan, snap, stockLaunchers)
        assertEquals(1, candidates.size)
        assertEquals("ads", candidates[0].category)
        assertTrue("wakes at boot" in candidates[0].description, candidates[0].description)
        assertTrue("2 scheduled jobs" in candidates[0].description, candidates[0].description)
    }

    @Test
    fun deepScanDoesNotNominateWithoutEvidenceOrWhenProtected() {
        val quiet = PackageName("com.vendor.quiet")
        val launcher = PackageName("com.google.android.tvlauncher")
        val packages = mapOf(quiet to state(), launcher to state(), PackageName("com.android.vending") to state())
        val snap = Samples.snapshot(packages = packages)
        val stockLaunchers = listOf(Component(launcher, ".MainActivity"))
        val scan = DeepScanResult(1, mapOf(
            // No boot receiver, no job, no provider: a name alone is not evidence.
            quiet to DeepScanEvidence(),
            // The stock launcher has all three kinds of evidence and still never nominates.
            launcher to DeepScanEvidence(bootReceivers = listOf(".BootReceiver"), providers = listOf("HomeProvider"), jobs = 3),
            // The Play Store is denylisted: evidence or not, it is never a candidate.
            PackageName("com.android.vending") to DeepScanEvidence(bootReceivers = listOf(".BootReceiver"), jobs = 1),
        ))

        assertTrue(ReaEngine.findDeepScanCandidates(scan, snap, stockLaunchers).isEmpty())
    }

    @Test
    fun deepScanCandidatesJoinTheStaticOnes() {
        val customizer = PackageName("com.vendor.customizer")
        val packages = mapOf(
            customizer to state(),
            PackageName("com.google.android.tvrecommendations") to state(),
        )
        val snap = Samples.snapshot(packages = packages)
        val stockLaunchers = listOf(Component(PackageName("com.google.android.tvlauncher"), ".MainActivity"))
        val scan = DeepScanResult(1, mapOf(
            customizer to DeepScanEvidence(providers = listOf("AdProvider")),
        ))

        val candidates = ReaEngine.findDebloatCandidates(snap, fakeFacts, stockLaunchers, deepScan = scan)
        val candidateNames = candidates.map { it.pkg.value }
        assertTrue("com.vendor.customizer" in candidateNames)
        assertTrue("com.google.android.tvrecommendations" in candidateNames)
    }

    // 2026-11-10: a Skyworth SWTV with no profile and no Google launcher showed
    // "Nothing to switch off" — no package name matched any REA signature. Heavy
    // measured RAM must nominate on its own, so an unknown TV still gets a plan.
    @Test
    fun findsHeavyRamSystemAppsByMeasuredRam() {
        val heavy = PackageName("com.vendor.heavyservice")
        val packages = mapOf(heavy to state())
        val snap = Samples.snapshot(packages = packages)
        val stockLaunchers = listOf(Component(PackageName("com.google.android.tvlauncher"), ".MainActivity"))

        val candidates = ReaEngine.findDebloatCandidates(
            snap, fakeFacts, stockLaunchers, ramByPackage = mapOf(heavy to 80 * 1024)
        )

        assertEquals(1, candidates.size)
        assertEquals("ram", candidates[0].category)
        assertEquals(80, candidates[0].estimatedRamMb)
    }

    @Test
    fun doesNotNominateByRamWithoutMeasurement() {
        val heavy = PackageName("com.vendor.heavyservice")
        val packages = mapOf(heavy to state())
        val snap = Samples.snapshot(packages = packages)
        val stockLaunchers = listOf(Component(PackageName("com.google.android.tvlauncher"), ".MainActivity"))

        // No ramByPackage entry: the estimate never nominates.
        val candidates = ReaEngine.findDebloatCandidates(snap, fakeFacts, stockLaunchers)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun doesNotNominateUserAppsByRam() {
        val heavy = PackageName("com.example.userapp")
        val packages = mapOf(heavy to state(system = false))
        val snap = Samples.snapshot(packages = packages)
        val stockLaunchers = listOf(Component(PackageName("com.google.android.tvlauncher"), ".MainActivity"))

        val candidates = ReaEngine.findDebloatCandidates(
            snap, fakeFacts, stockLaunchers, ramByPackage = mapOf(heavy to 120 * 1024)
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun doesNotNominateProtectedAppsByRam() {
        val settings = PackageName("com.android.settings")
        val packages = mapOf(settings to state())
        val snap = Samples.snapshot(packages = packages)
        val stockLaunchers = listOf(Component(PackageName("com.google.android.tvlauncher"), ".MainActivity"))

        val candidates = ReaEngine.findDebloatCandidates(
            snap, fakeFacts, stockLaunchers, ramByPackage = mapOf(settings to 120 * 1024)
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun doesNotNominateLightAppsByRam() {
        val light = PackageName("com.vendor.lightservice")
        val packages = mapOf(light to state())
        val snap = Samples.snapshot(packages = packages)
        val stockLaunchers = listOf(Component(PackageName("com.google.android.tvlauncher"), ".MainActivity"))

        val candidates = ReaEngine.findDebloatCandidates(
            snap, fakeFacts, stockLaunchers, ramByPackage = mapOf(light to 20 * 1024)
        )

        assertTrue(candidates.isEmpty())
    }

    // RFC-SHOP-2 (2026-11-12): the four manifest-proven tvlauncher ad components are the ones
    // to switch off when tvlauncher is installed — none of them is the home component, so all
    // four are found, even with no deep scan at all.
    @Test
    fun launcherAdComponentsAreTheHardcodedTvlauncherOnes() {
        val launcher = PackageName("com.google.android.tvlauncher")
        val snap = Samples.snapshot(
            packages = mapOf(launcher to state(), PackageName("com.google.android.tvrecommendations") to state()),
            home = HomeResolution(Component(launcher, ".MainActivity"), listOf(HomeClaimant(Component(launcher, ".MainActivity"), 2))),
        )
        assertEquals(ReaEngine.TVLAUNCHER_AD_COMPONENTS.sortedBy { it.flat }, ReaEngine.findLauncherAdComponents(null, snap))
    }

    // RFC-SHOP-2: a launcher with no hardcoded components (a Coocaa launcher on the Skyworth
    // SWTV) gets only what the deep scan actually saw — an ad service, an ad provider and an
    // ad boot receiver — and never its home entry point, never a component with no ad signature,
    // and never one the dump already shows disabled.
    @Test
    fun launcherAdComponentsComeFromTheScanForALauncherWithoutHardcodedOnes() {
        val vendor = PackageName("com.vendor.launcher")
        val snap = Samples.snapshot(
            packages = mapOf(vendor to state(), PackageName("com.google.android.tvrecommendations") to state()),
            home = HomeResolution(Component(vendor, ".MainActivity"), listOf(HomeClaimant(Component(vendor, ".MainActivity"), 2))),
        )
        val scan = DeepScanResult(1, mapOf(
            vendor to DeepScanEvidence(
                bootReceivers = listOf(".AdsBootReceiver"),
                services = listOf("com.vendor.launcher.ads.AdService", "com.vendor.launcher.ImageLoader"),
                activities = listOf(".MainActivity", ".ShopActivity"),
                providers = listOf("com.vendor.launcher.ads.AdProvider"),
                disabledComponents = listOf(Component(vendor, "com.vendor.launcher.ads.OldAdService")),
            ),
        ))
        // ImageLoader matches no signature ("ima" is deliberately not one: it matches "Image"
        // as well as the ads SDK, and every IMA class also contains "ads"). .ShopActivity is
        // not ad machinery either — "shop" is not a signature.
        assertEquals(
            listOf(
                Component(vendor, ".AdsBootReceiver"),
                Component(vendor, "com.vendor.launcher.ads.AdProvider"),
                Component(vendor, "com.vendor.launcher.ads.AdService"),
            ),
            ReaEngine.findLauncherAdComponents(scan, snap),
        )
    }

    // RFC-SHOP-2: a hardcoded component the dump already shows disabled was off before us —
    // never plan it, or Undo would switch on something that was off before the plan.
    @Test
    fun launcherAdComponentsNeverIncludeOneThatWasAlreadyOff() {
        val launcher = PackageName("com.google.android.tvlauncher")
        val snap = Samples.snapshot(
            packages = mapOf(launcher to state()),
            home = HomeResolution(Component(launcher, ".MainActivity"), listOf(HomeClaimant(Component(launcher, ".MainActivity"), 2))),
        )
        val off = Component(launcher, "com.google.android.tvlauncher.home.featuredcarousel.WhyThisAdActivity")
        val scan = DeepScanResult(1, mapOf(launcher to DeepScanEvidence(disabledComponents = listOf(off))))
        val found = ReaEngine.findLauncherAdComponents(scan, snap)
        assertFalse(off in found)
        assertEquals(3, found.size)
    }

    // RFC-SHOP-2: Clean Stock Home is eligible on any home the plan knows how to clean — a
    // vendor launcher's Shop tab is served by the vendor's own ad packages and ad components,
    // not by Google's — and out on Fire OS, on a person's launcher, and on ours.
    @Test
    fun cleanStockHomeIsEligibleOnAnyHomeExceptFireOsAPersonsLauncherOrOurs() {
        val vendor = PackageName("com.vendor.launcher")
        val vendorSnap = Samples.snapshot(
            packages = mapOf(vendor to state(system = true)),
            home = HomeResolution(Component(vendor, ".MainActivity"), listOf(HomeClaimant(Component(vendor, ".MainActivity"), 2))),
        )
        assertTrue(ReaEngine.isCleanStockHomeEligible(vendorSnap))
        // Fire OS: an Amazon home, whatever its launcher is.
        val fireFacts = Samples.miTvFacts().copy(brand = "Insignia", manufacturer = "Amazon", model = "AFTDCT99", device = "unknown")
        val fire = PackageName("com.amazon.tv.launcher")
        val fireSnap = Samples.snapshot(
            facts = fireFacts,
            packages = mapOf(fire to state(system = true)),
            home = HomeResolution(Component(fire, ".ui.HomeActivity_vNext"), listOf(HomeClaimant(Component(fire, ".ui.HomeActivity_vNext"), 950))),
        )
        assertFalse(ReaEngine.isCleanStockHomeEligible(fireSnap))
        // A person's launcher: a user app claiming HOME.
        val persons = PackageName("com.example.personslauncher")
        val personsSnap = Samples.snapshot(
            packages = mapOf(persons to state(system = false)),
            home = HomeResolution(Component(persons, ".MainActivity"), listOf(HomeClaimant(Component(persons, ".MainActivity"), 2))),
        )
        assertFalse(ReaEngine.isCleanStockHomeEligible(personsSnap))
        // Ours, in charge.
        val ours = PackageName("com.gathin.tvhome")
        val oursSnap = Samples.snapshot(
            packages = mapOf(ours to state(system = false)),
            home = HomeResolution(Component(ours, ".MainActivity"), listOf(HomeClaimant(Component(ours, ".MainActivity"), 2))),
        )
        assertFalse(ReaEngine.isCleanStockHomeEligible(oursSnap))
        // A Google launcher installed, HOME undecided: still eligible (the old rule).
        val googleSnap = Samples.snapshot(
            packages = mapOf(PackageName("com.google.android.tvlauncher") to state()),
            home = HomeResolution(null, emptyList()),
        )
        assertTrue(ReaEngine.isCleanStockHomeEligible(googleSnap))
        // HOME undecided and no Google launcher: nothing to clean.
        val noneSnap = Samples.snapshot(
            packages = mapOf(PackageName("com.vendor.app") to state()),
            home = HomeResolution(null, emptyList()),
        )
        assertFalse(ReaEngine.isCleanStockHomeEligible(noneSnap))
    }

    // RFC-SHOP-2: the launcher Clean Stock Home clears and restarts is the TV's own resolved
    // home when it is real and installed — a vendor launcher as well as a Google one — never a
    // Google component on a TV with no Google launcher, and never a person's launcher or ours.
    @Test
    fun stockHomeToCleanIsTheTvsOwnHomeOrNull() {
        val vendor = PackageName("com.vendor.launcher")
        val vendorSnap = Samples.snapshot(
            packages = mapOf(vendor to state(system = true)),
            home = HomeResolution(Component(vendor, ".MainActivity"), listOf(HomeClaimant(Component(vendor, ".MainActivity"), 2))),
        )
        assertEquals(Component(vendor, ".MainActivity"), ReaEngine.stockHomeToClean(vendorSnap, emptyList()))
        val noneSnap = Samples.snapshot(
            packages = mapOf(PackageName("com.vendor.app") to state()),
            home = HomeResolution(null, emptyList()),
        )
        assertEquals(null, ReaEngine.stockHomeToClean(noneSnap, emptyList()))
    }

    @Test
    fun synthesizesRamGroupSeparately() {
        val candidates = listOf(
            ReaEngine.Candidate(PackageName("com.vendor.heavyservice"), "Heavyservice", "Heavy RAM use", "ram", 80),
            ReaEngine.Candidate(PackageName("com.vendor.oldapp"), "Oldapp", "Bloat", "bloat", 30)
        )
        val groups = ReaEngine.synthesizeReaGroups(candidates)
        assertEquals(2, groups.size)
        assertEquals("rea_ram", groups[0].id)
        assertEquals("rea_bloat", groups[1].id)
    }
}
