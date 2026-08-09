package com.deep.gpslogger

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal, dependency-free writer for a single-sheet .xlsx (OOXML) file.
 *
 * Why not a library: Apache POI (the usual choice) drags in a large,
 * awkward dependency tree that's a poor fit for an Android app this small.
 * A single sheet with plain numbers/text doesn't need it - a valid xlsx is
 * just a zip archive with a handful of small XML parts, which this writes
 * directly.
 *
 * Supports Int, Long, Double/Float, and String cell values. Anything else
 * is written as its toString().
 */
object XlsxWriter {

    fun write(out: OutputStream, headers: List<String>, rows: List<List<Any?>>) {
        ZipOutputStream(out).use { zip ->
            writeStoredOrDeflated(zip, "[Content_Types].xml", contentTypesXml())
            writeStoredOrDeflated(zip, "_rels/.rels", rootRelsXml())
            writeStoredOrDeflated(zip, "xl/workbook.xml", workbookXml())
            writeStoredOrDeflated(zip, "xl/_rels/workbook.xml.rels", workbookRelsXml())
            writeStoredOrDeflated(zip, "xl/worksheets/sheet1.xml", sheetXml(headers, rows))
        }
    }

    private fun writeStoredOrDeflated(zip: ZipOutputStream, name: String, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val entry = ZipEntry(name)
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun contentTypesXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>"""

    private fun rootRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private fun workbookXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets>
<sheet name="Route" sheetId="1" r:id="rId1"/>
</sheets>
</workbook>"""

    private fun workbookRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>"""

    private fun colLetter(index: Int): String {
        // 0 -> A, 1 -> B, ...
        var n = index
        val sb = StringBuilder()
        do {
            sb.insert(0, ('A' + (n % 26)))
            n = n / 26 - 1
        } while (n >= 0)
        return sb.toString()
    }

    private fun escapeXml(s: String): String {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun cellXml(colIndex: Int, rowIndex: Int, value: Any?): String {
        val ref = "${colLetter(colIndex)}${rowIndex}"
        return when (value) {
            null -> """<c r="$ref"/>"""
            is Int, is Long -> """<c r="$ref"><v>$value</v></c>"""
            is Double, is Float -> """<c r="$ref"><v>$value</v></c>"""
            is String -> """<c r="$ref" t="inlineStr"><is><t>${escapeXml(value)}</t></is></c>"""
            else -> """<c r="$ref" t="inlineStr"><is><t>${escapeXml(value.toString())}</t></is></c>"""
        }
    }

    private fun sheetXml(headers: List<String>, rows: List<List<Any?>>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        sb.append("<sheetData>")

        // Header row (row 1)
        sb.append("""<row r="1">""")
        headers.forEachIndexed { i, h -> sb.append(cellXml(i, 1, h)) }
        sb.append("</row>")

        // Data rows
        rows.forEachIndexed { rIdx, row ->
            val excelRow = rIdx + 2 // 1 is the header row
            sb.append("""<row r="$excelRow">""")
            row.forEachIndexed { cIdx, value -> sb.append(cellXml(cIdx, excelRow, value)) }
            sb.append("</row>")
        }

        sb.append("</sheetData>")
        sb.append("</worksheet>")
        return sb.toString()
    }
}