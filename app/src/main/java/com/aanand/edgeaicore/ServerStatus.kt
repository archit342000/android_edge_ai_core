package com.aanand.edgeaicore

/** The kind of state the server is in. The order matches the levels of `res/drawable/status_light.xml`. */
enum class ServerStatus { OFF, STARTING, READY, ERROR }

/** A server status: its [kind], which decides what the UI allows, and a [detail] line shown to the person. */
data class ServerStatusSnapshot(val kind: ServerStatus, val detail: String) {
    companion object {
        val OFF = ServerStatusSnapshot(ServerStatus.OFF, "")
    }
}
