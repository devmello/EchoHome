package com.echobedside.feature.media

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The Now Playing ambient colours (NowPlaying.dc.html `base/a/b/c`): [base] fills the screen,
 * [a]/[b]/[c] colour the three blurred blobs. Opaque ARGB ints.
 */
data class AmbientPalette(val base: Int, val a: Int, val b: Int, val c: Int) {
    companion object {
        /** No artwork: a quiet slate palette. */
        val NEUTRAL = AmbientPalette(0xFF0E1116.toInt(), 0xFF2B3A55.toInt(), 0xFF56657F.toInt(), 0xFF8C9BB5.toInt())
    }
}

/** A quantised colour and how many sampled pixels fell into it. */
data class Swatch(val rgb: Int, val population: Int) {
    val hsl: FloatArray by lazy { rgbToHsl(rgb) }
}

/**
 * A small artwork palette extractor (no AndroidX Palette, see docs/architecture.md): median cut
 * over a 5-bit-per-channel histogram, then role picking tuned to the design's sample palettes
 * (dominant hue for blob A and a dark base, the most vibrant contrasting hue for B, a light
 * accent for C). Works on plain ARGB pixels so it's unit-testable; callers downscale the
 * artwork to ~32×32 first.
 */
object PaletteExtractor {

    fun extract(pixels: IntArray, maxColors: Int = 16): AmbientPalette {
        val swatches = quantize(pixels, maxColors)
        if (swatches.isEmpty()) return AmbientPalette.NEUTRAL
        val total = swatches.sumOf { it.population }.toFloat()

        // A: dominant, preferring coloured over near-black / near-white.
        val a = swatches.maxBy { s ->
            val (_, sat, l) = s.hsl
            val usable = if (l < 0.05f || l > 0.95f) 0.15f else 1f
            s.population / total * (0.35f + sat) * usable
        }
        val aH = a.hsl
        val grey = swatches.all { it.hsl[1] < 0.12f || it.hsl[2] < 0.04f || it.hsl[2] > 0.97f }

        // B: vibrant and as far from A's hue as the artwork allows.
        val b = swatches.filter { it !== a }.maxByOrNull { s ->
            val (h, sat, l) = s.hsl
            val share = sqrt(s.population / total)
            val hueFar = if (grey) 1f else 0.25f + hueDistance(h, aH[0]) / 180f
            val midLight = 1f - abs(l - 0.55f) * 1.4f
            share * (0.2f + sat) * hueFar * midLight.coerceAtLeast(0.05f)
        } ?: a

        // C: a light accent, distinct from both.
        val c = swatches.filter { it !== a && it !== b }.maxByOrNull { s ->
            val (_, sat, l) = s.hsl
            val share = sqrt(s.population / total)
            val light = 1f - abs(l - 0.72f) * 1.3f
            share * (0.3f + sat) * light.coerceAtLeast(0.05f) * (0.3f + min(rgbDistance(s.rgb, a.rgb), rgbDistance(s.rgb, b.rgb)))
        } ?: b

        val satCap = if (grey) 0.12f else 0.9f
        val blobA = shape(a.hsl, satMin = if (grey) 0f else 0.35f, satMax = satCap, lMin = 0.24f, lMax = 0.42f)
        val blobB = shape(b.hsl, satMin = if (grey) 0f else 0.45f, satMax = satCap, lMin = 0.42f, lMax = 0.66f)
        val blobC = shape(c.hsl, satMin = if (grey) 0f else 0.45f, satMax = satCap, lMin = 0.58f, lMax = 0.8f)
        val base = hslToRgb(aH[0], min(aH[1], if (grey) 0.08f else 0.6f), 0.085f)
        return AmbientPalette(base, blobA, blobB, blobC)
    }

    private fun shape(hsl: FloatArray, satMin: Float, satMax: Float, lMin: Float, lMax: Float): Int =
        hslToRgb(hsl[0], hsl[1].coerceIn(satMin, max(satMin, satMax)), hsl[2].coerceIn(lMin, lMax))

    /** Median cut. Pixels with alpha < 128 are ignored. Sorted by population, largest first. */
    fun quantize(pixels: IntArray, maxColors: Int): List<Swatch> {
        val hist = IntArray(1 shl 15)
        for (p in pixels) {
            if ((p ushr 24) < 128) continue
            hist[(((p shr 19) and 31) shl 10) or (((p shr 11) and 31) shl 5) or ((p shr 3) and 31)]++
        }
        var n = 0
        for (c in hist) if (c > 0) n++
        if (n == 0) return emptyList()
        val colors = IntArray(n)
        var k = 0
        for (i in hist.indices) if (hist[i] > 0) colors[k++] = i

        val boxes = ArrayList<Box>()
        boxes += Box(colors, 0, n, hist)
        while (boxes.size < maxColors) {
            // Split the box with the most pixels that can still be split.
            val box = boxes.filter { it.canSplit }.maxByOrNull { it.population } ?: break
            boxes.remove(box)
            boxes.addAll(box.split())
        }
        return boxes.map { it.swatch() }.sortedByDescending { it.population }
    }

    private class Box(val colors: IntArray, val from: Int, val to: Int, val hist: IntArray) {
        var population = 0
        var minR = 31; var maxR = 0; var minG = 31; var maxG = 0; var minB = 31; var maxB = 0

        init {
            for (i in from until to) {
                val c = colors[i]
                val r = c shr 10; val g = (c shr 5) and 31; val b = c and 31
                minR = min(minR, r); maxR = max(maxR, r)
                minG = min(minG, g); maxG = max(maxG, g)
                minB = min(minB, b); maxB = max(maxB, b)
                population += hist[c]
            }
        }

        val canSplit get() = to - from > 1

        fun split(): List<Box> {
            val dr = maxR - minR; val dg = maxG - minG; val db = maxB - minB
            val shift = when { dr >= dg && dr >= db -> 10; dg >= db -> 5; else -> 0 }
            val sorted = colors.copyOfRange(from, to).sortedBy { (it shr shift) and 31 }
            for (i in sorted.indices) colors[from + i] = sorted[i]
            // Median by population.
            var acc = 0
            var mid = from + 1
            for (i in from until to) {
                acc += hist[colors[i]]
                if (acc >= population / 2) { mid = (i + 1).coerceIn(from + 1, to - 1); break }
            }
            return listOf(Box(colors, from, mid, hist), Box(colors, mid, to, hist))
        }

        fun swatch(): Swatch {
            var r = 0L; var g = 0L; var b = 0L
            for (i in from until to) {
                val c = colors[i]; val w = hist[c]
                r += ((c shr 10) * 8 + 4) * w.toLong()
                g += (((c shr 5) and 31) * 8 + 4) * w.toLong()
                b += ((c and 31) * 8 + 4) * w.toLong()
            }
            val p = population.coerceAtLeast(1)
            return Swatch(rgb((r / p).toInt(), (g / p).toInt(), (b / p).toInt()), population)
        }
    }
}

internal fun rgb(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

/** h in degrees 0..360, s and l 0..1. */
fun rgbToHsl(color: Int): FloatArray {
    val r = ((color shr 16) and 255) / 255f
    val g = ((color shr 8) and 255) / 255f
    val b = (color and 255) / 255f
    val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
    val l = (mx + mn) / 2f
    val d = mx - mn
    if (d < 1e-6f) return floatArrayOf(0f, 0f, l)
    val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
    var h = when (mx) {
        r -> (g - b) / d + (if (g < b) 6f else 0f)
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    } * 60f
    if (h >= 360f) h -= 360f
    return floatArrayOf(h, s, l)
}

fun hslToRgb(h: Float, s: Float, l: Float): Int {
    val c = (1f - abs(2f * l - 1f)) * s
    val hp = ((h % 360f) + 360f) % 360f / 60f
    val x = c * (1f - abs(hp % 2f - 1f))
    val (r1, g1, b1) = when {
        hp < 1 -> Triple(c, x, 0f)
        hp < 2 -> Triple(x, c, 0f)
        hp < 3 -> Triple(0f, c, x)
        hp < 4 -> Triple(0f, x, c)
        hp < 5 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    return rgb(((r1 + m) * 255f + 0.5f).toInt(), ((g1 + m) * 255f + 0.5f).toInt(), ((b1 + m) * 255f + 0.5f).toInt())
}

fun hueDistance(a: Float, b: Float): Float { val d = abs(a - b) % 360f; return if (d > 180f) 360f - d else d }

/** 0..1, Euclidean in RGB. */
private fun rgbDistance(x: Int, y: Int): Float {
    val dr = ((x shr 16) and 255) - ((y shr 16) and 255)
    val dg = ((x shr 8) and 255) - ((y shr 8) and 255)
    val db = (x and 255) - (y and 255)
    return sqrt((dr * dr + dg * dg + db * db).toFloat()) / 441.7f
}

/** Linear blend of two opaque colours (used for the 1.2 s palette crossfade). */
fun blend(from: Int, to: Int, t: Float): Int {
    val u = t.coerceIn(0f, 1f)
    fun ch(s: Int) = (((from shr s) and 255) + (((to shr s) and 255) - ((from shr s) and 255)) * u + 0.5f).toInt()
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}
