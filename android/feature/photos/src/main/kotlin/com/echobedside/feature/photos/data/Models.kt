package com.echobedside.feature.photos.data

import java.time.LocalDateTime

/** An Immich album (or a demo album). */
data class Album(
    val id: String,
    val name: String,
    val assetCount: Int,
)

/**
 * One photo in an album.
 * @param takenAt local wall-clock time it was taken (Immich `localDateTime`), null if unknown
 * @param place "Big Sur" — city, else state, else country (Immich exifInfo), null if unknown
 * @param width/height original pixel size if known (0 = unknown), used to plan the decode
 */
data class PhotoAsset(
    val id: String,
    val takenAt: LocalDateTime?,
    val place: String? = null,
    val width: Int = 0,
    val height: Int = 0,
) {
    /**
     * Slow-zoom origin as fractions of the screen, like the design's per-photo
     * `transform-origin` (60% 55%, 40% 45%, 50% 40%). Stable per asset.
     */
    val pivotX: Float get() = 0.40f + ((id.hashCode() ushr 4) % 21) / 100f
    val pivotY: Float get() = 0.40f + ((id.hashCode() ushr 12) % 16) / 100f
}

/** Where the slideshow's data came from: [offline] = network failed, playing from the disk cache. */
data class Loaded<T>(val value: T, val offline: Boolean)

/** Thrown for Immich HTTP errors (401 bad key, 404 album gone, 5xx). Network failures stay IOException. */
class ImmichHttpException(val code: Int, message: String) : java.io.IOException(message)

/** A preview that downloaded but doesn't decode (not an IOException: the network is fine). */
class BadImageException(message: String) : Exception(message)
