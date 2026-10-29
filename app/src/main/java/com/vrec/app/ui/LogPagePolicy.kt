package com.vrec.app.ui

/**
 * Presentation-only pagination adapted from AAYoutube's log viewer.
 * All operations are deterministic, so navigation and following new entries are testable.
 */
internal object LogPagePolicy {
    fun wrapRows(entries: List<String>, columns: Int): List<String> {
        val width = columns.coerceAtLeast(3)
        val continuation = (width - 2).coerceAtLeast(1)
        return buildList {
            for (entry in entries) {
                for (part in entry.split('\n')) {
                    if (part.length <= width) {
                        add(part)
                    } else {
                        add(part.substring(0, width))
                        var at = width
                        while (at < part.length) {
                            add("  " + part.substring(at, minOf(at + continuation, part.length)))
                            at += continuation
                        }
                    }
                }
            }
        }
    }

    fun pageCount(rows: Int, pageSize: Int): Int {
        val size = pageSize.coerceAtLeast(1)
        return (rows / size + if (rows % size == 0) 0 else 1).coerceAtLeast(1)
    }

    fun selectedPage(requested: Int, followNewest: Boolean, totalPages: Int): Int {
        val last = (totalPages - 1).coerceAtLeast(0)
        return if (followNewest) last else requested.coerceIn(0, last)
    }

    fun pageRows(rows: List<String>, pageSize: Int, selectedPage: Int): List<String> {
        val size = pageSize.coerceAtLeast(1)
        val page = selectedPage.coerceIn(0, pageCount(rows.size, size) - 1)
        return rows.drop(page * size).take(size)
    }
}
