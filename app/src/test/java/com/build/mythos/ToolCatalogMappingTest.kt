package com.build.mythos

import com.build.mythos.cc.ToolCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Katalog alat agen API harus sama dengan jembatan aplikasi dan punya status langkah di UI untuk setiap alat. */
class ToolCatalogMappingTest {
    private val dir: File? = listOf("src/main/java/com/build/mythos", "app/src/main/java/com/build/mythos", "../app/src/main/java/com/build/mythos")
        .map { File(it) }.firstOrNull { it.isDirectory }

    @Test fun alatJembatanDiKatalogSamaDenganDiCcBridge() {
        assertEquals(CC_BRIDGE_TOOL_NAMES, ToolCatalog.bridgedNames)
    }

    @Test fun setiapAlatKatalogPunyaStatusLangkahDiCcBridge() {
        assertNotNull("Direktori sumber tidak ditemukan", dir)
        val lines = File(dir!!, "CcBridge.kt").readLines()
        val start = lines.indexOfFirst { it.contains("fun ccIconDesc(") }
        assertTrue("Fungsi ccIconDesc tidak ditemukan di CcBridge.kt", start >= 0)
        val end = (start + 1 until lines.size).firstOrNull { lines[it] == "}" } ?: -1
        assertTrue("Penutup fungsi ccIconDesc tidak ditemukan di CcBridge.kt", end > start)
        val iconBody = lines.subList(start, end + 1).joinToString("\n")
        for (n in ToolCatalog.names) {
            val ok = iconBody.contains("\"$n\" ->") ||
                (n in CONNECTOR_TOOL_NAMES && iconBody.contains("in CONNECTOR_TOOL_NAMES -> connectorStatus")) ||
                (n in DANA_TOOL_NAMES && iconBody.contains("in DANA_TOOL_NAMES -> danaStatus"))
            assertTrue("$n belum punya status langkah di ccIconDesc", ok)
        }
    }
}
