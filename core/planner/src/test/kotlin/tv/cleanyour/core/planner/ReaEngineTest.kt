package tv.cleanyour.core.planner

import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.SettingKey
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

    @Test
    fun buildsCleanStockHomeOps() {
        val packages = mapOf(
            PackageName("com.google.android.tvlauncher") to state(),
            PackageName("com.google.android.tvrecommendations") to state()
        )
        val snap = Samples.snapshot(packages = packages)

        val ops = ReaEngine.buildCleanStockHomeOps(snap)
        assertTrue(ops.any { it is Op.Disable && it.pkg == PackageName("com.google.android.tvrecommendations") })
        assertTrue(ops.any { it is Op.ClearPackage && it.pkg == PackageName("com.google.android.tvrecommendations") })
        assertTrue(ops.any { it is Op.DeleteContent })
        assertTrue(ops.any { it is Op.SettingsPut && it.key == SettingKey.FREEPLAY_ENABLED && it.value == "0" })
        assertTrue(ops.any { it is Op.SettingsPut && it.key == SettingKey.LIMIT_AD_TRACKING && it.value == "1" })
        assertTrue(ops.any { it is Op.ClearPackage && it.pkg == PackageName("com.google.android.tvlauncher") })
        assertTrue(ops.any { it is Op.RestartHome && it.component.pkg == PackageName("com.google.android.tvlauncher") })
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
