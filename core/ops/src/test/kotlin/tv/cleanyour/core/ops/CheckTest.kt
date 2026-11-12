package tv.cleanyour.core.ops

import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.InstallFlag
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.testing.fixtures.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The verify predicates against the reference TV's real outputs (and hostile variants).
class CheckTest {
    private val r = ShellRenderer()
    private val capture = Fixtures.miTv()
    private val launcher = Component(PackageName("com.gathin.tvhome"), ".MainActivity")
    private val stock = Component(PackageName("com.google.android.tvlauncher"), ".MainActivity")

    private fun ok(v: VerifyResult) = assertEquals(VerifyResult.Ok, v)
    private fun bad(v: VerifyResult) = assertTrue(v is VerifyResult.Mismatch, v.toString())

    @Test fun disableAndEnableReadTheDisabledList() {
        val disabled = capture.packagesDisabledAfterText
        ok(r.check(Op.Disable(PackageName("com.miui.tv.analytics")), disabled, null))
        bad(r.check(Op.Disable(PackageName("com.google.android.youtube.tv")), disabled, null))
        ok(r.check(Op.Enable(PackageName("com.google.android.youtube.tv")), disabled, null))
        bad(r.check(Op.Enable(PackageName("com.miui.tv.analytics")), disabled, null))
        // A prefix is not a match, and injected noise does not help.
        bad(r.check(Op.Disable(PackageName("com.miui.tv")), disabled, null))
        bad(r.check(Op.Disable(PackageName("com.miui.tv.analytics")), "garbage\npackage:com.miui.tv.analytics.fake\n", null))
        ok(r.check(Op.Disable(PackageName("com.miui.tv.analytics")), "  package:com.miui.tv.analytics  \r\n", null))
    }

    @Test fun homeIsTheLastLineOfTheBriefResolve() {
        val home = capture.homeActivityText
        ok(r.check(Op.VerifyHome(stock), home, null))
        bad(r.check(Op.VerifyHome(launcher), home, null))
        ok(r.check(Op.SetHome(launcher), "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\ncom.gathin.tvhome/.MainActivity\n", null))
        bad(r.check(Op.SetHome(launcher), "", null))
        bad(r.check(Op.SetHome(launcher), "com.gathin.tvhome/.MainActivity\nandroid/com.android.internal.app.ResolverActivity\n", null))
    }

    @Test fun settingsReadTheNamespaceListing() {
        val secure = capture.settingsSecureText
        ok(r.check(Op.SettingsPut(SettingKey.SCREENSAVER_ENABLED, "1"), secure, null))
        bad(r.check(Op.SettingsPut(SettingKey.SCREENSAVER_ENABLED, "0"), secure, null))
        ok(r.check(Op.SettingsPut(SettingKey.SCREENSAVER_COMPONENTS, "com.google.android.backdrop/.Backdrop"), secure, null))
        bad(r.check(Op.SettingsPut(SettingKey.SCREENSAVER_COMPONENTS, null), secure, null))
        ok(r.check(Op.SettingsPut(SettingKey.DEVICE_NAME, null), "other=1\n", null))
        ok(r.check(Op.SettingsPut(SettingKey.DEVICE_NAME, "Mi TV 4A"), "device_name=Mi TV 4A\n", null))
        bad(r.check(Op.SettingsPut(SettingKey.DEVICE_NAME, "Mi TV 4A"), "device_name=Mi TV 4A old\n", null))
        // R1 R-12: an untrimmed typed name applies as typed and verifies (the read's lines are trimmed).
        ok(r.check(Op.SettingsPut(SettingKey.DEVICE_NAME, "Mi TV "), "device_name=Mi TV \n", null))
        ok(r.check(Op.SettingsPut(SettingKey.DEVICE_NAME, " TV"), "device_name= TV\n", null))
        bad(r.check(Op.SettingsPut(SettingKey.DEVICE_NAME, " TV"), "device_name=TV\n", null))
    }

    @Test fun installAndDefaultStateReadTheDump() {
        val dump = """
            Packages:
              Package [com.gathin.tvhome] (1a2b3c):
                userId=10123
                pkg=Package{4d5e6f com.gathin.tvhome}
                versionCode=7 minSdk=21 targetSdk=28
                versionName=0.1.0
                flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]
                User 0: ceDataInode=1 installed=true hidden=false suspended=false stopped=false notLaunched=false enabled=0 instant=false virtual=false
        """.trimIndent()
        ok(r.check(Op.Install(RemotePath(RemoteDir.TMP, listOf("x.apk")), Sha256("a".repeat(64)), launcher.pkg, 7, Sha256("b".repeat(64)), setOf(InstallFlag.REPLACE)), dump, null))
        bad(r.check(Op.Install(RemotePath(RemoteDir.TMP, listOf("x.apk")), Sha256("a".repeat(64)), launcher.pkg, 8, Sha256("b".repeat(64)), emptySet()), dump, null))
        bad(r.check(Op.Install(RemotePath(RemoteDir.TMP, listOf("x.apk")), Sha256("a".repeat(64)), PackageName("com.other.app"), 7, Sha256("b".repeat(64)), emptySet()), dump, null))
        ok(r.check(Op.DefaultState(launcher.pkg), dump, null))
        bad(r.check(Op.DefaultState(launcher.pkg), dump.replace("enabled=0", "enabled=3"), null))
        assertEquals(EnabledState.DISABLED_USER, PackageDump.enabledState(dump.replace("enabled=0", "enabled=3")))
        assertTrue(!PackageDump.isPersistent(dump))
        assertTrue(PackageDump.isPersistent(dump.replace("ALLOW_BACKUP ]", "ALLOW_BACKUP PERSISTENT ]")))
        assertTrue(PackageDump.isPersistent(dump.replace("flags=[", "pkgFlags=[ PERSISTENT")))
        assertTrue(!PackageDump.isPersistent(dump.replace("flags=[", "pkgFlags=[ NOTPERSISTENT")))
        assertTrue(!PackageDump.exists(launcher.pkg, ""))
        bad(r.check(Op.Install(RemotePath(RemoteDir.TMP, listOf("x.apk")), Sha256("a".repeat(64)), launcher.pkg, 7, Sha256("b".repeat(64)), emptySet()), "", null))
    }

    // RFC-SHOP-2 (2026-11-12): a component switch is verified against the state pm itself
    // prints back — "Package <pkg>/<cls> new state: disabled-user" — because the exit code is
    // no signal (pm prints the state it read back, which is not always the state asked for).
    // pm prints the component shortened (flattenToShortString), so a fully-qualified class reads
    // back as "pkg/.home.Foo"; a class already relative reads back as it is.
    @Test fun componentSwitchesReadTheStatePmPrintsBack() {
        val c = Component(PackageName("com.google.android.tvlauncher"),
            "com.google.android.tvlauncher.home.featuredcarousel.WhyThisAdActivity")
        ok(r.check(Op.DisableComponent(c), "Package com.google.android.tvlauncher/.home.featuredcarousel.WhyThisAdActivity new state: disabled-user\n", null))
        ok(r.check(Op.DisableComponent(c), "Package com.google.android.tvlauncher/.home.featuredcarousel.WhyThisAdActivity new state: disabled\n", null))
        bad(r.check(Op.DisableComponent(c), "Package com.google.android.tvlauncher/.home.featuredcarousel.WhyThisAdActivity new state: enabled\n", null))
        bad(r.check(Op.DisableComponent(c), "", null))
        ok(r.check(Op.EnableComponent(c), "Package com.google.android.tvlauncher/.home.featuredcarousel.WhyThisAdActivity new state: enabled\n", null))
        bad(r.check(Op.EnableComponent(c), "Package com.google.android.tvlauncher/.home.featuredcarousel.WhyThisAdActivity new state: disabled-user\n", null))
        // A state line for a different component does not verify this one.
        bad(r.check(Op.DisableComponent(c), "Package com.google.android.tvlauncher/.MainActivity new state: disabled-user\n", null))
        // A relative class prints as it is.
        val rel = Component(PackageName("com.vendor.launcher"), ".AdsBootReceiver")
        ok(r.check(Op.DisableComponent(rel), "Package com.vendor.launcher/.AdsBootReceiver new state: disabled-user\n", null))
    }

    @Test fun uninstallAndFilesAndTheRest() {
        val all = capture.packagesAllText
        bad(r.check(Op.Uninstall(PackageName("com.google.android.youtube.tv")), all, null))
        ok(r.check(Op.Uninstall(PackageName("com.gathin.tvhome")), all, null))
        val path = RemotePath(RemoteDir.OVERLAY, listOf("config.json"))
        val sha = Sha256("c".repeat(64))
        val blob = tv.cleanyour.core.model.BlobRef(sha, 10, "p")
        ok(r.check(Op.PushAsset(blob, path, sha), "${sha.hex}  ${path.absolute}\n", null))
        bad(r.check(Op.PushAsset(blob, path, sha), "${"d".repeat(64)}  ${path.absolute}\n", null))
        bad(r.check(Op.PushAsset(blob, path, sha), "", null))
        ok(r.check(Op.RemoveFile(path), "sha256sum: ${path.absolute}: No such file or directory\n", null))
        bad(r.check(Op.RemoveFile(path), "${sha.hex}  ${path.absolute}\n", null))
        for (op in listOf(Op.Mkdir(RemoteDir.TMP), Op.PressHome, Op.Reboot, Op.DebugOff, Op.Settle(1), GoldenCases.cue(tv.cleanyour.core.model.CurtainStage.FINALE), Op.RestartHome(launcher), Op.StartActivity(launcher, false), Op.Read(tv.cleanyour.core.model.ProbeKind.PROPS))) {
            ok(r.check(op, "anything", Prior()))
        }
    }

    // Launcher v2.4.0's precondition for the home screen switch: `am start -W` must say
    // "Status: ok" and print no Error line, or the TV's own home screen is left alone.
    @Test fun startAndWaitNeedsStatusOk() {
        val start = Op.StartActivity(launcher, wait = true)
        ok(r.check(start, "Starting: Intent { cmp=com.gathin.tvhome/.MainActivity }\nStatus: ok\nLaunchState: COLD\nComplete\n", null))
        ok(r.check(start, "Warning: Activity not started, its current task has been brought to the front\nStatus: ok\nComplete\n", null))
        bad(r.check(start, "Starting: Intent { cmp=com.gathin.tvhome/.MainActivity }\n", null))
        bad(r.check(start, "Starting: Intent { cmp=com.gathin.tvhome/.MainActivity }\nError type 3\nError: Activity class {com.gathin.tvhome/com.gathin.tvhome.MainActivity} does not exist.\n", null))
        bad(r.check(start, "Status: ok\nError: Activity not started, unable to resolve Intent\n", null))
        bad(r.check(start, "", null))
    }
}
