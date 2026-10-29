package com.vrec.app.calls

/**
 * Some WhatsApp self-managed Telecom calls also emit cellular phone-state
 * OFFHOOK. Never manufacture a simultaneous SIM call without independent
 * evidence from a real non-VoIP Telecom session.
 */
internal object CallSourceConflictPolicy {
    fun suppressFallbackSimOffhook(
        activeTelecomVoip: Boolean,
        knownCellularTelecom: Boolean,
    ): Boolean = activeTelecomVoip && !knownCellularTelecom
}
