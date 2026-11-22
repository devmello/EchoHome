package app.vesper.feature.camera.ui

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import app.vesper.feature.camera.face.BlazeFaceFinder
import app.vesper.feature.camera.face.FaceBox
import app.vesper.feature.camera.face.FaceFinder
import app.vesper.feature.camera.face.FaceImage
import app.vesper.feature.camera.face.LbpEmbedder
import app.vesper.feature.camera.face.LegacyFaceFinder
import app.vesper.feature.camera.face.ModelFiles
import app.vesper.feature.camera.face.SyntheticFace
import app.vesper.feature.camera.face.TfliteEmbedder
import app.vesper.feature.camera.face.alignFace
import app.vesper.feature.camera.face.cosine

/**
 * Bench stage "selftest" (debug, camera off): both detectors on the same drawn-face frames, then
 * alignment + each embedder model, with load time, ms per run and PSS (Debug.getPss) loaded and
 * after release. No camera and no real face needed. Returns log lines.
 */
class ModelSelfTest(private val context: Context, private val runs: Int = 20) {
    private val out = ArrayList<String>()
    private fun log(s: String) { out += s }
    private fun pssMb() = Debug.getPss() / 1024f
    private fun gcPss(): Float { repeat(2) { System.gc(); System.runFinalization() }; SystemClock.sleep(300); return pssMb() }

    /**
     * One embedder variant in a fresh process (PSS isn't muddied by an earlier model's freed heap):
     * [model] is an asset base name (`models/<model>.tflite`, e.g. edgeface_xs_fp16), checked
     * against `models/selftest_drawn112_<family>.txt` (family = name without the last `_part`).
     */
    fun variant(model: String, threads: Int, xnnpack: Boolean): List<String> {
        SystemClock.sleep(SETTLE_MS)
        val frame = SyntheticFace.frame(320, 240, 160f, 120f, 40f)
        val e = SyntheticFace.eyes(160f, 120f, 40f)
        val box = FaceBox((e[0] + e[2]) / 2, (e[1] + e[3]) / 2, e[2] - e[0], 1f)
        val face = FaceImage()
        repeat(runs) { alignFace(frame, box, face) } // JIT warm-up
        val ta = SystemClock.elapsedRealtimeNanos()
        repeat(runs) { alignFace(frame, box, face) }
        val align = (SystemClock.elapsedRealtimeNanos() - ta) / runs
        val pss0 = gcPss()
        val emb = TfliteEmbedder("v", { ModelFiles.load(context, "models/$model.tflite") }, threads, xnnpack)
        val tl = SystemClock.elapsedRealtimeNanos()
        emb.embed(face)
        val first = SystemClock.elapsedRealtimeNanos() - tl
        val tr = SystemClock.elapsedRealtimeNanos()
        repeat(runs) { emb.embed(face) }
        val per = (SystemClock.elapsedRealtimeNanos() - tr) / runs
        val loaded = pssMb()
        val drawn = FaceImage().also { SyntheticFace.rgb(112, 112, 56f, 56f, 30f).copyInto(it.rgb) }
        val cos = reference("models/selftest_drawn112_${model.substringBeforeLast('_')}.txt")?.let { cosine(emb.embed(drawn), it) }
        emb.release()
        val after = gcPss()
        SystemClock.sleep(3000)
        val later = gcPss()
        log("variant $model threads=$threads xnnpack=$xnnpack: ${ms(per)} ms/embedding (first ${ms(first)}, interpreter ${emb.loadMs} ms); " +
            "cos vs original ${cos?.let { "%.4f".format(it) } ?: "-"}; pss ${"%.1f".format(pss0)} → loaded ${"%.1f".format(loaded)} (+${"%.1f".format(loaded - pss0)}) → released ${"%.1f".format(after)} → +3 s ${"%.1f".format(later)} MB; align ${ms(align)} ms")
        return out
    }

    private fun reference(path: String): FloatArray? = try {
        context.assets.open(path).bufferedReader().use { r -> r.readText().trim().split(',').map { it.toFloat() }.toFloatArray() }
    } catch (_: java.io.IOException) { null }

    fun run(): List<String> {
        SystemClock.sleep(SETTLE_MS)
        val frames = listOf(Triple(160f, 120f, 40f), Triple(110f, 140f, 30f), Triple(200f, 100f, 55f), Triple(160f, 120f, 18f))
            .map { (x, y, r) -> Triple(SyntheticFace.frame(320, 240, x, y, r), SyntheticFace.eyes(x, y, r), r) }
        val pss0 = gcPss()
        log("pss start ${"%.1f".format(pss0)} MB")

        val legacy = LegacyFaceFinder()
        detect(legacy, frames)
        legacy.close()

        val t0 = SystemClock.elapsedRealtimeNanos()
        val blaze = BlazeFaceFinder(ModelFiles.load(context, ModelFiles.DETECTOR))
        log("blazeface load ${ms(SystemClock.elapsedRealtimeNanos() - t0)} ms")
        val box = detect(blaze, frames)
        val pssBlaze = pssMb()
        log("pss blazeface loaded ${"%.1f".format(pssBlaze)} MB (+${"%.1f".format(pssBlaze - pss0)})")

        val face = FaceImage()
        val frame = frames[0].first
        val ta = SystemClock.elapsedRealtimeNanos()
        repeat(runs) { alignFace(frame, box ?: FaceBox(160f, 113f, 30f, 1f), face) }
        log("align ${ms((SystemClock.elapsedRealtimeNanos() - ta) / runs)} ms (landmarks=${box?.landmarks != null})")

        // The exact 112×112 drawing (no camera conversion), for comparing with the desktop reference.
        val drawn = FaceImage().also { SyntheticFace.rgb(112, 112, 56f, 56f, 30f).copyInto(it.rgb) }
        for (m in ModelFiles.EMBEDDERS) {
            val ref = reference(m.reference)
            val e = TfliteEmbedder("selftest", { ModelFiles.load(context, m.path) }, m.threads, m.xnnpack)
            val before = gcPss()
            val tl = SystemClock.elapsedRealtimeNanos()
            e.embed(face)
            val load = SystemClock.elapsedRealtimeNanos() - tl
            val tr = SystemClock.elapsedRealtimeNanos()
            repeat(runs) { e.embed(face) }
            val per = (SystemClock.elapsedRealtimeNanos() - tr) / runs
            val loaded = pssMb()
            val mine = e.embed(drawn)
            log("embed ${m.id}: first ${ms(load)} ms (interpreter ${e.loadMs} ms), then ${ms(per)} ms/embedding; " +
                "pss +${"%.1f".format(loaded - before)} MB; cos vs original framework on the drawn face ${ref?.let { "%.4f".format(cosine(mine, it)) } ?: "-"}")
            e.release()
            log("pss after ${m.key} release ${"%.1f".format(gcPss())} MB")
        }
        val lbp = LbpEmbedder()
        val tb = SystemClock.elapsedRealtimeNanos()
        repeat(runs) { lbp.embed(face) }
        log("embed lbp ${ms((SystemClock.elapsedRealtimeNanos() - tb) / runs)} ms")
        blaze.close()
        log("pss after all released ${"%.1f".format(gcPss())} MB (start ${"%.1f".format(pss0)})")
        return out
    }

    private fun detect(f: FaceFinder, frames: List<Triple<app.vesper.feature.camera.capture.YPlane, FloatArray, Float>>): FaceBox? {
        var first: FaceBox? = null
        val t = SystemClock.elapsedRealtimeNanos()
        val found = IntArray(frames.size)
        val err = FloatArray(frames.size)
        repeat(runs) {
            frames.forEachIndexed { i, (fr, eyes, _) ->
                val b = f.find(fr).firstOrNull()
                if (b != null) {
                    found[i]++
                    val mx = (eyes[0] + eyes[2]) / 2; val my = (eyes[1] + eyes[3]) / 2
                    err[i] = kotlin.math.hypot(b.cx - mx, b.cy - my)
                    if (i == 0 && first == null) first = b
                }
            }
        }
        val per = (SystemClock.elapsedRealtimeNanos() - t) / (runs * frames.size)
        log("detect ${f.name}: ${ms(per)} ms/frame (320×240); found per frame ${found.joinToString("/")} of $runs (r=${frames.joinToString("/") { it.third.toInt().toString() }}); " +
            "eye-mid error px ${err.joinToString("/") { "%.1f".format(it) }}; first=$first")
        return first
    }

    /** The app's other features finish starting up first (their PSS isn't counted as the model's). */
    private companion object { const val SETTLE_MS = 10_000L }

    private fun ms(ns: Long) = "%.1f".format(ns / 1e6)
}
