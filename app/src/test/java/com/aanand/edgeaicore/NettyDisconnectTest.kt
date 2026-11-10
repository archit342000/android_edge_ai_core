package com.aanand.edgeaicore

import io.ktor.server.application.ApplicationCall
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy

class NettyDisconnectTest {
    @Test
    fun aCallThatIsNotANettyCallIsNotWatched() {
        val call = Proxy.newProxyInstance(
            ApplicationCall::class.java.classLoader, arrayOf(ApplicationCall::class.java)
        ) { _, _, _ -> throw UnsupportedOperationException() } as ApplicationCall
        assertNull(NettyDisconnect.watch(call) { throw AssertionError("must not run") })
    }
}
