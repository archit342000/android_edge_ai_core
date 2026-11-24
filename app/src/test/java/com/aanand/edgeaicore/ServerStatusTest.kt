package com.aanand.edgeaicore

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The status light is chosen by `ServerStatus.ordinal`, so the enum's order must match the levels of `status_light.xml`. */
class ServerStatusTest {
    @Test
    fun eachKindLightsTheStatusLightLevelOfItsOwnColour() {
        val expected = mapOf(
            ServerStatus.OFF to "@color/console_muted",
            ServerStatus.STARTING to "@color/console_led",
            ServerStatus.READY to "@color/console_ok",
            ServerStatus.ERROR to "@color/console_bad",
        )
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/drawable/status_light.xml"))
        val levels = doc.documentElement.childNodes.let { nodes -> (0 until nodes.length).map { nodes.item(it) } }
            .filterIsInstance<Element>()
        assertEquals(ServerStatus.entries.size, levels.size)
        for (kind in ServerStatus.entries) {
            val level = levels[kind.ordinal]
            assertEquals("maxLevel of $kind", kind.ordinal.toString(), level.getAttribute("android:maxLevel"))
            val solids = level.getElementsByTagName("solid")
            val dot = solids.item(solids.length - 1) as Element
            assertEquals("colour of $kind", expected[kind], dot.getAttribute("android:color"))
        }
    }
}
