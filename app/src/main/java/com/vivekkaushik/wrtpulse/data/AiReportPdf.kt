package com.vivekkaushik.wrtpulse.data

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File

/**
 * Renders the plain-text report into a paginated PDF with the platform's own PdfDocument —
 * no dependency, no print framework, just pages of text that read on any phone or laptop.
 *
 * The text is laid out one wrapped line at a time against a fixed A4 page; a new page starts
 * when the current one has 36 points of margin left. Headings (the ALL-CAPS lines the text
 * builder emits) get the only styling there is: bold and a darker grey.
 */
object AiReportPdf {

    private const val PAGE_W = 595 // A4 at 72 dpi, points
    private const val PAGE_H = 842
    private const val MARGIN = 44
    private const val BODY_SIZE = 9.5f
    private const val LEADING = 13.5f
    private const val HEADING_SIZE = 11f

    /** Writes the report to [file] and returns it; the caller hands it to the share sheet. */
    fun write(text: String, file: File): File {
        val doc = PdfDocument()
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(38, 44, 44)
            textSize = BODY_SIZE
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(12, 30, 27)
            textSize = HEADING_SIZE
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val maxWidth = (PAGE_W - 2 * MARGIN).toFloat()

        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, doc.pages.size + 1).create())
        var canvas = page.canvas
        var y = MARGIN.toFloat()
        var pageNum = 1

        fun newPageIfNeeded(needed: Float) {
            if (y + needed <= (PAGE_H - MARGIN).toFloat()) return
            doc.finishPage(page)
            pageNum += 1
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
            canvas = page.canvas
            y = MARGIN.toFloat()
        }

        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            if (line.isEmpty()) {
                y += LEADING * 0.6f
                return@forEach
            }
            val paint = if (isHeading(line)) heading else body
            val wrapped = wrap(line, paint, maxWidth)
            wrapped.forEach { chunk ->
                newPageIfNeeded(LEADING)
                canvas.drawText(chunk, MARGIN.toFloat(), y, paint)
                y += LEADING
            }
        }
        doc.finishPage(page)
        file.parentFile?.mkdirs()
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    /** The text report's ALL-CAPS section lines — the only lines that get heading styling. */
    private fun isHeading(line: String): Boolean =
        line.length in 3..40 && line.uppercase() == line && line.none { it.isLowerCase() } &&
            line.any { it.isLetter() }

    /** Greedy word wrap against the paint's own measurement of the available width. */
    private fun wrap(line: String, paint: Paint, maxWidth: Float): List<String> {
        if (paint.measureText(line) <= maxWidth) return listOf(line)
        val out = mutableListOf<String>()
        val words = line.split(' ')
        var current = StringBuilder()
        words.forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth || current.isEmpty()) {
                current = StringBuilder(candidate)
            } else {
                out += current.toString()
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }
}
