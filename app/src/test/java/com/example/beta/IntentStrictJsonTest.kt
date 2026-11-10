package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IntentStrictJsonTest {
    @Test fun rejectsDuplicateKeysAtEveryDepthAndEscapedAliases() {
        listOf("""{"items":[],"items":[]}""", """{"item":{"quantity":1,"quantity":2}}""",
            """{"quantity":1,"\u0071uantity":2}""").forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) { JsonParser(raw).parseStrict() }
        }
    }

    @Test fun rejectsNonJsonNumbersEscapesAndControls() {
        listOf("01", "+1", ".5", "1.", "1e", "--2", "١", "\"\\q\"", "\"line\nline\"").forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) { JsonParser(raw).parseStrict() }
        }
    }

    @Test fun validExactNumbersAndEscapedNewlineRemainSupported() {
        assertEquals(JsonValue.Num("1.25e-2"), JsonParser("1.25e-2").parseStrict())
        assertEquals(JsonValue.Str("line\nline"), JsonParser("\"line\\nline\"").parseStrict())
    }
}
