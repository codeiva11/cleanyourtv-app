// RFC-DEEP-1 (2026-11-12, fork): the REA deep scan's result. The prober reads, over wireless
// ADB, what the static snapshot cannot see: which packages wake at boot, keep scheduled jobs,
// expose content providers, and what certificate signed them. The planner nominates a package
// from this only when the evidence shows it can bring ads back after a reboot (a BOOT_COMPLETED
// receiver, a scheduled job, or a provider feeding the launcher) — a package with no evidence
// here is never nominated by the scan, however its name looks. Best-effort throughout: a section
// a firmware cannot answer is empty, never a failure of the scan or of Setup.

package tv.cleanyour.core.model

data class DeepScanEvidence(
    val bootReceivers: List<String> = emptyList(),
    val providers: List<String> = emptyList(),
    val jobs: Int = 0,
    val signatureHex: String? = null,
) {
    val hasRevivalEvidence: Boolean get() = bootReceivers.isNotEmpty() || providers.isNotEmpty() || jobs > 0
}

data class DeepScanResult(
    val scannedAtMs: Long,
    val evidence: Map<PackageName, DeepScanEvidence> = emptyMap(),
) {
    val isEmpty: Boolean get() = evidence.isEmpty()
}
