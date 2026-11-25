package com.aanand.edgeaicore

/** A card on the Server page that explains a system request before the phone's own "Allow?" box (rule book 7.11). */
enum class ExplainCard { NOTIFICATIONS, BATTERY }

/** What the explain-card decision depends on. */
data class ExplainCardState(
    /** The stored server status; cards show only once the server is ready, the point where they matter. */
    val kind: ServerStatus,
    /** Notifications are missing: not granted, and only on API 33 and later where they are a runtime permission. */
    val notificationsMissing: Boolean,
    /** The battery-optimisation exemption is missing. */
    val batteryMissing: Boolean,
    val notificationsAnswered: Boolean,
    val batteryAnswered: Boolean,
    /** The card whose request is waiting for its result, if any. */
    val awaiting: ExplainCard?,
)

/** The card to show, and whether its buttons can be pressed. */
data class ExplainCardChoice(val card: ExplainCard?, val buttonsEnabled: Boolean)

/**
 * Decides which explain card the Server page shows. Notifications come before the battery exemption; any answer ("Not now",
 * a denial, a grant, coming back from the battery screen) hides that card for good; a card waiting for its result stays,
 * with its buttons switched off. Starting the server never waits for a card. Plain Kotlin.
 */
object ExplainCardPolicy {
    fun next(state: ExplainCardState): ExplainCardChoice {
        state.awaiting?.let { return ExplainCardChoice(it, buttonsEnabled = false) }
        if (state.kind != ServerStatus.READY) return ExplainCardChoice(null, buttonsEnabled = false)
        val card = when {
            state.notificationsMissing && !state.notificationsAnswered -> ExplainCard.NOTIFICATIONS
            state.batteryMissing && !state.batteryAnswered -> ExplainCard.BATTERY
            else -> null
        }
        return ExplainCardChoice(card, buttonsEnabled = card != null)
    }
}
