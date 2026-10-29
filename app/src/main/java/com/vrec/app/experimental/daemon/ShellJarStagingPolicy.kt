package com.vrec.app.experimental.daemon

/**
 * Android shell cannot read app-specific external storage on some OEM/OS builds.
 * Prefer its private staging path; use a short-lived MediaStore Downloads entry
 * only if the shell proves the original file is unreadable.
 */
internal object ShellJarStagingPolicy {
    enum class Route { APP_EXTERNAL, MEDIASTORE_DOWNLOADS, UNSUPPORTED }

    fun choose(appExternalReadableByShell: Boolean, sdk: Int): Route = when {
        appExternalReadableByShell -> Route.APP_EXTERNAL
        sdk >= 29 -> Route.MEDIASTORE_DOWNLOADS
        else -> Route.UNSUPPORTED
    }
}
