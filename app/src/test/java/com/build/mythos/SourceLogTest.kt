package com.build.mythos

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SourceLogTest {
    @Before fun clearBefore() { SourceLog.clear() }
    @After fun clearAfter() { SourceLog.clear() }
    @Test fun dedupesAndIgnoresNonHttp() {
        SourceLog.clear()
        SourceLog.add("A", "https://www.contoh.id/halaman/")
        SourceLog.add("A lagi", "https://www.contoh.id/halaman#bagian")
        SourceLog.add("X", "javascript:alert(1)")
        SourceLog.add("Y", "ftp://contoh.id/file")
        assertEquals(1, SourceLog.snapshot().size)
        assertEquals("contoh.id", SourceLog.snapshot()[0].host)
    }

    @Test fun pageTitleReplacesHostTitle() {
        SourceLog.clear()
        SourceLog.add("", "https://kemdikbud.go.id/a")
        SourceLog.add("Judul Halaman", "https://kemdikbud.go.id/a")
        assertEquals("Judul Halaman", SourceLog.snapshot().single().title)
    }

    @Test fun clearEmptiesList() {
        SourceLog.add("B", "https://b.com/x")
        SourceLog.clear()
        assertTrue(SourceLog.snapshot().isEmpty())
    }
}
