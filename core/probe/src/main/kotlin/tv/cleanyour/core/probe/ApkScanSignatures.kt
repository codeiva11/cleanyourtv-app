// RFC-SHOP-5 (2026-11-12, fork): the signature set the on-phone APK scan (ApkScanner)
// matches a TV's own APKs against. DATA, not per-brand code branches: the scanner
// detects what is installed and matches these signatures, so it adapts to any Android
// TV / Google TV company, model or architecture without a profile database. The
// signatures were derived by REA analysis of the public APKs below (2026-11-12, in the
// sandbox — rea-agents 6.7.0, apktool 3.0.3), and ONLY what REA actually found is
// listed; a signature derived from one APK is verified against the others before it
// lands here. The app's own rules (REA rules 1-4, deep scan, this scan) together cover
// firmware bloatware — what has no evidence or is safety-protected gets nothing.
//
// The APKs REA analysed (APKPure public versions, sizes verified 2026-11-12):
// com.google.android.tvlauncher 6.4.6-580192592-f (1010919201), 11,159,931 bytes:
//   shop.library.LibraryPageActivity + shop.tvodplayer.TvodPlayerActivity, the four
//   ad components of ReaEngine.TVLAUNCHER_AD_COMPONENTS, ad providers, pagead2 strings.
// com.google.android.apps.tv.launcherx 1.0.616930155 (446873), 34,945,738 bytes:
//   KinetoscopeTvodActivity, ads.controlsmenu.AdsControlsMenuActivity,
//   AdsSignalsContentProvider, "Why this ad?".
// com.google.android.tvrecommendations 4.1.8-478844784-f (1010904873), 6,706,673 bytes:
//   NotificationContentProvider, PromoSyncJobService, BootCompletedReceiver — 0 Shop
//   strings, no ad-network strings in dex (its ads run through the launcher's surfaces).
// com.amazon.tv.launcher 7251003.1 (725100310), 33,238,152 bytes (Fire OS launcher):
//   no Shop/tvod components; commerce is deep-link purchase activities
//   (VideoActionDialogActivityDI, VideoItemPurchaseActivityDI, PrimeFreeTrialActivity),
//   "SPONSORED" and "Featured" strings are present. Fire OS has no tvlauncher-style
//   Shop activities to disable, and ReaEngine.isCleanStockHomeEligible already returns
//   false for it — these signatures are evidence for the data file's breadth, not a
//   new Clean Stock Home path.
// com.google.android.videos: no plain APK exists publicly (XAPK only) and its split
// base APK is not decodable — no signatures are claimed from it here.

package tv.cleanyour.core.probe

import tv.cleanyour.core.model.ApkScanIds

object ApkScanSignatures {
    // A manifest component whose fully-resolved class is one of these is a Shop
    // activity — REA-proven in tvlauncher 6.4.6 (manifest + Phenotype registration).
    // The hardcoded ReaEngine.TVLAUNCHER_SHOP_COMPONENTS stays as the fallback; the
    // scan finds the same two on any tvlauncher version that keeps them, and the
    // launcherx Kinetoscope TVOD activities on any launcherx that keeps them.
    val EXACT_SHOP_COMPONENTS: Set<String> = setOf(
        "com.google.android.tvlauncher.shop.library.LibraryPageActivity",
        "com.google.android.tvlauncher.shop.tvodplayer.TvodPlayerActivity",
        "com.google.android.apps.tv.launcherx.entity.asset.tvod.kinetoscope.KinetoscopeTvodActivity",
        "com.google.android.apps.tv.launcherx.entity.asset.tvod.kinetoscope.KinetoscopeTvodKidsModeActivity",
    )

    // REA-proven ad components: tvlauncher's
    // home.featuredcarousel.WhyThisAdActivity,
    // home.AdsPersonalizationAwarenessDialogActivity,
    // targetedpromotions.RedeemTargetedPromotionActivity and
    // home.AdsPersonalizationAwarenessNotificationReceiver (these are the same four
    // class names ReaEngine.TVLAUNCHER_AD_COMPONENTS hardcodes — the scanner finds
    // them on any tvlauncher version that keeps them, without the hardcode);
    // launcherx's AdsControlsMenuActivity, PromotionVoucherActivity and the YouTube
    // promotion dialog/receiver. The launcherx receiver's manifest class is
    // UlpPromotionBroadcastReceiver_Receiver, not the bare name.
    val EXACT_AD_COMPONENTS: Set<String> = setOf(
        "com.google.android.tvlauncher.home.featuredcarousel.WhyThisAdActivity",
        "com.google.android.tvlauncher.home.AdsPersonalizationAwarenessDialogActivity",
        "com.google.android.tvlauncher.targetedpromotions.RedeemTargetedPromotionActivity",
        "com.google.android.tvlauncher.home.AdsPersonalizationAwarenessNotificationReceiver",
        "com.google.android.apps.tv.launcherx.ads.controlsmenu.AdsControlsMenuActivity",
        "com.google.android.apps.tv.launcherx.promotionvoucher.ui.PromotionVoucherActivity",
        "com.google.android.apps.tv.launcherx.kids.errorui.promotion.UlpPromotionForYoutubeDialogActivity",
        "com.google.android.apps.tv.launcherx.kids.errorui.promotion.UlpPromotionBroadcastReceiver_Receiver",
    )

    // REA-proven ad-signal providers: launcherx's AdsSignalsContentProvider (authority
    // "com.google.android.apps.tv.launcherx.ads.signals.AdsSignalsContentProvider");
    // tvrecommendations' NotificationContentProvider (its NotificationContentProvider
    // authority also serves the android.tv notifications authority).
    val EXACT_AD_PROVIDERS: Set<String> = setOf(
        "com.google.android.apps.tv.launcherx.ads.signals.AdsSignalsContentProvider",
        "com.google.android.tvrecommendations.NotificationContentProvider",
    )

    // A provider whose authority contains one of these serves ad signals — the
    // authority is what the scan reads from the manifest, not the class name.
    val AD_PROVIDER_AUTHORITY_SUBSTRINGS: List<String> = listOf("adssignals", "ads.signals")

    // A manifest component whose intent-filter data host is one of these opens a Shop
    // page — tvlauncher's LibraryPageActivity registers tv.google.com/library
    // (http/https). A deep link alone is strong evidence: a non-Shop app has no reason
    // to handle the Shop's library URL.
    val SHOP_DEEP_LINK_HOSTS: Set<String> = setOf("tv.google.com")

    // A dex or resources string naming one of these ad networks is strong evidence
    // the APK serves ads: the pagead2 gen_204 beacon (tvlauncher dex, 5 hits),
    // doubleclick.net and googlesyndication.com. A string alone nominates the PACKAGE
    // (section 2 / a REA group) — never a component.
    val STRONG_AD_STRINGS: List<String> = listOf(
        "pagead2.googlesyndication.com",
        "doubleclick.net",
        "googlesyndication.com",
    )

    // Corroborating strings: worth recording, never nominating on their own. "shop"
    // and "tvod" name the Shop tab; "sponsored" and the "Why this ad?" /
    // "Ad Personalization" / "targeted promotion" rows are its UI; "kinetoscope" is
    // launcherx's TVOD engine; the two Amazon hosts are the Fire OS launcher's
    // config/ad endpoints (decompile evidence, not a Shop lever).
    val CORROBORATING_STRINGS: List<String> = listOf(
        "shop",
        "tvod",
        "sponsored",
        "featured carousel",
        "why this ad",
        "ad personalization",
        "targeted promotion",
        "customize your recommendations",
        "buy or rent",
        "kinetoscope",
        "atv-ext.amazon.com",
        "api.amazonvideo.com",
    )

    // Manifest-component substrings, each with the planner group it feeds. This is the
    // same list as ReaEngine.AD_COMPONENT_SIGNATURES, extended with the Fire OS
    // commerce substrings REA found (purchase, subscription, primefreetrial, primesignup)
    // and the telemetry / vendor-bloat substrings (the facts table's telemetry and
    // vendor lists are the same families). The scanner matches them at a word
    // boundary in the class name, so "ads" does not fire on "Downloads" and "shop"
    // does not fire on "Workshop" — a plain contains() would nominate innocent apps.
    val COMPONENT_SIGNATURES: List<Pair<String, String>> = listOf(
        "ads" to "ads",
        "adssignals" to "ads",
        "sponsor" to "ads",
        "banner" to "ads",
        "promos" to "ads",
        "promotion" to "ads",
        "recommend" to "ads",
        "featuredcarousel" to "ads",
        "infeed" to "ads",
        "instream" to "ads",
        "targetedpromotion" to "ads",
        "adpersonalization" to "ads",
        "doubleclick" to "ads",
        "advertis" to "ads",
        "commercial" to "ads",
        "freepay" to "ads",
        "whythisad" to "ads",
        "shop" to "ads",
        "tvod" to "ads",
        "purchase" to "ads",
        "subscription" to "ads",
        "primefreetrial" to "ads",
        "primesignup" to "ads",
        "telemetry" to "telemetry",
        "analytics" to "telemetry",
        "tracking" to "telemetry",
        "metrics" to "telemetry",
        "usagestats" to "telemetry",
        "crashlog" to "telemetry",
        "partnercustomizer" to "bloat",
        "customizer" to "bloat",
        "demonow" to "bloat",
        "demomode" to "bloat",
    )

    // Which substring kind a matched component signature is: Shop activities are the
    // pages themselves; telemetry and vendor-bloat substrings are their own kind; the
    // rest are ad components.
    private val SHOP_SUBSTRINGS = setOf("shop", "tvod", "purchase", "subscription", "primefreetrial", "primesignup")
    private val TELEMETRY_SUBSTRINGS = setOf("telemetry", "analytics", "tracking", "metrics", "usagestats", "crashlog")
    private val BLOAT_SUBSTRINGS = setOf("partnercustomizer", "customizer", "demonow", "demomode")

    fun signatureId(sig: String): String = when (sig) {
        in SHOP_SUBSTRINGS -> ApkScanIds.SHOP_ACTIVITY
        in TELEMETRY_SUBSTRINGS -> ApkScanIds.TELEMETRY_COMPONENT
        in BLOAT_SUBSTRINGS -> ApkScanIds.BLOAT_COMPONENT
        else -> ApkScanIds.AD_ACTIVITY
    }

    // Package-name substrings that mark a suspect as a launcher, so the scan pulls
    // launcher APKs first (the Shop tab lives in one, whatever its brand).
    val APK_SCAN_TARGET_SUBSTRINGS: List<String> = listOf("launcher", "leanback", "patchwall")

    // Bounds. The scan pulls at most MAX_APK_SCANS APKs, launchers first; an APK over
    // MAX_APK_BYTES is skipped as evidence-absent (launcherx is 35 MB, Fire OS 33 MB,
    // tvlauncher 11 MB — 40 MB covers every launcher REA found). Only the base APK is
    // copied (ProbeScripts.copyApk copies base.apk only, as the signer copy does).
    const val MAX_APK_BYTES = 40L * 1024 * 1024
    const val MAX_APK_SCANS = 4
    const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024
    const val MAX_ARSC_BYTES = 16L * 1024 * 1024
    const val MAX_DEX_BYTES = 16L * 1024 * 1024
    const val MAX_DEX_FILES = 8
    const val MAX_MATCHED_STRINGS = 50
    const val MAX_DEX_STRINGS = 300_000
    const val MAX_ARSC_STRINGS = 120_000 // launcherx's global pool is 84,868 strings
    const val MAX_MATCHES_PER_COMPONENT = 4
    const val MAX_XML_COMPONENTS = 2_000
    const val MAX_TOTAL_MATCHES = 500
}
