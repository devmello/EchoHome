package com.echobedside.feature.camera.face

import kotlin.math.sqrt

/**
 * Stage 5: a face crop → an embedding vector. [id] is stored with every template, so templates
 * made by a different embedder are never compared (re-enrol after a model change).
 */
interface FaceEmbedder {
    val id: String
    val dim: Int
    fun embed(crop: GreyImage): FloatArray
}

/**
 * Classical, model-free embedding: uniform LBP(8,1) histograms on a 7×7 grid of the 112×112
 * crop (Ahonen et al. 2004, the "LBPH" recogniser), Hellinger-mapped (sqrt) and L2-normalised so
 * cosine similarity works. No model file and no licence question; much weaker than a
 * MobileFaceNet-class network, so it's a **baseline** for a small household with "Not me" as the
 * safety net. Swap in a TFLite embedder (same interface) once a licence-clean model is chosen
 * (docs/status/camera.md).
 */
class LbpEmbedder(private val grid: Int = 7) : FaceEmbedder {
    override val id = "lbp-u2-8-1-g$grid-v1"
    override val dim = grid * grid * BINS

    override fun embed(crop: GreyImage): FloatArray {
        val w = crop.w; val h = crop.h
        val out = FloatArray(dim)
        val cellW = (w - 2) / grid
        val cellH = (h - 2) / grid
        for (y in 1 until h - 1) {
            val cy = ((y - 1) / cellH).coerceAtMost(grid - 1)
            for (x in 1 until w - 1) {
                val cx = ((x - 1) / cellW).coerceAtMost(grid - 1)
                val c = crop[x, y]
                var code = 0
                if (crop[x - 1, y - 1] >= c) code = code or 0x80
                if (crop[x, y - 1] >= c) code = code or 0x40
                if (crop[x + 1, y - 1] >= c) code = code or 0x20
                if (crop[x + 1, y] >= c) code = code or 0x10
                if (crop[x + 1, y + 1] >= c) code = code or 0x08
                if (crop[x, y + 1] >= c) code = code or 0x04
                if (crop[x - 1, y + 1] >= c) code = code or 0x02
                if (crop[x - 1, y] >= c) code = code or 0x01
                out[(cy * grid + cx) * BINS + UNIFORM[code]] += 1f
            }
        }
        // Per-cell normalise, Hellinger map, then L2 over the whole vector.
        for (cell in 0 until grid * grid) {
            var s = 0f
            for (b in 0 until BINS) s += out[cell * BINS + b]
            if (s > 0f) for (b in 0 until BINS) out[cell * BINS + b] = sqrt(out[cell * BINS + b] / s)
        }
        return l2normalize(out)
    }

    companion object {
        const val BINS = 59
        /** Maps an 8-bit LBP code to one of 58 uniform patterns (≤2 transitions) or bin 58. */
        val UNIFORM: IntArray = IntArray(256).also { t ->
            var next = 0
            for (c in 0 until 256) {
                var transitions = 0
                for (i in 0 until 8) if (((c shr i) and 1) != ((c shr ((i + 1) % 8)) and 1)) transitions++
                t[c] = if (transitions <= 2) next++ else 58
            }
        }
    }
}

fun l2normalize(v: FloatArray): FloatArray {
    var s = 0.0
    for (x in v) s += x * x
    val n = sqrt(s).toFloat()
    if (n > 0f) for (i in v.indices) v[i] /= n
    return v
}

fun cosine(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size) { "dim ${a.size} != ${b.size}" }
    var dot = 0.0; var na = 0.0; var nb = 0.0
    for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
    if (na == 0.0 || nb == 0.0) return 0f
    return (dot / (sqrt(na) * sqrt(nb))).toFloat()
}

/** One person's enrolled templates (3–5 per person, NOTES §6.6). */
class FaceTemplates(val profileId: String, val embedderId: String, val vectors: List<FloatArray>) {
    /**
     * The person's own acceptance threshold: just under the weakest similarity between two of
     * their enrolment shots (they had to look like themselves across 5 angles), never below
     * [floor]. Adapts to the embedder's scale without a global calibration.
     */
    fun threshold(floor: Float, slack: Float = 0.01f): Float {
        if (vectors.size < 2) return floor
        var weakest = 1f
        for (i in vectors.indices) for (j in i + 1 until vectors.size) weakest = minOf(weakest, cosine(vectors[i], vectors[j]))
        return maxOf(floor, weakest - slack)
    }

    /** Similarity of [v] to this person: the mean of the best two template scores (robust to one bad shot). */
    fun score(v: FloatArray): Float {
        val s = vectors.map { cosine(v, it) }.sortedDescending()
        return if (s.size >= 2) (s[0] + s[1]) / 2f else s.firstOrNull() ?: 0f
    }
}

data class Match(val profileId: String?, val score: Float, val secondScore: Float)

/**
 * Cosine compare against every enrolled person. A match needs the person's own threshold and a
 * [margin] over the runner-up (so two similar people don't flip-flop).
 */
class FaceMatcher(var floor: Float = 0.5f, var margin: Float = 0.02f) {
    fun match(v: FloatArray, people: List<FaceTemplates>, excluded: Set<String> = emptySet()): Match {
        val scored = people.filter { it.profileId !in excluded && it.vectors.isNotEmpty() && it.vectors[0].size == v.size }
            .map { it to it.score(v) }
            .sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return Match(null, 0f, 0f)
        val second = scored.getOrNull(1)?.second ?: 0f
        val ok = best.second >= best.first.threshold(floor) && best.second - second >= margin
        return Match(if (ok) best.first.profileId else null, best.second, second)
    }
}

/**
 * "Recognition once per arrival, ≤3 tries; switch only after 2 consecutive matches."
 * Feed it one [Match] per recognition try. [decided] is the profile to switch to, [failed] means
 * give up until the next arrival.
 */
class ConsecutiveMatcher(private val maxTries: Int = 3, private val needed: Int = 2) {
    var tries = 0; private set
    var decided: String? = null; private set
    val failed: Boolean get() = decided == null && (tries >= maxTries || tries + (needed - streak) > maxTries)
    val done: Boolean get() = decided != null || failed
    private var streakId: String? = null
    private var streak = 0

    fun offer(m: Match): String? {
        if (done) return decided
        tries++
        val id = m.profileId
        if (id != null && id == streakId) streak++
        else { streakId = id; streak = if (id != null) 1 else 0 }
        if (id != null && streak >= needed) decided = id
        return decided
    }

    fun reset() { tries = 0; decided = null; streakId = null; streak = 0 }
}
