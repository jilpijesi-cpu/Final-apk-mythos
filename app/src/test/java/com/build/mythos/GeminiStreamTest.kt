package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiStreamTest {
    private fun all(vararg lines: String): Pair<GeminiStreamParser, List<GeminiEvent>> {
        val p = GeminiStreamParser()
        return p to p.feed(lines.joinToString("\n") + "\n")
    }

    @Test fun streamsAssistantDeltasAndIgnoresUserEcho() {
        val (p, ev) = all(
            """{"type":"init","session_id":"s1","model":"gemini-x"}""",
            """{"type":"message","role":"user","content":"halo"}""",
            """{"type":"message","role":"assistant","content":"Ha","delta":true}""",
            """{"type":"message","role":"assistant","content":"lo!","delta":true}""",
            """{"type":"result","status":"success","stats":{}}"""
        )
        assertEquals("Halo!", p.text())
        assertTrue(p.sawJson)
        assertEquals(listOf("Ha", "lo!"), ev.filterIsInstance<GeminiEvent.Text>().map { it.delta })
        assertEquals(1, ev.count { it === GeminiEvent.SegmentStart })
        assertEquals(GeminiEvent.Done(true), ev.last())
    }

    @Test fun handlesChunksSplitInTheMiddleOfALine() {
        val p = GeminiStreamParser()
        val a = p.feed("""{"type":"message","role":"assistant","con""")
        assertTrue(a.isEmpty())
        val b = p.feed("""tent":"Tes","delta":true}""" + "\n")
        assertEquals(listOf("Tes"), b.filterIsInstance<GeminiEvent.Text>().map { it.delta })
    }

    @Test fun trailingLineWithoutNewlineIsFlushedByFinish() {
        val p = GeminiStreamParser()
        assertTrue(p.feed("""{"type":"message","role":"assistant","content":"Akhir","delta":true}""").isEmpty())
        assertEquals(listOf("Akhir"), p.finish().filterIsInstance<GeminiEvent.Text>().map { it.delta })
        assertEquals("Akhir", p.text())
    }

    @Test fun fullMessageAfterDeltasIsNotShownTwice() {
        val (p, _) = all(
            """{"type":"message","role":"assistant","content":"Jawab","delta":true}""",
            """{"type":"message","role":"assistant","content":"Jawab"}"""
        )
        assertEquals("Jawab", p.text())
    }

    @Test fun nonDeltaMessageIsUsedWhenNoDeltasExist() {
        val (p, ev) = all("""{"type":"message","role":"assistant","content":"Utuh"}""")
        assertEquals("Utuh", p.text())
        assertEquals(1, ev.filterIsInstance<GeminiEvent.Text>().size)
    }

    @Test fun toolCallsBecomeCodexStyleItemsAndSeparateSegments() {
        val (p, ev) = all(
            """{"type":"message","role":"assistant","content":"Saya baca file.","delta":true}""",
            """{"type":"tool_use","tool_name":"read_file","tool_id":"t1","parameters":{"absolute_path":"/a/b.txt"}}""",
            """{"type":"tool_result","tool_id":"t1","status":"success","output":"isi"}""",
            """{"type":"message","role":"assistant","content":"Selesai.","delta":true}"""
        )
        val start = ev.filterIsInstance<GeminiEvent.ToolStart>().single().item
        assertEquals("commandExecution", start.getString("type"))
        assertEquals("cat /a/b.txt", start.getString("command"))
        val end = ev.filterIsInstance<GeminiEvent.ToolEnd>().single().item
        assertEquals("t1", end.getString("id"))
        assertEquals("completed", end.getString("status"))
        assertEquals("isi", end.getString("output"))
        assertEquals(0, end.getInt("exitCode"))
        assertEquals(2, ev.count { it === GeminiEvent.SegmentStart })
        assertEquals("Saya baca file.\n\nSelesai.", p.text())
    }

    @Test fun failedToolAndShellAndWriteItems() {
        val (_, ev) = all(
            """{"type":"tool_use","tool_name":"run_shell_command","tool_id":"a","parameters":{"command":"ls -la"}}""",
            """{"type":"tool_result","tool_id":"a","status":"error","output":"gagal"}""",
            """{"type":"tool_use","tool_name":"write_file","tool_id":"b","parameters":{"file_path":"/x/y.kt","content":"..."}}""",
            """{"type":"tool_use","tool_name":"google_web_search","tool_id":"c","parameters":{"query":"cari ini"}}""",
            """{"type":"tool_use","tool_name":"alat_aneh","tool_id":"d","parameters":{}}"""
        )
        val ends = ev.filterIsInstance<GeminiEvent.ToolEnd>()
        assertEquals("failed", ends.single().item.getString("status"))
        assertEquals(1, ends.single().item.getInt("exitCode"))
        val starts = ev.filterIsInstance<GeminiEvent.ToolStart>().map { it.item }
        assertEquals("ls -la", starts[0].getString("command"))
        assertEquals("fileChange", starts[1].getString("type"))
        assertEquals("/x/y.kt", starts[1].getJSONArray("changes").getJSONObject(0).getString("path"))
        assertEquals("webSearch", starts[2].getString("type"))
        assertEquals("cari ini", starts[2].getString("query"))
        assertEquals("dynamicToolCall", starts[3].getString("type"))
    }

    @Test fun errorsAndResultStatus() {
        val (p, ev) = all(
            """{"type":"error","severity":"warning","message":"hanya peringatan"}""",
            """{"type":"error","severity":"error","message":"kuota habis"}""",
            """{"type":"result","status":"error"}"""
        )
        assertEquals(listOf(GeminiEvent.Failure("kuota habis")), ev.filterIsInstance<GeminiEvent.Failure>())
        assertEquals("kuota habis", p.failed)
        assertEquals(GeminiEvent.Done(false), ev.last())
    }

    @Test fun plainTextAndBrokenJsonAreIgnoredAndNotCountedAsJson() {
        val (p, ev) = all("Loaded cached credentials.", "{bukan json", "Halo biasa")
        assertTrue(ev.isEmpty())
        assertFalse(p.sawJson)
        assertEquals("", p.text())
        assertNull(p.failed)
    }

    @Test fun detectsOldCliThatRejectsTheFlag() {
        assertTrue(geminiStreamUnsupported("Unknown arguments: output-format, outputFormat", false))
        assertTrue(geminiStreamUnsupported("Invalid values:\n  Argument: output-format, Given: \"stream-json\", Choices: \"text\", \"json\"", false))
        assertFalse(geminiStreamUnsupported("Unknown arguments: output-format", true))
        assertFalse(geminiStreamUnsupported("Error: quota exceeded", false))
        assertFalse(geminiStreamUnsupported("", false))
    }

    @Test fun commandGetsStreamFlagOnlyWhenAskedAndOldFormatIsReplaced() {
        val quote = { s: String -> "'$s'" }
        assertEquals(
            "gemini --model 'auto' --output-format stream-json -p {prompt}",
            geminiCliCommandWithModel("gemini -p {prompt}", "auto", quote, streamJson = true)
        )
        assertEquals(
            "gemini --model 'auto' -p {prompt}",
            geminiCliCommandWithModel("gemini -p {prompt}", "auto", quote)
        )
        assertEquals(
            "gemini --model 'flash' --output-format stream-json -p {prompt}",
            geminiCliCommandWithModel("gemini -o json --output-format text -p {prompt}", "flash", quote, streamJson = true)
        )
    }
}
