package app.vesper.feature.assistant.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextTest {
    private fun say(s: String) = SpeechText.forSpeech(s)

    @Test fun `times`() {
        assertEquals("Your alarm is set for six forty a m.", say("Your alarm is set for 6:40 AM."))
        assertEquals("At nine oh five p m, and seven o'clock.", say("At 9:05 pm, and 7:00."))
        assertEquals("Meet at seven a m", say("Meet at 7am"))
        assertEquals("It's twelve p m", say("It’s 12 PM"))
    }

    @Test fun `numbers, units and symbols`() {
        assertEquals("seventy percent chance, high fifty eight degrees", say("70% chance, high 58°"))
        assertEquals("minus three degrees Celsius", say("-3°C"))
        assertEquals("two point five hours", say("2.5 hours"))
        assertEquals("one hundred five", say("105"))
        assertEquals("two thousand twenty six", say("2026"))
        assertEquals("nineteen eighty four", say("1984"))
        assertEquals("the twenty second", say("the 22nd"))
        assertEquals("the first and the third", say("the 1st and the 3rd"))
        assertEquals("ten minutes", say("**10** minutes"))
        assertEquals("salt and pepper", say("salt & pepper"))
    }
}
