// RFC-SHOP-5 (2026-11-12, fork): the on-phone APK scanner. It pulls one installed APK
// over wireless ADB and reads it on the phone — binary AndroidManifest.xml (AXML),
// resources.arsc and dex string tables — against the REA-derived ApkScanSignatures.
//
// Pure Kotlin, no native code, no Node/Java/JADX/apktool on the phone, no server calls,
// no profile downloads: the TV's own APKs are the source, so the design runs on any
// Android TV / Google TV company, model or architecture (APK formats are
// ABI-independent; a native .so name is recorded as a footprint note only, never
// evidence). REA stays a development-time tool — it derived the signatures in the
// sandbox; it is NEVER a runtime dependency.
//
// Deliberately lite: manifest components, provider authorities, intent-filter deep
// links, dex class names and ad-network strings. Full decompilation (JADX/Ghidra-level
// code analysis — tracing an obfuscated MainActivity tab gate or a Phenotype server
// flag) stays desktop-REA work and is NOT part of this build.
//
// Every read is bounded like ApkSigners: a hostile or truncated APK returns null or an
// empty finding, never a crash. A pull, copy or parse that fails is evidence-absent —
// the plan runs without that finding, never fails Setup.

package tv.cleanyour.core.probe

import tv.cleanyour.core.model.ApkScanComponent
import tv.cleanyour.core.model.ApkScanFinding
import tv.cleanyour.core.model.ApkScanIds
import tv.cleanyour.core.model.ApkScanMatch
import tv.cleanyour.core.model.ApkScanMatchKind
import tv.cleanyour.core.model.CLASS_RE
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.PackageName
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

object ApkScanner {

    // A short read that fails safe: any malformed length, bad offset or unexpected end
    // of data throws Malformed, and the caller turns it into "no finding".
    private class Malformed(message: String) : Exception(message)

    private class Reader(val bytes: ByteArray) {
        fun check(offset: Int, length: Int) {
            if (offset < 0 || length < 0 || offset + length > bytes.size) {
                throw Malformed("offset $offset + $length past ${bytes.size}")
            }
        }

        fun u8(at: Int): Int {
            check(at, 1)
            return bytes[at].toInt() and 0xFF
        }

        fun u16(at: Int): Int {
            check(at, 2)
            return (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        }

        fun i32(at: Int): Int {
            check(at, 4)
            return (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)
        }

        fun u32(at: Int): Int = i32(at)

        fun slice(at: Int, length: Int): ByteArray {
            check(at, length)
            return bytes.copyOfRange(at, at + length)
        }
    }

    private val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    // --- the entry point -------------------------------------------------------

    fun scan(apk: ByteArray, pkg: PackageName, nowMs: Long): ApkScanFinding? {
        if (apk.isEmpty() || apk.size.toLong() > ApkScanSignatures.MAX_APK_BYTES) return null
        val parts = try {
            readZip(apk)
        } catch (_: Exception) {
            null
        } ?: return null
        val manifest = parts.manifest?.let { Manifest.parse(it, pkg) } ?: return null
        var truncated = parts.truncated
        val components = mutableListOf<ApkScanComponent>()
        val matches = mutableListOf<ApkScanMatch>()
        for (parsed in manifest.components) {
            val comp = parsed.toComponent() ?: continue
            val compMatches = matchComponent(parsed)
            if (compMatches.isEmpty()) continue
            val category = when {
                compMatches.any { it.category == "ads" } -> "ads"
                compMatches.any { it.category == "telemetry" } -> "telemetry"
                else -> "bloat"
            }
            components += ApkScanComponent(comp, category, compMatches)
            matches += compMatches
        }
        // resources.arsc strings: a bad table yields nothing, never a crash.
        parts.arsc?.let { arsc ->
            val found = scanArsc(arsc)
            matches += found.matches
            truncated = truncated || found.truncated
        }
        // dex strings and class names, same best-effort rule.
        for (dex in parts.dexFiles) {
            val found = scanDex(dex)
            matches += found.matches
            truncated = truncated || found.truncated
        }
        val cappedMatches = matches.take(ApkScanSignatures.MAX_TOTAL_MATCHES)
        val matchedStrings = cappedMatches
            .filter { it.kind == ApkScanMatchKind.STRING }
            .map { it.value }
            .distinct()
            .take(ApkScanSignatures.MAX_MATCHED_STRINGS)
        return ApkScanFinding(
            pkg = pkg,
            apkSizeBytes = apk.size.toLong(),
            versionCode = manifest.versionCode,
            components = components,
            matchedStrings = matchedStrings,
            matches = cappedMatches,
            nativeLibs = parts.nativeLibs,
            stringsTruncated = truncated,
            scannedAtMs = nowMs,
        )
    }

    // --- zip -----------------------------------------------------------------

    private data class ZipParts(
        val manifest: ByteArray?,
        val arsc: ByteArray?,
        val dexFiles: List<ByteArray>,
        val nativeLibs: List<String>,
        val truncated: Boolean,
    )

    private fun readZip(apk: ByteArray): ZipParts {
        val zin = ZipInputStream(ByteArrayInputStream(apk))
        var manifest: ByteArray? = null
        var arsc: ByteArray? = null
        val dexFiles = mutableListOf<ByteArray>()
        val nativeLibs = mutableListOf<String>()
        var truncated = false
        while (true) {
            val entry = zin.nextEntry ?: break
            val name = entry.name
            try {
                when {
                    name == "AndroidManifest.xml" && manifest == null -> {
                        manifest = readCapped(zin, ApkScanSignatures.MAX_MANIFEST_BYTES)
                        if (manifest == null) truncated = true
                    }
                    name == "resources.arsc" && arsc == null -> {
                        arsc = readCapped(zin, ApkScanSignatures.MAX_ARSC_BYTES)
                        if (arsc == null) truncated = true
                    }
                    name.startsWith("classes") && name.endsWith(".dex") &&
                        dexFiles.size < ApkScanSignatures.MAX_DEX_FILES -> {
                        val bytes = readCapped(zin, ApkScanSignatures.MAX_DEX_BYTES)
                        if (bytes == null) truncated = true else dexFiles += bytes
                    }
                    name.endsWith(".so") -> nativeLibs += name.substringAfterLast('/')
                }
            } finally {
                zin.closeEntry()
            }
        }
        return ZipParts(manifest, arsc, dexFiles, nativeLibs, truncated)
    }

    // Reads at most [cap] bytes; null when the entry is bigger (the caller treats it as
    // absent and marks the finding truncated).
    private fun readCapped(stream: InputStream, cap: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size().toLong() > cap) return null
        }
        return out.toByteArray()
    }

    // --- string pools ----------------------------------------------------------

    // Binary string pool (AXML chunk type 0x0001 and the first table chunk of
    // resources.arsc): u32 headerSize (28), stringCount, styleCount, flags (bit 8 =
    // UTF-8, else UTF-16LE), stringsStart, stylesStart, then stringCount u32 offsets,
    // then the strings. UTF-8 lengths are one byte (0x80 high bit means a second byte),
    // UTF-16 lengths one u16 (0x8000 high bit means a second u16) — the length is in
    // characters, not bytes. Returns null on any malformed offset or length. Verified
    // against apktool's output on tvlauncher, launcherx, tvrecommendations and the
    // Fire OS launcher (including both UTF-8 and UTF-16 pools).
    private fun readStringPool(r: Reader, chunkAt: Int, chunkSize: Int): List<String>? {
        return try {
            r.check(chunkAt, chunkSize)
            if (chunkSize < 28) return null
            val stringCount = r.u32(chunkAt + 8)
            if (stringCount < 0 || stringCount > 1_000_000) return null
            val flags = r.u32(chunkAt + 16)
            val stringsStart = r.u32(chunkAt + 20)
            if (stringsStart < 0 || stringsStart > chunkSize) return null
            val utf8 = (flags and 0x100) != 0
            val dataBase = chunkAt + stringsStart
            val out = ArrayList<String>(stringCount)
            for (i in 0 until stringCount) {
                val offset = r.u32(chunkAt + 28 + i * 4)
                // An offset must stay inside the chunk's string data, not just the file.
                if (offset < 0 || offset > chunkSize - stringsStart) return null
                out += if (utf8) readUtf8String(r, dataBase + offset) else readUtf16String(r, dataBase + offset)
            }
            out
        } catch (_: Malformed) {
            null
        }
    }

    private fun readUtf16String(r: Reader, at: Int): String {
        // Length: one u16; 0x8000 high bit means a second u16, and the length is
        // ((first & 0x7FFF) << 16) | second (AOSP ResStringPool encoding).
        var len = r.u16(at)
        var p = at + 2
        if (len and 0x8000 != 0) {
            len = ((len and 0x7FFF) shl 16) or r.u16(p)
            p += 2
        }
        if (len > 100_000) throw Malformed("string too long: $len")
        r.check(p, len * 2)
        val chars = CharArray(len)
        for (i in 0 until len) chars[i] = r.u16(p + i * 2).toChar()
        return String(chars)
    }

    private fun readUtf8String(r: Reader, at: Int): String {
        // Two lengths in a row: the UTF-16 character count, then the UTF-8 byte count.
        // Each is one byte, or two when the 0x80 high bit is set on the first.
        var len = r.u8(at)
        var p = at + 1
        if (len and 0x80 != 0) {
            len = ((len and 0x7F) shl 8) or r.u8(p)
            p += 1
        }
        var byteLen = r.u8(p)
        p += 1
        if (byteLen and 0x80 != 0) {
            byteLen = ((byteLen and 0x7F) shl 8) or r.u8(p)
            p += 1
        }
        if (byteLen > 1_000_000) throw Malformed("string too long: $byteLen")
        return String(r.slice(p, byteLen), Charsets.UTF_8)
    }

    // --- AXML ------------------------------------------------------------------

    private data class Attr(val type: Int, val data: Int, val str: String?)

    private data class ParsedComponent(
        val kind: String, // "activity" | "activity-alias" | "service" | "receiver" | "provider"
        val pkg: PackageName,
        val className: String, // fully resolved
        val authority: String? = null,
        val deepLinks: List<String> = emptyList(), // "https://tv.google.com/library"
    ) {
        // A class name the manifest invents is not trusted: Component() throws on a
        // name outside CLASS_RE or a name longer than the model allows, so a hostile
        // APK can only make this skip, never crash the scan.
        fun toComponent(): Component? {
            if (!CLASS_RE.matches(className)) return null
            if (pkg.value.length + 1 + className.length > 300) return null
            return Component(pkg, className)
        }
    }

    private data class ManifestInfo(
        val packageName: String,
        val versionCode: Long,
        val components: List<ParsedComponent>,
    )

    private object Manifest {
        private const val CHUNK_STRING_POOL = 0x0001
        private const val CHUNK_START_ELEMENT = 0x0102
        private const val CHUNK_END_ELEMENT = 0x0103
        private val COMPONENT_KINDS = setOf("activity", "activity-alias", "service", "receiver", "provider")

        fun parse(bytes: ByteArray, pkg: PackageName): ManifestInfo? {
            return try {
                parseOrThrow(bytes, pkg)
            } catch (_: Malformed) {
                null
            }
        }

        private fun parseOrThrow(bytes: ByteArray, pkg: PackageName): ManifestInfo {
            val r = Reader(bytes)
            if (bytes.size < 8) throw Malformed("manifest too short")
            if (r.u32(0) != 0x00080003) throw Malformed("not an AXML file")
            if (r.u16(8) != CHUNK_STRING_POOL) throw Malformed("first chunk is not a string pool")
            val poolSize = r.u32(12)
            val strings = readStringPool(r, 8, poolSize) ?: throw Malformed("string pool unreadable")
            fun s(idx: Int): String? = if (idx in strings.indices) strings[idx] else null

            var packageName = pkg.value
            var versionCode = 0L
            val components = mutableListOf<ParsedComponent>()
            var current: MutableComponent? = null
            var inFilter = false
            var pos = 8 + poolSize
            var guard = 0
            while (pos < bytes.size) {
                if (++guard > 100_000) throw Malformed("too many chunks")
                r.check(pos, 8)
                val type = r.u16(pos)
                val headerSize = r.u16(pos + 2)
                val size = r.u32(pos + 4)
                if (size < 8 || size > bytes.size - pos) throw Malformed("bad chunk size $size at $pos")
                when (type) {
                    CHUNK_START_ELEMENT -> {
                        // The 16-byte node header (chunk header 8 + lineNumber +
                        // comment), then ns (+16), name (+20), attributeStart (+24),
                        // attributeSize (+26), attributeCount (+28), idIndex (+30),
                        // classIndex (+32), styleIndex (+34) — 36 bytes in all.
                        r.check(pos, 36)
                        val nsIdx = r.i32(pos + 16)
                        val nameIdx = r.i32(pos + 20)
                        val name = s(nameIdx) ?: throw Malformed("element name index $nameIdx")
                        val attrStart = r.u16(pos + 24)
                        val attrSize = r.u16(pos + 26)
                        val attrCount = r.u16(pos + 28)
                        if (attrSize != 20) throw Malformed("attribute size $attrSize")
                        if (attrCount > 100) throw Malformed("attribute count $attrCount")
                        val attrBase = pos + headerSize + attrStart
                        r.check(attrBase, attrCount * 20)
                        val attrs = mutableMapOf<String, Attr>()
                        for (i in 0 until attrCount) {
                            val a = attrBase + i * 20
                            val aNsIdx = r.i32(a)
                            val aName = s(r.i32(a + 4)) ?: continue
                            val aRaw = r.i32(a + 8)
                            val dataType = r.u8(a + 15)
                            val data = r.i32(a + 16)
                            val str = when (dataType) {
                                0x03 -> s(data) // TYPE_STRING
                                else -> if (aRaw >= 0) s(aRaw) else null
                            }
                            val ns = if (aNsIdx >= 0) s(aNsIdx) ?: "" else ""
                            attrs["$ns|$aName"] = Attr(dataType, data, str)
                        }
                        fun attr(name: String): Attr? = attrs["$ANDROID_NS|$name"] ?: attrs["|$name"]
                        fun attrStr(name: String): String? = attr(name)?.str
                        when (name) {
                            "manifest" -> {
                                packageName = attrStr("package") ?: packageName
                                val vc = attr("versionCode")
                                if (vc != null && vc.type == 0x10) { // TYPE_INT_DEC
                                    versionCode = vc.data.toLong() and 0xFFFFFFFFL
                                } else if (vc?.str != null) {
                                    versionCode = vc.str.toLongOrNull() ?: 0L
                                }
                            }
                            in COMPONENT_KINDS -> {
                                val rawName = attrStr("name") ?: ""
                                val cls = resolveClass(rawName, packageName)
                                if (cls.isNotBlank()) {
                                    val mut = MutableComponent(
                                        kind = name,
                                        cls = cls,
                                        authority = attrStr("authorities"),
                                    )
                                    current = mut
                                    if (components.size < ApkScanSignatures.MAX_XML_COMPONENTS) {
                                        components += mut
                                    }
                                }
                            }
                            "intent-filter" -> if (current != null) inFilter = true
                            "data" -> if (current != null && inFilter) {
                                val scheme = attrStr("scheme") ?: ""
                                val host = attrStr("host") ?: ""
                                val path = attrStr("pathPrefix") ?: attrStr("path") ?: ""
                                if (host.isNotBlank()) current.deepLinks += "$scheme://$host$path"
                            }
                        }
                    }
                    CHUNK_END_ELEMENT -> {
                        // The 16-byte node header, then ns (+16) and name (+20).
                        r.check(pos, 24)
                        val name = s(r.i32(pos + 20))
                        when (name) {
                            "intent-filter" -> inFilter = false
                            in COMPONENT_KINDS -> current = null
                        }
                    }
                    else -> {
                        // Namespace, resource-map and text chunks are skipped — the scan
                        // reads components, names and data only.
                    }
                }
                pos += size
            }
            return ManifestInfo(
                packageName = packageName,
                versionCode = versionCode,
                components = components.map { it.toParsed(pkg) },
            )
        }

        private class MutableComponent(val kind: String, val cls: String, val authority: String?) {
            val deepLinks = mutableListOf<String>()
            fun toParsed(pkg: PackageName) = ParsedComponent(kind, pkg, cls, authority, deepLinks.toList())
        }

        // ".Foo" -> package + ".Foo"; "Foo" -> package + ".Foo"; otherwise the name as
        // written (a relative name is how launchers declare most of their components).
        private fun resolveClass(raw: String, packageName: String): String = when {
            raw.startsWith(".") -> packageName + raw
            raw.contains(".") -> raw
            else -> "$packageName.$raw"
        }
    }

    // --- resources.arsc ---------------------------------------------------------

    private data class StringMatches(
        val matches: List<ApkScanMatch>,
        val truncated: Boolean,
    )

    // resources.arsc: file header (u16 type 0x0002, u16 headerSize 12, u32 size), then
    // the global string pool as the first chunk. Only the global pool is read: the
    // per-package tables are not needed, because a package's ad strings also appear
    // in the global pool when compiled normally (verified: tvlauncher's "Welcome to
    // Shop" and its pagead2 references are in it). Matching is capped, so a huge table
    // cannot exhaust the phone.
    private fun scanArsc(bytes: ByteArray): StringMatches {
        try {
            val r = Reader(bytes)
            if (bytes.size < 12) return StringMatches(emptyList(), false)
            if (r.u16(0) != 0x0002) return StringMatches(emptyList(), false)
            val poolAt = 12
            r.check(poolAt, 8)
            if (r.u16(poolAt) != 0x0001) return StringMatches(emptyList(), false)
            val poolSize = r.u32(poolAt + 4)
            val strings = readStringPool(r, poolAt, poolSize) ?: return StringMatches(emptyList(), false)
            return matchStrings(strings, ApkScanSignatures.MAX_ARSC_STRINGS, "resources")
        } catch (_: Malformed) {
            return StringMatches(emptyList(), false)
        }
    }

    // --- dex ---------------------------------------------------------------------

    // A dex file: header (112 bytes) — magic "dex\n", string_ids_size at 56,
    // string_ids_off at 60, type_ids_size at 64, type_ids_off at 68. String data is
    // MUTF-8: uleb128 utf16-length, then bytes, then a 0x00 terminator. Only the string
    // table and the type table are read; code is never executed or disassembled.
    private fun scanDex(bytes: ByteArray): StringMatches {
        try {
            val r = Reader(bytes)
            if (bytes.size < 112) return StringMatches(emptyList(), false)
            if (r.u8(0) != 'd'.code || r.u8(1) != 'e'.code || r.u8(2) != 'x'.code || r.u8(3) != '\n'.code) {
                return StringMatches(emptyList(), false)
            }
            val stringIdsSize = r.u32(56)
            val stringIdsOff = r.u32(60)
            val typeIdsSize = r.u32(64)
            val typeIdsOff = r.u32(68)
            if (stringIdsSize < 0 || stringIdsSize > 10_000_000 || typeIdsSize < 0 || typeIdsSize > 10_000_000) {
                return StringMatches(emptyList(), false)
            }
            val strings = ArrayList<String>(minOf(stringIdsSize, ApkScanSignatures.MAX_DEX_STRINGS))
            val truncated = stringIdsSize > ApkScanSignatures.MAX_DEX_STRINGS
            for (i in 0 until minOf(stringIdsSize, ApkScanSignatures.MAX_DEX_STRINGS)) {
                val off = r.u32(stringIdsOff + i * 4)
                // One bad string is skipped (as an empty placeholder, so the string
                // indices the type table uses stay aligned) — the dex as a whole is
                // only abandoned when its tables themselves are unreadable.
                strings += try {
                    readDexString(r, off)
                } catch (_: Malformed) {
                    ""
                }
            }
            val matches = matchStrings(strings, ApkScanSignatures.MAX_DEX_STRINGS, "dex").matches.toMutableList()
            // Type descriptors are class names: match them against the same component
            // signatures, so a dex class that embeds ad machinery is evidence even
            // when the manifest never declares it.
            for (i in 0 until minOf(typeIdsSize, ApkScanSignatures.MAX_DEX_STRINGS)) {
                val stringIdx = r.u32(typeIdsOff + i * 4)
                if (stringIdx < 0 || stringIdx >= strings.size) continue
                val descriptor = strings[stringIdx]
                for ((sig, category) in ApkScanSignatures.COMPONENT_SIGNATURES) {
                    if (matchesAtWordBoundary(descriptor, sig)) {
                        val id = ApkScanSignatures.signatureId(sig)
                        matches += ApkScanMatch(
                            signatureId = id,
                            kind = ApkScanMatchKind.COMPONENT,
                            value = descriptor,
                            evidence = "dex class $descriptor matches signature \"$sig\"",
                            category = category,
                        )
                        break
                    }
                }
                if (matches.size >= ApkScanSignatures.MAX_TOTAL_MATCHES) break
            }
            return StringMatches(matches, truncated)
        } catch (_: Malformed) {
            return StringMatches(emptyList(), false)
        }
    }

    private fun readDexString(r: Reader, at: Int): String {
        // uleb128 utf16-length, then the bytes, then a 0x00 terminator.
        var p = at
        var len = 0
        var shift = 0
        while (true) {
            val b = r.u8(p)
            p += 1
            len = len or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) throw Malformed("uleb128 too long")
        }
        val start = p
        var end = start
        while (end - start < 8192) {
            if (r.u8(end) == 0) break
            end += 1
        }
        if (end - start >= 8192) throw Malformed("dex string too long")
        return String(r.slice(start, end - start), Charsets.UTF_8)
    }

    // --- matching ------------------------------------------------------------------

    // One string table (arsc or dex) matched against the ad-network strings (strong)
    // and the corroborating strings (recorded, never nominating). The first cap
    // strings are scanned; a table longer than the cap is marked truncated.
    private fun matchStrings(strings: List<String>, cap: Int, source: String): StringMatches {
        val matches = mutableListOf<ApkScanMatch>()
        val truncated = strings.size > cap
        for (raw in strings.take(cap)) {
            val lower = raw.lowercase()
            val strong = ApkScanSignatures.STRONG_AD_STRINGS.firstOrNull { it in lower }
            if (strong != null) {
                matches += ApkScanMatch(
                    signatureId = ApkScanIds.AD_NETWORK_STRING,
                    kind = ApkScanMatchKind.STRING,
                    value = raw.take(200),
                    evidence = "$source string \"${raw.take(80)}\" names an ad network ($strong)",
                    category = "ads",
                )
                continue
            }
            val corroborating = ApkScanSignatures.CORROBORATING_STRINGS.firstOrNull { it in lower }
            if (corroborating != null) {
                matches += ApkScanMatch(
                    signatureId = ApkScanIds.CORROBORATING_STRING,
                    kind = ApkScanMatchKind.STRING,
                    value = raw.take(200),
                    evidence = "$source string \"${raw.take(80)}\" mentions ads (corroborating only)",
                    category = "ads",
                )
            }
            if (matches.size >= ApkScanSignatures.MAX_TOTAL_MATCHES) break
        }
        return StringMatches(matches, truncated)
    }

    // A manifest component matched against the signature set. The home entry point is
    // never nominated (the planner's allowed() re-applies the same rule): the launcher
    // itself is the thing being cleaned, not its home screen.
    private fun matchComponent(parsed: ParsedComponent): List<ApkScanMatch> {
        val cls = parsed.className
        val lower = cls.lowercase()
        if (lower.contains("mainactivity") || lower.contains("homeactivity")) return emptyList()
        val out = mutableListOf<ApkScanMatch>()
        // REA-proven exact components: a class the public APKs were decoded to contain.
        if (cls in ApkScanSignatures.EXACT_SHOP_COMPONENTS) {
            out += ApkScanMatch(ApkScanIds.SHOP_ACTIVITY, ApkScanMatchKind.COMPONENT, cls, "manifest component $cls is a REA-proven Shop activity", "ads")
        }
        if (cls in ApkScanSignatures.EXACT_AD_COMPONENTS) {
            out += ApkScanMatch(ApkScanIds.AD_ACTIVITY, ApkScanMatchKind.COMPONENT, cls, "manifest component $cls is a REA-proven ad component", "ads")
        }
        if (cls in ApkScanSignatures.EXACT_AD_PROVIDERS) {
            out += ApkScanMatch(ApkScanIds.AD_PROVIDER, ApkScanMatchKind.PROVIDER, cls, "manifest provider $cls is a REA-proven ad-signal provider", "ads")
        }
        val authority = parsed.authority
        if (authority != null && ApkScanSignatures.AD_PROVIDER_AUTHORITY_SUBSTRINGS.any { it in authority.lowercase() } &&
            out.none { it.signatureId == ApkScanIds.AD_PROVIDER }
        ) {
            out += ApkScanMatch(ApkScanIds.AD_PROVIDER, ApkScanMatchKind.PROVIDER, authority, "manifest provider $cls serves ad signals (authority $authority)", "ads")
        }
        for (link in parsed.deepLinks) {
            val host = link.substringAfter("://", "").substringBefore('/')
            if (host in ApkScanSignatures.SHOP_DEEP_LINK_HOSTS) {
                out += ApkScanMatch(ApkScanIds.SHOP_DEEP_LINK, ApkScanMatchKind.DEEP_LINK, link, "manifest component $cls opens the Shop deep link $link", "ads")
            }
        }
        // Signature substrings, matched at a word boundary in the class name.
        for ((sig, category) in ApkScanSignatures.COMPONENT_SIGNATURES) {
            if (matchesAtWordBoundary(cls, sig)) {
                val id = ApkScanSignatures.signatureId(sig)
                // Do not double-count a component already recorded above: one match
                // per signature id, the exact or first one wins.
                if (out.none { it.signatureId == id }) {
                    out += ApkScanMatch(id, ApkScanMatchKind.COMPONENT, cls, "manifest component $cls matches signature \"$sig\"", category)
                }
            }
            if (out.size >= ApkScanSignatures.MAX_MATCHES_PER_COMPONENT) break
        }
        return out
    }

    // A signature must start a word in the class name: at the start, after '.', '$',
    // '_' or '/', or on an upper-case camel hump. So "ads" matches
    // AdsPersonalization... and .ads.controls..., but not Downloads (the "ads" in
    // "Downloads" starts mid-word), and "shop" does not fire on "Workshop".
    internal fun matchesAtWordBoundary(cls: String, sig: String): Boolean {
        val lower = cls.lowercase()
        var from = 0
        while (true) {
            val at = lower.indexOf(sig, from)
            if (at < 0) return false
            if (at == 0 || cls[at].isUpperCase() || cls[at - 1] in "._$/") return true
            from = at + 1
        }
    }
}
