package tv.cleanyour.core.planner

import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName

// What S3 counts: the numeral, the group rows of the summary card and "n of m off" in the list all
// come from these, so the screen and the plan cannot drift apart (emulator run, round 3). A row is
// off exactly when the plan switches something off for its package — a Disable step, or a
// DisableComponent step for one of its components (RFC-SHOP-4: the launcher's Shop/ad
// components are rows of their launcher's package, so the Found screen counts them).

// The numeral and headline on S3 ("We found 23 things to switch off").
val PlanSummary.switchOffCount: Int get() = groups.sumOf { it.off }

// The packages the list shows as "Off", in list order.
val PlanSummary.switchOffPackages: List<PackageName> get() = groups.flatMap { g -> g.rows.filter { it.off }.map { it.pkg } }

// The packages the plan switches off, in step order.
val Plan.disabledPackages: List<PackageName> get() = steps.mapNotNull { (it.op as? Op.Disable)?.pkg }
