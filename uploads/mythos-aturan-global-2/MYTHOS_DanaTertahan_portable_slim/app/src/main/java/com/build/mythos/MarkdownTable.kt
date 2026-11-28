package com.build.mythos

// Deteksi tabel Markdown (| a | b |) tanpa Android: murni JVM supaya bisa dites.

internal class MdTable(val header: List<String>, val rows: List<List<String>>)

internal sealed class MdBlock {
    class Text(val text: String) : MdBlock()
    class Table(val table: MdTable) : MdBlock()
}

private val sepRe = Regex("""^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$""")

internal fun isTableSeparator(line: String): Boolean = line.contains('-') && sepRe.matches(line)

internal fun isTableLine(line: String): Boolean = line.trimStart().startsWith("|")

/** Pecah satu baris tabel menjadi sel; '|' di awal/akhir diabaikan, `\|` dianggap teks biasa. */
internal fun splitTableCells(line: String): List<String> {
    var t = line.trim()
    if (t.startsWith("|")) t = t.substring(1)
    if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
    val cells = ArrayList<String>()
    val cur = StringBuilder()
    var i = 0
    while (i < t.length) {
        val c = t[i]
        if (c == '\\' && i + 1 < t.length && t[i + 1] == '|') { cur.append('|'); i += 2; continue }
        if (c == '|') { cells.add(cur.toString().trim()); cur.setLength(0) } else cur.append(c)
        i++
    }
    cells.add(cur.toString().trim())
    return cells
}

/** Tabel dimulai di baris berawalan '|' yang diikuti baris pemisah (---); berlanjut selama baris berawalan '|'. */
internal fun splitMdBlocks(src: String): List<MdBlock> {
    val lines = src.split("\n")
    val out = ArrayList<MdBlock>()
    val text = StringBuilder()
    fun flushText() { if (text.isNotEmpty()) { out.add(MdBlock.Text(text.toString().trimEnd('\n'))); text.setLength(0) } }
    var i = 0
    while (i < lines.size) {
        if (isTableLine(lines[i]) && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
            flushText()
            val header = splitTableCells(lines[i])
            val rows = ArrayList<List<String>>()
            var j = i + 2
            while (j < lines.size && isTableLine(lines[j])) {
                val r = splitTableCells(lines[j])
                rows.add(List(header.size) { k -> r.getOrElse(k) { "" } })
                j++
            }
            out.add(MdBlock.Table(MdTable(header, rows)))
            i = j
        } else {
            text.append(lines[i]).append('\n')
            i++
        }
    }
    flushText()
    return out
}
