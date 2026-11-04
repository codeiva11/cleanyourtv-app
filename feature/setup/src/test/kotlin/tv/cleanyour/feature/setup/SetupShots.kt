package tv.cleanyour.feature.setup

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import tv.cleanyour.core.engine.NarrationLine
import tv.cleanyour.core.engine.NarrationState
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.flows.ChipState
import tv.cleanyour.core.flows.DoneSummary
import tv.cleanyour.core.flows.Finishing
import tv.cleanyour.core.flows.NoSetupActions
import tv.cleanyour.core.flows.NothingWhy
import tv.cleanyour.core.flows.Reconnect
import tv.cleanyour.core.flows.RestartRead
import tv.cleanyour.core.flows.RestartState
import tv.cleanyour.core.flows.SetupExtras
import tv.cleanyour.core.flows.SetupState
import tv.cleanyour.core.flows.StopCard
import tv.cleanyour.core.flows.StopCase
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.core.model.WizardDraft
import tv.cleanyour.core.planner.GroupSummary
import tv.cleanyour.core.planner.GuardedSection
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.PlanSummary
import tv.cleanyour.core.planner.RowSummary
import tv.cleanyour.core.probe.ProbeStage
import tv.cleanyour.testing.fixtures.Samples
import tv.cleanyour.ui.design.component.UndoSnackbar
import tv.cleanyour.ui.design.component.rememberUndoSnackbarState

// Every state of :feature:setup's screens (PRODUCT-SPEC 4: probing, S3, S3a, S3b, S4a, S4b, S4c to
// S4e, S5, and the stops of section 4 that live here: install failure, TV refuses other home
// screens, TV not back after a restart), with the M5 cards: "We put the TV's own home screen back"
// (HOME_PUT_BACK), the resume card for a Manage change and "Reconnect to the TV to put the old
// home screen back" (R-11). The copy comes from this module's strings, as the app formats it.

private fun fakeShot(): ByteArray {
    val bmp = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(0xFF05060A.toInt())
    val paint = Paint()
    val tiles = intArrayOf(0xFF2A2F45.toInt(), 0xFF1F5FD0.toInt(), 0xFF4A4A50.toInt(), 0xFF138A7A.toInt())
    repeat(4) { i -> paint.color = tiles[i]; canvas.drawRoundRect(RectF(70f + i * 130f, 230f, 180f + i * 130f, 320f), 16f, 16f, paint) }
    paint.color = 0xFF3A3A40.toInt()
    canvas.drawRoundRect(RectF(60f, 40f, 160f, 70f), 8f, 8f, paint)
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
}

// Sample data: the Mi TV 4A plan of PRODUCT-SPEC 4 S3.

private val tvId = Samples.miTvId()
private const val NOW = Samples.NOW_MS

private fun row(pkg: String, name: String, description: String, off: Boolean = true, protectedRow: Boolean = false, why: String? = null) =
    RowSummary(PackageName(pkg), name, description, off, protectedRow, why)

private val adsGroup = GroupSummary("ads", "Adverts and sponsored rows", off = 5, total = 6, rows = listOf(
    row("com.mitv.tvhome.atv", "PatchWall", "Xiaomi's home screen with adverts"),
    row("com.mitv.tvhome.mitvplus", "Mi TV+ promos", "Promoted content rows", off = false),
    row("com.glance.tv", "Glance", "Adverts on the lock screen"),
    row("com.xiaomi.mitv.ad", "Mi ad service", "Fetches the adverts for the rows"),
    row("com.mitv.shop", "Mi Shop banner", "Sponsored banner on the home screen"),
    row("com.mitv.screensaver.ads", "Screensaver adverts", "Adverts in the screensaver"),
    row("com.droidlogic.tvinput", "Live TV and HDMI", "Plays your HDMI set-top box", off = false, protectedRow = true,
        why = "Live TV plays your HDMI set-top box; switching it off would lose the Inputs button."),
))
private val trackingGroup = GroupSummary("tracking", "Tracking and analytics", off = 7, total = 7, rows = listOf(
    row("com.xiaomi.analytics", "Xiaomi Analytics", "Reports what you watch to Xiaomi"),
    row("com.alphonso.tv", "Alphonso", "Listens to what's on to sell ads"),
))
private val nobodyGroup = GroupSummary("nobody", "Apps nobody asked for", off = 8, total = 8, rows = listOf(
    row("com.example.demo", "Demo mode", "Shop display mode"),
))
private val extrasGroup = GroupSummary("brand", "Xiaomi extras", off = 2, total = 2, rows = listOf(
    row("com.xiaomi.mitv.smartshare", "Mi Smart Share", "Casting from Xiaomi phones"),
))
private val keepNames = listOf("Netflix", "YouTube", "Prime Video")
private fun summary(groups: List<GroupSummary>) = PlanSummary(groups, keepNames)

private fun plan(summary: PlanSummary): Plan = Samples.plan(emptyList()).copy(summary = summary)

private val fullPlan = plan(summary(listOf(adsGroup, trackingGroup, nobodyGroup, extrasGroup)))
private val zeroPlan = plan(summary(listOf(GroupSummary("ads", "Adverts and sponsored rows", 0, 0, emptyList()))))
private val unknownPlan = plan(summary(listOf(
    GroupSummary("ads", "Adverts and sponsored rows", off = 3, total = 3, rows = emptyList()),
    GroupSummary("tracking", "Tracking and analytics", off = 4, total = 4, rows = emptyList()),
    GroupSummary("nobody", "Apps nobody asked for", off = 2, total = 2, rows = emptyList()),
)))
// The emulator TV (round 3): an unknown model where nothing is sure, so the one switch-off is the
// TV's own home screen inside the home screen switch, listed and counted like any other.
private val homeSwitchGroup = GroupSummary("home", "Needed for the new home screen", off = 1, total = 1, rows = listOf(
    row("com.google.android.tvlauncher", "Android TV home", "The TV's own home screen. Switched off only after the new one works.",
        protectedRow = true, why = "part of the home screen switch; it comes back on if the switch is undone"),
))
// The Setup plan sends the dock and the look before the switch (RFC-DOCK-1), so its config.json
// push is on the list as the sync line planHash uses.
private const val CONFIG_SYNC = "sync:/sdcard/Android/data/com.gathin.tvhome/files/config.json 44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
private val homeSwitchCommands = listOf(
    CONFIG_SYNC,
    "pm disable-user --user 0 com.google.android.tvlauncher",
    "cmd package set-home-activity com.gathin.tvhome/.MainActivity",
)
private val homeSwitchOnlyPlan = plan(summary(listOf(homeSwitchGroup)))
// RFC-CURTAIN-1: the same plan with the TV curtain, as "Show commands" lists it: the curtain's install,
// its start, one am broadcast per cue, and its removal last.
private val noHomePlan = plan(summary(listOf(
    GroupSummary("ads", "Adverts and sponsored rows", off = 6, total = 6, rows = emptyList()),
    GroupSummary("tracking", "Tracking and analytics", off = 7, total = 7, rows = emptyList()),
    GroupSummary("nobody", "Apps nobody asked for", off = 3, total = 3, rows = emptyList()),
)))
// 0.4.7: the Google TV Streamer's plan as the planner summarises it when the TV keeps its own home
// screen (CASTING): its profile's home group lists the setup wizard and the home screen as protected
// rows, with the planner's own reasons, which the sheet replaces by role (HomeRole).
private val section = GuardedSection("home", 0, 0, RunToken("0123456789abcdef"), confirmWindowMs = 90_000,
    expectedHome = Samples.launcherComponent, rollbackIfFocus = emptySet(), rollback = emptyList())
private val longSection = section.copy(confirmWindowMs = 120_000)

private val commands = listOf(
    "pm disable-user --user 0 com.mitv.tvhome.atv",
    "pm disable-user --user 0 com.glance.tv",
    CONFIG_SYNC,
    "cmd package set-home-activity tv.cleanyour.launcher/.Home",
)

private val connected = SetupExtras(tvName = "Mi TV 4A", chip = ChipState.CONNECTED)
private val working = SetupExtras(tvName = "Mi TV 4A", chip = ChipState.WORKING)
private val offline = SetupExtras(tvName = "Mi TV 4A", chip = ChipState.OFFLINE)
private val customising = connected.copy(
    commands = commands,
    draft = WizardDraft(3, Level.SIMPLE, emptySet(), emptySet(), false, null, NOW),
)

private fun found(plan: Plan = fullPlan, level: Level = Level.SIMPLE) = SetupState.Found(tvId, plan, level, sheet = null)

private fun running(lines: List<NarrationLine>, percent: Int, thumbnail: ByteArray?) =
    SetupState.Working(tvId, RunState.Running(fullPlan.copy(sections = listOf(section)), step = 3, narration = lines, percent = percent, thumbnail = thumbnail), thumbnail)
private fun runningNoSwitch(lines: List<NarrationLine>, percent: Int, thumbnail: ByteArray?) =
    SetupState.Working(tvId, RunState.Running(noHomePlan, step = 3, narration = lines, percent = percent, thumbnail = thumbnail), thumbnail)

private fun checkState(thumbnail: ByteArray?, deadlineAheadMs: Long = 84_000, homeCheck: Boolean? = null, s: GuardedSection = section) =
    SetupState.Check(tvId, RunState.AwaitingConfirm(fullPlan, s, NOW + deadlineAheadMs, homeCheck, thumbnail), thumbnail)

private fun done(off: Int = 23, home: Boolean = true, skipped: List<String> = emptyList(), recommended: Boolean = false, restart: RestartState? = null,
                 restoreEarlier: Boolean = false) =
    SetupState.Done(tvId, DoneSummary(off, home, if (restoreEarlier) NOW - 36_000_000 else NOW, skipped, recommended, restoreEarlier), restart)

private val narration = listOf(
    NarrationLine("Restore point saved", NarrationState.DONE),
    NarrationLine("Adverts switched off (6)", NarrationState.DONE),
    NarrationLine("Tracking: 4 of 7...", NarrationState.DOING),
    NarrationLine("Apps nobody asked for", NarrationState.UPCOMING),
    NarrationLine("Installing the new home screen", NarrationState.UPCOMING),
    NarrationLine("Switching Home over", NarrationState.UPCOMING),
)
private val narrationAllStates = listOf(
    NarrationLine("Restore point saved", NarrationState.DONE),
    NarrationLine("Adverts switched off (6)", NarrationState.DONE),
    NarrationLine("Play Games wouldn't switch off", NarrationState.SKIPPED),
    NarrationLine("Mi Cast refused", NarrationState.FAILED),
    NarrationLine("Tracking switched off (7)", NarrationState.DONE),
    NarrationLine("Apps nobody asked for: 3 of 8...", NarrationState.DOING),
    NarrationLine("Replacing the home screen app", NarrationState.UPCOMING),
    NarrationLine("Switching Home over", NarrationState.UPCOMING),
)

// A stop card with the copy the app's ResourceFlowCopy would put on it.
@Composable
private fun card(case: StopCase, title: Int, body: Int, primary: Int, secondary: Int? = null, tertiary: Int? = null, args: Map<String, Any> = emptyMap()) =
    StopCard(
        case, msg(title, *args.toList().toTypedArray()), msg(body, *args.toList().toTypedArray()), msg(primary, *args.toList().toTypedArray()),
        secondary?.let { msg(it, *args.toList().toTypedArray()) }, planId = null, tertiary = tertiary?.let { msg(it, *args.toList().toTypedArray()) },
    )

private val lostArgs = mapOf<String, Any>("done" to 14, "total" to 23, "tv" to "Mi TV 4A")

@Composable
private fun connectionLost() = card(StopCase.CONNECTION_LOST, R.string.stop_connection_lost_title, R.string.stop_connection_lost_body,
    R.string.stop_connection_lost_primary, R.string.stop_connection_lost_secondary, R.string.stop_connection_lost_tertiary, lostArgs)

@Composable
private fun resume() = card(StopCase.RESUME_AFTER_DEATH, R.string.stop_resume_title, R.string.stop_resume_body,
    R.string.stop_resume_primary, R.string.stop_resume_secondary, R.string.stop_resume_tertiary, lostArgs)

@Composable
private fun homePutBack() = card(StopCase.HOME_PUT_BACK, R.string.stop_resume_home_title, R.string.stop_resume_home_body,
    R.string.stop_resume_home_primary, R.string.stop_resume_home_secondary, args = lostArgs)

@Composable
private fun home(case: StopCase, title: Int, body: Int, primary: Int, secondary: Int?, args: Map<String, Any> = emptyMap()) =
    card(case, title, body, primary, secondary, args = args)

@Composable
private fun Setup(state: SetupState, extras: SetupExtras = connected) = SetupFlow(state, extras, NoSetupActions, nowMs = { NOW })

@Composable
private fun Stopped(c: StopCard, extras: SetupExtras = connected) = Setup(SetupState.Stopped(tvId, c), extras)

object SetupShots {
    val all: List<Shot> = listOf(
        // S2d to S3 probing
        Shot("probing_start") { Setup(SetupState.Probing(tvId, ProbeStage.FACTS, null)) },
        Shot("probing_packages") { Setup(SetupState.Probing(tvId, ProbeStage.PACKAGES, null)) },
        // Test build: the card before the plan for a list not tested yet
        Shot("probing_candidate") { Setup(SetupState.Probing(tvId, ProbeStage.PACKAGES, null), connected.copy(candidate = tv.cleanyour.core.flows.CandidateOffer("xiaomi-mitv4i-magnolia", "#111"))) },

        // S3 What we found, with the unknown model, zero, no screen lock and TV-refuses-home variants
        Shot("found_simple") { Setup(found()) },
        Shot("found_zero") { Setup(found(zeroPlan)) },
        // Emulator run: a TV with no app to suggest for the dock; the row names the new home screen.
        Shot("found_unknown_model") { Setup(found(unknownPlan), connected.copy(unknownModel = true)) },
        Shot("found_unknown_home_switch_only") { Setup(found(homeSwitchOnlyPlan), connected.copy(unknownModel = true)) },
        Shot("found_no_screen_lock") { Setup(found(), connected.copy(noScreenLock = true)) },
        // 0.4.7 (HomeKept): the TV keeps its own home screen; the outcome row sits under the headline
        // with the short reason and Why. UX finding 12: the brand from the TV's facts, not its nickname.
        // Issue #2: a Fire TV Edition set is named for Fire OS.
        // RF-PROFILES-63 P1 and P2: Android 7 (the clean-up only), and a profile with no known stock home.
        // Issue #8: Home answers to a launcher the person installed; the TV's own home screen is off.
        // beta3 (issue #1119): another home screen app claims Home where the switch could go ahead.
        // beta4 (issue #825): a system service's FallbackHome claims Home where the switch could go ahead.
        // beta4 (issue #945): PatchWall stays on while its feeds are off, not by this app.
        // The Google TV Streamer (CASTING): the outcome, the casting reason, and the Why sheet.
        // An unlisted TV whose setup wizard has to stay on: a list is what would change it.
        // UX finding 5, PM 7: nothing to switch off on a kept TV: Done, no "Make it clean"; an unlisted
        // TV offers Help add this TV, and says when its report was sent (SentReports).
        Shot("found_starting") { Setup(found(), connected.copy(starting = true)) },
        // D36 (RF-LAUNCHER-240 F1): the plan replaces a home screen app signed with another key.
        // Issue #275: a TCL Google TV, whose remote's gear, Netflix and YouTube buttons need Google TV's home.
        // RFC-HELPER-1 (test build): the Home button helper chosen on a casting TV: the redirect's outcome, never "keeps its own".
        // #251: a Google TV cleaned before, nothing left to switch off: Customise still offers the helper.

        // S3a Customise: Simple with the other groups collapsed; Custom with every group open, the
        // package names and the undo snackbar; the "Why?" sheet
        Shot("customise_sheet") {
            SheetFrame({ Setup(found()) }) { CustomiseContent(found(), customising, NoSetupActions, rememberUndoSnackbarState(), {}, {}) }
        },
        // A dock with a row under it: "Dock: Netflix, ... plus 2 apps in the row below", the groups closed.
        Shot("customise_sheet_custom") {
            Box(Modifier.fillMaxSize()) {
                SheetFrame({ Setup(found(level = Level.CUSTOM)) }) {
                    CustomiseContent(found(level = Level.CUSTOM), connected.copy(commands = commands, draft = WizardDraft(3, Level.CUSTOM, emptySet(), emptySet(), true, null, NOW)),
                        NoSetupActions, rememberUndoSnackbarState(), {}, {}, initiallyOpen = listOf("ads", "tracking", "nobody", "brand"))
                }
                // The undo snackbar after "Mi TV+ promos" was tapped (S3a), as the sheet shows it.
                UndoSnackbar(msg(R.string.customise_snackbar_kept, "name" to "Mi TV+ promos"), {},
                    Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = 28.dp))
            }
        },
        // The home screen switch's own switch-off: shown Off with no pill to flip, counted in "1 of 1 off".
        Shot("customise_sheet_home_switch") {
            SheetFrame({ Setup(found(homeSwitchOnlyPlan)) }) {
                CustomiseContent(found(homeSwitchOnlyPlan), connected.copy(commands = homeSwitchCommands), NoSetupActions, rememberUndoSnackbarState(), {}, {})
            }
        },
        // RFC-CURTAIN-1: Custom shows "Show the progress on the TV" (on by default) and the cue lines.
        Shot("customise_why_sheet") { SheetFrame({ Setup(found()) }, maxHeight = 360.dp) { WhyContent(found()) } },
        // 0.4.7 (UX findings 1, 3): the Google TV Streamer keeps its own home screen: the outcome card
        // replaces the Home screen group, and its wizard and home screen rows say they stay.
        // RFC-HELPER-1 (test build): Customise's Home button helper, switched on, and its consent.
        // 0.4.7: a plan of switch-offs and the look only (the helper plan has no "Needed for the new home screen" group and no set-home-activity).


        // S4a Making it clean
        Shot("working_58") { Setup(running(narration, 58, remember { fakeShot() }), working) },
        Shot("working_all_narration_states") { Setup(running(narrationAllStates, 72, remember { fakeShot() }), working) },
        Shot("working_no_thumbnail") { Setup(running(narration, 12, null), working) },
        // UX finding 10: a plan without the home screen switch ends with the buzz "done".
        Shot("working_no_check") { Setup(runningNoSwitch(narration.take(4), 40, remember { fakeShot() }), working) },
        Shot("working_back_highlights_stop") { Setup(running(narration, 58, remember { fakeShot() }), working.copy(highlightStop = true)) },
        Shot("working_rolling_back") { Setup(running(narration, 58, remember { fakeShot() }), working.copy(rollingBack = true, highlightStop = true)) },

        // S4b Can you see it? (90 s, the 120 s window of D23, the live check passed, no picture)
        Shot("check_84s") { Setup(checkState(remember { fakeShot() })) },
        Shot("check_home_checked") { Setup(checkState(remember { fakeShot() }, deadlineAheadMs = 60_000, homeCheck = true)) },
        Shot("check_no_thumbnail") { Setup(checkState(null)) },
        Shot("check_two_minute_window") { Setup(checkState(null, deadlineAheadMs = 118_000, s = longSection)) },

        // S4c Stopped: the TV's own home screen is back (UX 4.7), each case. 0.4.7 (UX findings 16, 23):
        // the wizard card is the Wi-Fi card: "Keep the TV's own home screen" first, then "I've done
        // that, try again" (the wizard owns the Wi-Fi) or "Try again" (the TV could not say).
        Shot("stopped_home_setupwraith_wifi") {
            Stopped(home(StopCase.HOME_SETUPWRAITH, R.string.stop_home_setupwraith_title, R.string.stop_home_setupwraith_wifi_body,
                R.string.stop_home_setupwraith_secondary, R.string.stop_home_setupwraith_wifi_secondary), connected.copy(commands = commands))
        },
        // RF-LAUNCHER-240 F6: the TV could not say who saved its Wi-Fi.
        Shot("stopped_home_setupwraith_wifi_unknown") {
            Stopped(home(StopCase.HOME_SETUPWRAITH, R.string.stop_home_setupwraith_title, R.string.stop_home_setupwraith_wifi_unknown_body,
                R.string.stop_home_setupwraith_secondary, R.string.stop_home_user_said_no_primary), connected.copy(commands = commands))
        },
        Shot("stopped_home_rolling_back") {
            Stopped(home(StopCase.HOME_SETUPWRAITH, R.string.stop_home_setupwraith_title, R.string.stop_home_setupwraith_wifi_body,
                R.string.stop_home_setupwraith_secondary, R.string.stop_home_setupwraith_wifi_secondary), connected.copy(rollingBack = true))
        },
        Shot("stopped_home_other_launcher") {
            Stopped(home(StopCase.HOME_OTHER_LAUNCHER, R.string.stop_home_other_launcher_title, R.string.stop_home_other_launcher_body,
                R.string.stop_home_other_launcher_primary, R.string.stop_home_other_launcher_secondary, mapOf("launcher" to "Projectivy")), connected.copy(commands = commands))
        },
        Shot("stopped_home_unknown") {
            Stopped(home(StopCase.HOME_UNKNOWN, R.string.stop_home_unknown_title, R.string.stop_home_unknown_body,
                R.string.stop_home_unknown_primary, R.string.stop_home_unknown_secondary), connected.copy(commands = commands))
        },
        // Issue #629: a Settings page was open on the TV during the check.
        Shot("stopped_home_settings_open") {
            Stopped(home(StopCase.HOME_SETTINGS_OPEN, R.string.stop_home_unknown_title, R.string.stop_home_settings_open_body,
                R.string.stop_home_user_said_no_primary, R.string.stop_home_setupwraith_secondary), connected.copy(commands = commands))
        },
        Shot("stopped_home_user_said_no") {
            Stopped(home(StopCase.HOME_USER_SAID_NO, R.string.stop_home_user_said_no_title, R.string.stop_home_user_said_no_body,
                R.string.stop_home_user_said_no_primary, R.string.stop_home_user_said_no_secondary), connected.copy(commands = commands))
        },
        Shot("stopped_home_timed_out") {
            Stopped(home(StopCase.HOME_TIMED_OUT, R.string.stop_home_timed_out_title, R.string.stop_home_timed_out_body,
                R.string.stop_home_timed_out_primary, R.string.stop_home_timed_out_secondary), connected.copy(commands = commands))
        },
        // QA of 0.4.5: things stayed switched off, so the card says how many, never "as it was".
        Shot("stopped_home_timed_out_switched_off") {
            Stopped(home(StopCase.HOME_TIMED_OUT, R.string.stop_home_timed_out_title, R.string.stop_home_timed_out_body_off,
                R.string.stop_home_timed_out_primary, R.string.stop_home_timed_out_secondary, args = mapOf("count" to 3)), connected.copy(commands = commands))
        },
        // M5: step R put the TV's own home screen back after a lost connection (D-W8-16)
        Shot("stopped_home_put_back") { Stopped(homePutBack(), connected.copy(commands = commands)) },
        Shot("stopped_details_sheet") { SheetFrame({ Stopped(homePutBack(), connected.copy(commands = commands)) }, maxHeight = 420.dp) { DetailsContent(commands) } },

        // S4d Stopped: connection lost mid-plan (UX 4.14), and R-11's reconnect line
        // Stop and undo ask first: the question over the Working screen and over the stop card.
        Shot("confirm_stop_and_undo") {
            SheetFrame({ Setup(running(narration, 58, remember { fakeShot() }), working) }, maxHeight = 360.dp) {
                ConfirmQuestionFor(tv.cleanyour.core.flows.Confirm.STOP_AND_UNDO, {}, {})
            }
        },
        Shot("confirm_stop_here") { SheetFrame({ Stopped(connectionLost(), connected) }, maxHeight = 360.dp) { ConfirmQuestionFor(tv.cleanyour.core.flows.Confirm.STOP_HERE, {}, {}) } },
        Shot("confirm_undo") { SheetFrame({ Stopped(connectionLost(), connected) }, maxHeight = 360.dp) { ConfirmQuestionFor(tv.cleanyour.core.flows.Confirm.UNDO, {}, {}) } },
        Shot("stopped_connection_lost_reconnecting") { Stopped(connectionLost(), offline.copy(reconnect = Reconnect.TRYING)) },
        Shot("stopped_connection_lost_back") { Stopped(connectionLost(), connected.copy(reconnect = Reconnect.BACK)) },
        Shot("stopped_connection_lost_gave_up") { Stopped(connectionLost(), offline.copy(reconnect = Reconnect.GAVE_UP)) },
        // 0.4.6: a stopped run's card with "Report a problem" under it, the way out the person did not choose.
        Shot("stopped_connection_lost_report", height = 900.dp) { Stopped(connectionLost(), offline) },
        Shot("stopped_reconnect_to_put_home_back") {
            Stopped(connectionLost().copy(body = msg(R.string.stop_reconnect_home_body, "tv" to "Mi TV 4A")), offline.copy(reconnect = Reconnect.TRYING))
        },
        Shot("stopped_step_failed") {
            Stopped(card(StopCase.STEP_FAILED, R.string.stop_step_failed_title, R.string.stop_step_failed_body,
                R.string.stop_connection_lost_primary, R.string.stop_connection_lost_secondary, R.string.stop_connection_lost_tertiary, lostArgs))
        },

        // S4e Resume after process death: the setup, an open HOME section, a Manage change, and R-11
        Shot("stopped_resume") { Stopped(resume(), connected.copy(chip = ChipState.WAITING)) },
        Shot("stopped_resume_with_home") {
            Stopped(resume().copy(body = stringResource(R.string.stop_resume_home_body)), connected.copy(chip = ChipState.WAITING))
        },
        Shot("stopped_resume_change") {
            Stopped(resume().copy(title = msg(R.string.stop_resume_change_title, "tv" to "Mi TV 4A"), body = stringResource(R.string.stop_resume_change_body)),
                connected.copy(chip = ChipState.WAITING))
        },
        Shot("stopped_resume_reconnect_home") {
            Stopped(resume().copy(body = msg(R.string.stop_reconnect_home_body, "tv" to "Mi TV 4A")), offline.copy(reconnect = Reconnect.TRYING))
        },

        // Install failure, undone, probe failure
        Shot("stopped_install_failed") {
            Stopped(card(StopCase.INSTALL_FAILED, R.string.stop_install_failed_title, R.string.stop_install_failed_body,
                R.string.stop_install_failed_primary, args = mapOf("free" to 180, "needed" to 600)))
        },
        Shot("stopped_install_failed_generic") {
            Stopped(card(StopCase.INSTALL_FAILED, R.string.stop_install_failed_title, R.string.stop_install_failed_generic_body, R.string.stop_install_failed_primary))
        },
        Shot("stopped_undone") {
            Stopped(card(StopCase.UNDONE, R.string.stop_undone_title, R.string.stop_undone_body, R.string.stop_undone_primary), connected.copy(commands = commands))
        },
        Shot("stopped_probe_failed") {
            Stopped(card(StopCase.PROBE_FAILED, R.string.stop_probe_failed_title, R.string.stop_probe_failed_body,
                R.string.stop_probe_failed_primary, R.string.stop_probe_failed_secondary))
        },
        Shot("stopped_plan_refused") {
            Stopped(card(StopCase.PLAN_REFUSED, R.string.stop_plan_refused_title, R.string.stop_plan_refused_body,
                R.string.stop_probe_failed_primary, R.string.stop_probe_failed_secondary))
        },

        // S5 Done and the restart flow (D24, UX 4.8)
        Shot("done_home_on") { Setup(done()) },
        Shot("done_restart_recommended") { Setup(done(recommended = true)) },
        // beta3 QA: a later run names the first restore point, the one Undo everything goes back to.
        Shot("done_restore_point_earlier") { Setup(done(restoreEarlier = true)) },
        Shot("done_without_home") { Setup(done(off = 16, home = false)) },
        // 0.4.7 (UX findings 6, 7, 9): the TV keeps its own home screen: "Clean-up done", the outcome in
        // the lead with Why, no card to add, and after the restart "Home screen is the TV's own".
        // UX finding 18: the switch held but the look did not reach the TV.
        Shot("done_look_failed") { Setup(done(), connected.copy(doneLookFailed = true)) },
        // RFC-HELPER-1 (test build): Done after the Home button helper, and after a restart.
        Shot("done_skipped_one") { Setup(done(off = 22, skipped = listOf("Play Games"))) },
        Shot("done_skipped_many") { Setup(done(off = 20, skipped = listOf("Play Games", "Mi Cast", "Mi Home"))) },
        // Issue #15: nothing to switch off and no home switch, on a TV we know and on one we don't.
        Shot("done_nothing_changed") { Setup(done(off = 0, home = false, recommended = true), connected.copy(doneNothing = true)) },
        Shot("done_nothing_changed_unknown") { Setup(done(off = 0, home = false, recommended = true), connected.copy(doneNothing = true, unknownModel = true)) },
        // 0.3.3: every switch-off stayed on (issue #10 on a TV without an exact list), and Stop here before anything changed.
        Shot("done_nothing_kept_on") { Setup(done(off = 0, home = false, skipped = listOf("PatchWall"), recommended = true), connected.copy(doneNothing = true, doneNothingWhy = NothingWhy.KEPT_ON, unknownModel = true)) },
        Shot("done_nothing_stopped") { Setup(done(off = 0, home = false, recommended = true), connected.copy(doneNothing = true, doneNothingWhy = NothingWhy.STOPPED)) },
        Shot("done_restarting") { Setup(done(restart = RestartState.Restarting(NOW))) },
        Shot("done_restarting_slow") { Setup(done(restart = RestartState.Restarting(NOW)), connected.copy(restartSlow = true)) },
        Shot("done_back_after_restart") { Setup(done(restart = RestartState.Back(72_000)), connected.copy(restartReenabled = 2)) },
        // 0.4.8 (Restarts): the restart held, with "Tell us it worked"; it failed, with Report a problem.
        Shot("done_restart_held", 1100.dp) { Setup(done(restart = RestartState.Back(72_000)), connected.copy(restartRead = RestartRead.HELD)) },
        Shot("done_restart_failed", 1100.dp) { Setup(done(restart = RestartState.Back(72_000)), connected.copy(restartRead = RestartRead.FAILED)) },
        // 0.4.8 (PLAN item 3): "This is your TV now" after Setup sent the look.
        Shot("done_not_back") {
            val c = card(StopCase.NOT_BACK_AFTER_RESTART, R.string.stop_not_back_title, R.string.stop_not_back_body, R.string.stop_not_back_primary, R.string.stop_not_back_secondary)
            Setup(done(restart = RestartState.NotBack(300_000)), offline.copy(card = c.copy(body = c.body + "\n\n" + stringResource(R.string.stop_not_back_auto))))
        },
        Shot("done_finishing") { Setup(done(), connected.copy(finishing = Finishing.SWITCHING)) },
        Shot("done_finished") { Setup(done(), connected.copy(chip = ChipState.DEBUGGING_OFF, finishing = Finishing.DONE)) },
        // D7: "Keep debugging on" chosen, so Done says debugging stays on (baseline QA, 0.4.0).
        Shot("done_keep_debugging") { Setup(done(), connected.copy(keepsDebugging = true)) },
        // QA of 0.4.5: OK on the timed out card; the home screen is unchanged and Done says why.
        Shot("done_home_kept_timed_out") { Setup(done(off = 3, home = false), connected.copy(doneHomeKept = tv.cleanyour.core.engine.HomeOutcome.TIMED_OUT)) },
        // The Google Play build (STORES-PLAN 1): Done offers no card to add.
        Shot("play_done_home_on") { Setup(done(), connected.copy(addOns = false)) },
    )
}
