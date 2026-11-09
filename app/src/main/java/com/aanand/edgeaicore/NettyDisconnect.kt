package com.aanand.edgeaicore

import io.ktor.server.application.ApplicationCall
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.routing.RoutingApplicationCall
import io.netty.channel.ChannelFutureListener
import kotlinx.coroutines.DisposableHandle

/**
 * Tells a route when the client's connection closes. Ktor 2.3.12 on Netty does not cancel an HTTP/1.1 call by itself
 * when the connection drops, so a non-streaming request would otherwise run its generation to the end for nobody.
 */
object NettyDisconnect {
    /**
     * Runs [onGone] once when the connection of [call] closes. Returns a handle that removes the listener, or null when
     * [call] is not a Netty call (for example in the Ktor test host), in which case nothing is watched.
     */
    fun watch(call: ApplicationCall, onGone: () -> Unit): DisposableHandle? {
        val engineCall = (call as? RoutingApplicationCall)?.engineCall as? NettyApplicationCall ?: return null
        val future = engineCall.context.channel().closeFuture()
        val listener = ChannelFutureListener { onGone() }
        future.addListener(listener)
        return DisposableHandle { future.removeListener(listener) }
    }
}
