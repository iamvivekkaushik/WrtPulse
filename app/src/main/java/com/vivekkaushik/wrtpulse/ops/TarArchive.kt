package com.vivekkaushik.wrtpulse.ops

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/** One member of a tar archive, named the way the router's tar would unpack it. */
data class TarEntry(val name: String, val size: Long, val isDirectory: Boolean)

/**
 * A gzipped tar, read on the phone before it is trusted anywhere near the router.
 *
 * Config backups are small, so the whole thing is inflated into memory and walked once. The
 * reader covers what busybox tar writes — ustar headers and GNU long-name records — and
 * refuses anything it cannot account for, because a half-read archive that looks fine is
 * exactly the failure this exists to prevent.
 */
class TarArchive private constructor(private val raw: ByteArray, private val members: List<Member>) {

    private class Member(val entry: TarEntry, val dataOffset: Int)

    val entries: List<TarEntry> get() = members.map { it.entry }

    /** The bytes of one regular file, by the name [entries] reports; null when absent. */
    fun read(name: String): ByteArray? {
        val m = members.firstOrNull { it.entry.name == name && !it.entry.isDirectory } ?: return null
        return raw.copyOfRange(m.dataOffset, m.dataOffset + m.entry.size.toInt())
    }

    /** Text of one file — the uci configs the restore warnings read. */
    fun readText(name: String): String? = read(name)?.toString(Charsets.UTF_8)

    /**
     * Names that would land outside / when unpacked: anything with a `..` segment. Leading
     * slashes are not counted — tar strips them itself, and so does [normalize].
     */
    val unsafePaths: List<String>
        get() = entries.map { it.name }.filter { n -> n.split('/').any { it == ".." } }

    /** Whether this holds a router's configuration at all: something under etc/. */
    val looksLikeOpenWrtConfig: Boolean get() = entries.any { it.name.startsWith("etc/") }

    companion object {

        /** Inflated size past which this stops being a config backup and starts being a bomb. */
        const val MAX_INFLATED_BYTES = 32 * 1024 * 1024

        private const val BLOCK = 512

        fun isGzip(bytes: ByteArray): Boolean =
            bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

        /** Opens the archive, or null when the bytes are not a gzipped tar this can read. */
        fun open(gzipped: ByteArray): TarArchive? {
            if (!isGzip(gzipped)) return null
            val tar = inflate(gzipped) ?: return null
            val members = parse(tar) ?: return null
            return TarArchive(tar, members)
        }

        /** As busybox tar stores a name: no leading slashes or `./`, no trailing slash. */
        fun normalize(name: String): String {
            var n = name.trimEnd('/')
            while (n.startsWith("./") || n.startsWith("/")) n = n.removePrefix("./").trimStart('/')
            return n
        }

        private fun inflate(gzipped: ByteArray): ByteArray? = runCatching {
            GZIPInputStream(ByteArrayInputStream(gzipped)).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_INFLATED_BYTES) return null
                }
                out.toByteArray()
            }
        }.getOrNull()

        private fun parse(tar: ByteArray): List<Member>? {
            val out = ArrayList<Member>()
            var pos = 0
            var longName: String? = null
            while (pos + BLOCK <= tar.size) {
                if (isZeroBlock(tar, pos)) break
                if (!checksumOk(tar, pos)) return null
                val size = octal(tar, pos + 124, 12) ?: return null
                val type = (tar[pos + 156].toInt() and 0xff).toChar()
                val dataStart = pos + BLOCK
                if (dataStart + size > tar.size) return null
                when (type) {
                    // GNU long name: this record's data is the name of the next one.
                    'L' -> longName = text(tar, dataStart, size.toInt())
                    // Long link targets and pax headers carry nothing this needs.
                    'K', 'x', 'g' -> Unit
                    else -> {
                        val name = longName ?: run {
                            val base = text(tar, pos, 100)
                            val prefix = if (text(tar, pos + 257, 5) == "ustar") text(tar, pos + 345, 155) else ""
                            if (prefix.isEmpty()) base else "$prefix/$base"
                        }
                        longName = null
                        val normalized = normalize(name)
                        if (normalized.isNotEmpty()) {
                            out += Member(TarEntry(normalized, size, type == '5' || name.endsWith("/")), dataStart)
                        }
                    }
                }
                pos = dataStart + (((size + BLOCK - 1) / BLOCK) * BLOCK).toInt()
            }
            return out
        }

        private fun isZeroBlock(tar: ByteArray, pos: Int): Boolean {
            for (i in pos until pos + BLOCK) if (tar[i] != 0.toByte()) return false
            return true
        }

        /** Every header byte summed, with the checksum field itself counted as spaces. */
        private fun checksumOk(tar: ByteArray, pos: Int): Boolean {
            val stored = octal(tar, pos + 148, 8) ?: return false
            var sum = 0L
            for (i in 0 until BLOCK) {
                sum += if (i in 148 until 156) 32 else (tar[pos + i].toInt() and 0xff)
            }
            return sum == stored
        }

        private fun octal(tar: ByteArray, off: Int, len: Int): Long? {
            val s = text(tar, off, len).trim()
            if (s.isEmpty()) return 0
            if (!s.all { it in '0'..'7' }) return null
            return s.toLongOrNull(8)
        }

        /** The NUL-terminated string in a header field. */
        private fun text(tar: ByteArray, off: Int, len: Int): String {
            var end = off
            val limit = minOf(off + len, tar.size)
            while (end < limit && tar[end] != 0.toByte()) end++
            return String(tar, off, end - off, Charsets.UTF_8)
        }
    }
}
