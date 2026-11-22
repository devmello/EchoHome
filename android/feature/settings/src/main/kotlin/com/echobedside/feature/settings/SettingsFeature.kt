package com.echobedside.feature.settings

import com.echobedside.core.model.EchoFeature
import com.echobedside.core.model.FeatureHost

/**
 * `:feature:settings` (agent K — web settings page).
 *
 * A small HTTP server on the LAN serving a settings web app for a phone or Mac browser, built from
 * `services.settingsSchema` (every feature's [com.echobedside.core.model.SettingSpec]s), writing
 * `services.settings` / `services.secrets`. Paired with a code shown on the Echo.
 */
object SettingsFeature : EchoFeature {
    override val id = "settings"

    override fun install(host: FeatureHost) {
        // Not built yet.
    }
}
