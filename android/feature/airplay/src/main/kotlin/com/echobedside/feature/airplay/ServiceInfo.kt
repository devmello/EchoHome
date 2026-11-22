package com.echobedside.feature.airplay

/**
 * The mDNS services the receiver must advertise, as reported by the native side
 * (`nativeServiceInfo`): `_raop._tcp` (AirPlay 1 + 2) and, in AirPlay 2 mode, `_airplay._tcp`.
 */
internal data class ServiceInfo(
    val port: Int,
    /** `AABBCCDDEEFF@Echo Bedside`. */
    val raopName: String,
    val airplayName: String,
    val ap2: Boolean,
    val raopTxt: List<Pair<String, String>>,
    val airplayTxt: List<Pair<String, String>>,
) {
    companion object {
        fun parse(text: String): ServiceInfo {
            var port = 0
            var raopName = ""
            var airplayName = ""
            var ap2 = false
            val raop = mutableListOf<Pair<String, String>>()
            val airplay = mutableListOf<Pair<String, String>>()
            for (line in text.lineSequence()) {
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq)
                val value = line.substring(eq + 1)
                when {
                    key == "port" -> port = value.toIntOrNull() ?: 0
                    key == "raop_name" -> raopName = value
                    key == "airplay_name" -> airplayName = value
                    key == "ap2" -> ap2 = value == "true"
                    key.startsWith("raop.") -> raop += key.removePrefix("raop.") to value
                    key.startsWith("airplay.") -> airplay += key.removePrefix("airplay.") to value
                }
            }
            return ServiceInfo(port, raopName, airplayName, ap2, raop, airplay)
        }
    }
}
