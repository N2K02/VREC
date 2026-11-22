package com.vrec.app.experimental.daemon

import java.io.EOFException
import java.net.SocketTimeoutException

/**
 * Safe, actionable import failure reasons for the warning UI. Never expose
 * shell paths, contact data, filenames, tokens, or raw exception messages.
 */
internal object AudioImportDiagnostic {
    fun code(error: Throwable): String = when {
        error is SocketTimeoutException -> "TRANSFER_TIMEOUT"
        error is EOFException -> "TRANSFER_INCOMPLETE"
        error.message?.contains("completion", ignoreCase = true) == true -> "TRANSFER_INCOMPLETE"
        error.message?.contains("Ogg pages failed", ignoreCase = true) == true ||
            error.message?.contains("integrity verification", ignoreCase = true) == true ->
            "OGG_INTEGRITY"
        error.message?.contains("Opus", ignoreCase = true) == true ||
            error.message?.contains("packet duration", ignoreCase = true) == true ->
            "OPUS_PACKET"
        error is java.io.IOException -> "TRANSFER_IO"
        else -> "AUDIO_FINALIZATION"
    }

    fun warning(error: Throwable): String =
        "VRec could not import the recording audio (${code(error)}). " +
            "The original shell capture was kept for retry."
}
