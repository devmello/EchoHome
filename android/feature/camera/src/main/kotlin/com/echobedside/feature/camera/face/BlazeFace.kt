package com.echobedside.feature.camera.face

import com.echobedside.feature.camera.capture.YPlane
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import org.tensorflow.lite.Interpreter

/**
 * MediaPipe BlazeFace short-range (face_detection_short_range graph options): 128×128 RGB in
 * [-1, 1], 896 SSD anchors, 16 regressors per anchor (box centre + size, then six keypoints,
 * all in input pixels relative to the anchor centre), one logit per anchor, weighted NMS.
 */
object BlazeFace {
    const val INPUT = 128
    const val NUM_ANCHORS = 896
    const val NUM_COORDS = 16
    const val MIN_SCORE = 0.5f
    const val NMS_IOU = 0.3f

    /**
     * Anchor centres (x, y pairs, 0..1): SsdAnchorsCalculator with strides 8,16,16,16, two
     * anchors per layer, layers of the same stride sharing a grid, fixed anchor size.
     */
    val ANCHORS: FloatArray = run {
        val strides = intArrayOf(8, 16, 16, 16)
        val out = FloatArray(NUM_ANCHORS * 2)
        var k = 0
        var i = 0
        while (i < strides.size) {
            val s = strides[i]
            var perCell = 0
            while (i < strides.size && strides[i] == s) { perCell += 2; i++ }
            val f = INPUT / s
            for (y in 0 until f) for (x in 0 until f) repeat(perCell) {
                out[k++] = (x + 0.5f) / f; out[k++] = (y + 0.5f) / f
            }
        }
        check(k == out.size)
        out
    }

    /**
     * Decodes raw outputs ([reg] = 896×16, [logits] = 896) into faces in frame pixels (via [lb]),
     * largest first.
     */
    fun decode(reg: FloatArray, logits: FloatArray, lb: Letterbox, minScore: Float = MIN_SCORE, maxFaces: Int = 4): List<FaceBox> {
        val minLogit = ln(minScore / (1f - minScore))
        val cand = ArrayList<Int>()
        for (a in 0 until NUM_ANCHORS) if (logits[a] >= minLogit) cand += a
        if (cand.isEmpty()) return emptyList()
        val score = FloatArray(NUM_ANCHORS)
        for (a in cand) score[a] = 1f / (1f + exp(-logits[a].coerceIn(-100f, 100f)))
        cand.sortByDescending { score[it] }
        // Normalised boxes (xmin, ymin, xmax, ymax) and keypoints for each candidate.
        val box = FloatArray(NUM_ANCHORS * 4)
        val kp = FloatArray(NUM_ANCHORS * 12)
        for (a in cand) {
            val ax = ANCHORS[2 * a]; val ay = ANCHORS[2 * a + 1]
            val o = a * NUM_COORDS
            val cx = ax + reg[o] / INPUT; val cy = ay + reg[o + 1] / INPUT
            val w = reg[o + 2] / INPUT; val h = reg[o + 3] / INPUT
            box[4 * a] = cx - w / 2; box[4 * a + 1] = cy - h / 2; box[4 * a + 2] = cx + w / 2; box[4 * a + 3] = cy + h / 2
            for (j in 0 until 6) { kp[12 * a + 2 * j] = ax + reg[o + 4 + 2 * j] / INPUT; kp[12 * a + 2 * j + 1] = ay + reg[o + 5 + 2 * j] / INPUT }
        }
        val out = ArrayList<FaceBox>()
        val left = cand.toMutableList()
        while (left.isNotEmpty() && out.size < maxFaces) {
            val top = left[0]
            // Weighted NMS: average everything overlapping the best box, weighted by score.
            var wsum = 0f
            val lm = FloatArray(12)
            val it = left.iterator()
            while (it.hasNext()) {
                val a = it.next()
                if (a != top && iou(box, top, a) <= NMS_IOU) continue
                val w = score[a]
                wsum += w
                for (j in 0 until 12) lm[j] += kp[12 * a + j] * w
                it.remove()
            }
            for (j in 0 until 12) {
                val m = lm[j] / wsum * INPUT
                lm[j] = if (j % 2 == 0) lb.toFrameX(m) else lb.toFrameY(m)
            }
            out += faceBox(lm, score[top])
        }
        out.sortByDescending { it.eyeDist }
        return out
    }

    /** Eye midpoint + eye distance from the keypoints (frame px). */
    fun faceBox(lm: FloatArray, score: Float): FaceBox {
        val ex0 = lm[2 * Landmark.RIGHT_EYE]; val ey0 = lm[2 * Landmark.RIGHT_EYE + 1]
        val ex1 = lm[2 * Landmark.LEFT_EYE]; val ey1 = lm[2 * Landmark.LEFT_EYE + 1]
        return FaceBox((ex0 + ex1) / 2f, (ey0 + ey1) / 2f, hypot(ex1 - ex0, ey1 - ey0), score, lm)
    }

    private fun iou(b: FloatArray, i: Int, j: Int): Float {
        val ix0 = max(b[4 * i], b[4 * j]); val iy0 = max(b[4 * i + 1], b[4 * j + 1])
        val ix1 = min(b[4 * i + 2], b[4 * j + 2]); val iy1 = min(b[4 * i + 3], b[4 * j + 3])
        val inter = max(0f, ix1 - ix0) * max(0f, iy1 - iy0)
        val ai = (b[4 * i + 2] - b[4 * i]) * (b[4 * i + 3] - b[4 * i + 1])
        val aj = (b[4 * j + 2] - b[4 * j]) * (b[4 * j + 3] - b[4 * j + 1])
        val u = ai + aj - inter
        return if (u <= 0f) 0f else inter / u
    }
}

/**
 * Stage 3 with BlazeFace on TFLite (XNNPACK, [threads] threads). Gives eye/nose/mouth landmarks
 * for alignment and finds turned / tilted faces `android.media.FaceDetector` misses.
 * Loaded when created; [close] frees the interpreter.
 */
class BlazeFaceFinder(model: ByteBuffer, threads: Int = 2) : FaceFinder {
    override val name = "blazeface"
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(threads).setUseXNNPACK(true))
    private val input = ByteBuffer.allocateDirect(BlazeFace.INPUT * BlazeFace.INPUT * 3 * 4).order(ByteOrder.nativeOrder())
    private val inputF = input.asFloatBuffer()
    private val regBuf = ByteBuffer.allocateDirect(BlazeFace.NUM_ANCHORS * BlazeFace.NUM_COORDS * 4).order(ByteOrder.nativeOrder())
    private val clsBuf = ByteBuffer.allocateDirect(BlazeFace.NUM_ANCHORS * 4).order(ByteOrder.nativeOrder())
    private val reg = FloatArray(BlazeFace.NUM_ANCHORS * BlazeFace.NUM_COORDS)
    private val cls = FloatArray(BlazeFace.NUM_ANCHORS)
    private val regIdx: Int
    private val clsIdx: Int

    init {
        var r = -1; var c = -1
        for (i in 0 until interpreter.outputTensorCount) {
            val shape = interpreter.getOutputTensor(i).shape()
            when (shape.last()) { BlazeFace.NUM_COORDS -> r = i; 1 -> c = i }
        }
        val inShape = interpreter.getInputTensor(0).shape()
        if (r < 0 || c < 0 || inShape.contentToString() != "[1, 128, 128, 3]") {
            interpreter.close()
            throw IllegalStateException("unexpected BlazeFace model: in=${inShape.contentToString()}")
        }
        regIdx = r; clsIdx = c
    }

    override fun find(frame: YPlane): List<FaceBox> {
        val lb = letterbox(frame, BlazeFace.INPUT, inputF)
        input.rewind(); regBuf.rewind(); clsBuf.rewind()
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), mapOf<Int, Any>(regIdx to regBuf, clsIdx to clsBuf))
        regBuf.rewind(); regBuf.asFloatBuffer().get(reg)
        clsBuf.rewind(); clsBuf.asFloatBuffer().get(cls)
        return BlazeFace.decode(reg, cls, lb).mapNotNull { it.sane(frame.width, frame.height) }
    }

    override fun close() = interpreter.close()
}
