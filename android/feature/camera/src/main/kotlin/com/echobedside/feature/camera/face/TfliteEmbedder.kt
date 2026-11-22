package com.echobedside.feature.camera.face

import android.content.Context
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import org.tensorflow.lite.Interpreter

/**
 * One face-embedding model in the assets (MODELS.md). [id] goes into every template, so
 * switching models means enrolling again.
 */
class EmbedderModel(
    /** Value of the `camera.embedder` setting. */
    val key: String,
    val title: String,
    val id: String,
    val path: String,
    val xnnpack: Boolean,
    val policy: MatchPolicy,
    val threads: Int = 2,
) {
    /** The original framework's embedding of the drawn 112×112 face (self-test). */
    val reference: String
        get() = "models/selftest_drawn112_" + path.substringAfterLast('/').substringBeforeLast('.').substringBeforeLast('_') + ".txt"
}

/** The model files in this module's assets (android/feature/camera/MODELS.md). */
object ModelFiles {
    /**
     * Idiap EdgeFace-XXS (47 M MACs, LFW 99.57 %), fp16 weights on XNNPACK: ~70 ms per embedding on
     * the Echo, the default.
     */
    val EDGEFACE_XXS = EmbedderModel("edgeface-xxs", "EdgeFace-XXS (fastest)", "edgeface-xxs-fp16-v1", "models/edgeface_xxs_fp16.tflite",
        xnnpack = true, policy = MatchPolicy.NEURAL)
    /**
     * Idiap EdgeFace-XS γ = 0.6 (76 M MACs, LFW 99.73 %, IJB-C 94.85 % at FAR 1e-4): ~110 ms,
     * the "more accurate" option. Faster and better on LFW than the MobileFaceNet it replaced (~0.3–0.44 s).
     */
    val EDGEFACE_XS = EmbedderModel("edgeface-xs", "EdgeFace-XS (more accurate)", "edgeface-xs-g06-fp16-v1", "models/edgeface_xs_fp16.tflite",
        xnnpack = true, policy = MatchPolicy.NEURAL)
    val EMBEDDERS = listOf(EDGEFACE_XXS, EDGEFACE_XS)
    fun embedder(key: String): EmbedderModel = EMBEDDERS.firstOrNull { it.key == key } ?: EMBEDDERS[0]

    const val DETECTOR = "models/blaze_face_short_range.tflite"

    /**
     * Memory-maps an asset (the app keeps .tflite uncompressed, so pages are shared and dropped
     * under pressure); reads it into a direct buffer if it was compressed after all.
     */
    fun load(context: Context, path: String): ByteBuffer = try {
        context.assets.openFd(path).use { fd ->
            FileInputStream(fd.fileDescriptor).use { s -> s.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
        }
    } catch (e: FileNotFoundException) {
        if (e.message?.contains("compressed") != true) throw e
        val bytes = context.assets.open(path).use { it.readBytes() }
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.rewind() }
    }
}

/**
 * A 512-d face model from [ModelFiles.EMBEDDERS] (EdgeFace, CosFace loss): aligned 112×112 RGB in, (x − 127.5)/127.5,
 * 512-d out, L2-normalised here. The interpreter is created on first use and freed by [release].
 */
class TfliteEmbedder(
    override val id: String,
    private val model: () -> ByteBuffer,
    private val threads: Int = 2,
    private val xnnpack: Boolean = true,
) : FaceEmbedder {
    override val dim = 512
    override val policy = MatchPolicy.NEURAL
    private var interpreter: Interpreter? = null
    private val input = ByteBuffer.allocateDirect(112 * 112 * 3 * 4).order(ByteOrder.nativeOrder())
    private val inputF = input.asFloatBuffer()
    private val output = ByteBuffer.allocateDirect(dim * 4).order(ByteOrder.nativeOrder())
    /** Last interpreter load time, ms (shown on the bench screen). */
    @Volatile var loadMs = 0L; private set
    val loaded: Boolean get() = interpreter != null

    @Synchronized
    override fun embed(face: FaceImage): FloatArray {
        require(face.size == 112) { "embedder needs 112×112" }
        val it = interpreter ?: open()
        inputF.clear()
        for (p in face.rgb) {
            inputF.put(((p shr 16) and 0xFF) / 127.5f - 1f)
            inputF.put(((p shr 8) and 0xFF) / 127.5f - 1f)
            inputF.put((p and 0xFF) / 127.5f - 1f)
        }
        input.rewind(); output.rewind()
        it.run(input, output)
        output.rewind()
        val v = FloatArray(dim)
        output.asFloatBuffer().get(v)
        return l2normalize(v)
    }

    private fun open(): Interpreter {
        val t0 = System.nanoTime()
        val it = Interpreter(model(), Interpreter.Options().setNumThreads(threads).setUseXNNPACK(xnnpack))
        val inShape = it.getInputTensor(0).shape().contentToString()
        val outShape = it.getOutputTensor(0).shape().contentToString()
        if (inShape != "[1, 112, 112, 3]" || outShape != "[1, $dim]") {
            it.close()
            throw IllegalStateException("unexpected embedder model: in=$inShape out=$outShape")
        }
        interpreter = it
        loadMs = (System.nanoTime() - t0) / 1_000_000
        return it
    }

    @Synchronized
    override fun release() {
        interpreter?.close()
        interpreter = null
    }
}
