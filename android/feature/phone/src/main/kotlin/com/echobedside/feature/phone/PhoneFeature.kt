package com.echobedside.feature.phone

import com.echobedside.core.model.EchoFeature
import com.echobedside.core.model.FeatureHost

/**
 * `:feature:phone` (agent J — iPhone over Bluetooth).
 *
 * iPhone notifications on the Echo via ANCS (Bluetooth LE) into services.notifications + island
 * (calls shown, answered on the phone), and the Echo as a Bluetooth speaker (A2DP sink + AVRCP)
 * publishing the phone's Now Playing into services.media as MediaSourceKind.BLUETOOTH.
 */
object PhoneFeature : EchoFeature {
    override val id = "phone"

    override fun install(host: FeatureHost) {
        // Not built yet.
    }
}
