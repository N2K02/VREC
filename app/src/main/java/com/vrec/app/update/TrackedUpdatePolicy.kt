package com.vrec.app.update

internal object TrackedUpdatePolicy {
    private val versionLine = Regex("""(?im)^[ \t]*Version code:[ \t]*(\d+)[ \t]*$""")
    private val hash = Regex("""sha256:([a-fA-F0-9]{64})""")

    fun versionCode(body: String): Long? = versionLine.findAll(body).singleOrNull()
        ?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }

    fun digest(metadata: String): String? = hash.matchEntire(metadata)?.groupValues?.get(1)?.lowercase()

    fun matchesAsset(
        repository: String,
        tag: String,
        track: String,
        stableVersion: String?,
        name: String,
        url: String,
    ): Boolean {
        val version = stableVersion?.let(Regex::escape) ?: "[0-9]+[.][0-9]+-experimental[.][0-9]+"
        val expected = Regex("^VREC-${Regex.escape(track)}-$version[.]apk$", RegexOption.IGNORE_CASE)
        val prefix = "https://github.com/$repository/releases/download/"
        return expected.matches(name) && url.startsWith(prefix, ignoreCase = true) &&
            url.substring(prefix.length) == "$tag/$name"
    }

    fun acceptsSigningUpdate(installed: List<String>, incoming: List<String>, history: List<String>): Boolean {
        if (installed.isEmpty() || incoming.isEmpty()) return false
        if (installed.toSet() == incoming.toSet()) return true
        return installed.size == 1 && incoming.size == 1 &&
            incoming.single() == history.lastOrNull() && installed.single() in history
    }
}
