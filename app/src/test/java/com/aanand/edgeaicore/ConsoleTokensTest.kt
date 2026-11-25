package com.aanand.edgeaicore

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.io.InputStreamReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Checks the app's Console at Rest resources against a snapshot of the design system's `tokens.json`
 * (`src/test/resources/console-at-rest/tokens.json`): every shared value has a resource with the same value,
 * text stays readable on every surface it is drawn on, and the sphere numbers copied from the reference page
 * agree with the formulas `tokens.json` gives in words.
 */
class ConsoleTokensTest {

    private val tokens: JsonObject = javaClass.classLoader!!.getResourceAsStream("console-at-rest/tokens.json")!!.use {
        JsonParser.parseReader(InputStreamReader(it)).asJsonObject
    }
    private val day = Resources(File(RES_DIR, "values"), null)
    private val night = Resources(File(RES_DIR, "values-night"), day)

    @Test
    fun everyColourMatchesForDayAndNight() {
        val colour = tokens.obj("colour")
        for ((mode, res) in listOf("day" to day, "night" to night)) {
            for ((key, value) in colour.obj(mode).entrySet()) {
                when (key) {
                    "dots" -> value.asJsonArray.forEachIndexed { i, v -> assertColour(res, "console_dot_${i + 1}", v.asString) }
                    "solid" -> value.asJsonObject.entrySet().forEach { (k, v) -> assertColour(res, "console_solid_${snake(k)}", v.asString) }
                    else -> assertColour(res, "console_${snake(key)}", value.asString)
                }
            }
        }
        for ((key, value) in colour.obj("code").entrySet()) assertColour(day, "console_code_${snake(key)}", value.asString)
    }

    @Test
    fun spacingSizesAndCornersMatch() {
        tokens.getAsJsonArray("space").forEachIndexed { i, v -> assertNumber(day, "space_${i + 1}", v.asDouble) }
        assertNumber(day, "page_padding_phone", tokens.obj("pagePadding")["phone"].asDouble)
        assertNumber(day, "page_padding_other", tokens.obj("pagePadding")["other"].asDouble)
        val size = tokens.obj("size")
        assertNumber(day, "touch_phone", size.obj("touch")["phone"].asDouble)
        assertNumber(day, "touch_other", size.obj("touch")["other"].asDouble)
        for (key in listOf("measure", "sidePanel", "rail", "statusLight")) assertNumber(day, snake(key), size[key].asDouble)
        for ((name, corners) in tokens.obj("radius").entrySet()) {
            for ((corner, v) in corners.asJsonObject.entrySet()) assertNumber(day, "radius_${name}_${snake(corner)}", v.asDouble)
        }
    }

    @Test
    fun lettering() {
        for ((name, style) in tokens.obj("text").entrySet()) {
            val s = style.asJsonObject
            val size = s["size"]
            if (size.isJsonArray) {
                assertNumber(day, "text_${name}_size_min", size.asJsonArray[0].asDouble)
                assertNumber(day, "text_${name}_size_max", size.asJsonArray[1].asDouble)
            } else {
                assertNumber(day, "text_${name}_size", size.asDouble)
            }
            assertNumber(day, "text_${name}_weight", s["weight"].asDouble)
            s["tracking"]?.let { assertNumber(day, "text_${name}_tracking", it.asDouble) }
            s["lineHeight"]?.let { assertNumber(day, "text_${name}_line_height", it.asDouble) }
        }
        val fontFiles = mapOf(
            "display" to mapOf(600 to "sora_semibold", 700 to "sora_bold"),
            "text" to mapOf(400 to "instrument_sans_regular", 500 to "instrument_sans_medium", 600 to "instrument_sans_semibold"),
            "mono" to mapOf(400 to "jetbrains_mono_regular", 500 to "jetbrains_mono_medium"),
        )
        for ((role, weights) in tokens.obj("font").obj("weights").entrySet()) {
            for (w in weights.asJsonArray) {
                val file = fontFiles.getValue(role)[w.asInt] ?: error("No font file mapped for $role $w")
                assertTrue("$file.ttf is missing", File(RES_DIR, "font/$file.ttf").isFile)
            }
        }
    }

    @Test
    fun theThreeCurvesMatch() {
        for ((name, points) in tokens.obj("motion").obj("curve").entrySet()) {
            val file = File(RES_DIR, "interpolator/ease_$name.xml")
            val e = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            val actual = listOf("controlX1", "controlY1", "controlX2", "controlY2").map { e.getAttribute("android:$it").toDouble() }
            assertEquals("curve $name", points.asJsonArray.map { it.asDouble }, actual)
        }
    }

    @Test
    fun lineHeightsAreTheTextSizeTimesTheTokenFactor() {
        for ((style, size) in listOf("greeting" to "text_greeting_size_min", "page" to "text_page_size_min", "body" to "text_body_size", "code" to "text_code_size")) {
            assertNumber(day, "line_height_$style", day.number(size) * day.number("text_${style}_line_height"))
        }
    }

    @Test
    fun motionShadowsGlassAndSphereNumbers() {
        for ((key, v) in tokens.obj("motion").obj("duration").entrySet()) assertNumber(day, "duration_$key", v.asDouble)
        assertNumber(day, "breakpoint_tablet", tokens.obj("breakpoint").getAsJsonArray("tablet")[0].asDouble)
        assertNumber(day, "breakpoint_computer", tokens.obj("breakpoint").getAsJsonArray("computer")[0].asDouble)
        val elevation = tokens.obj("shadow").obj("androidElevation")
        assertNumber(day, "elevation_rest", elevation["rest"].asDouble)
        assertNumber(day, "elevation_lift", elevation["lift"].asDouble)
        val glass = tokens.obj("glass")
        for (key in listOf("frost", "floatBlur", "panelBlur", "saturate")) assertNumber(day, "glass_${snake(key)}", glass[key].asDouble)

        val sphere = tokens.obj("sphere")
        assertNumber(day, "sphere_dots_computer", sphere.obj("dots")["computer"].asDouble)
        assertNumber(day, "sphere_dots_phone", sphere.obj("dots")["phone"].asDouble)
        assertNumber(day, "sphere_size_factor", sphere["sizeFactor"].asDouble)
        assertNumber(day, "sphere_open_until_scroll", sphere["openUntilScroll"].asDouble)
        val opacity = sphere.obj("opacity")
        for ((mode, res) in listOf("day" to day, "night" to night)) {
            assertNumber(res, "sphere_strength_open", opacity.obj("open")[mode].asDouble)
            assertNumber(res, "sphere_strength_read", opacity.obj("read")[mode].asDouble)
        }
        assertNumber(day, "sphere_phone_factor", opacity["phoneFactor"].asDouble)
        val words = sphere.obj("wordsOnSphere")
        assertNumber(day, "sphere_fade_strength", words["fadeStrength"].asDouble)
        assertNumber(day, "sphere_fade_blur", words["fadeBlur"].asDouble)
        assertNumber(day, "sphere_fade_pad_x_min", words.getAsJsonArray("padX")[0].asDouble)
        assertNumber(day, "sphere_fade_pad_x_max", words.getAsJsonArray("padX")[1].asDouble)
        assertNumber(day, "sphere_fade_pad_y_min", words.getAsJsonArray("padY")[0].asDouble)
        assertNumber(day, "sphere_fade_pad_y_max", words.getAsJsonArray("padY")[1].asDouble)
        assertNumber(day, "sphere_fade_patch_radius", words["patchRadius"].asDouble)
        assertNumber(day, "sphere_panel_under_open_mix", words["panelUnderOpenMix"].asDouble)
    }

    @Test
    fun sphereNumbersFromTheReferencePageAgreeWithTheFormulasInWords() {
        val sphere = tokens.obj("sphere")
        val radius = sphere.obj("radius")
        assertInFormula(radius["computer"].asString, "sphere_radius_computer")
        assertInFormula(radius["computer"].asString, "sphere_radius_computer_min")
        assertInFormula(radius["phone"].asString, "sphere_radius_phone")
        assertInFormula(sphere["stretch"].asString, "sphere_stretch_factor")
        assertInFormula(sphere["stretch"].asString, "sphere_stretch_cap")
        assertInFormula(sphere["spacing"].asString, "sphere_golden_angle")
        val padRule = sphere.obj("wordsOnSphere")["padRule"].asString
        assertTrue(padRule, padRule.contains("${pct(day.number("sphere_fade_pad_x_factor"))}%"))
        assertTrue(padRule, padRule.contains("${pct(day.number("sphere_fade_pad_y_factor"))}%"))
    }

    /**
     * Text must reach 4.5 to 1 on every surface it is drawn on (rule book 2.1). Solid surfaces are checked as they are.
     * A translucent surface is checked over its worst case: the page with the brightest-to-darkest dot under it, at reading
     * strength and the dot's alpha cap. A blurred surface spreads a dot over its blur radius, so the dot's strength is cut by
     * (dot radius / (dot radius + blur))^2, using the largest dot the sphere draws.
     */
    @Test
    fun textContrastOnEverySurface() {
        val colour = tokens.obj("colour")
        val maxDot = (day.number("sphere_dot_size_min") + day.number("sphere_dot_size_range")) *
            (day.number("sphere_dot_size_base") + day.number("sphere_dot_size_user") * day.number("sphere_size_factor"))
        val panelBlur = tokens.obj("glass")["panelBlur"].asDouble
        val floatBlur = tokens.obj("glass")["floatBlur"].asDouble
        for ((mode, res) in listOf("day" to day, "night" to night)) {
            val c = colour.obj(mode)
            fun col(key: String) = rgba(c[key].asString)
            val bg = col("bg")
            val read = res.number("sphere_strength_read") * res.number("sphere_alpha_cap")
            fun under(blur: Double) = c.getAsJsonArray("dots").map { over(rgba(it.asString), bg, read * (maxDot / (maxDot + blur)).pow(2)) }
            val panel = under(panelBlur).map { over(col("glassOpen"), it) }
            val solid = c.obj("solid")
            val surfaces = mapOf(
                "glassOpen" to panel,
                "glassStrong on glassOpen" to panel.map { over(col("glassStrong"), it) },
                "glass" to under(floatBlur).map { over(col("glass"), it) },
                "pop" to under(floatBlur).map { over(col("pop"), it) },
                "solid glassOpen" to listOf(rgba(solid["glassOpen"].asString)),
                "solid glassStrong" to listOf(rgba(solid["glassStrong"].asString)),
                "solid glass" to listOf(rgba(solid["glass"].asString)),
                "solid pop" to listOf(rgba(solid["pop"].asString)),
            )
            val tints = mapOf(
                "accentSoft on glassOpen" to panel.map { over(col("accentSoft"), it) },
                "well on glassOpen" to panel.map { over(col("well"), it) },
                "accentSoft on solid glassOpen" to listOf(over(col("accentSoft"), rgba(solid["glassOpen"].asString))),
                "well on solid glassOpen" to listOf(over(col("well"), rgba(solid["glassOpen"].asString))),
            )
            // Words on the open sphere sit on a fade patch: the page colour at fade strength over a full-strength dot.
            val open = res.number("sphere_strength_open") * res.number("sphere_alpha_cap")
            val fade = c.getAsJsonArray("dots").map { over(bg, over(rgba(it.asString), bg, open), day.number("sphere_fade_strength")) }

            for (text in listOf("fg", "muted", "accentText", "ok", "warn", "bad")) {
                for ((name, under) in surfaces) assertReadable("$mode $text on $name", col(text), under)
            }
            for (text in listOf("fg", "muted", "accentText")) {
                for ((name, under) in tints) assertReadable("$mode $text on $name", col(text), under)
            }
            for (text in listOf("fg", "muted")) assertReadable("$mode $text on the fade", col(text), fade)
            assertReadable("$mode accentFg on accent", col("accentFg"), listOf(col("accent")))
            assertReadable("$mode accentFg on badSolid", col("accentFg"), listOf(col("badSolid")))
            assertReadable("$mode codeFg on codeBg", col("codeFg"), (surfaces.getValue("glassOpen") + surfaces.getValue("solid glassOpen")).map { over(col("codeBg"), it) })
        }
    }

    private fun assertReadable(what: String, text: DoubleArray, backgrounds: List<DoubleArray>) {
        for (b in backgrounds) {
            val ratio = contrast(text, b)
            if (ratio < MIN_CONTRAST) fail("$what: contrast ${"%.2f".format(ratio)} is below $MIN_CONTRAST")
        }
    }

    private fun assertInFormula(formula: String, resource: String) {
        val raw = day.raw(resource).removeSuffix("dp")
        assertTrue("$resource ($raw) is not in \"$formula\"", formula.contains(raw))
    }

    private fun assertColour(res: Resources, name: String, token: String) {
        val expected = rgba(token)
        val actual = argb(res.raw(name))
        for (i in 0..2) assertEquals("$name channel $i", expected[i], actual[i], 0.0)
        assertEquals("$name alpha", (expected[3] * 255).let { Math.round(it).toDouble() }, actual[3] * 255, 0.01)
    }

    private fun assertNumber(res: Resources, name: String, expected: Double) {
        val actual = res.number(name)
        assertTrue("$name is $actual, tokens.json says $expected", abs(actual - expected) <= 1e-6 * max(1.0, abs(expected)))
    }

    /** One resource directory, falling back to the default directory as Android does for night resources. */
    private class Resources(dir: File, private val fallback: Resources?) {
        private val values = HashMap<String, String>()

        init {
            dir.listFiles { f -> f.extension == "xml" }?.forEach { file ->
                val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                val nodes = doc.documentElement.childNodes
                for (i in 0 until nodes.length) {
                    val e = nodes.item(i) as? Element ?: continue
                    val name = e.getAttribute("name")
                    if (name.isNotEmpty()) values[name] = e.textContent.trim()
                }
            }
        }

        fun raw(name: String): String {
            val v = values[name] ?: fallback?.raw(name) ?: error("No resource named $name")
            return if (v.startsWith("@")) raw(v.substringAfter('/')) else v
        }

        fun number(name: String): Double = raw(name).removeSuffix("dp").removeSuffix("sp").toDouble()
    }

    private fun JsonObject.obj(key: String): JsonObject = getAsJsonObject(key)

    private fun JsonArray.forEachIndexed(block: (Int, JsonElement) -> Unit) = toList().forEachIndexed(block)

    private fun snake(key: String) = key.replace(Regex("([A-Z])")) { "_" + it.value.lowercase() }

    private fun pct(fraction: Double) = Math.round(fraction * 100).toString()

    /** `#RRGGBB` or `rgba(r,g,b,a)` as r, g, b in 0..255 and a in 0..1. */
    private fun rgba(v: String): DoubleArray {
        if (v.startsWith("#")) return doubleArrayOf(hex(v, 1), hex(v, 3), hex(v, 5), 1.0)
        val parts = v.substringAfter('(').substringBefore(')').split(',').map { it.trim().toDouble() }
        return doubleArrayOf(parts[0], parts[1], parts[2], parts[3])
    }

    /** Android `#AARRGGBB` as r, g, b in 0..255 and a in 0..1. */
    private fun argb(v: String): DoubleArray {
        require(v.length == 9) { "Expected #AARRGGBB, got $v" }
        return doubleArrayOf(hex(v, 3), hex(v, 5), hex(v, 7), hex(v, 1) / 255.0)
    }

    private fun hex(v: String, at: Int) = v.substring(at, at + 2).toInt(16).toDouble()

    private fun over(top: DoubleArray, base: DoubleArray, alpha: Double = top[3]) =
        DoubleArray(4) { if (it == 3) 1.0 else top[it] * alpha + base[it] * (1 - alpha) }

    private fun luminance(c: DoubleArray): Double {
        fun ch(x: Double): Double = (x / 255).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(c[0]) + 0.7152 * ch(c[1]) + 0.0722 * ch(c[2])
    }

    private fun contrast(a: DoubleArray, b: DoubleArray): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    companion object {
        /** Gradle runs unit tests from the module directory. */
        private val RES_DIR = File("src/main/res")
        private const val MIN_CONTRAST = 4.5
    }
}
