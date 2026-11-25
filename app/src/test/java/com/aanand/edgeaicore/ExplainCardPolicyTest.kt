package com.aanand.edgeaicore

import org.junit.Assert.assertEquals
import org.junit.Test

class ExplainCardPolicyTest {
    private fun next(
        kind: ServerStatus = ServerStatus.READY,
        notifications: Boolean = true,
        battery: Boolean = true,
        notificationsAnswered: Boolean = false,
        batteryAnswered: Boolean = false,
        awaiting: ExplainCard? = null,
    ) = ExplainCardPolicy.next(ExplainCardState(kind, notifications, battery, notificationsAnswered, batteryAnswered, awaiting))

    @Test
    fun noCardUntilTheServerIsReady() {
        for (kind in listOf(ServerStatus.OFF, ServerStatus.STARTING, ServerStatus.ERROR)) {
            assertEquals(ExplainCardChoice(null, false), next(kind))
        }
    }

    @Test
    fun notificationsComeBeforeTheBatteryExemption() {
        assertEquals(ExplainCardChoice(ExplainCard.NOTIFICATIONS, true), next())
    }

    @Test
    fun anAnsweredCardIsNotShownAgain() {
        assertEquals(ExplainCardChoice(ExplainCard.BATTERY, true), next(notificationsAnswered = true))
        assertEquals(ExplainCardChoice(null, false), next(notificationsAnswered = true, batteryAnswered = true))
    }

    @Test
    fun aGrantedPermissionNeedsNoCard() {
        assertEquals(ExplainCardChoice(ExplainCard.BATTERY, true), next(notifications = false))
        assertEquals(ExplainCardChoice(null, false), next(notifications = false, battery = false))
    }

    @Test
    fun belowApi33NotificationsAreNeverMissing() {
        // The activity passes notificationsMissing = false below API 33.
        assertEquals(ExplainCardChoice(ExplainCard.BATTERY, true), next(notifications = false, battery = true))
    }

    @Test
    fun aCardWaitingForItsResultStaysWithItsButtonsOff() {
        assertEquals(ExplainCardChoice(ExplainCard.BATTERY, false), next(awaiting = ExplainCard.BATTERY, notificationsAnswered = true))
        // Even if the server stops meanwhile, the card stays until its result arrives.
        assertEquals(ExplainCardChoice(ExplainCard.NOTIFICATIONS, false), next(kind = ServerStatus.OFF, awaiting = ExplainCard.NOTIFICATIONS))
    }
}
