package com.echobedside.feature.assistant

import com.echobedside.core.model.EchoFeature
import com.echobedside.core.model.FeatureHost

/**
 * `:feature:assistant` (agent G — Voice assistant).
 *
 * Assistant screen + droplet (Assistant.dc.html), HA Assist pipeline client streaming mic audio, transcript, steps, answer cards, TTS, assistant LiveActivity.
 *
 * Register screens/overlays/key handlers and bind owned services in [install]; see EchoFeature.
 */
object AssistantFeature : EchoFeature {
    override val id = "assistant"

    override fun install(host: FeatureHost) {
        // Not built yet.
    }
}
