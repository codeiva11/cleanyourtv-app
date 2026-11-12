// RFC-SHOP-5 (2026-11-12, fork): the on-phone APK scanner's tests. The fixtures are
// hand-built binary AXML / resources.arsc / dex blobs — small, deterministic, and
// shaped on the real tvlauncher v6.4.6 manifest REA decoded (the same class names,
// the same intent-filter deep link, the same pagead2 string) — so a parser that
// cannot read a real APK fails here first, on the phone's behalf, in CI.

package tv.cleanyour.core.probe

import tv.cleanyour.core.model.ApkScanIds
import tv.cleanyour.core.model.PackageName
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApkScannerTest {
    private fun pkg(s: String) = PackageName(s)

    // A realistic launcher APK: the tvlauncher v6.4.6 manifest subset REA decoded
    // (relative class names, as aapt writes them), its pagead2 string in both the
    // resources pool and a dex string table, and a native lib entry.
    @Test fun scansARealisticLauncherApk() {
        val apk = ApkFixtures.launcherApk()
        val finding = ApkScanner.scan(apk, pkg("com.google.android.tvlauncher"), 1000L)
        assertNotNull(finding)
        assertEquals(apk.size.toLong(), finding.apkSizeBytes)
        assertEquals(1010919201L, finding.versionCode)
        val classes = finding.components.map { it.component.cls }
        assertTrue("com.google.android.tvlauncher.shop.library.LibraryPageActivity" in classes)
        assertTrue("com.google.android.tvlauncher.shop.tvodplayer.TvodPlayerActivity" in classes)
        assertTrue("com.google.android.tvlauncher.home.AdsPersonalizationAwarenessDialogActivity" in classes)
        assertTrue("com.google.android.tvlauncher.ads.signals.TestAdsSignalsContentProvider" in classes)
        // The home entry point and the "ads" inside "Downloads" are never nominated.
        assertFalse(classes.any { "MainActivity" in it })
        assertFalse(classes.any { "DownloadsActivity" in it })
        val shop = finding.components.single { it.component.cls.endsWith("LibraryPageActivity") }
        assertTrue(shop.matches.any { it.signatureId == ApkScanIds.SHOP_ACTIVITY })
        assertTrue(shop.matches.any { it.signatureId == ApkScanIds.SHOP_DEEP_LINK })
        assertTrue(finding.matchedStrings.any { "pagead2.googlesyndication.com" in it })
        assertTrue(finding.hasStrongAdEvidence)
        assertEquals("ads", finding.strongestCategory)
        assertTrue("libfoo.so" in finding.nativeLibs)
        assertFalse(finding.stringsTruncated)
        assertEquals(1000L, finding.scannedAtMs)
    }

    // A manifest with nothing ad-like, and no arsc or dex at all: a finding exists
    // (the APK scanned), but with no strong evidence — the planner never nominates
    // on it.
    @Test fun manifestWithoutShopFindsNothingStrong() {
        val apk = ApkFixtures.plainApk()
        val finding = ApkScanner.scan(apk, pkg("com.example.plain"), 1000L)
        assertNotNull(finding)
        assertTrue(finding.components.isEmpty())
        assertFalse(finding.hasStrongAdEvidence)
        assertNull(finding.strongestCategory)
    }

    // A manifest alone is enough: arsc and dex are best-effort, their absence is
    // not a failure.
    @Test fun missingArscAndDexStillScansManifest() {
        val apk = ApkFixtures.manifestOnlyApk()
        val finding = ApkScanner.scan(apk, pkg("com.google.android.tvlauncher"), 1000L)
        assertNotNull(finding)
        assertTrue(finding.components.any { it.component.cls.endsWith("LibraryPageActivity") })
    }

    // Garbage in, null out — never a crash, whatever the APK contains.
    @Test fun corruptApkReturnsNull() {
        assertNull(ApkScanner.scan("not a zip at all".toByteArray(), pkg("com.example.x"), 0L))
        assertNull(ApkScanner.scan(byteArrayOf(0x50, 0x4B, 0x03, 0x04), pkg("com.example.x"), 0L))
        assertNull(ApkScanner.scan(byteArrayOf(), pkg("com.example.x"), 0L))
        val zipWithBadManifest = ApkFixtures.zip(mapOf("AndroidManifest.xml" to "garbage".toByteArray()))
        assertNull(ApkScanner.scan(zipWithBadManifest, pkg("com.example.x"), 0L))
    }

    @Test fun oversizedApkReturnsNull() {
        val big = ByteArray((ApkScanSignatures.MAX_APK_BYTES + 1).toInt())
        assertNull(ApkScanner.scan(big, pkg("com.example.x"), 0L))
    }

    // The word-boundary rule: "ads" fires on AdsActivity, not on DownloadsActivity;
    // "shop" fires on ShopActivity, not on WorkshopActivity.
    @Test fun wordBoundaryMatching() {
        assertTrue(ApkScanner.matchesAtWordBoundary("com.example.app.AdsActivity", "ads"))
        assertFalse(ApkScanner.matchesAtWordBoundary("com.example.app.DownloadsActivity", "ads"))
        assertTrue(ApkScanner.matchesAtWordBoundary("com.example.app.ShopActivity", "shop"))
        assertFalse(ApkScanner.matchesAtWordBoundary("com.example.app.WorkshopActivity", "shop"))
        assertTrue(ApkScanner.matchesAtWordBoundary("com.example.app.ads.controls.Foo", "ads"))
        assertTrue(ApkScanner.matchesAtWordBoundary("com.example.app.shop.library.Foo", "shop"))
        assertTrue(ApkScanner.matchesAtWordBoundary("com.example.app.LauncherCustomizer", "customizer"))
        assertFalse(ApkScanner.matchesAtWordBoundary("com.example.app.CustomActivity", "customizer"))
    }

    // Relative class names resolve against the manifest's package, as aapt writes them.
    @Test fun relativeClassNamesResolve() {
        val apk = ApkFixtures.relativeNameApk()
        val finding = ApkScanner.scan(apk, pkg("com.test.launcher"), 1000L)
        assertNotNull(finding)
        assertTrue(finding.components.any { it.component.cls == "com.test.launcher.shop.ShopActivity" })
        assertTrue(finding.components.any { it.component.cls == "com.test.launcher.ShopActivity" })
    }
}

// --- the fixtures ------------------------------------------------------------

// A little-endian writer for the binary blobs below.
private class LeWriter {
    private val out = ByteArrayOutputStream()
    fun u16(v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }

    fun i32(v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }

    fun bytes(b: ByteArray) = out.write(b)
    fun toByteArray(): ByteArray = out.toByteArray()
}

private data class FixtureAttr(val ns: String?, val name: String, val type: Int, val data: Int, val str: String?)
private data class FixtureElement(val name: String, val attrs: List<FixtureAttr> = emptyList(), val children: List<FixtureElement> = emptyList())

internal object ApkFixtures {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT = 0x10

    private fun strAttr(name: String, value: String) = FixtureAttr(ANDROID_NS, name, TYPE_STRING, 0, value)
    private fun intAttr(name: String, value: Int) = FixtureAttr(ANDROID_NS, name, TYPE_INT, value, null)
    private fun plainAttr(name: String, value: String) = FixtureAttr(null, name, TYPE_STRING, 0, value)

    fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // The tvlauncher v6.4.6 manifest subset REA decoded: both Shop activities, one
    // ad component, an ad-signal provider, the home entry point, and a Downloads
    // activity (the word-boundary trap).
    fun launcherApk(): ByteArray {
        val manifest = axml(
            FixtureElement(
                "manifest",
                listOf(plainAttr("package", "com.google.android.tvlauncher"), intAttr("versionCode", 1010919201)),
                listOf(
                    FixtureElement(
                        "activity",
                        listOf(strAttr("name", ".shop.library.LibraryPageActivity")),
                        listOf(
                            FixtureElement(
                                "intent-filter",
                                emptyList(),
                                listOf(
                                    FixtureElement("action", listOf(strAttr("name", "android.intent.action.VIEW"))),
                                    FixtureElement(
                                        "data",
                                        listOf(
                                            strAttr("scheme", "https"),
                                            strAttr("host", "tv.google.com"),
                                            strAttr("pathPrefix", "/library"),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    FixtureElement("activity", listOf(strAttr("name", ".shop.tvodplayer.TvodPlayerActivity"))),
                    FixtureElement("activity", listOf(strAttr("name", ".home.AdsPersonalizationAwarenessDialogActivity"))),
                    FixtureElement(
                        "provider",
                        listOf(
                            strAttr("name", ".ads.signals.TestAdsSignalsContentProvider"),
                            strAttr("authorities", "com.google.android.tvlauncher.ads.signals.AdsSignalsContentProvider"),
                        ),
                    ),
                    FixtureElement("activity", listOf(strAttr("name", ".MainActivity"))),
                    FixtureElement("activity", listOf(strAttr("name", ".DownloadsActivity"))),
                ),
            ),
        )
        val arsc = arsc(listOf("Shop", "Welcome to Shop", "https://pagead2.googlesyndication.com/pagead/gen_204", "hello world"))
        val dex = dex(
            listOf(
                "Lcom/google/android/tvlauncher/shop/library/LibraryPageActivity;",
                "https://pagead2.googlesyndication.com/pagead/gen_204",
                "com.example.DownloadsManager",
            ),
            listOf(0),
        )
        return zip(
            mapOf(
                "AndroidManifest.xml" to manifest,
                "resources.arsc" to arsc,
                "classes.dex" to dex,
                "lib/armeabi-v7a/libfoo.so" to byteArrayOf(0x7F, 0x45, 0x4C, 0x46),
            ),
        )
    }

    fun manifestOnlyApk(): ByteArray {
        val manifest = axml(
            FixtureElement(
                "manifest",
                listOf(plainAttr("package", "com.google.android.tvlauncher"), intAttr("versionCode", 1010919201)),
                listOf(FixtureElement("activity", listOf(strAttr("name", ".shop.library.LibraryPageActivity")))),
            ),
        )
        return zip(mapOf("AndroidManifest.xml" to manifest))
    }

    fun plainApk(): ByteArray {
        val manifest = axml(
            FixtureElement(
                "manifest",
                listOf(plainAttr("package", "com.example.plain"), intAttr("versionCode", 1)),
                listOf(FixtureElement("activity", listOf(strAttr("name", ".MainActivity")))),
            ),
        )
        val arsc = arsc(listOf("hello world"))
        return zip(mapOf("AndroidManifest.xml" to manifest, "resources.arsc" to arsc))
    }

    fun relativeNameApk(): ByteArray {
        val manifest = axml(
            FixtureElement(
                "manifest",
                listOf(plainAttr("package", "com.test.launcher"), intAttr("versionCode", 1)),
                listOf(
                    FixtureElement("activity", listOf(strAttr("name", ".shop.ShopActivity"))),
                    FixtureElement("activity", listOf(strAttr("name", "ShopActivity"))),
                ),
            ),
        )
        return zip(mapOf("AndroidManifest.xml" to manifest))
    }

    // --- the binary builders --------------------------------------------------

    // A binary string pool (UTF-16, flags = 0) holding [strings] in order.
    private fun stringPool(strings: List<String>): ByteArray {
        val w = LeWriter()
        val offsets = mutableListOf<Int>()
        val data = ByteArrayOutputStream()
        for (s in strings) {
            offsets += data.size()
            val lw = LeWriter()
            lw.u16(s.length)
            for (ch in s) lw.u16(ch.code)
            lw.u16(0)
            data.write(lw.toByteArray())
        }
        val stringsStart = 28 + 4 * strings.size
        w.u16(0x0001)
        w.u16(28)
        w.i32(28 + 4 * strings.size + data.size())
        w.i32(strings.size)
        w.i32(0)
        w.i32(0)
        w.i32(stringsStart)
        w.i32(0)
        for (o in offsets) w.i32(o)
        w.bytes(data.toByteArray())
        return w.toByteArray()
    }

    private fun axml(root: FixtureElement): ByteArray {
        // Two passes: collect every string first (the pool precedes the elements),
        // then emit the pool and the element tree.
        val strings = mutableListOf<String>()
        val index = mutableMapOf<String, Int>()
        fun str(s: String): Int = index.getOrPut(s) {
            strings += s
            strings.size - 1
        }
        fun collect(e: FixtureElement) {
            str(e.name)
            for (a in e.attrs) {
                if (a.ns != null) str(a.ns)
                str(a.name)
                if (a.str != null) str(a.str)
            }
            for (c in e.children) collect(c)
        }
        collect(root)
        val pool = stringPool(strings)
        // The attribute layout, byte by byte: ns(4) name(4) rawValue(4) size(2)
        // res0(1) dataType(1) data(4) — 20 bytes. res0 and dataType share one u16
        // write (low byte res0 = 0, high byte dataType), little-endian.
        val body = LeWriter()
        fun emitElement(e: FixtureElement) {
            val size = 36 + 20 * e.attrs.size
            body.u16(0x0102)
            body.u16(16)
            body.i32(size)
            body.i32(0)
            body.i32(-1)
            body.i32(-1)
            body.i32(str(e.name))
            body.u16(20)
            body.u16(20)
            body.u16(e.attrs.size)
            body.u16(0xFFFF)
            body.u16(0xFFFF)
            body.u16(0xFFFF)
            for (a in e.attrs) {
                body.i32(if (a.ns != null) str(a.ns) else -1)
                body.i32(str(a.name))
                body.i32(if (a.str != null) str(a.str) else -1)
                body.u16(8)
                // size(2) res0(1) dataType(1): one u16 holding res0 in the low byte
                // and dataType in the high byte.
                body.u16(a.type shl 8)
                body.i32(if (a.type == TYPE_STRING) str(a.str!!) else a.data)
            }
            for (c in e.children) emitElement(c)
            body.u16(0x0103)
            body.u16(16)
            body.i32(24)
            body.i32(0)
            body.i32(-1)
            body.i32(-1)
            body.i32(str(e.name))
        }
        emitElement(root)
        val elements = body.toByteArray()
        val out = LeWriter()
        out.i32(0x00080003)
        out.i32(8 + pool.size + elements.size)
        out.bytes(pool)
        out.bytes(elements)
        return out.toByteArray()
    }

    private fun arsc(strings: List<String>): ByteArray {
        val pool = stringPool(strings)
        val w = LeWriter()
        // ResTable_header: type(2) headerSize(2) size(4) packageCount(4) — the global
        // string pool starts at offset 12, which is what ApkScanner.scanArsc reads.
        w.u16(0x0002)
        w.u16(12)
        w.i32(12 + pool.size)
        w.i32(0) // packageCount: the fixture carries no packages, only the global pool
        w.bytes(pool)
        return w.toByteArray()
    }

    // A minimal dex: header + string_ids table + string_data items + type_ids table.
    private fun dex(strings: List<String>, typeStringIndices: List<Int>): ByteArray {
        val out = ByteArrayOutputStream()
        val headerSize = 112
        val stringIdsOff = headerSize
        val stringIdsSize = strings.size
        val dataOff = stringIdsOff + 4 * stringIdsSize
        // string_data items, in order; type_ids after them.
        val dataItems = mutableListOf<ByteArray>()
        var dataCursor = dataOff
        val stringDataOffsets = mutableListOf<Int>()
        for (s in strings) {
            stringDataOffsets += dataCursor
            val bytes = s.toByteArray(Charsets.UTF_8)
            val item = ByteArrayOutputStream()
            // uleb128 utf16-length (one byte: all fixture strings are short ASCII).
            item.write(bytes.size)
            item.write(bytes)
            item.write(0)
            dataItems += item.toByteArray()
            dataCursor += item.size()
        }
        val typeIdsOff = dataCursor
        val fileSize = typeIdsOff + 4 * typeStringIndices.size
        val w = LeWriter()
        w.bytes("dex\n035\u0000".toByteArray(Charsets.US_ASCII))
        w.i32(0) // checksum
        w.bytes(ByteArray(20)) // signature
        w.i32(fileSize)
        w.i32(headerSize)
        w.i32(0x12345678) // endian_tag
        w.i32(0) // link_size
        w.i32(0) // link_off
        w.i32(0) // map_off
        w.i32(stringIdsSize)
        w.i32(stringIdsOff)
        w.i32(typeStringIndices.size)
        w.i32(typeIdsOff)
        w.i32(0) // proto_ids_size
        w.i32(0) // proto_ids_off
        w.i32(0) // field_ids_size
        w.i32(0) // field_ids_off
        w.i32(0) // method_ids_size
        w.i32(0) // method_ids_off
        w.i32(0) // class_defs_size
        w.i32(0) // class_defs_off
        w.i32(0) // data_size
        w.i32(0) // data_off
        out.write(w.toByteArray())
        for (o in stringDataOffsets) {
            val lw = LeWriter()
            lw.i32(o)
            out.write(lw.toByteArray())
        }
        for (item in dataItems) out.write(item)
        for (idx in typeStringIndices) {
            val lw = LeWriter()
            lw.i32(idx)
            out.write(lw.toByteArray())
        }
        return out.toByteArray()
    }
}
