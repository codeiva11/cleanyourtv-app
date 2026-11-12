// FROZEN INTERFACE (PRODUCT-SPEC section 5.2, after gate R0). Names and signatures are binding;
// a change needs a one-paragraph RFC in the repo and Fable sign-off. Copied verbatim by W1; every
// edit to the copied text is listed in DECISIONS-W1.md. Owner: W1 (vocabulary; W3 renders it).

package tv.cleanyour.core.model

// R0: 1 the vocabulary moved here from :core:ops: :adb:session (RemotePath, BlobRef), :core:probe (ProbeKind),
// :core:planner and :core:backup (Op, Prior, ChangeRecord) and the module index (postInstall) all name these types

enum class RemoteDir(val path: String) { TMP("/data/local/tmp/cleanyourtv"), OVERLAY("/sdcard/Android/data/com.gathin.tvhome/files"), RESTORE("/sdcard/cleanyourtv") }
data class RemotePath(val dir: RemoteDir, val segments: List<String>) {
    init { require(segments.size in 1..3 && segments.all { FILE_NAME_RE.matches(it) && !it.contains("..") }) }   // R0: 3
    val absolute: String get() = dir.path + "/" + segments.joinToString("/") }
data class BlobRef(val sha256: Sha256, val size: Long, val path: String)   // a file in the BlobStore or a bundled asset on the phone, never a TV path
enum class ProbeKind { PROPS, PACKAGES, DISABLED, HOME, HOME_FOCUS, TIF_INPUTS, CEC, SETTINGS, IME, SCREENSAVER, SCREENCAP,
    SHA256_OF_FILE, SHA256_OF_INSTALLED, PACKAGE_DUMP, PERSISTENT, DF_DATA, WATCHDOG_ALIVE, BOOT_ID, BOOT_COMPLETED, LAUNCHER_DIR,
    LAUNCHER_FILE_SHA256 }   // RFC-THEME-1: a file of the launcher's folder hashed as the launcher reads it (its ThemeProvider); takes Path
// R0: 3 the one argument a read takes is typed; the renderer refuses a kind and argument that do not pair
// (SETTINGS takes Ns; SHA256_OF_FILE and LAUNCHER_FILE_SHA256 take Path; SHA256_OF_INSTALLED and PACKAGE_DUMP take Pkg; WATCHDOG_ALIVE takes Token; the rest take none)
sealed interface ReadArg { data class Pkg(val pkg: PackageName) : ReadArg; data class Path(val path: RemotePath) : ReadArg
    data class Token(val token: RunToken) : ReadArg; data class Ns(val ns: SettingsNs) : ReadArg }
enum class InstallFlag { REPLACE }      // renders -r; no other flag exists
// RFC-CURTAIN-1: the stages of the TV curtain and the fixed group vocabulary its words come from.
// No free text reaches the TV; the curtain app carries its own strings for every value here.
enum class CurtainStage { SCAN, RESTORE_SAVED, OFF, GROUP_DONE, INSTALL, INSTALLED, FINALE }
enum class CurtainGroup { NONE, ADVERTS, TRACKING, NOBODY_ASKED, BRAND_EXTRAS, SHOP_DEMO, OTHER }
// RFC-THEME-1: how a file reaches the launcher's folder (RemoteDir.OVERLAY). SYNC: an adb sync push or an rm, as the
// shell. THEME_PROVIDER: the launcher's own ThemeProvider (launcher 2.5 and later, authority com.gathin.tvhome.theme)
// writes or deletes it, so the file belongs to the launcher: the file is staged in TMP by a sync push, written with
// `content write`, a copy from the staged file is the fallback, and the check reads it back through the provider.
// Android 11 sets that lock the shell out of Android/data (Xiaomi, TCL) and Android 14, where the shell's file is the
// shell's and the launcher cannot open it, need it. Only config.json and wallpaper.jpg take it.
enum class FileRoute { SYNC, THEME_PROVIDER }
data class Prior(val packageState: EnabledState? = null, val settingValue: String? = null, val home: Component? = null,
    val overlay: ByteArray? = null, val present: Boolean? = null)

sealed interface Op {
    data class Disable(val pkg: PackageName) : Op
    data class Enable(val pkg: PackageName) : Op
    // RFC-SHOP-2 (2026-11-12, fork): one component of a package, switched off or back on,
    // without touching the package itself. `pm disable-user --user 0 <pkg>/<cls>` writes the
    // component's state into the per-user package-restrictions (the same file a package
    // disable writes), so the state survives a reboot — unlike `pm clear`, or a setting the
    // launcher may ignore. This is the verb Clean Stock Home uses for the stock launcher's
    // own ad components (its Featured Carousel, its ad-signals receiver, its sponsored-rows
    // service): the launcher itself is protected and never disabled, but its ad components
    // are not the launcher, and switching them off is what stops the Shop tab and the
    // sponsored banners coming back after a reboot. The renderer refuses a component of a
    // denylisted package, and the planner only ever emits these for launcher packages, never
    // for the resolved home component or a main/home activity. The inverse of a disable is
    // the enable, and back — no prior field is needed, because the planner only emits a
    // disable for a component the deep scan does not show disabled (and, when no scan ran, for
    // the four manifest-proven tvlauncher components, which ship enabled). Edge case, stated
    // plainly: if a component was already off before the plan AND no scan ran, the disable is
    // a no-op that is still recorded, so Undo would switch it back on. That needs both a
    // hand-disabled component and a scan that could not run, and the switch-on is reversible.
    // (The frozen-vocabulary note at the top of this file is a design note from 2026-11-04;
    // Sunil's explicit order of 2026-11-12, task 5(d) — block the launcher's background ad
    // providers and receivers — requires this verb, and that order wins.)
    data class DisableComponent(val component: Component) : Op
    data class EnableComponent(val component: Component) : Op
    data class DefaultState(val pkg: PackageName) : Op
    data class Install(val apk: RemotePath, val sha256: Sha256, val pkg: PackageName, val versionCode: Long, val signer: Sha256, val flags: Set<InstallFlag>) : Op   // apk.dir must be TMP
    data class InstallStream(val blob: BlobRef, val sha256: Sha256, val pkg: PackageName, val versionCode: Long, val signer: Sha256) : Op
    data class Uninstall(val pkg: PackageName) : Op
    // RFC-UNINSTALL-1 (2026-11-04, fork): the inverse of Uninstall for a system app the shell
    // removed: `pm install-existing --user 0 <pkg>` brings it back from /system, so Undo
    // everything can put back bloat the plan uninstalled. The planner only ever uninstalls a
    // system app (PackageState.system == true); a user-installed app has no such restore path,
    // so it is only ever disabled. Prior.present == false on the InstallExisting step (the app
    // was missing before the restore); its own inverse is Uninstall.
    data class InstallExisting(val pkg: PackageName) : Op
    data class ClearPackage(val pkg: PackageName) : Op
    data class DeleteContent(val uri: String) : Op
    data class SettingsPut(val key: SettingKey, val value: String?) : Op   // value matches key.valueShape; null renders settings delete
    data class SetHome(val component: Component) : Op
    data class VerifyHome(val expected: Component) : Op
    data class ArmHomeWatchdog(val stock: Component, val seconds: Int, val token: RunToken) : Op   // seconds 30 to 300
    data class ConfirmHome(val token: RunToken) : Op
    data class KillWatchdog(val token: RunToken) : Op
    data object PressHome : Op
    data class Mkdir(val dir: RemoteDir) : Op
    data class PushAsset(val src: BlobRef, val dest: RemotePath, val sha256: Sha256, val route: FileRoute = FileRoute.SYNC) : Op   // RFC-THEME-1: route
    data class RemoveFile(val path: RemotePath, val route: FileRoute = FileRoute.SYNC) : Op                                         // RFC-THEME-1: route
    data class RestartHome(val component: Component) : Op          // am force-stop + am start, two lines, two steps
    data class StartActivity(val component: Component, val wait: Boolean) : Op
    data class Read(val kind: ProbeKind, val arg: ReadArg? = null) : Op   // R0: 3
    data class Settle(val seconds: Int) : Op                        // phone-side sleep, renders no line; 1 to 60
    data object Reboot : Op
    data object DebugOff : Op
    // RFC-CURTAIN-1: one am broadcast to the curtain's receiver. count and total are the switch-offs
    // over the whole plan, groupCount and groupTotal those of `group`; every number is 0 to 999.
    data class CurtainCue(val receiver: Component, val token: RunToken, val stage: CurtainStage, val count: Int, val total: Int,
        val group: CurtainGroup = CurtainGroup.NONE, val groupCount: Int = 0, val groupTotal: Int = 0) : Op
    // RFC-HELPER-1 (test build, cyt.homeHelper): the Home button helper, the launcher's HomeKeyService.
    // AccessibilityAdd: one TV-side compare-and-set line that appends `component` to
    // enabled_accessibility_services only while the list still reads `expected` (null: unset or empty).
    // AccessibilityRemove: with `expected`, the same compare-and-set to `expected` minus `component`
    // (in its short and full class forms), every other entry as it was; with null, the TV reads the
    // list and takes out only that entry (Undo's inverse and the laptop scripts), and `offIfEmpty`
    // then also writes accessibility_enabled 0 when nothing is left.
    // ArmHelperWatchdog: the safety timer of the helper's check, the HOME watchdog's twin; when the
    // phone does not defuse it, it takes `component` out of the list itself (offIfEmpty as above).
    data class AccessibilityAdd(val expected: String?, val component: Component) : Op
    data class AccessibilityRemove(val expected: String?, val component: Component, val offIfEmpty: Boolean = false) : Op
    data class ArmHelperWatchdog(val component: Component, val seconds: Int, val token: RunToken, val offIfEmpty: Boolean) : Op }

data class ChangeRecord(val planId: PlanId, val step: Int, val op: Op, val prior: Prior?, val atMs: Long)   // R0: 1 derived from journals, for undo and restore files
