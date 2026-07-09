package com.mydream.calllogger.export

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A tiny, dependency-free writer for .xlsx (Office Open XML) spreadsheets.
 *
 * An xlsx file is simply a ZIP archive of XML parts. This writes the minimal set
 * of parts required for a single worksheet using inline strings and numbers,
 * producing a file that opens correctly in Excel, Google Sheets and LibreOffice
 * without pulling in a heavyweight library such as Apache POI.
 */
object XlsxWriter {

    sealed class Cell
    data class Text(val value: String) : Cell()
    data class Number(val value: Double) : Cell()

    fun write(
        out: OutputStream,
        sheetName: String,
        headers: List<String>,
        rows: List<List<Cell>>
    ) {
        ZipOutputStream(out).use { zip ->
            zip.putEntry("[Content_Types].xml", contentTypes())
            zip.putEntry("_rels/.rels", rootRels())
            zip.putEntry("xl/workbook.xml", workbook(sheetName))
            zip.putEntry("xl/_rels/workbook.xml.rels", workbookRels())
            zip.putEntry("xl/worksheets/sheet1.xml", sheet(headers, rows))
        }
    }

    private fun ZipOutputStream.putEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun contentTypes() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>"""

    private fun rootRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private fun workbook(sheetName: String) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets>
<sheet name="${escape(sheetName.take(31))}" sheetId="1" r:id="rId1"/>
</sheets>
</workbook>"""

    private fun workbookRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>"""

    private fun sheet(headers: List<String>, rows: List<List<Cell>>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        sb.append("<sheetData>")

        var rowNum = 1
        sb.append("<row r=\"").append(rowNum).append("\">")
        headers.forEachIndexed { c, h -> sb.append(textCell(colRef(c) + rowNum, h)) }
        sb.append("</row>")

        for (row in rows) {
            rowNum++
            sb.append("<row r=\"").append(rowNum).append("\">")
            row.forEachIndexed { c, cell ->
                val ref = colRef(c) + rowNum
                when (cell) {
                    is Text -> sb.append(textCell(ref, cell.value))
                    is Number -> sb.append("<c r=\"").append(ref).append("\"><v>")
                        .append(numberString(cell.value)).append("</v></c>")
                }
            }
            sb.append("</row>")
        }

        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun numberString(value: Double): String =
        // Render whole numbers without a trailing ".0", but only inside the range
        // where Long conversion is exact; otherwise fall back to the Double string.
        if (value.isFinite() && value == Math.floor(value) && kotlin.math.abs(value) < 1e15)
            value.toLong().toString()
        else value.toString()

    private fun textCell(ref: String, value: String) =
        "<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(value)}</t></is></c>"

    /** Converts a 0-based column index to a spreadsheet column reference (A, B, ... Z, AA, ...). */
    private fun colRef(index: Int): String {
        var i = index
        val sb = StringBuilder()
        while (i >= 0) {
            sb.insert(0, 'A' + (i % 26))
            i = i / 26 - 1
        }
        return sb.toString()
    }

    private fun escape(s: String): String = buildString {
        for (ch in s) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') append(' ') else append(ch)
            }
        }
    }
}
