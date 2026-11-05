package com.vrec.app.calls

/** Names and numbers observed in a call stay separate from address-book guesses. */
internal object VoipIdentityPolicy {
    fun name(current: String?, currentSource: String?, candidate: String?, source: String): String? {
        return if (prefersCandidate(current, currentSource, candidate, source)) candidate else current
    }

    fun prefersCandidate(current: String?, currentSource: String?, candidate: String?, source: String): Boolean {
        // A generic status title must never displace a verified caller name.
        if (CallMetadataResolver.cleanAppName(candidate) == null) return false
        fun rank(value: String?) = when (value) {
            "notification" -> 3 // App's call title may contain a whole group.
            "telecom" -> 2
            else -> 0
        }
        return current.isNullOrBlank() || rank(source) >= rank(currentSource)
    }

    /** Accept explicit numbers only; never split names or infer participants from contacts. */
    fun numbers(vararg groups: Collection<String>): List<String> = groups.flatMap { it }
        .mapNotNull { raw ->
            val value = raw.filterNot { Character.getType(it) == Character.FORMAT.toInt() }
                .replace(Regex("[\\s\\p{Zs}]+"), " ").trim().let { if (it.startsWith("tel:", true)) it.substring(4) else it }
            value.takeIf { Regex("^\\+?[0-9() .-]+$").matches(it) && it.count(Char::isDigit) in 5..18 }
        }.distinctBy { it.filter(Char::isDigit) }
}
