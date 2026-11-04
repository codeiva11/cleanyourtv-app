// FROZEN INTERFACE (PRODUCT-SPEC section 5.8, after gate R0). Names and signatures are binding;
// a change needs a one-paragraph RFC in the repo and Fable sign-off. Copied verbatim by W1; every
// edit to the copied text is listed in DECISIONS-W1.md. Owner: W3.

package tv.cleanyour.core.planner

import tv.cleanyour.core.model.*
import tv.cleanyour.core.ops.FailurePolicy
import tv.cleanyour.core.ops.Rendered
import tv.cleanyour.core.spec.FactsTable
import tv.cleanyour.core.spec.ProfileResolution

// Stock-only build (2026-11-04): the home screen switch is gone, so there is no launcher to
// install, no look or dock to send, no curtain to show and no helper. The only choices left
// are what to switch off, whether debugging stays on, and the REA / Clean Stock Home options.
data class Choices(val kept: Set<PackageName>, val extraOff: Set<PackageName>, val keepDebuggingOn: Boolean,
    val cleanStockHome: Boolean = false,   // Option A: Clean Stock Home (Ad-Free, 0-Risk Google UI)
    val autoDebloat: Boolean = false)   // REA Autonomous Deep RAM Debloat
enum class PlanKind { SETUP, PUT_BACK, SWITCH_OFF, LEFTOVERS, ADD_MODULE, REMOVE_MODULE, USE_STOCK_HOME,
    UNDO_EVERYTHING, BEFORE_FACTORY_RESET, DEBUG_OFF, WRITE_RESTORE_POINT, REBOOT }   // R0: 11 (was kind: String); REBOOT: RFC-W8-5
sealed interface PlanRequest {
    data class Setup(val resolution: ProfileResolution, val choices: Choices) : PlanRequest
    data class PutBack(val pkg: PackageName) : PlanRequest
    data class SwitchOff(val pkgs: Set<PackageName>) : PlanRequest
    data class Leftovers(val expectedDisabled: Set<PackageName>) : PlanRequest
    data class AddModule(val module: Module, val version: ModuleVersion, val apk: BlobRef) : PlanRequest   // R0: 14 renamed from Module
    data class RemoveModule(val pkg: PackageName) : PlanRequest
    data class UseStockHome(val stock: Component) : PlanRequest
    data class UndoEverything(val changes: List<ChangeRecord>, val removeLauncher: Boolean) : PlanRequest
    data object BeforeFactoryReset : PlanRequest
    data object DebugOff : PlanRequest
    data class WriteRestorePoint(val restorePointJson: BlobRef, val restoreTxt: BlobRef) : PlanRequest   // R0: 11 D44; pushes only, runs after every other plan
    data object Reboot : PlanRequest }   // RFC-W8-5: the D24 restart test, journaled like any other write, not gated (D17)
// R0: 9 the engine runs a section from this data alone: the live check compares homeFocus with expectedHome and rolls back
// when the focused package is in rollbackIfFocus; rollback is section 3.4 step R, rendered at run time
data class GuardedSection(val name: String, val from: Int, val to: Int, val token: RunToken, val confirmWindowMs: Long,
    val expectedHome: Component, val rollbackIfFocus: Set<PackageName>, val rollback: List<Op>)
data class Step(val index: Int, val op: Op, val rendered: Rendered, val prior: Prior?, val group: String?, val narration: String, val onFailure: FailurePolicy)
data class Plan(val id: PlanId, val tvId: TvId, val kind: PlanKind, val baseSnapshot: Sha256, val steps: List<Step>,
    val sections: List<GuardedSection>, val summary: PlanSummary, val hash: Sha256, val createdAtMs: Long, val appVersion: String)
data class PlanSummary(val groups: List<GroupSummary>, val keepNames: List<String>)
data class GroupSummary(val id: String, val title: String, val off: Int, val total: Int, val rows: List<RowSummary>)
data class RowSummary(val pkg: PackageName, val friendlyName: String, val description: String, val off: Boolean, val protected: Boolean, val whyProtected: String?)
sealed interface PlanResult { data class Ok(val plan: Plan) : PlanResult; data class Refused(val reasons: List<String>) : PlanResult }
interface Planner { fun plan(request: PlanRequest, snapshot: Snapshot, facts: FactsTable, journals: List<ChangeRecord>): PlanResult }
// pure; the implementation takes Clock, IdGen and OpRenderer in its constructor (R0: 15); Protected lives in :core:ops (R0: 1)
