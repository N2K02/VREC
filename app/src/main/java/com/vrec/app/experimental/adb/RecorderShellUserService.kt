package com.vrec.app.experimental.adb

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.TimeUnit

/** Runs only in Shizuku's authorized shell user-service process. */
class RecorderShellUserService : Binder() {
    init { attachInterface(null, DESCRIPTOR) }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == IBinder.INTERFACE_TRANSACTION) {
            reply?.writeString(DESCRIPTOR)
            return true
        }
        if (code == SHIZUKU_DESTROY) {
            android.os.Process.killProcess(android.os.Process.myPid())
            return true
        }
        if (code != EXECUTE) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(DESCRIPTOR)
        val cmd = data.readString() ?: throw IllegalArgumentException("Missing command")
        val proc = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().use { it.readText().take(16000) }
        if (!proc.waitFor(30, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw IllegalStateException("Shell command exceeded 30 seconds")
        }
        reply?.writeNoException()
        reply?.writeInt(proc.exitValue())
        reply?.writeString(output)
        return true
    }

    companion object {
        const val DESCRIPTOR = "com.vrec.app.experimental.adb.RecorderShellUserService"
        const val EXECUTE = IBinder.FIRST_CALL_TRANSACTION
        const val SHIZUKU_DESTROY = 16777114
    }
}
