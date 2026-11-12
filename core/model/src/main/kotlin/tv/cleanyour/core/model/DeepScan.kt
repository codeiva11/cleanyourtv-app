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
    // RFC-SHOP-2: the dump's Services: and Activities: sections, and the components the
    // per-user state already has switched off (the "User 0:" block's disabledComponents:
    // list). The planner reads these only for the launcher packages: they are the pool its
    // ad-component filter runs over, and the already-off list is what keeps Undo from
    // switching on a component that was off before the plan. They are not revival evidence
    // on their own — a service or activity without a boot receiver, a job or a provider
    // cannot bring ads back by itself.
    val services: List<String> = emptyList(),
    val activities: List<String> = emptyList(),
    val disabledComponents: List<Component> = emptyList(),
    // RFC-SHOP-5: what the on-phone APK scan found inside this package's own APK
    // (ApkScanFinding) — manifest components, provider authorities, dex/arsc
    // strings matched against the REA-derived signatures. Null when the APK was
    // not pulled or the scan found nothing: evidence-absent, never an error.
    val apkScan: ApkScanFinding? = null,
) {
    val hasRevivalEvidence: Boolean get() = bootReceivers.isNotEmpty() || providers.isNotEmpty() || jobs > 0
}

data class DeepScanResult(
    val scannedAtMs: Long,
    val evidence: Map<PackageName, DeepScanEvidence> = emptyMap(),
) {
    val isEmpty: Boolean get() = evidence.isEmpty()
}
