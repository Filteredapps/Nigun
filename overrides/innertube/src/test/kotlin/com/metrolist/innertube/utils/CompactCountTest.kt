package com.metrolist.innertube.utils

import org.junit.Assert.*
import org.junit.Test

class CompactCountTest {
    @Test fun countsAreNumericAcrossCompactAndLocalizedFormats() {
        val cases = mapOf(
            "999" to 999L, "1K" to 1000L, "1.5K subscribers" to 1500L,
            "1,5K" to 1500L, "1,234 subscribers" to 1234L,
            "1.234 subscribers" to 1234L, "1\u00a0234" to 1234L,
            "2.3M" to 2300000L, "1.2 מיליון מנויים" to 1200000L,
            "3 thousand subscribers" to 3000L,
        )
        cases.forEach { (text, expected) -> assertEquals(text, expected, parseCompactCount(text)) }
        assertEquals(listOf("1.2M", "1K", "999"), listOf("999", "1K", "1.2M").sortedByDescending(::parseCompactCount))
        assertNull(parseCompactCount("unavailable"))
        assertNull(parseCompactCount(null))
        assertNull(parseCompactCount("-5"))
    }
}
