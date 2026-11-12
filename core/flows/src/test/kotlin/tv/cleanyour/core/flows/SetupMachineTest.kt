package tv.cleanyour.core.flows

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.discovery.DiscoveryEvent
import tv.cleanyour.core.discovery.FoundTv
import tv.cleanyour.core.discovery.ServiceRef
import tv.cleanyour.core.engine.JournalRecord
import tv.cleanyour.core.engine.StartResult
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.model.WizardDraft
import tv.cleanyour.core.engine.HomeOutcome
import tv.cleanyour.core.engine.NarrationLine
import tv.cleanyour.core.engine.NarrationState
import tv.cleanyour.core.engine.PauseReason
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.DebugState
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.ops.ShellRenderer
import tv.cleanyour.core.planner.DefaultPlanner
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.engine.SectionState
import tv.cleanyour.core.model.ConnectionState
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.testing.fixtures.FakeTvSession
import tv.cleanyour.core.store.GateMethod
import tv.cleanyour.testing.fixtures.FakeCredentialGate
import tv.cleanyour.testing.fixtures.FakeEngine
import tv.cleanyour.testing.fixtures.FakeIdGen
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SetupMachineTest {

    private fun found(tv: FoundTv = miTvFound) = DiscoveryEvent.Found(tv)

    // Welcome to Found: discovery, Start, the port opening, the Allow dialog, the probe and the plan.
    private suspend fun kotlinx.coroutines.test.TestScope.toFound(h: Harness, m: SetupMachine) {
        h.discovery.emit(found())
        settle()
        m.dispatch(SetupIntent.Start)
        settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        settle(700)
    }

    @Test fun welcomeListsTvsAndStopsLookingAfterEightSeconds() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = h.setup(backgroundScope)
        settle()
        assertEquals(1, h.discovery.activeCollectors)
        h.discovery.emit(found())
        settle()
        val w = assertIs<SetupState.Welcome>(m.state.value)
        assertEquals(listOf(HOST), w.found.map { it.host })
        assertTrue(w.looking)
        settle(8_000)
        assertFalse(assertIs<SetupState.Welcome>(m.state.value).looking)
    }

    @Test fun twoTvsOpenThePickerAndPickingOneOpensTheDoor() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = h.setup(backgroundScope)
        h.discovery.emit(found())
        h.discovery.emit(found(miTvFound.copy(host = "192.0.2.11", friendlyName = "Bedroom")))
        settle()
        m.dispatch(SetupIntent.Start)
        settle()
        assertIs<SetupState.Picker>(m.state.value)
        m.dispatch(SetupIntent.PickTv("192.0.2.11"))
        settle()
        val d = assertIs<SetupState.OpenDoor>(m.state.value)
        assertEquals("192.0.2.11", d.tv.host)
        assertEquals(DoorPhase.STEPS, d.phase)
        assertEquals(Families.ATV, d.family)
        // F1: discovery stops once Welcome and the picker are left.
        assertEquals(0, h.discovery.activeCollectors)
    }

    @Test fun portOpeningConnectsAndTheAllowDialogShowsTheFingerprint() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        m.effects.test {
            h.discovery.emit(found())
            settle()
            m.dispatch(SetupIntent.Start)
            settle()
            h.discovery.open5555[HOST] = true
            settle(2_100)
            assertEquals(SetupEffect.HapticDouble, drain().first())
            val d = assertIs<SetupState.OpenDoor>(m.state.value)
            assertEquals(DoorPhase.CONNECTED, d.phase)
            assertEquals("AB:CD:7F:2A", d.fingerprint)
            settle(700)
            assertIs<SetupState.Found>(m.state.value)
        }
        assertEquals("7F:2A", fingerprintTail("ab:cd:7f:2a"))
    }

    @Test fun noAllowDialogOnANewTvAsksIsThisYourTv() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = h.setup(backgroundScope)
        h.discovery.emit(found()); settle()
        m.dispatch(SetupIntent.Start); settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        assertEquals(DoorPhase.IDENTITY, assertIs<SetupState.OpenDoor>(m.state.value).phase)
        assertEquals(Samples.miTvFacts().serialLast4, assertNotNull(m.extras.value.identity).serialLast4)
        m.dispatch(SetupIntent.ConfirmIdentity)
        settle(700)
        assertIs<SetupState.Found>(m.state.value)
    }

    @Test fun probeSavesTheBaselineAndRecordsTheTvWithItsDraft() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        val f = assertIs<SetupState.Found>(m.state.value)
        assertEquals(h.setupPlan.id, f.plan.id)
        assertEquals(Level.SIMPLE, f.level)
        assertTrue("snapshot BASELINE" in h.prober.calls)
        assertNotNull(h.snapshots.baseline(f.tvId))
        val tv = h.tvs.tvs.value.single()
        assertEquals("Mi TV 4A", tv.nickname)
        assertEquals(3, tv.wizard?.step)
        assertEquals(DebugState.ON, tv.debugging)
        assertEquals("xiaomi-mitv-axso0", tv.profileId)
    }

    @Test fun keepingAPackageReplansAndPersistsTheDraft() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.Keep(promoPkg, keep = true))
        settle()
        val last = h.planner.requests.filterIsInstance<PlanRequest.Setup>().last()
        assertEquals(setOf(promoPkg), last.choices.kept)
        assertEquals(setOf(promoPkg), h.tvs.tvs.value.single().wizard?.kept)
        m.dispatch(SetupIntent.Keep(promoPkg, keep = false))
        settle()
        assertEquals(emptySet(), h.planner.requests.filterIsInstance<PlanRequest.Setup>().last().choices.kept)
    }

    @Test fun customiseEntersTheCustomLevel() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.Open(Sheet.CUSTOMISE))
        settle()
        val f = assertIs<SetupState.Found>(m.state.value)
        assertEquals(Sheet.CUSTOMISE, f.sheet)
        assertEquals(Level.CUSTOM, f.level)
        assertEquals(Level.CUSTOM, h.tvs.tvs.value.single().level)
    }

    @Test fun makeItCleanPromptsOnceAndStartsWithAPassForThisTv() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.engine.onStart = { emit(it.tvId, RunState.Running(it, 0, listOf(NarrationLine("Restore point saved", NarrationState.DOING)), 10, null)) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.effects.test {
            m.dispatch(SetupIntent.MakeItClean)
            settle()
            assertEquals(listOf(SetupEffect.RequestCredential, SetupEffect.RequestNotificationPermission), drain().takeLast(2))
        }
        assertEquals(1, h.credential.prompts.size)
        assertEquals("GATE_SUBTITLE{tv=Mi TV 4A}", h.credential.prompts.single().subtitle)
        val start = h.engine.calls.filterIsInstance<FakeEngine.Call.Start>().single()
        assertEquals(StartResult.Started, start.result)
        assertEquals(h.setupPlan.hash, start.confirmed)
        val w = assertIs<SetupState.Working>(m.state.value)
        assertEquals(10, w.run.percent)
        assertEquals(4, h.tvs.tvs.value.single().wizard?.step)
        assertEquals(listOf(h.setupPlan.id), h.ledger.runs(w.tvId).map { it.planId })
    }

    @Test fun cancelledPromptStartsNothing() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.credential.mode = FakeCredentialGate.Mode.FAIL
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean)
        settle()
        assertTrue(h.engine.calls.isEmpty())
        assertIs<SetupState.Found>(m.state.value)
        assertFalse(m.extras.value.starting)
    }

    @Test fun noScreenLockRunsWithANonePassAndShowsTheGreyLine() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.credential.mode = FakeCredentialGate.Mode.NO_SCREEN_LOCK
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean)
        settle()
        val start = h.engine.calls.filterIsInstance<FakeEngine.Call.Start>().first()
        assertEquals(GateMethod.NONE, start.pass?.method)
        assertTrue(m.extras.value.noScreenLock)
    }



    // QA of 0.4.5 (bug-040-retry): a Setup whose home check ran out of time. The run is journaled as
    // DefaultEngine journals it: the clean-up switch-off done, the section armed, the TV's own home
    // screen switched off and Home set, then the timer ran out and step R (what the TV's watchdog
    // does too) switched the old home screen back on and gave it Home again. The TV then reads with
    // its own home screen in charge.
    private fun timedOutTakeover(h: Harness, id: String, debloat: Boolean = true): tv.cleanyour.core.planner.Plan {
        val token = RunToken("00000000000000aa")
        val head = if (debloat) listOf<Op>(Op.Disable(adPkg)) else emptyList()
        val ops = head + listOf(Op.ArmHomeWatchdog(Samples.stockLauncher, 150, token), Op.Disable(Samples.stockLauncher.pkg),
            Op.SetHome(Launcher.home), Op.VerifyHome(Launcher.home))
        val section = tv.cleanyour.core.planner.GuardedSection("home_takeover", head.size, head.size + 3, token, 90_000, Launcher.home, setOf(Samples.stockLauncher.pkg),
            listOf(Op.Enable(Samples.stockLauncher.pkg), Op.SetHome(Samples.stockLauncher)))
        return Samples.plan(ops, id = tv.cleanyour.core.model.PlanId(id), sections = listOf(section))
    }

    // The second run as DefaultEngine journals it when the person says yes on the check screen.
    private suspend fun FakeEngine.runAndConfirm(h: Harness, p: tv.cleanyour.core.planner.Plan) {
        val j = h.journal
        val from = p.sections.single().from
        j.append(JournalRecord.PlanStart(p.id, p.tvId, p.baseSnapshot, p.steps.size, p.hash, "test", Samples.NOW_MS))
        for (step in p.steps) {
            if (step.index == from) j.append(JournalRecord.Section(p.id, "home_takeover", RunToken("00000000000000aa"), SectionState.ARMED, Samples.NOW_MS + 150_000))
            j.append(JournalRecord.Begin(p.id, step.index, step.op, emptyList(), null, Samples.NOW_MS))
            j.append(JournalRecord.Done(p.id, step.index, 0, true, 1))
        }
        j.append(JournalRecord.Section(p.id, "home_takeover", RunToken("00000000000000aa"), SectionState.CONFIRMED, Samples.NOW_MS))
        j.append(JournalRecord.End(p.id, RunStatus.COMPLETED))
        finish(p, RunStatus.COMPLETED, HomeOutcome.CONFIRMED)
    }

    private suspend fun FakeEngine.runAndTimeOut(h: Harness, p: tv.cleanyour.core.planner.Plan) {
        val j = h.journal
        val from = p.sections.single().from
        j.append(JournalRecord.PlanStart(p.id, p.tvId, p.baseSnapshot, p.steps.size, p.hash, "test", Samples.NOW_MS))
        for (step in p.steps) {
            if (step.index == from) j.append(JournalRecord.Section(p.id, "home_takeover", RunToken("00000000000000aa"), SectionState.ARMED, Samples.NOW_MS + 150_000))
            j.append(JournalRecord.Begin(p.id, step.index, step.op, emptyList(), null, Samples.NOW_MS))
            j.append(JournalRecord.Done(p.id, step.index, 0, true, 1))
        }
        j.append(JournalRecord.Rollback(p.id, from))
        j.append(JournalRecord.Begin(p.id, from + 1, Op.Enable(Samples.stockLauncher.pkg), emptyList(), null, Samples.NOW_MS))
        j.append(JournalRecord.Done(p.id, from + 1, 0, true, 1))
        j.append(JournalRecord.Begin(p.id, from + 2, Op.SetHome(Samples.stockLauncher), emptyList(), null, Samples.NOW_MS))
        j.append(JournalRecord.Done(p.id, from + 2, 0, true, 1))
        j.append(JournalRecord.Section(p.id, "home_takeover", RunToken("00000000000000aa"), SectionState.ROLLED_BACK, Samples.NOW_MS))
        j.append(JournalRecord.End(p.id, RunStatus.COMPLETED_WITH_SKIPS))
        h.prober.snapshotFor = { r -> Samples.miTvSnapshot(reason = r) }   // the TV's own home screen is Home again
        finish(p, RunStatus.COMPLETED_WITH_SKIPS, HomeOutcome.TIMED_OUT)
    }

    // "Try again" (the card's second button) reads the TV again, plans the switch afresh and runs it;
    // it never goes to Done with the old home screen on the TV. Before, it was "OK"'s action.

    // beta3 QA: Done named the newest read as the restore point ("saved at 22:47") while Undo everything
    // goes back to the first one (12:31). Done now names the first one, and says when it is from an
    // earlier run.
    @Test fun doneNamesTheRestorePointUndoGoesBackTo() = runTest(timeout = 30.seconds) {
        for (earlier in listOf(false, true)) {
            val h = Harness()
            h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
            val first = Samples.NOW_MS - 36_000_000
            if (earlier) h.snapshots.save(Samples.snapshot(reason = SnapshotReason.BASELINE, takenAtMs = first))
            h.engine.onStart = { p -> finish(p, RunStatus.COMPLETED) }
            val m = h.setup(backgroundScope)
            toFound(h, m)
            m.dispatch(SetupIntent.MakeItClean); settle()
            val done = assertIs<SetupState.Done>(m.state.value, "$earlier: ${m.state.value}").summary
            assertEquals(earlier, done.restorePointEarlier)
            assertEquals(if (earlier) first else Samples.NOW_MS, done.restorePointAtMs)
        }
    }

    // "OK" keeps the old home screen: Done counts only what stayed switched off (not the TV's own
    // home screen, which step R switched back on), says the home screen is unchanged and why.
    @Test fun timedOutOkEndsOnAnHonestDone() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = timedOutTakeover(h, "01FAKEPLAN0000000000000001")
        h.engine.onStart = { p -> if (p.kind == PlanKind.SETUP) runAndTimeOut(h, p) else finish(p, RunStatus.COMPLETED) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        val card = assertIs<SetupState.Stopped>(m.state.value).card
        assertEquals(StopCase.HOME_TIMED_OUT, card.case)
        // One thing stayed switched off, so the card never says the TV is as it was.
        assertEquals("STOP_HOME_TIMED_OUT_BODY_OFF{count=1,launcher=}", card.body)
        m.dispatch(SetupIntent.StopAction(StopAction.PRIMARY)); settle()
        assertEquals(1, h.engine.calls.count { it is FakeEngine.Call.Start && it.plan.kind == PlanKind.SETUP }, "OK never starts the switch again")
        val done = assertIs<SetupState.Done>(m.state.value).summary
        assertFalse(done.homeScreenOn)
        assertEquals(1, done.switchedOff, "the TV's own home screen is on again, so it is not counted")
        assertEquals(HomeOutcome.TIMED_OUT, m.extras.value.doneHomeKept)
        assertEquals(1, h.ledger.progress(h.setupPlan.id).doneDisables)
    }

    // With nothing left switched off (the plan had only the switch, put back), the card says the TV
    // is as it was, which is then true.
    @Test fun timedOutWithNothingSwitchedOffSaysTheTvIsAsItWas() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = timedOutTakeover(h, "01FAKEPLAN0000000000000001", debloat = false)
        h.engine.onStart = { p -> if (p.kind == PlanKind.SETUP) runAndTimeOut(h, p) else finish(p, RunStatus.COMPLETED) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertEquals("STOP_HOME_TIMED_OUT_BODY{launcher=}", assertIs<SetupState.Stopped>(m.state.value).card.body)
    }

    @Test fun connectionLostShowsCountsThenCarryOnContinuesWithThePass() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.engine.onStart = { p -> emit(p.tvId, RunState.Paused(p, 1, PauseReason.CONNECTION_LOST, AdbError.Transport.Closed)) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        val s = assertIs<SetupState.Stopped>(m.state.value)
        assertEquals(StopCase.CONNECTION_LOST, s.card.case)
        assertEquals("STOP_CONNECTION_LOST_BODY{done=0,total=2}", s.card.body)
        assertTrue(s.card.tertiary.orEmpty().startsWith("STOP_CONNECTION_LOST_TERTIARY"))
        assertEquals(ChipState.OFFLINE, m.extras.value.chip)
        settle()
        assertEquals(Reconnect.BACK, m.extras.value.reconnect)   // the fake session is still connected
        m.dispatch(SetupIntent.CarryOn); settle()
        val cont = h.engine.calls.filterIsInstance<FakeEngine.Call.ContinueRun>().single()
        assertNotNull(cont.pass)
        assertNull(cont.refused)
        assertEquals(1, h.credential.prompts.size)   // one prompt per TV session (D17)
    }

    @Test fun stopAndUndoStopsThenUndoesOnThePause() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.engine.onStart = { p -> emit(p.tvId, RunState.Running(p, 0, emptyList(), 5, null)) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        assertIs<SetupState.Found>(m.state.value, "found before Make it clean")
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Working>(m.state.value, "after Make it clean: ${h.engine.calls} ${m.extras.value.starting} ${h.credential.prompts}")
        assertTrue(m.back(), "back is consumed on 4a")
        assertTrue(m.extras.value.highlightStop, "back only highlights Stop and undo")
        m.dispatch(SetupIntent.StopAndUndo); settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Stop }, "engine.stop called")
        h.engine.emit(h.setupPlan.tvId, RunState.Paused(h.setupPlan, 1, PauseReason.USER_STOP, null))
        settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.UndoRun }, "undoRun after the user stop: ${h.engine.calls}")
        h.engine.finish(h.setupPlan, RunStatus.ROLLED_BACK)
        settle()
        assertEquals(StopCase.UNDONE, assertIs<SetupState.Stopped>(m.state.value).card.case)
    }

    @Test fun finishSwitchesDebuggingOffAndOpensManage() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
        h.discovery.open5555[HOST] = false
        m.effects.test {
            m.dispatch(SetupIntent.Finish)
            settle(700)
            assertEquals(SetupEffect.Navigate("manage/${Samples.miTvId().value}"), drain().last())
        }
        assertTrue(h.planner.requests.any { it == PlanRequest.DebugOff })
        val debugOff = h.engine.calls.filterIsInstance<FakeEngine.Call.Start>().last()
        assertNull(debugOff.pass)   // SESSION_END needs no pass (R0: 6)
        assertEquals(DebugState.OFF, h.tvs.tvs.value.single().debugging)
        assertEquals(Finishing.DONE, m.extras.value.finishing)
    }

    // STORES-PLAN 1: "Add a card" on Done opens Cards and modules in the full build; the Play build
    // has no add-ons, so Done does not offer it and the intent goes nowhere.

    // R5: a STALE pause leaves the run open; the machine ends it so the fresh plan can start.
    @Test fun aStaleRunIsEndedSoTheFreshPlanCanStart() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val first = h.setupPlan
        val second = h.plan(listOf(Op.Disable(adPkg)), id = "01FAKEPLAN0000000000000002")
        h.engine.onStart = { p ->
            if (p.id == first.id) { h.setupPlan = second; emit(p.tvId, RunState.Paused(p, 0, PauseReason.STALE, null)) }
            else finish(p, RunStatus.COMPLETED)
        }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Abandon }, "the stale run is ended")
        assertEquals(second.id, assertIs<SetupState.Found>(m.state.value).plan.id)
        assertTrue(m.extras.value.staleNotice)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertEquals(StartResult.Started, h.engine.calls.filterIsInstance<FakeEngine.Call.Start>().last().result)
        assertIs<SetupState.Done>(m.state.value)
    }

    // R5: a start refused RUN_OPEN shows the open run instead of leaving the screen on Found.
    @Test fun anOpenRunIsShownWhenTheStartIsRefused() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        val open = h.plan(listOf(Op.Disable(promoPkg)), id = "01FAKEPLAN0000000000000009")
        h.engine.emit(open.tvId, RunState.Paused(open, 1, PauseReason.USER_STOP, null))
        m.dispatch(SetupIntent.MakeItClean); settle()
        val s = assertIs<SetupState.Stopped>(m.state.value)
        assertEquals(StopCase.RESUME_AFTER_DEATH, s.card.case)
        assertEquals(open.id, s.card.planId)
        assertFalse(m.extras.value.starting)
    }

    // R5, D7: the 10 minute background rule only switches debugging off where a write ran.
    @Test fun tenMinutesInTheBackgroundSwitchesDebuggingOffOnlyAfterAWrite() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        h.sessionEnd.watch(backgroundScope)
        toFound(h, m)
        h.foreground.set(false); settle(10 * 60_000L + 100)
        assertFalse(h.planner.requests.any { it == PlanRequest.DebugOff }, "a probe-only session never sends debug_off")
        h.foreground.set(true); settle()
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
        h.discovery.open5555[HOST] = false
        h.foreground.set(false); settle(10 * 60_000L + 100)
        assertEquals(1, h.planner.requests.count { it == PlanRequest.DebugOff })
        assertNull(h.engine.calls.filterIsInstance<FakeEngine.Call.Start>().last().pass)
        assertEquals(DebugState.OFF, h.tvs.tvs.value.single().debugging)
    }

    // R5, D7: Finish only says "done" when the probe confirmed debugging is off.
    @Test fun finishDoesNotClaimDebuggingOffWhenThePortStaysOpen() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
        val before = h.discovery.probes.size
        m.effects.test {
            m.dispatch(SetupIntent.Finish)
            settle(3_100)
            assertEquals(SetupEffect.Navigate("manage/${Samples.miTvId().value}"), drain().last())
        }
        assertEquals(3, h.discovery.probes.size - before, "three probes a second apart before recording on")
        assertEquals(DebugState.ON, h.tvs.tvs.value.single().debugging)
        assertTrue(m.extras.value.finishing != Finishing.DONE)
        assertTrue(m.extras.value.chip != ChipState.DEBUGGING_OFF)
    }

    // RF-PROFILES-63 P2: an exact profile that knows no stock launcher (a China PatchWall home, a
    // projector's own) gets the clean-up instead of a plan the planner refuses as a whole.
    // RF-PROFILES-63 P2: an exact profile that knows no stock launcher (a China PatchWall home, a
    // projector's own) still reaches Found, and the plan is the clean-up alone: no install, no
    // home screen switch.
    @Test fun aProfileWithNoKnownStockHomeGetsTheCleanUp() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.profiles.resolver = { _, _ -> tv.cleanyour.core.spec.ProfileResolution.Exact(miProfile.copy(stockLaunchers = emptyList())) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        val found = assertIs<SetupState.Found>(m.state.value)
        assertTrue(found.plan.sections.isEmpty())
        assertTrue(found.plan.steps.none { it.op is Op.Install || it.op is Op.InstallStream || it.op is Op.SetHome || it.op is Op.ArmHomeWatchdog })
    }

    @Test fun keepDebuggingOnSkipsDebugOffAtFinish() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.KeepDebuggingOn(true)); settle()
        assertTrue(h.planner.requests.filterIsInstance<PlanRequest.Setup>().last().choices.keepDebuggingOn)
        m.dispatch(SetupIntent.MakeItClean); settle()
        // Baseline QA (0.4.0): Done still said debugging switches off at Finish; it now knows it stays on.
        assertIs<SetupState.Done>(m.state.value)
        assertTrue(m.extras.value.keepsDebugging)
        m.dispatch(SetupIntent.Finish); settle(700)
        assertFalse(h.planner.requests.any { it == PlanRequest.DebugOff })
    }

    @Test fun withoutKeepDebuggingOnDoneSaysFinishSwitchesItOff() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
        assertFalse(m.extras.value.keepsDebugging)
        m.dispatch(SetupIntent.Finish); settle(700)
        assertTrue(h.planner.requests.any { it == PlanRequest.DebugOff })
    }

    @Test fun pairingOnlyTvGoesToThePairingDeadEnd() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = h.setup(backgroundScope)
        h.discovery.emit(found(miTvFound.copy(port5555Open = false, connectService = ServiceRef("adb-tls", HOST, 37000))))
        settle()
        m.dispatch(SetupIntent.Start); settle()
        assertIs<SetupState.PairingNeeded>(m.state.value)
        assertTrue(m.back())
        assertIs<SetupState.Welcome>(m.state.value)
    }

    @Test fun typedAddressMustBeAHomeNetworkAddress() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = h.setup(backgroundScope)
        m.dispatch(SetupIntent.Open(Sheet.ADDRESS)); settle()
        assertEquals(Sheet.ADDRESS, m.extras.value.sheet)
        m.dispatch(SetupIntent.EnterAddress("8.8.8.8")); settle()
        assertTrue(m.extras.value.addressError)
        assertIs<SetupState.Welcome>(m.state.value)
        m.dispatch(SetupIntent.EnterAddress("192.168.1.50")); settle()
        assertEquals("192.168.1.50", assertIs<SetupState.OpenDoor>(m.state.value).tv.host)
        assertNull(m.extras.value.sheet)
        assertTrue(isHomeAddress("10.0.0.2") && isHomeAddress("172.16.4.1") && isHomeAddress("169.254.1.1"))
        assertFalse(isHomeAddress("172.32.0.1") || isHomeAddress("192.168.1") || isHomeAddress("192.168.1.256"))
    }

    @Test fun startWithNoTvWaitsOnTheDoorAndAdoptsTheFirstTvFound() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = h.setup(backgroundScope)
        m.dispatch(SetupIntent.Start); settle()
        assertEquals("", assertIs<SetupState.OpenDoor>(m.state.value).tv.host)
        assertEquals(1, h.discovery.activeCollectors)
        settle(20_000)
        assertEquals(StopCase.TV_NOT_FOUND, m.extras.value.card?.case)
        h.discovery.emit(found()); settle()
        assertEquals(HOST, assertIs<SetupState.OpenDoor>(m.state.value).tv.host)
        assertNull(m.extras.value.card)
    }

    @Test fun waitingForTheAllowTapCountsTimeAndAskAgainRetries() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = h.setup(backgroundScope)
        m.dispatch(SetupIntent.EnterAddress("192.168.1.50")); settle()
        settle(3_000)
        assertTrue(assertIs<SetupState.OpenDoor>(m.state.value).waitedMs >= 3_000)
        m.dispatch(SetupIntent.ChangeFamily("Amazon")); settle()
        val d = assertIs<SetupState.OpenDoor>(m.state.value)
        assertEquals(Families.FIRE, d.family)
        assertEquals("BANNER_FIRE", d.banner)
    }

    // A TCL UnionTV in India got the China-market 本机信息 steps from the TCL chip: the chip now asks
    // about the menus first, and only Chinese menus get those steps and the home screen banner.
    @Test fun theTclChipAsksAboutTheMenusBeforeAnySteps() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = h.setup(backgroundScope)
        m.dispatch(SetupIntent.EnterAddress("192.168.1.50")); settle()
        m.dispatch(SetupIntent.ChangeFamily("TCL")); settle()
        val asking = assertIs<SetupState.OpenDoor>(m.state.value)
        assertEquals(Families.TCL_ASK, asking.family)
        assertNull(asking.banner)
        assertEquals(DoorPhase.STEPS, asking.phase)

        m.dispatch(SetupIntent.ChangeFamily(Families.ofTclMenus(chinese = false))); settle()
        val global = assertIs<SetupState.OpenDoor>(m.state.value)
        assertEquals(Families.TCL_GLOBAL, global.family)
        assertNull(global.banner)

        // Changing the answer (back through the chip) works the other way too.
        m.dispatch(SetupIntent.ChangeFamily("TCL")); settle()
        assertEquals(Families.TCL_ASK, assertIs<SetupState.OpenDoor>(m.state.value).family)
        m.dispatch(SetupIntent.ChangeFamily(Families.ofTclMenus(chinese = true))); settle()
        val china = assertIs<SetupState.OpenDoor>(m.state.value)
        assertEquals(Families.TCL, china.family)
        assertEquals("BANNER_TCL", china.banner)
        assertEquals("192.168.1.50", china.tv.host)
    }

    @Test fun brandChipsMapToTheirFamilies() {
        assertEquals(Families.TCL_ASK, Families.ofBrand("TCL"))
        assertEquals(Families.TCL_ASK, Families.ofBrand("tcl"))
        assertEquals(Families.ATV, Families.ofBrand("Xiaomi"))
        assertEquals(Families.GTV, Families.ofBrand("Google"))
        assertEquals(Families.FIRE, Families.ofBrand("Amazon"))
        assertEquals(Families.UNKNOWN, Families.ofBrand("Other"))
        assertEquals(Families.TCL_GLOBAL, Families.ofTclMenus(chinese = false))
        assertEquals(Families.TCL, Families.ofTclMenus(chinese = true))
        // Every family the screen can be sent is one the machine takes as it is.
        assertTrue(Families.TCL_ASK in Families.all && Families.TCL_GLOBAL in Families.all && Families.TCL in Families.all)
        // A TCL found on the network still gets the generic steps, never the China-market ones.
        assertEquals(Families.UNKNOWN, Families.of(FoundTv("192.168.1.71", "TCL C635", "C635", emptySet(), true, null, null, 0L)))
    }

    @Test fun wrongTvAtARememberedAddressShowsTheIdentityCard() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val other = Samples.tv().copy(id = TvId("0123456789abcdef"))
        h.tvs.upsert(other)
        val m = h.setup(backgroundScope)
        h.discovery.emit(found()); settle()
        m.dispatch(SetupIntent.Start); settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        assertEquals(StopCase.IDENTITY_CHANGED, m.extras.value.card?.case)
        assertTrue(h.session.calls.isEmpty())   // D37: no line sent to the wrong TV
    }

    @Test fun resumeStartShowsTheCountsAndResumesTheEngine() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        h.ledger.record(h.setupPlan)
        h.discovery.open5555[HOST] = true
        val m = h.setup(backgroundScope, SetupStart.Resume(tv.id, h.setupPlan.id))
        settle()
        val s = assertIs<SetupState.Stopped>(m.state.value)
        assertEquals(StopCase.RESUME_AFTER_DEATH, s.card.case)
        assertEquals("STOP_RESUME_BODY{done=0,total=2,tv=Mi TV 4A}", s.card.body)
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Resume })
        h.engine.emit(tv.id, RunState.Paused(h.setupPlan, 1, PauseReason.IN_DOUBT, null))
        m.dispatch(SetupIntent.StopAction(StopAction.TERTIARY)); settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Abandon })
    }

    // R5, D7: after process death the journal shows writes ran, so the TV is ended like any other.
    @Test fun resumeAfterProcessDeathKeepsTheDebugOffPromise() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        h.ledger.record(h.setupPlan)
        h.journal.append(JournalRecord.PlanStart(h.setupPlan.id, tv.id, Samples.zeroHash, 2, h.setupPlan.hash, "0.1.0", 0))
        h.journal.append(JournalRecord.Begin(h.setupPlan.id, 0, Op.Disable(adPkg), listOf("fake"), null, 1))
        h.journal.append(JournalRecord.Done(h.setupPlan.id, 0, 0, true, 5))
        h.discovery.open5555[HOST] = true
        h.setup(backgroundScope, SetupStart.Resume(tv.id, h.setupPlan.id))
        settle()
        h.discovery.open5555[HOST] = false
        h.sessionEnd.endAll()
        assertTrue(h.planner.requests.any { it == PlanRequest.DebugOff })
    }

    @Test fun startDestinationFollowsTheDisk() = runTest(timeout = 30.seconds) {
        val h = Harness()
        assertEquals(StartDestination.Welcome, resolveStart(emptyList(), h.journal))
        val tv = Samples.tv()
        assertEquals(StartDestination.Manage(tv.id), resolveStart(listOf(tv), h.journal))
        val draft = tv.copy(wizard = WizardDraft(3, Level.SIMPLE, emptySet(), emptySet(), false, null, 0))
        assertEquals(StartDestination.ContinueSetup(tv.id), resolveStart(listOf(draft), h.journal))
        h.journal.append(JournalRecord.PlanStart(h.setupPlan.id, tv.id, Samples.zeroHash, 2, h.setupPlan.hash, "0.1.0", 0))
        assertEquals(StartDestination.ResumeRun(tv.id, h.setupPlan.id), resolveStart(listOf(draft), h.journal))
    }

    @Test fun continueStartReconnectsToTheDraftTv() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv().copy(wizard = WizardDraft(3, Level.CUSTOM, setOf(promoPkg), emptySet(), false, null, 0))
        h.tvs.upsert(tv)
        h.discovery.open5555[HOST] = true
        val m = h.setup(backgroundScope, SetupStart.Continue(tv.id))
        settle(); settle(700)
        val f = assertIs<SetupState.Found>(m.state.value)
        assertEquals(Level.CUSTOM, f.level)
        assertEquals(setOf(promoPkg), h.planner.requests.filterIsInstance<PlanRequest.Setup>().last().choices.kept)
    }

    // RFC-SHOP-7 (2026-11-12): a kept choice for a core Shop/ad package, recorded
    // while the probe misread it as user-installed (videos, v0.4.7-48/-49 on Sunil's
    // SWTV), is dropped from the stored draft when the plan would uninstall it by
    // name — otherwise the stale choice vetoes section 2 forever. The plan uninstalls
    // videos by name, and the draft stored on disk no longer keeps it.
    @Test fun staleKeptChoiceForACorePackageIsDroppedFromTheDraft() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val videos = PackageName("com.google.android.videos")
        val tv = Samples.tv().copy(wizard = WizardDraft(3, Level.CUSTOM, setOf(videos), emptySet(), false, null, 0))
        h.tvs.upsert(tv)
        val planner = DefaultPlanner(h.clock, FakeIdGen(), ShellRenderer(), "0.4.7")
        h.planner.respond = { request, snap -> planner.plan(request, snap, h.facts, emptyList()) }
        h.discovery.open5555[HOST] = true
        val m = h.setup(backgroundScope, SetupStart.Continue(tv.id))
        settle(); settle(700)
        val f = assertIs<SetupState.Found>(m.state.value, h.logLines.joinToString("\n"))
        // The plan uninstalls videos by name despite the stored kept choice.
        assertTrue(f.plan.steps.map { it.op }.contains(Op.Uninstall(videos)), "the core package is uninstalled by name")
        assertTrue(f.plan.steps.map { it.op }.contains(Op.ClearPackage(videos)), "the core package is cleared before uninstall")
        // ...and the stored draft no longer keeps it.
        val stored = h.tvs.tvs.value.single { it.id == tv.id }
        assertFalse(stored.wizard?.kept?.contains(videos) == true, "the stale kept choice is dropped from the draft")
        assertTrue(h.logLines.any { it.contains("dropped") && it.contains(videos.value) }, h.logLines.joinToString("\n"))
    }

    // RFC-W8-1: "No" on the identity line is an intent; it closes the session and records nothing.
    @Test fun noOnTheIdentityLineClosesTheSessionAndRecordsNothing() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = h.setup(backgroundScope)
        h.discovery.emit(found()); settle()
        m.dispatch(SetupIntent.Start); settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        assertEquals(DoorPhase.IDENTITY, assertIs<SetupState.OpenDoor>(m.state.value).phase)
        m.dispatch(SetupIntent.RejectIdentity); settle()
        assertIs<SetupState.Welcome>(m.state.value)
        assertTrue(FakeTvSession.Call.Close in h.session.calls)
        assertTrue(h.tvs.tvs.value.isEmpty())
        // Finding 1: "No" offers the address entry, and the same device is not offered again.
        assertEquals(Sheet.ADDRESS, m.extras.value.sheet)
        assertNull(m.extras.value.identity)
        h.discovery.emit(found()); settle()
        assertTrue(assertIs<SetupState.Welcome>(m.state.value).found.isEmpty())
        m.back(); settle()                                  // the sheet closes; Start waits for a TV
        m.dispatch(SetupIntent.Start); settle()
        assertEquals("", assertIs<SetupState.OpenDoor>(m.state.value).tv.host)
        h.discovery.emit(found()); settle()
        assertEquals("", assertIs<SetupState.OpenDoor>(m.state.value).tv.host)
    }

    // RFC-W8-5: the restart test is a REBOOT plan through the runner (journaled, no prompt), never a
    // direct session call; things that came back on are switched off after it.
    @Test fun theRestartTestRunsARebootPlanWithoutAPrompt() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
        val prompts = h.credential.prompts.size
        m.dispatch(SetupIntent.Restart); settle()
        assertTrue(h.planner.requests.any { it == PlanRequest.Reboot })
        val reboot = h.engine.calls.filterIsInstance<FakeEngine.Call.Start>().last()
        assertEquals(PlanKind.REBOOT, reboot.plan.kind)
        assertNull(reboot.pass)
        assertEquals(StartResult.Started, reboot.result)
        assertEquals(prompts, h.credential.prompts.size)
        assertTrue(h.session.calls.none { it == FakeTvSession.Call.Reboot })
        assertIs<RestartState.Back>(assertIs<SetupState.Done>(m.state.value).restartState)
    }

    // RFC-W8-5: a boot wait that runs out leaves the reboot step in doubt and the run open; the
    // machine ends it (nothing to undo) so later writes are not refused, and shows the 5 minute card.
    @Test fun aRestartThatDoesNotComeBackEndsItsRunAndShowsTheCard() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
        h.engine.onStart = { p ->
            if (p.kind == PlanKind.REBOOT) emit(p.tvId, RunState.Paused(p, 0, PauseReason.CONNECTION_LOST, AdbError.Transport.Timeout("boot")))
            else finish(p, RunStatus.COMPLETED)
        }
        m.dispatch(SetupIntent.Restart); settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Abandon })
        assertIs<RestartState.NotBack>(assertIs<SetupState.Done>(m.state.value).restartState)
        assertEquals(StopCase.NOT_BACK_AFTER_RESTART, m.extras.value.card?.case)
    }

    // R1 R-11: with the HOME section open and the TV away, the engine's abandon does nothing; the
    // card says to reconnect, and the abandon runs once the TV answers again.
    @Test fun stopHereWithTheHomeSectionOpenWaitsForTheTv() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.plan(listOf(Op.Disable(adPkg)), takeover = true)
        h.engine.onStart = { p ->
            h.journal.append(JournalRecord.PlanStart(p.id, p.tvId, Samples.zeroHash, p.steps.size, p.hash, "0.1.0", 0))
            h.journal.append(JournalRecord.Section(p.id, "home_takeover", RunToken("00000000000000aa"), SectionState.ARMED, 0))
            emit(p.tvId, RunState.Paused(p, 2, PauseReason.CONNECTION_LOST, AdbError.Transport.Closed))
        }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertEquals(StopCase.CONNECTION_LOST, assertIs<SetupState.Stopped>(m.state.value).card.case)
        val connected = h.session.state.value
        h.session.setState(ConnectionState.Idle)
        h.discovery.open5555[HOST] = false
        m.dispatch(SetupIntent.StopAction(StopAction.SECONDARY)); settle()
        assertTrue(h.engine.calls.none { it is FakeEngine.Call.Abandon })
        val s = assertIs<SetupState.Stopped>(m.state.value)
        assertTrue(s.card.body.startsWith("STOP_RECONNECT_HOME_BODY"), s.card.body)
        h.session.setState(connected)
        h.discovery.open5555[HOST] = true
        settle(2_100)
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Abandon })
    }

    // W3 D-W3-2: step R rebuilt from the journal ends the run ABANDONED with no steps of its own
    // and home CONNECTION_LOST; the card says the old home screen is back.
    @Test fun stepRFromTheJournalEndsOnTheOldHomeScreenIsBackCard() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        h.ledger.record(h.setupPlan)
        h.discovery.open5555[HOST] = true
        val m = h.setup(backgroundScope, SetupStart.Resume(tv.id, h.setupPlan.id))
        settle()
        h.engine.emit(tv.id, RunState.Finished(h.setupPlan.copy(steps = emptyList()), RunStatus.ABANDONED, emptyList(), HomeOutcome.CONNECTION_LOST))
        settle()
        val s = assertIs<SetupState.Stopped>(m.state.value)
        assertEquals(StopCase.HOME_PUT_BACK, s.card.case)
        assertEquals("STOP_HOME_PUT_BACK_TITLE{tv=Mi TV 4A}", s.card.title)
        assertEquals("STOP_RESUME_HOME_BODY{tv=Mi TV 4A}", s.card.body)
    }

    // R5-W8 O2: the resume card for an open Manage write does not say "setting up", and the run
    // ends in Manage, not on the wizard's Done.
    @Test fun theResumeCardForAManageWriteSaysChangeAndEndsInManage() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        val put = h.plan(listOf(Op.Enable(adPkg)), kind = PlanKind.PUT_BACK, id = "01FAKEPUTBACK0000000000001")
        h.ledger.record(put)
        h.discovery.open5555[HOST] = true
        val m = h.setup(backgroundScope, SetupStart.Resume(tv.id, put.id))
        settle()
        val s = assertIs<SetupState.Stopped>(m.state.value)
        assertEquals("STOP_RESUME_CHANGE_TITLE{done=0,total=0,tv=Mi TV 4A}", s.card.title)
        assertTrue(s.card.body.startsWith("STOP_RESUME_CHANGE_BODY"), s.card.body)
        h.engine.emit(tv.id, RunState.Paused(put, 0, PauseReason.IN_DOUBT, null)); settle()
        m.effects.test {
            h.engine.finish(put, RunStatus.COMPLETED); settle()
            assertEquals(SetupEffect.Navigate("manage/${tv.id.value}"), drain().last())
        }
        assertFalse(m.state.value is SetupState.Done)
    }

    // Bug 2 of the emulator run: an address typed on the "No TV found yet" card never probed port
    // 5555, because the placeholder door and the typed door are the same state class and phase.
    @Test fun anAddressTypedFromTheNotFoundCardProbesItsPortAndConnects() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.register(TYPED, h.session)
        h.sessions.allowPromptFor[TYPED] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        m.dispatch(SetupIntent.Start); settle()
        settle(20_000)
        assertEquals(StopCase.TV_NOT_FOUND, m.extras.value.card?.case)
        m.dispatch(SetupIntent.StopAction(StopAction.SECONDARY)); settle()
        assertEquals(Sheet.ADDRESS, m.extras.value.sheet)
        h.discovery.open5555[TYPED] = true
        m.dispatch(SetupIntent.EnterAddress(TYPED)); settle()
        assertTrue(TYPED in h.discovery.probes)
        assertEquals(0, h.discovery.activeCollectors)       // a typed door stops looking
        assertNull(m.extras.value.card)
        settle(700)
        assertIs<SetupState.Found>(m.state.value)
        assertEquals(listOf<tv.cleanyour.core.model.Endpoint>(tv.cleanyour.core.model.Endpoint.PlainTcp(TYPED)), h.sessions.opens)
    }

    @Test fun anAddressTypedOnWelcomeProbesItsPortAndConnects() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.register(TYPED, h.session)
        h.sessions.allowPromptFor[TYPED] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        h.discovery.emit(found()); settle()                  // one TV found, and still the address is offered
        m.dispatch(SetupIntent.Open(Sheet.ADDRESS)); settle()
        assertEquals(Sheet.ADDRESS, m.extras.value.sheet)
        m.dispatch(SetupIntent.EnterAddress(TYPED)); settle()
        assertEquals(TYPED, assertIs<SetupState.OpenDoor>(m.state.value).tv.host)
        settle(4_100)
        assertTrue(h.discovery.probes.count { it == TYPED } >= 2)   // waiting: the poll runs
        h.discovery.open5555[TYPED] = true
        settle(2_100)
        settle(700)
        assertIs<SetupState.Found>(m.state.value)
        assertTrue(HOST !in h.discovery.probes)
    }

    @Test fun typingAnotherAddressWhileWaitingMovesThePollToIt() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.register(TYPED, h.session)
        h.sessions.allowPromptFor[TYPED] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        val wrong = "192.168.1.40"
        m.dispatch(SetupIntent.EnterAddress(wrong)); settle()
        settle(4_100)
        assertTrue(h.discovery.probes.count { it == wrong } >= 2)
        m.dispatch(SetupIntent.Open(Sheet.ADDRESS)); settle()
        h.discovery.open5555[TYPED] = true
        m.dispatch(SetupIntent.EnterAddress(TYPED)); settle()
        val wrongProbes = h.discovery.probes.count { it == wrong }
        assertTrue(TYPED in h.discovery.probes)
        settle(4_100)
        assertEquals(wrongProbes, h.discovery.probes.count { it == wrong })   // the old poll stopped
        assertIs<SetupState.Found>(m.state.value)
        assertEquals(listOf<tv.cleanyour.core.model.Endpoint>(tv.cleanyour.core.model.Endpoint.PlainTcp(TYPED)), h.sessions.opens)
    }

    // Finding 3: the dock sheet offers launchable apps only, never a home screen or a provider.

    // CytLog: the wizard writes redacted milestones for "Share diagnostics", never an address,
    // a serial or a package name.
    @Test fun theWizardLogsItsMilestonesWithoutAddressesSerialsOrPackages() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.engine.onStart = { finish(it, RunStatus.COMPLETED, null) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        val log = h.logLines.joinToString("\n")
        for (want in listOf("setup: connect start", "setup: connected", "setup: probe start", "setup: probe done", "setup: plan ready", "setup: run finished COMPLETED")) {
            assertTrue(h.logLines.any { it.startsWith(want) }, "missing $want in\n$log")
        }
        val serial = Samples.miTvFacts().serialLast4
        for (secret in listOf(HOST, serial, adPkg.value, promoPkg.value, "com.netflix.ninja", Samples.miTvId().value)) assertTrue(secret !in log, "$secret logged")
    }

    // RF-LAUNCHER-250 F1: a profile's switch-off that the live check kept, because the package runs
    // the TV's inputs, is a skip: Setup ends on Done (never the "We stopped" card), the line names it
    // as kept for the inputs, and the generic "harmless" line is only for the other skips.
    @Test fun aSwitchOffKeptForTheTvInputsEndsOnDoneWithItsOwnLine() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.plan(listOf(Op.Disable(adPkg), Op.Disable(promoPkg)))
        h.engine.onStart = { p -> if (p.kind != PlanKind.SETUP) finish(p, RunStatus.COMPLETED) else {
            h.journal.append(JournalRecord.Begin(p.id, 0, p.steps[0].op, listOf("fake"), null, 20))
            h.journal.append(JournalRecord.Failed(p.id, 0, null, tv.cleanyour.core.engine.DefaultEngine.KEPT_TV_INPUT, ""))
            h.journal.append(JournalRecord.Begin(p.id, 1, p.steps[1].op, listOf("fake"), null, 21))
            h.journal.append(JournalRecord.Failed(p.id, 1, 1, "exit 1", "package not on the TV"))
            emit(p.tvId, RunState.Finished(p, RunStatus.COMPLETED_WITH_SKIPS, p.steps, null))
        } }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        val d = assertIs<SetupState.Done>(m.state.value)
        val kept = m.extras.value.doneKept
        assertEquals(1, kept.size, kept.toString())
        assertEquals(1, d.summary.skipped.size, "only the other skip gets the generic line")
        assertTrue(kept.single() !in d.summary.skipped)
        assertTrue(h.engine.calls.none { it is FakeEngine.Call.ContinueRun })
    }

    // Upstream #435: a vendor's FallbackHome answers Home but is no launcher, so when the switch fails
    // the card never names it as "another home screen app"; a real one is still named.
    @Test fun aFallbackHomeIsNotAnotherLauncher() = runTest(timeout = 30.seconds) {
        val cases = listOf(
            tv.cleanyour.core.model.Component(PackageName("com.tcl.keycustomfunctionservice"), ".FallbackHome") to StopCase.HOME_UNKNOWN,
            tv.cleanyour.core.model.Component(PackageName("com.android.provision"), ".DefaultActivity") to StopCase.HOME_UNKNOWN,
            tv.cleanyour.core.model.Component(PackageName("com.example.otherhome"), ".Home") to StopCase.HOME_OTHER_LAUNCHER,
        )
        for ((claimant, expected) in cases) {
            val h = Harness()
            h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
            h.prober.snapshotFor = { r ->
                Samples.miTvSnapshot(reason = r).let { s ->
                    s.copy(home = s.home.copy(claimants = s.home.claimants + tv.cleanyour.core.model.HomeClaimant(claimant, 1)))
                }
            }
            h.setupPlan = h.plan(listOf(Op.Disable(adPkg)), takeover = true)
            h.engine.onStart = { p -> finish(p, RunStatus.COMPLETED_WITH_SKIPS, HomeOutcome.VERIFY_FAILED) }
            val m = h.setup(backgroundScope)
            toFound(h, m)
            m.dispatch(SetupIntent.MakeItClean); settle()
            val s = assertIs<SetupState.Stopped>(m.state.value)
            assertEquals(expected, s.card.case, claimant.flat)
        }
    }

    // Issue #2 (A1): an unlisted Fire TV Edition set (an Insignia panel, Amazon the maker, an AFT
    // model code) resolves to the Amazon sure set; its facts say Fire OS, so the real planner makes
    // the clean-up alone: no section, no install.
    @Test fun anUnlistedFireTvOnTheAmazonSureSetGetsTheCleanUpAlone() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val amazon = tv.cleanyour.core.model.Component(PackageName("com.amazon.tv.launcher"), ".ui.HomeActivity_vNext")
        val fire = Samples.miTvFacts().copy(brand = "Insignia", manufacturer = "Amazon", model = "AFTDCT99", device = "unknown")
        h.prober.facts = fire
        h.prober.snapshotFor = { r ->
            Samples.snapshot(facts = fire, reason = r,
                packages = Samples.miTvPackages(emptySet()) + (amazon.pkg to tv.cleanyour.core.model.PackageState(tv.cleanyour.core.model.EnabledState.ENABLED, true, 1, null, false, false, "Home", false)),
                home = tv.cleanyour.core.model.HomeResolution(amazon, listOf(tv.cleanyour.core.model.HomeClaimant(amazon, 950))))
        }
        val groups = listOf(tv.cleanyour.core.model.PackageGroup("ads", "Adverts", tv.cleanyour.core.model.GroupDefault.DISABLE,
            listOf(adPkg, amazon.pkg).map { tv.cleanyour.core.model.PackageEntry(it, null) }))
        h.profiles.resolver = { _, _ -> tv.cleanyour.core.spec.ProfileResolution.BrandSureSet("Amazon", groups, listOf(amazon)) }
        val planner = tv.cleanyour.core.planner.DefaultPlanner(h.clock, tv.cleanyour.testing.fixtures.FakeIdGen(), tv.cleanyour.core.ops.ShellRenderer(), "0.3.2")
        h.planner.respond = { request, snap -> planner.plan(request, snap, h.facts, emptyList()) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        val found = assertIs<SetupState.Found>(m.state.value)
        assertTrue(found.plan.steps.none { it.op is Op.Install || it.op is Op.InstallStream || it.op is Op.SetHome || it.op is Op.ArmHomeWatchdog })
        // M4 (2026-11-12): autoDebloat and uninstallBloat are no longer gated on a stock Google
        // launcher, so the REA rules fire on this Fire TV too. The profile's DISABLE row for the
        // analytics app still wins its row (it is covered first), but the action follows the
        // uninstall toggle: a system app is permanently uninstalled, not only disabled. The
        // other seven are REA nominations (telemetry/ad signatures and the Shop/ad list) on the
        // same snapshot, each cleared first and then uninstalled for user 0.
        val uninstalls = listOf(
            adPkg,
            PackageName("com.google.android.leanbacklauncher.recommendations"),
            PackageName("com.xiaomi.android.tvsetup.partnercustomizer"),
            PackageName("android.autoinstalls.config.xioami.dangal"),
            PackageName("com.google.android.tv.bugreportsender"),
            PackageName("com.google.android.partnersetup"),
            PackageName("com.google.android.tv.frameworkpackagestubs"),
            PackageName("com.xiaomi.statistic"),
        )
        assertEquals(uninstalls.flatMap { listOf<Op>(Op.ClearPackage(it), Op.Uninstall(it)) }, found.plan.steps.map { it.op })
    }

    // A5: "Stop here" shows Done only once the engine's abandon really ended the run. While it is
    // still paused (step R could not put the TV's own home screen back) the card comes back instead.
    @Test fun stopHereDoesNotShowDoneWhileTheEngineIsPaused() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.engine.onStart = { p -> emit(p.tvId, RunState.Paused(p, 1, PauseReason.STEP_FAILED, AdbError.NonZeroExit(1, "refused"))) }
        h.engine.onAbandon = { id -> emit(id, RunState.Paused(h.setupPlan, 1, PauseReason.STEP_FAILED, AdbError.NonZeroExit(1, "refused again"))) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertEquals(StopCase.STEP_FAILED, assertIs<SetupState.Stopped>(m.state.value).card.case)
        m.dispatch(SetupIntent.StopAction(StopAction.SECONDARY)); settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Abandon })
        assertEquals(StopCase.STEP_FAILED, assertIs<SetupState.Stopped>(m.state.value, "not Done while paused").card.case)
        assertEquals(2, h.logLines.count { it.startsWith("setup: run paused") }, "each pause logged once")
        // The TV lets step R through this time: the abandon ends the run, and Done follows.
        h.engine.onAbandon = { _ -> finish(h.setupPlan, RunStatus.ABANDONED) }
        m.dispatch(SetupIntent.StopAction(StopAction.SECONDARY)); settle()
        assertIs<SetupState.Done>(m.state.value)
    }

    // The abandon's new pause equals the first one, so the run state's collector never sees it
    // (a StateFlow drops a value equal to the last one it delivered). The direct path logs it, once.
    @Test fun anAbandonThatPausesAgainLogsThatPauseOnce() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val error = AdbError.NonZeroExit(1, "refused")
        h.engine.onStart = { p -> emit(p.tvId, RunState.Paused(p, 1, PauseReason.STEP_FAILED, error)) }
        h.engine.onAbandon = { id ->
            emit(id, RunState.RollingBack(h.setupPlan, 1))
            emit(id, RunState.Paused(h.setupPlan, 1, PauseReason.STEP_FAILED, error))
        }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertEquals(1, h.logLines.count { it.startsWith("setup: run paused") })
        m.dispatch(SetupIntent.StopAction(StopAction.SECONDARY)); settle()
        assertEquals(StopCase.STEP_FAILED, assertIs<SetupState.Stopped>(m.state.value).card.case)
        assertEquals(2, h.logLines.count { it.startsWith("setup: run paused") }, h.logLines.joinToString("\n"))
    }

    // Issue #8 (TCL G07, exact profile tcl-g07-google-tv): launcherx, the profile's stock home, is
    // switched off and Home answers to a launcher the person installed. 0.3.2 asked the planner for
    // the switch, the planner found no stock home among the claimants and refused the whole plan
    // ("plan refused" after every probe). Now the wizard asks the planner's StockHome first: the
    // home screen was already changed, so S3 says so and the real planner makes the clean-up alone.
    // Issue #8 (TCL G07, exact profile tcl-g07-google-tv): launcherx, the profile's stock home, is
    // switched off and Home answers to a launcher the person installed. The real planner makes the
    // clean-up alone: the person's launcher is never switched off, and the plan is never refused.
    @Test fun issue8AHomeScreenAlreadyChangedGetsTheCleanUpAlone() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.prober.facts = tv.cleanyour.testing.fixtures.Issue033.g07Facts()
        h.prober.snapshotFor = { r -> tv.cleanyour.testing.fixtures.Issue033.g07Snapshot(r) }
        val source = tv.cleanyour.core.spec.BundledProfileSource()
        h.profiles.resolver = { f, _ -> source.resolveNow(f) }
        val planner = tv.cleanyour.core.planner.DefaultPlanner(h.clock, tv.cleanyour.testing.fixtures.FakeIdGen(), tv.cleanyour.core.ops.ShellRenderer(), "0.3.3")
        h.planner.respond = { request, snap -> planner.plan(request, snap, h.facts, emptyList()) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        val found = assertIs<SetupState.Found>(m.state.value, h.logLines.joinToString("\n"))
        val setup = h.planner.requests.filterIsInstance<PlanRequest.Setup>().last()
        assertIs<tv.cleanyour.core.spec.ProfileResolution.Exact>(setup.resolution)
        assertTrue(found.plan.sections.isEmpty())
        assertTrue(found.plan.steps.none { it.op == Op.Disable(tv.cleanyour.testing.fixtures.Issue033.personsLauncher.pkg) })
        assertTrue(h.logLines.none { it == "setup: plan refused" }, h.logLines.joinToString("\n"))
    }

    // Issues #171 (Xiaomi MiTV4I, brand sure set), #187 (Xiaomi Mi Box S, exact profile) and #220
    // (Polytron Google TV, no profile): Home already answers to com.gathin.tvhome, the new home
    // screen's package name, signed with another key, and the TV's own home screen is switched off.
    // 0.3.3 took that for ours and asked for the install and the switch, which the planner refuses
    // (D36), so every probe ended in "plan refused" and the card said the TV had stopped answering.
    // Now the home screen was already changed: S3 says so and the real planner makes the clean-up alone.
    private suspend fun kotlinx.coroutines.test.TestScope.issue034Found(snap: (SnapshotReason) -> tv.cleanyour.core.model.Snapshot): Pair<Harness, SetupMachine> {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.prober.facts = snap(SnapshotReason.BASELINE).facts
        h.prober.snapshotFor = { r -> snap(r) }
        val source = tv.cleanyour.core.spec.BundledProfileSource()
        h.profiles.resolver = { f, _ -> source.resolveNow(f) }
        val planner = tv.cleanyour.core.planner.DefaultPlanner(h.clock, tv.cleanyour.testing.fixtures.FakeIdGen(), tv.cleanyour.core.ops.ShellRenderer(), "0.3.4")
        h.planner.respond = { request, s -> planner.plan(request, s, h.facts, emptyList()) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        return h to m
    }

    @Test fun issue171And187And220AHomeScreenUnderOurNameWithAnotherKeyGetsTheCleanUpAlone() = runTest(timeout = 60.seconds) {
        val reports = listOf<Pair<String, (SnapshotReason) -> tv.cleanyour.core.model.Snapshot>>(
            "#171" to { r -> tv.cleanyour.testing.fixtures.Issue034.magnoliaSnapshot(reason = r) },
            "#187" to { r -> tv.cleanyour.testing.fixtures.Issue034.onedaySnapshot(reason = r) },
            "#220" to { r -> tv.cleanyour.testing.fixtures.Issue034.songshanSnapshot(reason = r) },
        )
        for ((issue, snap) in reports) {
            val (h, m) = issue034Found(snap)
            val log = "$issue\n" + h.logLines.joinToString("\n")
            val found = assertIs<SetupState.Found>(m.state.value, log)
            assertTrue(found.plan.sections.isEmpty(), log)
            val pkg = tv.cleanyour.testing.fixtures.Issue034.gathinHome.pkg
            assertTrue(found.plan.steps.none { it.op == Op.Disable(pkg) || it.op == Op.Uninstall(pkg) || it.op is Op.Install || it.op is Op.InstallStream && (it.op as Op.InstallStream).pkg == pkg }, log)
            assertTrue(h.logLines.none { it.startsWith("setup: plan refused") }, log)
        }
    }

    // The same TVs with our own key on the launcher in charge: the switch is already done, nothing
    // is blocked, and the plan is made (no "plan refused").
    @Test fun issue171And187And220OurOwnLauncherAlreadyInChargeIsAlreadyDone() = runTest(timeout = 60.seconds) {
        val ours = Samples.launcherArtifact().signer
        val reports = listOf<Pair<String, (SnapshotReason) -> tv.cleanyour.core.model.Snapshot>>(
            "#171" to { r -> tv.cleanyour.testing.fixtures.Issue034.magnoliaSnapshot(ours, r) },
            "#187" to { r -> tv.cleanyour.testing.fixtures.Issue034.onedaySnapshot(ours, r) },
            "#220" to { r -> tv.cleanyour.testing.fixtures.Issue034.songshanSnapshot(ours, r) },
        )
        for ((issue, snap) in reports) {
            val (h, m) = issue034Found(snap)
            val log = "$issue\n" + h.logLines.joinToString("\n")
            val found = assertIs<SetupState.Found>(m.state.value, log)
            assertTrue(found.plan.sections.isEmpty(), log)
            assertTrue(found.plan.steps.none { it.op is Op.Uninstall && (it.op as Op.Uninstall).pkg == tv.cleanyour.testing.fixtures.Issue034.gathinHome.pkg }, log)
            assertTrue(h.logLines.none { it.startsWith("setup: plan refused") }, log)
        }
    }

    // A plan the planner refuses has its own card (the TV answered; nothing was changed), not the
    // probe failure's "the TV stopped answering", and the log says why. An app the person installed
    // is not named in the reason; the TV's own apps and our package are.
    @Test fun issue171ARefusedPlanSaysWhyAndHasItsOwnCard() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.prober.facts = tv.cleanyour.testing.fixtures.Issue033.g07Facts()
        h.prober.snapshotFor = { r -> tv.cleanyour.testing.fixtures.Issue033.g07Snapshot(r) }
        h.planner.respond = { _, _ -> PlanResult.Refused(listOf(
            "Projectivy Launcher cannot be switched off: it is a home screen app you installed",
            "com.spocky.projengmenu is not on this TV",
            "the home screen app can only be replaced while the TV's own home screen is in charge (D36)",
            "the launcher artifact is com.gathin.tvhome, not com.google.android.apps.tv.launcherx",
        )) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        val stopped = assertIs<SetupState.Stopped>(m.state.value, h.logLines.joinToString("\n"))
        assertEquals(StopCase.PLAN_REFUSED, stopped.card.case)
        val because = h.logLines.filter { it.startsWith("setup: refused because ") }
        assertEquals(listOf(
            "setup: refused because an app you installed cannot be switched off: it is a home screen app you installed",
            "setup: refused because an app you installed is not on this TV",
            "setup: refused because the home screen app can only be replaced while the TV's own home screen is in charge (D36)",
            "setup: refused because the launcher artifact is com.gathin.tvhome, not com.google.android.apps.tv.launcherx",
        ), because, h.logLines.joinToString("\n"))
        assertTrue(h.logLines.none { "Projectivy" in it || "projengmenu" in it })
        // Try again reads the TV again; the same facts give the same card.
        m.dispatch(SetupIntent.StopAction(StopAction.PRIMARY)); settle(); settle(700)
        assertEquals(StopCase.PLAN_REFUSED, assertIs<SetupState.Stopped>(m.state.value).card.case)
    }

    // Issue #15 (a Smarttech set no profile knows, the switch blocked): nothing to switch off and no
    // home switch. Done says plainly that nothing was changed, never "Your TV is ready".
    @Test fun issue15ARunThatChangedNothingSaysSoOnDone() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.plan(emptyList())
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        val done = assertIs<SetupState.Done>(m.state.value)
        assertEquals(0, done.summary.switchedOff)
        assertTrue(m.extras.value.doneNothing)
        // A run that switched things off is not "nothing".
        val h2 = Harness()
        h2.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m2 = h2.setup(backgroundScope)
        toFound(h2, m2)
        m2.dispatch(SetupIntent.MakeItClean); settle()
        assertEquals(2, assertIs<SetupState.Done>(m2.state.value).summary.switchedOff)
        assertFalse(m2.extras.value.doneNothing)
    }

    // Issue #10: the finish line names the switch-offs the run skipped, so a report shows what stayed on.
    @Test fun theFinishLineNamesTheSkippedSwitchOffs() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.engine.onStart = { p -> emit(p.tvId, RunState.Finished(p, RunStatus.COMPLETED_WITH_SKIPS, listOf(p.steps[0]), null)) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
        assertTrue(h.logLines.any { it == "setup: run finished COMPLETED_WITH_SKIPS, 1 skipped: Disable ${adPkg.value}" }, h.logLines.joinToString("\n"))
        assertFalse(m.extras.value.doneNothing)
    }

    // 0.3.3: which "nothing was changed" body Done shows. Every planned switch-off stayed on (the TV
    // kept them, issue #10) on a TV without an exact list: never "We weren't sure about anything",
    // which is only for a plan with nothing to switch off there.
    @Test fun doneSaysEverythingStayedOnWhenTheTvKeptEverySwitchOff() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.profiles.resolver = { _, _ -> tv.cleanyour.core.spec.ProfileResolution.None(listOf(Samples.stockLauncher)) }
        h.engine.onStart = { p -> emit(p.tvId, RunState.Finished(p, RunStatus.COMPLETED_WITH_SKIPS, p.steps, null)) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        assertTrue(m.extras.value.unknownModel)
        m.dispatch(SetupIntent.MakeItClean); settle()
        val done = assertIs<SetupState.Done>(m.state.value)
        assertEquals(0, done.summary.switchedOff)
        assertTrue(m.extras.value.doneNothing)
        assertEquals(NothingWhy.KEPT_ON, m.extras.value.doneNothingWhy)
        // Nothing planned on the same TV: the "not sure" body.
        val h2 = Harness()
        h2.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h2.profiles.resolver = { _, _ -> tv.cleanyour.core.spec.ProfileResolution.None(listOf(Samples.stockLauncher)) }
        h2.setupPlan = h2.plan(emptyList())
        val m2 = h2.setup(backgroundScope)
        toFound(h2, m2)
        m2.dispatch(SetupIntent.MakeItClean); settle()
        assertTrue(m2.extras.value.doneNothing && m2.extras.value.unknownModel)
        assertEquals(NothingWhy.NOTHING_PLANNED, m2.extras.value.doneNothingWhy)
    }

    // N5: "Stop here" with no step done counts what the ledger shows, never the plan's switch-offs.
    @Test fun stopHereWithNothingDoneSwitchedNothingOff() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.engine.onStart = { p -> emit(p.tvId, RunState.Paused(p, 0, PauseReason.STEP_FAILED, AdbError.NonZeroExit(1, "refused"))) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertEquals(StopCase.STEP_FAILED, assertIs<SetupState.Stopped>(m.state.value).card.case)
        m.dispatch(SetupIntent.StopAction(StopAction.SECONDARY)); settle()
        val done = assertIs<SetupState.Done>(m.state.value)
        assertEquals(0, done.summary.switchedOff, "the plan had 2 switch-offs, none done")
        assertTrue(m.extras.value.doneNothing)
        assertEquals(NothingWhy.STOPPED, m.extras.value.doneNothingWhy)
    }

}
