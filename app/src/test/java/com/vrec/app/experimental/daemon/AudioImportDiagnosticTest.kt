package com.vrec.app.experimental.daemon

import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class AudioImportDiagnosticTest {
    @Test fun distinguishesIncompleteFetchFromTimeoutAndCorruptOgg() {
        assertEquals("TRANSFER_INCOMPLETE", AudioImportDiagnostic.code(EOFException()))
        assertEquals("TRANSFER_TIMEOUT", AudioImportDiagnostic.code(SocketTimeoutException()))
        assertEquals(
            "TRANSFER_INCOMPLETE",
            AudioImportDiagnostic.code(IllegalStateException("Invalid shell recording completion marker")),
        )
        assertEquals(
            "OGG_INTEGRITY",
            AudioImportDiagnostic.code(IllegalStateException("Ogg pages failed CRC, packet, or end-of-stream validation")),
        )
        assertEquals(
            "OPUS_PACKET",
            AudioImportDiagnostic.code(IllegalArgumentException("Invalid Opus packet duration")),
        )
        assertEquals("TRANSFER_IO", AudioImportDiagnostic.code(IOException("broken pipe")))
    }

    @Test fun warningNeverExposesExceptionMessageOrCallInformation() {
        val warning = AudioImportDiagnostic.warning(
            IOException("caller=12345678 /data/local/tmp/vrec_rec_sensitive.dat secret=private")
        )
        assertTrue(warning.contains("TRANSFER_IO"))
        assertTrue(warning.contains("kept for retry"))
        assertFalse(warning.contains("12345678"))
        assertFalse(warning.contains("/data/local/tmp"))
        assertFalse(warning.contains("secret"))
    }
}
