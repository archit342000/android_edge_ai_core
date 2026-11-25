package com.aanand.edgeaicore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlsPolicyTest {
    private fun state(
        kind: ServerStatus = ServerStatus.OFF,
        pending: PendingAction = PendingAction.NONE,
        model: Boolean = true,
        token: Boolean = true,
        busy: Set<BusyTask> = emptySet(),
    ) = ControlsPolicy.compute(ControlsState(ServerStatusSnapshot(kind, "detail"), pending, model, token, busy))

    @Test
    fun offWithAModelLetsTheServerStart() {
        val c = state(ServerStatus.OFF)
        assertFalse(c.switchChecked)
        assertTrue(c.switchEnabled)
        assertNull(c.switchReason)
        assertEquals(OffReason.START_SERVER_FIRST, c.testsReason)
        assertTrue(c.tests.values.none { it.enabled })
        assertFalse(c.working)
        assertNull(c.errorAction)
    }

    @Test
    fun offWithoutAModelSaysSelectAModelFirst() {
        val c = state(ServerStatus.OFF, model = false)
        assertFalse(c.switchEnabled)
        assertEquals(OffReason.SELECT_MODEL_FIRST, c.switchReason)
    }

    @Test
    fun startingIsCheckedSwitchedOffWithoutAReasonAndWorking() {
        val c = state(ServerStatus.STARTING)
        assertTrue(c.switchChecked)
        assertFalse(c.switchEnabled)
        assertNull(c.switchReason)
        assertTrue(c.working)
        assertEquals(OffReason.START_SERVER_FIRST, c.testsReason)
    }

    @Test
    fun readyEnablesTheSwitchAndEveryTest() {
        val c = state(ServerStatus.READY)
        assertTrue(c.switchChecked)
        assertTrue(c.switchEnabled)
        assertNull(c.testsReason)
        assertTrue(c.tests.values.all { it.enabled && !it.busy })
        assertFalse(c.working)
    }

    @Test
    fun anErrorOffersTryAgainWithAModelAndSelectModelWithout() {
        val withModel = state(ServerStatus.ERROR)
        assertEquals(ErrorAction.TRY_AGAIN, withModel.errorAction)
        assertTrue(withModel.switchEnabled)
        assertFalse(withModel.switchChecked)
        val noModel = state(ServerStatus.ERROR, model = false)
        assertEquals(ErrorAction.SELECT_MODEL, noModel.errorAction)
        assertEquals(OffReason.SELECT_MODEL_FIRST, noModel.switchReason)
    }

    @Test
    fun theErrorActionWaitsForAModelCopy() {
        assertTrue(state(ServerStatus.ERROR).errorActionEnabled)
        val copying = state(ServerStatus.ERROR, busy = setOf(BusyTask.MODEL_COPY))
        assertEquals(ErrorAction.TRY_AGAIN, copying.errorAction)
        assertFalse(copying.errorActionEnabled)
        assertFalse(state(ServerStatus.ERROR, model = false, busy = setOf(BusyTask.MODEL_COPY)).errorActionEnabled)
    }

    @Test
    fun aPendingStartReadsAsStartingWhateverIsStored() {
        val c = state(ServerStatus.ERROR, pending = PendingAction.START)
        assertEquals(ServerStatus.STARTING, c.kind)
        assertTrue(c.switchChecked)
        assertFalse(c.switchEnabled)
        assertNull(c.errorAction)
    }

    @Test
    fun aPendingStopReadsAsOff() {
        val c = state(ServerStatus.READY, pending = PendingAction.STOP)
        assertEquals(ServerStatus.OFF, c.kind)
        assertFalse(c.switchChecked)
        assertTrue(c.tests.values.none { it.enabled })
    }

    @Test
    fun aBusyTestIsBusyAndSwitchedOffWhileTheOthersStayUsable() {
        val c = state(ServerStatus.READY, busy = setOf(BusyTask.TEST_VISION))
        assertEquals(ControlState(enabled = false, busy = true), c.tests[BusyTask.TEST_VISION])
        assertEquals(ControlState(enabled = true, busy = false), c.tests[BusyTask.TEST_INFERENCE])
        assertTrue(c.working)
    }

    @Test
    fun aBusyTestStopsBeingUsableWhenTheServerStops() {
        val c = state(ServerStatus.OFF, busy = setOf(BusyTask.TEST_AUDIO))
        assertFalse(c.tests.getValue(BusyTask.TEST_AUDIO).enabled)
        assertEquals(OffReason.START_SERVER_FIRST, c.testsReason)
    }

    @Test
    fun aModelCopyKeepsTheSwitchOffAndSelectModelBusy() {
        val c = state(ServerStatus.OFF, model = false, busy = setOf(BusyTask.MODEL_COPY))
        assertFalse(c.switchEnabled)
        assertEquals(OffReason.WAIT_FOR_COPY, c.switchReason)
        assertEquals(ControlState(enabled = false, busy = true), c.selectModel)
        assertTrue(c.working)
    }

    @Test
    fun backupAndRestoreAreBusyOnlyThemselves() {
        val c = state(busy = setOf(BusyTask.BACKUP))
        assertEquals(ControlState(enabled = false, busy = true), c.backup)
        assertEquals(ControlState(enabled = true, busy = false), c.restore)
        val r = state(busy = setOf(BusyTask.RESTORE))
        assertEquals(ControlState(enabled = false, busy = true), r.restore)
    }

    @Test
    fun withoutATokenCopyAndDeleteSayGenerateOneFirst() {
        val c = state(token = false)
        assertFalse(c.copyToken.enabled)
        assertFalse(c.deleteToken.enabled)
        assertEquals(OffReason.GENERATE_TOKEN_FIRST, c.tokenReason)
        val t = state(token = true)
        assertTrue(t.copyToken.enabled && t.deleteToken.enabled)
        assertNull(t.tokenReason)
    }
}
