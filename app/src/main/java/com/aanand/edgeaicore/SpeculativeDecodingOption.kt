package com.aanand.edgeaicore

import com.google.ai.edge.litertlm.SpeculativeDecodingMode

/** The user's speculative decoding (MTP) setting. ADAPTIVE speculates only while that is measured to be faster. */
enum class SpeculativeDecodingOption {
    OFF, ON, ADAPTIVE;

    companion object {
        val DEFAULT = ADAPTIVE

        /** Parses a stored or passed value; null or an unknown value gives [DEFAULT]. */
        fun fromStored(value: String?): SpeculativeDecodingOption =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DEFAULT

        /** True when [value] is non-null but names no option, so the caller can log that it fell back to [DEFAULT]. */
        fun isUnknown(value: String?): Boolean = value != null && entries.none { it.name.equals(value, ignoreCase = true) }

        /**
         * The LiteRT-LM mode to configure, or null to leave speculative decoding unconfigured: for a model without a
         * drafter ([supported] false) and for [OFF].
         */
        fun toConfigMode(option: SpeculativeDecodingOption, supported: Boolean): SpeculativeDecodingMode? =
            if (!supported) null else when (option) {
                OFF -> null
                ON -> SpeculativeDecodingMode.ON
                ADAPTIVE -> SpeculativeDecodingMode.ADAPTIVE
            }
    }
}
