package com.vrec.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogPagePolicyTest {
    @Test fun wrapsLongEntriesIntoDisplayRows() {
        val rows = LogPagePolicy.wrapRows(listOf("12345678901234567890"), 10)
        assertEquals(listOf("1234567890", "  12345678", "  90"), rows)
        assertTrue(rows.all { it.length <= 10 })
    }

    @Test fun preservesShortRowsAndMultilineEntries() {
        assertEquals(listOf("one", "two", "three"),
            LogPagePolicy.wrapRows(listOf("one\ntwo", "three"), 10))
    }

    @Test fun emptyAndPartialPagesAreValid() {
        assertEquals(1, LogPagePolicy.pageCount(0, 8))
        assertEquals(1, LogPagePolicy.pageCount(8, 8))
        assertEquals(2, LogPagePolicy.pageCount(9, 8))
        assertEquals(emptyList<String>(), LogPagePolicy.pageRows(emptyList(), 10, 100))
    }

    @Test fun autoFollowAlwaysDisplaysNewestPage() {
        assertEquals(4, LogPagePolicy.selectedPage(0, true, 5))
        assertEquals(5, LogPagePolicy.selectedPage(0, true, 6))
    }

    @Test fun browsingOldPagesDoesNotJumpWhenLogsArrive() {
        assertEquals(1, LogPagePolicy.selectedPage(1, false, 5))
        assertEquals(1, LogPagePolicy.selectedPage(1, false, 8))
    }

    @Test fun paginationClampsAfterClearOrRetentionTrim() {
        assertEquals(0, LogPagePolicy.selectedPage(98, false, 1))
        assertEquals(2, LogPagePolicy.selectedPage(98, false, 3))
        val rows = (1..11).map { it.toString() }
        assertEquals(listOf("9", "10", "11"), LogPagePolicy.pageRows(rows, 4, 100))
        assertFalse(LogPagePolicy.wrapRows(emptyList(), 10).isNotEmpty())
    }
}
