package com.aanand.edgeaicore

/** What the person asked for that the service has not answered yet. */
enum class PendingAction { NONE, START, STOP }

/** Work started from the UI that keeps its control busy until it ends. */
enum class BusyTask { MODEL_COPY, TEST_INFERENCE, TEST_VISION, TEST_AUDIO, TEST_MULTI_TURN, TEST_HEALTH, TEST_LOAD, BACKUP, RESTORE }

/** Why a control is switched off, when the person can change it (rule book 7.1). */
enum class OffReason { SELECT_MODEL_FIRST, WAIT_FOR_COPY, START_SERVER_FIRST, GENERATE_TOKEN_FIRST }

/** The one action of the error strip. */
enum class ErrorAction { TRY_AGAIN, SELECT_MODEL }

/** Everything the activity's controls depend on. */
data class ControlsState(
    val status: ServerStatusSnapshot,
    val pending: PendingAction,
    val modelSelected: Boolean,
    val tokenExists: Boolean,
    val busy: Set<BusyTask>,
)

/** One pressable control: whether it can be pressed, and whether it is showing its busy state. */
data class ControlState(val enabled: Boolean, val busy: Boolean = false)

/** What every control shows. `MainActivity.render` applies it and nothing else sets these. */
data class Controls(
    val kind: ServerStatus,
    val switchChecked: Boolean,
    val switchEnabled: Boolean,
    val switchReason: OffReason?,
    val tests: Map<BusyTask, ControlState>,
    val testsReason: OffReason?,
    val selectModel: ControlState,
    val copyToken: ControlState,
    val deleteToken: ControlState,
    val tokenReason: OffReason?,
    val backup: ControlState,
    val restore: ControlState,
    val working: Boolean,
    val errorAction: ErrorAction?,
    /** False while a model copy runs: neither action may start until the file is complete. */
    val errorActionEnabled: Boolean,
)

/** Decides every control's state from [ControlsState]. Plain Kotlin, no Android calls. */
object ControlsPolicy {
    /** The six diagnostics tests. */
    val TESTS = listOf(
        BusyTask.TEST_INFERENCE, BusyTask.TEST_MULTI_TURN, BusyTask.TEST_VISION, BusyTask.TEST_AUDIO, BusyTask.TEST_HEALTH, BusyTask.TEST_LOAD,
    )

    fun compute(state: ControlsState): Controls {
        val kind = when (state.pending) {
            PendingAction.START -> ServerStatus.STARTING
            PendingAction.STOP -> ServerStatus.OFF
            PendingAction.NONE -> state.status.kind
        }
        val copying = BusyTask.MODEL_COPY in state.busy
        val offOrError = kind == ServerStatus.OFF || kind == ServerStatus.ERROR
        val switchEnabled = kind == ServerStatus.READY || (offOrError && state.modelSelected && !copying)
        val switchReason = when {
            !offOrError || switchEnabled -> null
            copying -> OffReason.WAIT_FOR_COPY
            else -> OffReason.SELECT_MODEL_FIRST
        }
        val ready = kind == ServerStatus.READY
        val testBusy = TESTS.any { it in state.busy }
        return Controls(
            kind = kind,
            switchChecked = kind == ServerStatus.STARTING || ready,
            switchEnabled = switchEnabled,
            switchReason = switchReason,
            tests = TESTS.associateWith { ControlState(enabled = ready && it !in state.busy, busy = it in state.busy) },
            testsReason = if (ready) null else OffReason.START_SERVER_FIRST,
            selectModel = ControlState(enabled = !copying, busy = copying),
            copyToken = ControlState(enabled = state.tokenExists),
            deleteToken = ControlState(enabled = state.tokenExists),
            tokenReason = if (state.tokenExists) null else OffReason.GENERATE_TOKEN_FIRST,
            backup = ControlState(enabled = BusyTask.BACKUP !in state.busy, busy = BusyTask.BACKUP in state.busy),
            restore = ControlState(enabled = BusyTask.RESTORE !in state.busy, busy = BusyTask.RESTORE in state.busy),
            working = kind == ServerStatus.STARTING || testBusy || copying,
            errorAction = when {
                kind != ServerStatus.ERROR -> null
                state.modelSelected -> ErrorAction.TRY_AGAIN
                else -> ErrorAction.SELECT_MODEL
            },
            errorActionEnabled = !copying,
        )
    }
}
