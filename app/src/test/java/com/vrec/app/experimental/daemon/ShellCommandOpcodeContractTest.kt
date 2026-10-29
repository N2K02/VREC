package com.vrec.app.experimental.daemon

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shell protocol is byte-oriented. Reusing one opcode for two commands
 * silently dispatches the first handler, which previously prevented the SIM
 * source acknowledgement and therefore blocked recorder startup.
 */
class ShellCommandOpcodeContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun everyShellCommandHasADistinctOpcode() {
        val shell = source("shellserver/src/com/vrec/app/shell/Main.java")
        val pattern = Regex("""private static final int (COMMAND_[A-Z_]+) = '([^'])';""")
        val mapping = pattern.findAll(shell).associate { it.groupValues[1] to it.groupValues[2] }
        assertTrue("Expected registered shell control opcodes", mapping.size >= 19)
        val duplicates = mapping.entries.groupBy { it.value }.filterValues { it.size > 1 }
        assertTrue("Colliding shell opcodes: $duplicates", duplicates.isEmpty())
    }

    @Test fun simSetupUsesItsOwnOpcodeOnBothSides() {
        val shell = source("shellserver/src/com/vrec/app/shell/Main.java")
        val client = source("app/src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
        assertTrue(shell.contains("COMMAND_SET_SIM_SOURCE = 'J'"))
        assertTrue(shell.contains("COMMAND_CAPTURE_CONTROL = 'Y'"))
        assertTrue(shell.contains("COMMAND_GO_AHEAD = 'O'"))
        assertTrue(shell.contains("COMMAND_HEALTH = 'H'"))
        assertTrue(shell.contains("COMMAND_HANDOVER = 'B'"))
        assertTrue(client.contains("open(context, 'J', byteArrayOf(selected.wireValue.toByte()))"))
        assertTrue(client.contains("open(context, 'Y', byteArrayOf(action.code.toByte()))"))
        assertFalse("Legacy SIM opcode would be interpreted as capture control",
            client.contains("open(context, 'Y', byteArrayOf(selected.wireValue.toByte()))"))
    }

    @Test fun sourceReplyMustStillBeVerifiedBeforeShowingReady() {
        assertEquals(CaptureSetupProtocol.Source.APPLIED,
            CaptureSetupProtocol.classifySource("SOURCE 0", 0))
        assertEquals(CaptureSetupProtocol.Source.UNCONFIRMED,
            CaptureSetupProtocol.classifySource("NO_RECORDING", 0))
        assertFalse(CaptureSetupProtocol.evaluate(
            "1 WAIT 0", "NO_RECORDING", true, false, 0).ready)
    }
}
