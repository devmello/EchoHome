package com.echobedside.feature.phone

/** What the pairing screen shows. */
data class PhoneState(
    val bluetoothOn: Boolean = false,
    val echoName: String = "Echo Show 5",
    /** LE advertising is running (the iPhone can find / reconnect to the Echo). */
    val advertising: Boolean = false,
    /** The gate: LE advertising works on this controller; null until probed. */
    val advertiseOk: Boolean? = null,
    val advertiseError: String? = null,
    /** Visible to Classic discovery (for pairing as a speaker). */
    val discoverable: Boolean = false,
    val pairingMode: Boolean = false,
    val pairing: PairingPrompt? = null,
    val phones: List<PhoneInfo> = emptyList(),
    val notificationsEnabled: Boolean = true,
    val audioEnabled: Boolean = true,
    /** One line for the status capsule ("Waiting for your iPhone", "Pairing…"). */
    val status: String = "",
)

data class PairingPrompt(val deviceName: String, val address: String, val passkey: String?, val variant: Int)

data class PhoneInfo(
    val address: String,
    val name: String,
    val bonded: Boolean,
    /** ANCS subscribed: notifications flow. */
    val notificationsConnected: Boolean,
    /** A2DP sink connected: it can stream to the Echo. */
    val audioConnected: Boolean,
)
