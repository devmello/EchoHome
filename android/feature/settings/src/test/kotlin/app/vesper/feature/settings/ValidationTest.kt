package app.vesper.feature.settings

import app.vesper.core.model.InMemorySecretStore
import app.vesper.core.model.SettingSpec
import app.vesper.core.model.SettingType
import app.vesper.feature.settings.api.ValidationException
import app.vesper.feature.settings.api.Validator
import app.vesper.feature.settings.api.Write
import app.vesper.feature.settings.api.Writer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ValidationTest {
    private fun spec(type: SettingType, vararg opts: Pair<String, String>, min: Float? = null, max: Float? = null) =
        SettingSpec("k", "Thing", type, options = opts.toList(), min = min, max = max)

    private fun ok(spec: SettingSpec, v: Any?) = Validator.validate(spec, v)
    private fun bad(spec: SettingSpec, v: Any?) {
        try { Validator.validate(spec, v); fail("accepted $v for ${spec.type}") } catch (e: ValidationException) {}
    }

    @Test fun text() {
        assertEquals(listOf(Write.Str("k", "Bedroom")), ok(spec(SettingType.TEXT), "  Bedroom "))
        bad(spec(SettingType.TEXT), 5)
        bad(spec(SettingType.TEXT), "two\nlines")
        bad(spec(SettingType.TEXT), "x".repeat(501))
    }

    @Test fun boolean() {
        assertEquals(listOf(Write.Bool("k", true)), ok(spec(SettingType.BOOLEAN), true))
        assertEquals(listOf(Write.Bool("k", false)), ok(spec(SettingType.BOOLEAN), "false"))
        bad(spec(SettingType.BOOLEAN), 1)
        bad(spec(SettingType.BOOLEAN), "yes")
    }

    @Test fun int() {
        val s = spec(SettingType.INT, min = 1f, max = 20f)
        assertEquals(listOf(Write.IntV("k", 9)), ok(s, 9))
        assertEquals(listOf(Write.IntV("k", 9)), ok(s, 9.0))
        assertEquals(listOf(Write.IntV("k", 20)), ok(s, "20"))
        bad(s, 9.5); bad(s, 0); bad(s, 21); bad(s, "nine"); bad(s, true); bad(s, Double.NaN)
    }

    @Test fun float() {
        val s = spec(SettingType.FLOAT, min = 0f, max = 1f)
        assertEquals(listOf(Write.FloatV("k", 0.25f)), ok(s, 0.25))
        bad(s, 1.5); bad(s, -0.1); bad(s, Double.POSITIVE_INFINITY); bad(s, JSONObject.NULL)
        assertEquals(listOf(Write.FloatV("k", 1000f)), ok(spec(SettingType.FLOAT), 1000)) // no range
    }

    @Test fun choice() {
        val s = spec(SettingType.CHOICE, "ap1" to "AirPlay 1", "ap2" to "AirPlay 2")
        assertEquals(listOf(Write.Str("k", "ap2")), ok(s, "ap2"))
        bad(s, "AirPlay 2"); bad(s, "ap3"); bad(s, 2)
    }

    @Test fun textList() {
        val s = spec(SettingType.TEXT_LIST)
        assertEquals(listOf(Write.Str("k", "AAPL,MSFT")), ok(s, JSONArray(listOf(" AAPL", "", "MSFT "))))
        assertEquals(listOf(Write.Str("k", "")), ok(s, JSONArray()))
        bad(s, JSONArray(listOf("a,b")))
        bad(s, JSONArray(listOf(1, 2)))
        bad(s, JSONArray((1..51).map { "x$it" }))
        bad(s, 5)
    }

    @Test fun time() {
        val s = spec(SettingType.TIME)
        assertEquals(listOf(Write.Str("k", "07:05")), ok(s, "07:05"))
        assertEquals(listOf(Write.Str("k", "23:59")), ok(s, "23:59"))
        bad(s, "24:00"); bad(s, "7:05"); bad(s, "07:60"); bad(s, "07:05:00"); bad(s, 705)
    }

    @Test fun color() {
        val s = spec(SettingType.COLOR)
        assertEquals(listOf(Write.Str("k", "#FF375F")), ok(s, "#ff375f"))
        assertEquals(listOf(Write.Remove("k")), ok(s, ""))
        assertEquals(listOf(Write.Remove("k")), ok(s, JSONObject.NULL))
        bad(s, "FF375F"); bad(s, "#FFF"); bad(s, "#GG0000"); bad(s, "red")
    }

    @Test fun location() {
        val s = spec(SettingType.LOCATION)
        assertEquals(listOf(Write.Str("k", "37.6819,-121.768"), Write.Str("k.place", "Livermore, California")),
            ok(s, JSONObject().put("lat", 37.6819).put("lon", -121.768).put("place", "Livermore, California")))
        assertEquals(listOf(Write.Str("k", "51.5,-0.12"), Write.Remove("k.place")), ok(s, "51.5, -0.12"))
        assertEquals(listOf(Write.Remove("k"), Write.Remove("k.place")), ok(s, ""))
        assertEquals(listOf(Write.Remove("k"), Write.Remove("k.place")), ok(s, JSONObject.NULL))
        bad(s, JSONObject().put("lat", 91).put("lon", 0))
        bad(s, JSONObject().put("lat", 0).put("lon", 181))
        bad(s, JSONObject().put("lat", "x").put("lon", 0))
        bad(s, "1,2,3")
        bad(s, 5)
    }

    @Test fun secret() {
        val s = spec(SettingType.SECRET)
        assertEquals(listOf(Write.Secret("k", "sk-ant-123")), ok(s, " sk-ant-123 "))
        assertEquals(listOf(Write.Secret("k", null)), ok(s, JSONObject.NULL))
        assertEquals(listOf(Write.Secret("k", null)), ok(s, "  "))
        bad(s, "a\nb"); bad(s, 12); bad(s, "x".repeat(9000))
        val list = SettingSpec("k", "Calendars", SettingType.SECRET, multiline = true, placeholder = "https://…")
        assertEquals(listOf(Write.Secret("k", "https://a/x.ics,webcal://b/y.ics")), ok(list, "https://a/x.ics\n\n webcal://b/y.ics \n"))
    }

    @Test fun action() {
        bad(SettingSpec("k", "Restart", SettingType.ACTION, onAction = { null }), true)
    }

    @Test fun writerKeepsAFeaturesOwnStoredType() {
        val st = FakeSettingsStore()
        st.putString("legacy.int", "5")
        Writer.apply(listOf(Write.IntV("legacy.int", 7), Write.IntV("new.int", 3), Write.Bool("b", true), Write.FloatV("f", .5f), Write.Remove("gone")),
            st, InMemorySecretStore())
        assertEquals("7", st.map["legacy.int"])
        assertEquals(3, st.map["new.int"])
        assertEquals(true, st.map["b"])
        assertEquals(.5f, st.map["f"])
    }
}
