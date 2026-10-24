package com.vrec.app.experimental.adb

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

/** Supported Shizuku v13 UserService bridge, not inaccessible newProcess. */
object ShizukuShell {
    @Volatile private var appContext: Context? = null
    @Volatile private var currentBinder: IBinder? = null
    @Volatile private var pending: CompletableDeferred<IBinder>? = null

    fun init(context: Context) { appContext = context.applicationContext }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                pending?.completeExceptionally(IllegalStateException("Shizuku service returned no binder"))
            } else {
                currentBinder = binder
                pending?.complete(binder)
            }
            pending = null
        }
        override fun onServiceDisconnected(name: ComponentName?) { currentBinder = null }
    }

    private suspend fun bound(): IBinder {
        currentBinder?.takeIf { it.pingBinder() }?.let { return it }
        check(Shizuku.pingBinder()) { "Shizuku is not running" }
        check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            "Authorize VRec in Shizuku"
        }
        val context = appContext ?: error("Shizuku bridge not initialized")
        val callback = CompletableDeferred<IBinder>()
        pending = callback
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, RecorderShellUserService::class.java.name)
        ).daemon(false).processNameSuffix("vrec_capture").debuggable(false).version(1)
        Shizuku.bindUserService(args, connection)
        return withTimeout(20_000L) { callback.await() }
    }

    suspend fun run(command: String): String {
        val binder = bound()
        return withContext(Dispatchers.IO) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(RecorderShellUserService.DESCRIPTOR)
                data.writeString(command)
                check(binder.transact(RecorderShellUserService.EXECUTE, data, reply, 0)) {
                    "Shizuku shell command transaction failed"
                }
                reply.readException()
                val code = reply.readInt()
                val output = reply.readString().orEmpty()
                check(code == 0) { "Shizuku command failed (" + code + "): " + output.take(400) }
                output
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }
}
