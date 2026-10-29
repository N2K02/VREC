package com.vrec.app.calls

/** Caller identity must come from Telecom, not from a missing number alone. */
internal object CallerPresentationPolicy {
    fun resolvedName(
        isCellular: Boolean,
        restrictedPresentation: Boolean,
        name: String?,
        number: String?,
    ): String? = name ?: if (isCellular && restrictedPresentation &&
        number.isNullOrBlank()) "Private number" else null
}
