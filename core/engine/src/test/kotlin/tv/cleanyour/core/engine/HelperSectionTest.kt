package tv.cleanyour.core.engine

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PlanId
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.ops.HelperLines
import tv.cleanyour.core.planner.DefaultPlanner
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.testing.fixtures.Samples
import tv.cleanyour.testing.fixtures.sha256Of
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// RFC-HELPER-1 (test build): the Home button helper's guarded section on the scripted TV. Home is
// never moved; the section adds our HomeKeyService, switches accessibility on when nothing else is
// listed, presses Home and asks; step R takes our entry out again, by the person's No, a focus on
// the TV's own home screen that stays, or a lost connection.
class HelperSectionTest {
    private val service = HelperLines.SERVICE
    private val reader = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"

    private fun Harness.helperPlan(): Plan {
        val bytes = "{\n  \"stock_home_redirect\": \"com.google.android.tvlauncher\",\n  \"key_filter\": false\n}\n".encodeToByteArray()
        val sha = sha256Of(bytes)
        tv.blobs[sha] = bytes
        return storedHelperPlan(BlobRef(sha, bytes.size.toLong(), "blob:config"))
    }

    // The helper, once bound, sends the TV's own home screen on to ours.
    private fun Harness.redirects() { tv.onHomeKey = { tv.focus = if (tv.helperBound) launcher else stock } }

    private suspend fun TestScope.checkScreen(h: Harness, engine: Engine, plan: Plan): RunState.AwaitingConfirm {
        assertEquals(StartResult.Started, engine.start(plan, h.tv, plan.hash, h.pass()))
        h.settle(engine)
        return assertIs<RunState.AwaitingConfirm>(engine.runState(h.tv.tvId).value)
    }

    @Test fun yesKeepsTheHelperAndTheTvsOwnHome() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this); h.redirects()
        val engine = h.engine()
        val plan = h.helperPlan()
        val s = checkScreen(h, engine, plan)
        assertEquals(DefaultPlanner.SECTION_HELPER, s.section.name)
        assertEquals(service.flat, h.tv.settings[SettingKey.ENABLED_ACCESSIBILITY])
        assertEquals("1", h.tv.settings[SettingKey.ACCESSIBILITY_ENABLED])
        assertTrue(s.section.token.value in h.tv.watchdogs, "the safety timer runs during the check")
        assertEquals(h.stock, h.tv.home, "HOME is never moved")
        advanceTimeBy(2_500); runCurrent()
        assertEquals(true, (engine.runState(h.tv.tvId).value as RunState.AwaitingConfirm).homeCheck)
        engine.confirmHome(h.tv.tvId)
        val fin = assertIs<RunState.Finished>(h.settle(engine))
        assertEquals(RunStatus.COMPLETED, fin.status, fin.skipped.toString())
        assertTrue(h.tv.watchdogs.isEmpty())
        assertEquals(service.flat, h.tv.settings[SettingKey.ENABLED_ACCESSIBILITY])
        assertEquals(h.stock, h.tv.home)
        assertTrue(h.tv.files.containsKey("/sdcard/Android/data/com.gathin.tvhome/files/config.json"), "the look with the redirect went first")
    }

    @Test fun noTakesOurEntryOutAndAccessibilityBackOff() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this); h.redirects()
        val engine = h.engine()
        val plan = h.helperPlan()
        checkScreen(h, engine, plan)
        engine.rejectHome(h.tv.tvId)
        val fin = assertIs<RunState.Finished>(h.settle(engine))
        assertEquals(HomeOutcome.USER_SAID_NO, fin.home)
        assertEquals(null, h.tv.settings[SettingKey.ENABLED_ACCESSIBILITY])
        assertEquals("0", h.tv.settings[SettingKey.ACCESSIBILITY_ENABLED])
        assertTrue(h.tv.watchdogs.isEmpty())
        assertEquals(listOf("section:ARMED", "section:ROLLED_BACK"), h.types(plan).filter { it.startsWith("section") })
    }

    // S6, S7: the helper did not bind, so Home stays on the TV's own home screen: rolled back.
    @Test fun theTvsOwnHomeInFrontRollsBack() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        h.tv.onHomeKey = { h.tv.focus = h.stock }
        val engine = h.engine()
        val plan = h.helperPlan()
        checkScreen(h, engine, plan)
        advanceTimeBy(10_000); runCurrent()   // the poll, then three more looks 1.5 s apart
        val fin = assertIs<RunState.Finished>(h.settle(engine))
        assertEquals(HomeOutcome.WRONG_FOCUS, fin.home)
        assertEquals(null, h.tv.settings[SettingKey.ENABLED_ACCESSIBILITY])
        assertEquals("0", h.tv.settings[SettingKey.ACCESSIBILITY_ENABLED])
    }

    // The stock home shows for a moment before the redirect (PLAN 3.3): one look at it is not enough.
    @Test fun aFlashOfTheTvsOwnHomeIsNotARollback() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this); h.redirects()
        val engine = h.engine()
        val plan = h.helperPlan()
        checkScreen(h, engine, plan)
        h.tv.focus = h.stock
        var seen = false
        h.tv.answer = { line -> if (line.startsWith("dumpsys activity activities")) { if (seen) h.tv.focus = h.launcher; seen = true }; null }
        advanceTimeBy(10_000); runCurrent()
        assertIs<RunState.AwaitingConfirm>(engine.runState(h.tv.tvId).value)
        assertEquals(service.flat, h.tv.settings[SettingKey.ENABLED_ACCESSIBILITY])
    }

    // S4: a list someone changed after the plan was made stops the run before step 0.
    @Test fun aListChangedSinceThePlanIsStale() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this); h.redirects()
        val engine = h.engine()
        val plan = h.helperPlan()
        h.tv.settings[SettingKey.ENABLED_ACCESSIBILITY] = reader
        engine.start(plan, h.tv, plan.hash, h.pass())
        val paused = assertIs<RunState.Paused>(h.settle(engine))
        assertEquals(PauseReason.STALE, paused.reason, paused.toString())
        assertEquals(reader, h.tv.settings[SettingKey.ENABLED_ACCESSIBILITY])
    }

    // The list changed between the probe and the write (after the run's own check): the add is
    // refused on the TV, nothing is written, and step R finds nothing of ours to take out.
    @Test fun aListChangedAtTheLastMomentIsNeverOverwritten() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this); h.redirects()
        val engine = h.engine()
        val plan = h.helperPlan()
        h.tv.dropOnLine = null
        var once = true
        h.tv.answer = { line -> if (once && line.startsWith("rm -f /data/local/tmp/cleanyourtv/home-ok-")) { once = false; h.tv.settings[SettingKey.ENABLED_ACCESSIBILITY] = reader }; null }
        engine.start(plan, h.tv, plan.hash, h.pass())
        val fin = assertIs<RunState.Finished>(h.settle(engine))
        assertEquals(HomeOutcome.VERIFY_FAILED, fin.home)
        assertEquals(reader, h.tv.settings[SettingKey.ENABLED_ACCESSIBILITY], "the reader's list is as it was")
        assertTrue(h.tv.watchdogs.isEmpty())
    }

    @Test fun aLostConnectionInTheCheckIsRolledBackWhenTheTvIsBack() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this); h.redirects()
        val engine = h.engine()
        val plan = h.helperPlan()
        checkScreen(h, engine, plan)
        h.tv.dropOnLine = { it.contains("'dumpsys' 'activity'") }
        advanceTimeBy(2_500); runCurrent(); h.settle(engine)
        assertEquals(PauseReason.CONNECTION_LOST, (engine.runState(h.tv.tvId).value as RunState.Paused).reason)
        assertEquals(service.flat, h.tv.settings[SettingKey.ENABLED_ACCESSIBILITY], "the TV's own timer would take it out if the phone never came back")
        h.tv.dropOnLine = null; h.tv.reconnect()
        engine.abandon(h.tv.tvId)
        val fin = assertIs<RunState.Finished>(h.settle(engine))
        assertEquals(RunStatus.ABANDONED, fin.status)
        assertEquals(null, h.tv.settings[SettingKey.ENABLED_ACCESSIBILITY])
        assertEquals("0", h.tv.settings[SettingKey.ACCESSIBILITY_ENABLED])
    }

    // Step R from the journal alone (no plan file): the add's Begin gives the list it wrote.
    @Test fun stepRFromTheJournalAloneTakesOurEntryOut() {
        val tvId = Samples.snapshot().tvId
        val id = PlanId("01HELPERPLAN00000000000001")
        val token = RunToken("00000000000000ab")
        val sha = tv.cleanyour.core.model.Sha256("0".repeat(64))
        val records = listOf(
            JournalRecord.PlanStart(id, tvId, sha, 12, sha, "0.4.8", 0),
            JournalRecord.Section(id, DefaultPlanner.SECTION_HELPER, token, SectionState.ARMED, 0),
            JournalRecord.Begin(id, 4, Op.ArmHelperWatchdog(service, 150, token, true), emptyList(), null, 0),
            JournalRecord.Begin(id, 5, Op.AccessibilityAdd(reader, service), emptyList(), Prior(settingValue = reader), 0),
            JournalRecord.Begin(id, 6, Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "1"), emptyList(), Prior(settingValue = "0"), 0),
        )
        val section = JournalStepR.build(records, tvId)!!.sections.single()
        assertEquals(DefaultPlanner.SECTION_HELPER, section.name)
        assertEquals(listOf(Op.AccessibilityRemove("$reader:${service.flat}", service), Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "0"),
            Op.ConfirmHome(token), Op.KillWatchdog(token), Op.PressHome), section.rollback)
        assertTrue(section.rollback.all { StepR.allows(it, section) })
        // The home switch's step R keeps its own vocabulary, and the helper's never moves HOME.
        assertTrue(!StepR.allows(Op.SetHome(Samples.stockLauncher), section) && !StepR.allows(Op.Enable(Samples.stockLauncher.pkg), section))
        assertTrue(!StepR.allows(Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "1"), section))
    }
}
