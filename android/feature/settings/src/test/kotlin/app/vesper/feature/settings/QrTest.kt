package app.vesper.feature.settings

import app.vesper.feature.settings.ui.Qr
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class QrTest {
    @Test fun formatBitsMatchTheSpecTable() {
        // ISO 18004 Table C.1, level M: mask 0 = 101010000010010, mask 5 = 100000011001110, mask 7 = 100101010100000.
        assertEquals(0b101010000010010, Qr.formatBits(0))
        assertEquals(0b100000011001110, Qr.formatBits(5))
        assertEquals(0b100101010100000, Qr.formatBits(7))
    }

    @Test fun reedSolomonMatchesTheKnownExample() {
        // "HELLO WORLD" 1-M (thonky.com's worked example): data codewords → 10 EC codewords.
        val data = intArrayOf(32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236, 17, 236, 17).map { it.toByte() }.toByteArray()
        val ec = Qr.rsRemainder(data, Qr.rsDivisor(10)).map { it.toInt() and 0xFF }
        assertEquals(listOf(196, 35, 39, 119, 235, 215, 231, 226, 93, 23), ec)
    }

    @Test fun dataCodewordsPadCorrectly() {
        val d = Qr.dataCodewords("AB".toByteArray(), 1)
        // 0100 00000010 01000001 01000010 0000 → 0x40 0x24 0x14 0x20, then EC 11 pads.
        assertArrayEquals(byteArrayOf(0x40, 0x24, 0x14, 0x20, 0xEC.toByte(), 0x11, 0xEC.toByte(), 0x11), d.copyOf(8))
        assertEquals(16, d.size)
    }

    @Test fun picksTheSmallestVersion() {
        assertEquals(1, Qr.encode("http://a/").version)
        assertEquals(2, Qr.encode("http://192.168.10.136:8080").version) // 26 bytes: the most a 2-M holds
        assertEquals(3, Qr.encode("http://192.168.10.136:8080/").version)
        assertEquals(3, Qr.encode("http://192.168.100.200:8080/settings").version)
        assertEquals(29, Qr.encode("http://192.168.100.200:8080/settings").size)
        try { Qr.encode("x".repeat(200)); fail() } catch (e: IllegalArgumentException) {}
    }

    @Test fun finderPatternsAndTiming() {
        val q = Qr.encode("http://192.168.10.136:8080/")
        val n = q.size
        for ((ox, oy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
            for (i in 0 until 7) {
                assertTrue(q[ox + i, oy]); assertTrue(q[ox + i, oy + 6]); assertTrue(q[ox, oy + i]); assertTrue(q[ox + 6, oy + i])
            }
            assertTrue(q[ox + 3, oy + 3])
            assertTrue(!q[ox + 1, oy + 1])
        }
        for (i in 8 until n - 8) assertEquals(i % 2 == 0, q[i, 6])
        assertTrue(q[8, n - 8]) // dark module
    }

    /** Writes PNGs to build/qr/ for decoding with a real reader (see docs/status/settings.md). */
    @Test fun writesPngsForAnExternalDecoder() {
        val dir = File("build/qr").apply { mkdirs() }
        for (url in listOf("http://192.168.10.136:8080/", "http://192.168.100.200:8080/settings", "http://10.0.0.5/")) {
            val q = Qr.encode(url)
            val scale = 10
            val img = BufferedImage((q.size + 8) * scale, (q.size + 8) * scale, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until img.height) for (x in 0 until img.width) {
                val mx = x / scale - 4; val my = y / scale - 4
                val dark = mx in 0 until q.size && my in 0 until q.size && q[mx, my]
                img.setRGB(x, y, if (dark) 0 else 0xFFFFFF)
            }
            ImageIO.write(img, "png", File(dir, "v${q.version}-m${q.mask}.png"))
            File(dir, "v${q.version}-m${q.mask}.txt").writeText(url)
        }
        // Every mask as well, for one URL.
        for (m in 0 until 8) {
            val q = Qr.encode("http://192.168.10.136:8080/".toByteArray(), forceMask = m)
            val img = BufferedImage((q.size + 8) * 8, (q.size + 8) * 8, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until img.height) for (x in 0 until img.width) {
                val mx = x / 8 - 4; val my = y / 8 - 4
                img.setRGB(x, y, if (mx in 0 until q.size && my in 0 until q.size && q[mx, my]) 0 else 0xFFFFFF)
            }
            ImageIO.write(img, "png", File(dir, "mask$m.png"))
        }
    }
}
