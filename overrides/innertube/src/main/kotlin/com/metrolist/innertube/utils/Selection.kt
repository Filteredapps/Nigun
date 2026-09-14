package com.metrolist.innertube.utils

/** Preserve the chosen item when items before it are removed. */
fun <T> selectedIndex(items: List<T>, selectedId: String?, id: (T) -> String): Int =
    items.indexOfFirst { id(it) == selectedId }.coerceAtLeast(0)
