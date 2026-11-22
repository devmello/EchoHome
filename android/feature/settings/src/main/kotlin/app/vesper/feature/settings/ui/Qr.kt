package app.vesper.feature.settings.ui

/**
 * A minimal QR Code encoder (ISO/IEC 18004): byte mode, error correction level M, versions 1–6
 * (up to 106 bytes, plenty for "http://192.168.10.136:8080/"). Written for this module, after
 * the structure of Project Nayuki's MIT-licensed reference implementation.
 */
class Qr private constructor(val version: Int, val mask: Int, private val modules: Array<BooleanArray>) {
    val size: Int get() = modules.size
    /** true = dark. [x] is the column, [y] the row. */
    operator fun get(x: Int, y: Int): Boolean = modules[y][x]

    companion object {
        // Error correction level M, versions 1..6: total codewords, EC codewords per block, blocks.
        private val TOTAL = intArrayOf(0, 26, 44, 70, 100, 134, 172)
        private val EC_PER_BLOCK = intArrayOf(0, 10, 16, 26, 18, 24, 16)
        private val BLOCKS = intArrayOf(0, 1, 1, 1, 2, 2, 4)
        const val MAX_VERSION = 6

        fun dataCapacity(version: Int) = TOTAL[version] - EC_PER_BLOCK[version] * BLOCKS[version]

        fun encode(text: String): Qr = encode(text.toByteArray(Charsets.UTF_8))

        fun encode(data: ByteArray, forceMask: Int = -1): Qr {
            // Byte mode: 4-bit mode + 8-bit count (versions 1–9) + 8 bits per byte.
            val bitsNeeded = 4 + 8 + data.size * 8
            val version = (1..MAX_VERSION).firstOrNull { dataCapacity(it) * 8 >= bitsNeeded }
                ?: throw IllegalArgumentException("Too long for a version $MAX_VERSION QR code")
            val codewords = addEcAndInterleave(dataCodewords(data, version), version)
            val size = version * 4 + 17
            val m = Array(size) { BooleanArray(size) }
            val fn = Array(size) { BooleanArray(size) }
            drawFunctionPatterns(m, fn, version)
            drawCodewords(m, fn, codewords)
            var best = forceMask
            if (best < 0) {
                var bestPenalty = Int.MAX_VALUE
                for (mask in 0 until 8) {
                    applyMask(m, fn, mask)
                    drawFormatBits(m, fn, mask)
                    val p = penalty(m)
                    if (p < bestPenalty) { bestPenalty = p; best = mask }
                    applyMask(m, fn, mask) // undo (XOR)
                }
            }
            applyMask(m, fn, best)
            drawFormatBits(m, fn, best)
            return Qr(version, best, m)
        }

        internal fun dataCodewords(data: ByteArray, version: Int): ByteArray {
            val cap = dataCapacity(version)
            val bits = BitBuffer()
            bits.append(0b0100, 4)
            bits.append(data.size, 8)
            for (b in data) bits.append(b.toInt() and 0xFF, 8)
            bits.append(0, minOf(4, cap * 8 - bits.size))
            bits.append(0, (8 - bits.size % 8) % 8)
            val out = ByteArray(cap)
            var i = 0
            while (i < bits.size / 8) { out[i] = bits.byteAt(i).toByte(); i++ }
            var pad = 0xEC
            while (i < cap) { out[i++] = pad.toByte(); pad = pad xor (0xEC xor 0x11) }
            return out
        }

        internal fun addEcAndInterleave(data: ByteArray, version: Int): ByteArray {
            val blocks = BLOCKS[version]
            val ecLen = EC_PER_BLOCK[version]
            val dataLen = data.size / blocks // equal blocks for M, versions 1–6
            val divisor = rsDivisor(ecLen)
            val dataBlocks = (0 until blocks).map { data.copyOfRange(it * dataLen, (it + 1) * dataLen) }
            val ecBlocks = dataBlocks.map { rsRemainder(it, divisor) }
            val out = ByteArray(TOTAL[version])
            var k = 0
            for (i in 0 until dataLen) for (b in 0 until blocks) out[k++] = dataBlocks[b][i]
            for (i in 0 until ecLen) for (b in 0 until blocks) out[k++] = ecBlocks[b][i]
            return out
        }

        internal fun rsDivisor(degree: Int): ByteArray {
            val result = ByteArray(degree)
            result[degree - 1] = 1
            var root = 1
            for (i in 0 until degree) {
                for (j in 0 until degree) {
                    result[j] = gfMul(result[j].toInt() and 0xFF, root).toByte()
                    if (j + 1 < degree) result[j] = (result[j].toInt() xor result[j + 1].toInt()).toByte()
                }
                root = gfMul(root, 0x02)
            }
            return result
        }

        internal fun rsRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
            val result = ByteArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() xor result[0].toInt()) and 0xFF
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = (result[i].toInt() xor gfMul(divisor[i].toInt() and 0xFF, factor)).toByte()
            }
            return result
        }

        private fun gfMul(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        private fun set(m: Array<BooleanArray>, fn: Array<BooleanArray>, x: Int, y: Int, dark: Boolean) {
            m[y][x] = dark; fn[y][x] = true
        }

        private fun drawFunctionPatterns(m: Array<BooleanArray>, fn: Array<BooleanArray>, version: Int) {
            val size = m.size
            for (i in 0 until size) { set(m, fn, 6, i, i % 2 == 0); set(m, fn, i, 6, i % 2 == 0) }
            for ((cx, cy) in listOf(3 to 3, size - 4 to 3, 3 to size - 4)) {
                for (dy in -4..4) for (dx in -4..4) {
                    val x = cx + dx; val y = cy + dy
                    if (x in 0 until size && y in 0 until size) {
                        val d = maxOf(Math.abs(dx), Math.abs(dy))
                        set(m, fn, x, y, d != 2 && d != 4)
                    }
                }
            }
            if (version >= 2) {
                val p = version * 4 + 10 // the one alignment pattern of versions 2–6
                for (dy in -2..2) for (dx in -2..2) set(m, fn, p + dx, p + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
            }
            drawFormatBits(m, fn, 0) // reserves the format areas (and the dark module)
        }

        internal fun formatBits(mask: Int): Int {
            val data = (0b00 shl 3) or mask // level M = 00
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            return ((data shl 10) or rem) xor 0x5412
        }

        private fun drawFormatBits(m: Array<BooleanArray>, fn: Array<BooleanArray>, mask: Int) {
            val bits = formatBits(mask)
            val size = m.size
            fun bit(i: Int) = ((bits ushr i) and 1) != 0
            for (i in 0..5) set(m, fn, 8, i, bit(i))
            set(m, fn, 8, 7, bit(6))
            set(m, fn, 8, 8, bit(7))
            set(m, fn, 7, 8, bit(8))
            for (i in 9 until 15) set(m, fn, 14 - i, 8, bit(i))
            for (i in 0 until 8) set(m, fn, size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(m, fn, 8, size - 15 + i, bit(i))
            set(m, fn, 8, size - 8, true)
        }

        private fun drawCodewords(m: Array<BooleanArray>, fn: Array<BooleanArray>, data: ByteArray) {
            val size = m.size
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!fn[y][x] && i < data.size * 8) {
                        m[y][x] = ((data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                        i++
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(m: Array<BooleanArray>, fn: Array<BooleanArray>, mask: Int) {
            val size = m.size
            for (y in 0 until size) for (x in 0 until size) {
                if (fn[y][x]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert) m[y][x] = !m[y][x]
            }
        }

        /** The standard mask penalty (N1 runs, N2 blocks, N3 finder-like, N4 balance). */
        internal fun penalty(m: Array<BooleanArray>): Int {
            val size = m.size
            var p = 0
            fun runs(get: (Int, Int) -> Boolean) {
                for (a in 0 until size) {
                    var run = 1
                    for (b in 1 until size) {
                        if (get(a, b) == get(a, b - 1)) run++ else { if (run >= 5) p += run - 2; run = 1 }
                    }
                    if (run >= 5) p += run - 2
                }
            }
            runs { a, b -> m[a][b] }
            runs { a, b -> m[b][a] }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = m[y][x]
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) p += 3
            }
            val pat = booleanArrayOf(true, false, true, true, true, false, true)
            fun finderLike(get: (Int) -> Boolean, n: Int): Int {
                var c = 0
                for (s in 0..n - 7) {
                    if ((0 until 7).all { get(s + it) == pat[it] }) {
                        val before = (1..4).all { s - it < 0 || !get(s - it) }
                        val after = (1..4).all { s + 6 + it >= n || !get(s + 6 + it) }
                        if (before || after) c++
                    }
                }
                return c
            }
            for (a in 0 until size) {
                p += 40 * finderLike({ m[a][it] }, size)
                p += 40 * finderLike({ m[it][a] }, size)
            }
            val dark = m.sumOf { row -> row.count { it } }
            val total = size * size
            p += 10 * (Math.abs(dark * 20 - total * 10) / total)
            return p
        }
    }

    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val size get() = bits.size
        fun append(v: Int, n: Int) { for (i in n - 1 downTo 0) bits += ((v ushr i) and 1) != 0 }
        fun byteAt(i: Int): Int { var b = 0; for (k in 0 until 8) b = (b shl 1) or (if (bits[i * 8 + k]) 1 else 0); return b }
    }
}
