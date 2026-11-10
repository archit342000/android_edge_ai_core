package com.aanand.edgeaicore

/** The user's choice of backend for the vision or audio encoder; AUTO follows the main backend. */
enum class EncoderBackendOption {
    AUTO, CPU, GPU;

    companion object {
        /** Parses a stored or passed value; null or an unknown value gives [AUTO]. */
        fun fromStored(value: String?): EncoderBackendOption =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AUTO

        /** True when [value] is non-null but names no option, so the caller can log that it fell back to [AUTO]. */
        fun isUnknown(value: String?): Boolean = value != null && entries.none { it.name.equals(value, ignoreCase = true) }
    }
}
