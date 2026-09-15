package com.metrolist.innertube.utils

import java.math.BigDecimal

/** Public subscriber counts can be compact or use localized decimal/grouping separators. */
fun parseCompactCount(value: String?): Long? {
    val text = value?.replace(Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]"), "")
        ?.trim()?.lowercase() ?: return null
    if (text.startsWith("-")) return null
    val match = Regex(
        "([0-9][0-9.,\\s\\u00a0\\u202f]*)(thousand|million|billion|מיליארד|מיליון|מיל׳|אלפים|אלף|[kmb])?",
    ).find(text) ?: return null
    var number = match.groupValues[1].replace(Regex("[\\s\\u00a0\\u202f]+"), "")
    val suffix = match.groupValues[2]
    val multiplier = when (suffix) {
        "k", "thousand", "אלף", "אלפים" -> 1_000L
        "m", "million", "מיליון", "מיל׳" -> 1_000_000L
        "b", "billion", "מיליארד" -> 1_000_000_000L
        else -> 1L
    }
    if (suffix.isEmpty() && Regex("\\d{1,3}(?:[.,]\\d{3})+").matches(number)) {
        number = number.replace(",", "").replace(".", "")
    } else {
        val index = maxOf(number.lastIndexOf('.'), number.lastIndexOf(','))
        if (index >= 0) {
            number = number.substring(0, index).replace(",", "").replace(".", "") +
                "." + number.substring(index + 1)
        }
    }
    val count = number.toBigDecimalOrNull()?.multiply(BigDecimal.valueOf(multiplier)) ?: return null
    return count.takeIf { it >= BigDecimal.ZERO && it <= BigDecimal.valueOf(Long.MAX_VALUE) }?.toLong()
}
