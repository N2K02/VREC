package com.vrec.app.experimental.daemon

/** Preserve damaged shell originals while reporting successful sibling imports. */
internal object ImportBatchPolicy {
    fun message(imported: Int, failed: Int): String =
        when {
            failed > 0 && imported > 0 -> "Imported $imported recordings; $failed failed and kept on the device for recovery."
            failed > 0 -> "$failed recordings could not be imported; originals kept on the device for recovery."
            imported > 0 -> "Imported $imported enhanced recordings."
            else -> "No pending enhanced recordings."
        }

    fun shouldFailBatch(imported: Int, failed: Int): Boolean = imported == 0 && failed > 0
}
