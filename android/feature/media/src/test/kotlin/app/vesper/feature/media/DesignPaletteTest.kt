package app.vesper.feature.media

import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * The extractor against the designer's hand-picked palettes: the fixtures are 64 px crops of the
 * three sample covers as rendered from design/NowPlaying.dc.html, and the expected colours are
 * that file's `base/a/b/c` for each track.
 */
class DesignPaletteTest {
    private fun pixels(name: String): IntArray {
        val img: BufferedImage = ImageIO.read(javaClass.classLoader!!.getResource(name))
        val small = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        small.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(img, 0, 0, 32, 32, null); dispose()
        }
        return small.getRGB(0, 0, 32, 32, null, 0, 32)
    }

    private fun check(name: String, expected: AmbientPalette) {
        val p = PaletteExtractor.extract(pixels(name))
        val hex = { c: Int -> "%06X".format(c and 0xFFFFFF) }
        println("$name: base=${hex(p.base)} a=${hex(p.a)} b=${hex(p.b)} c=${hex(p.c)}  (design ${hex(expected.base)} ${hex(expected.a)} ${hex(expected.b)} ${hex(expected.c)})")
        for ((role, got, want) in listOf(Triple("base", p.base, expected.base), Triple("a", p.a, expected.a), Triple("b", p.b, expected.b), Triple("c", p.c, expected.c))) {
            val g = rgbToHsl(got); val w = rgbToHsl(want)
            assertTrue("$name $role hue ${g[0]} vs ${w[0]}", hueDistance(g[0], w[0]) <= 30f)
            assertTrue("$name $role lightness ${g[2]} vs ${w[2]}", abs(g[2] - w[2]) <= 0.16f)
            assertTrue("$name $role saturation ${g[1]} vs ${w[1]}", abs(g[1] - w[1]) <= 0.3f)
        }
    }

    @Test fun coastlineHours() = check("art_a.png", AmbientPalette(0xFF0B2329.toInt(), 0xFF1E6A78.toInt(), 0xFFE0784C.toInt(), 0xFFF4B98A.toInt()))
    @Test fun smallOrbits() = check("art_b.png", AmbientPalette(0xFF140726.toInt(), 0xFF5A2AA8.toInt(), 0xFFE8418C.toInt(), 0xFF9B7BFF.toInt()))
    @Test fun nightSwim() = check("art_c.png", AmbientPalette(0xFF030A1E.toInt(), 0xFF123A8A.toInt(), 0xFF1C9BD6.toInt(), 0xFF6FD8FF.toInt()))
}
