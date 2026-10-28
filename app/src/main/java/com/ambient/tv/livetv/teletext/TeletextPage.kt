package com.ambient.tv.livetv.teletext

/** One received teletext page: header text and rows 1..24, 7-bit codes as broadcast. */
class TeletextPage(
    /** Hex page number, 0x100..0x8FF. */
    val number: Int,
    val subcode: Int,
    /** C12..C14: national option of the default Latin G0 set. */
    val nationalOption: Int,
    /** C6: a subtitle page, shown boxed over the picture. */
    val subtitle: Boolean,
    /** C5: a newsflash page, also boxed. */
    val newsflash: Boolean,
    /** Header positions 8..39. */
    val header: ByteArray,
    val rows: Array<ByteArray?> = arrayOfNulls(25),
    /** FLOF links (packet 27): red, green, yellow, cyan, -, index; 0 = none. */
    var links: IntArray? = null,
) {
    fun copy() = TeletextPage(number, subcode, nationalOption, subtitle, newsflash, header.copyOf(),
        Array(25) { rows[it]?.copyOf() }, links?.copyOf())

    /** Row 0 as 40 codes: the page number in place of the first eight, then the broadcast header. */
    fun headerRow(shownNumber: Int = number): ByteArray {
        val label = " P%3X  ".format(shownNumber).padEnd(8).take(8)
        return ByteArray(40) { i -> if (i < 8) (if (i == 0) 0x07 else label[i].code).toByte() else header[i - 8] }
    }
}

/** One character cell after attribute processing. */
data class TeletextCell(
    /** The character, or ' ' for mosaics and spacing attributes. */
    val char: Char = ' ',
    /** Sixel bits 0..5 (top-left, top-right, middle-left, middle-right, bottom-left, bottom-right); -1 = text. */
    val mosaic: Int = -1,
    val separated: Boolean = false,
    val foreground: Int = 7,
    val background: Int = 0,
    val doubleHeight: Boolean = false,
    val flash: Boolean = false,
    val conceal: Boolean = false,
    /** Inside start/end box: what a subtitle or newsflash page shows over the picture. */
    val boxed: Boolean = false,
)

data class TeletextRow(val cells: List<TeletextCell>, val doubleHeight: Boolean, val hidden: Boolean)

/** A subtitle line: boxed text and the colour it starts with (0..7). */
data class TeletextSubtitleLine(val text: String, val color: Int)

/** RGB of the eight teletext colours. */
val TELETEXT_COLORS = intArrayOf(0x000000, 0xFF0000, 0x00FF00, 0xFFFF00, 0x0000FF, 0xFF00FF, 0x00FFFF, 0xFFFFFF)

/**
 * Latin G0 national option subsets (ETS 300 706 table 36) for the 13 replaceable positions
 * 0x23 0x24 0x40 0x5B 0x5C 0x5D 0x5E 0x5F 0x60 0x7B 0x7C 0x7D 0x7E, indexed by C12 C13 C14.
 */
private val NATIONAL_POSITIONS = intArrayOf(0x23, 0x24, 0x40, 0x5B, 0x5C, 0x5D, 0x5E, 0x5F, 0x60, 0x7B, 0x7C, 0x7D, 0x7E)
private val NATIONAL_SUBSETS = arrayOf(
    "£\$@←½→↑#—¼‖¾÷", // 0 English
    "#\$§ÄÖÜ^_°äöüß", // 1 German
    "#¤ÉÄÖÅÜ_éäöåü", // 2 Swedish/Finnish/Hungarian
    "£\$é°ç→↑#ùàòèì", // 3 Italian
    "éïàëêùî#èâôûç", // 4 French
    "ç\$¡áéíóú¿üñèà", // 5 Portuguese/Spanish
    "#ůčťžýířéáěúš", // 6 Czech/Slovak
    "£\$@←½→↑#—¼‖¾÷", // 7 (not Latin West): English as the safest fallback
)

/** The displayed character for a G0 code with [national] option. */
fun teletextChar(code: Int, national: Int): Char {
    if (code == 0x7F) return '■'
    if (code < 0x20) return ' '
    val position = NATIONAL_POSITIONS.indexOf(code)
    if (position >= 0) return NATIONAL_SUBSETS[national.coerceIn(0, 7)][position]
    return code.toChar()
}

/** Text of a row without attributes, for page lists and tests. */
fun TeletextRow.text(): String = cells.joinToString("") { if (it.mosaic >= 0) " " else it.char.toString() }

/**
 * Level 1 spacing attributes for one page: colours, mosaics (contiguous/separated, hold), double
 * height, flash, conceal and boxes. A double-height row hides the row below it.
 */
fun layoutTeletextPage(page: TeletextPage, shownNumber: Int = page.number): List<TeletextRow> {
    val result = ArrayList<TeletextRow>(25)
    var skipNext = false
    for (r in 0 until 25) {
        val codes = if (r == 0) page.headerRow(shownNumber) else page.rows[r]
        if (skipNext) {
            skipNext = false
            result += TeletextRow(List(40) { TeletextCell() }, doubleHeight = false, hidden = true)
            continue
        }
        if (codes == null) { result += TeletextRow(List(40) { TeletextCell() }, false, false); continue }
        val row = layoutTeletextRow(codes, page.nationalOption, allowDoubleHeight = r in 1..22)
        result += row
        if (row.doubleHeight) skipNext = true
    }
    return result
}

fun layoutTeletextRow(codes: ByteArray, national: Int, allowDoubleHeight: Boolean = true): TeletextRow {
    var fg = 7
    var bg = 0
    var mosaic = false
    var separated = false
    var hold = false
    var heldMosaic = -1
    var heldSeparated = false
    var doubleHeight = false
    var flash = false
    var conceal = false
    var boxed = false
    var anyDouble = false
    val cells = ArrayList<TeletextCell>(40)
    for (i in 0 until 40) {
        val code = codes.getOrElse(i) { 0x20 }.toInt() and 0x7F
        if (code < 0x20) {
            // "Set-at" attributes act on this cell already.
            when (code) {
                0x09 -> flash = false
                0x0C -> { if (doubleHeight) heldMosaic = -1; doubleHeight = false }
                0x18 -> conceal = true
                0x19 -> separated = false
                0x1A -> separated = true
                0x1C -> bg = 0
                0x1D -> bg = fg
                0x1E -> hold = true
            }
            val showHeld = hold && mosaic && heldMosaic >= 0
            cells += TeletextCell(' ', if (showHeld) heldMosaic else -1, if (showHeld) heldSeparated else false,
                fg, bg, doubleHeight, flash, conceal, boxed)
            // "Set-after" attributes act from the next cell.
            when (code) {
                in 0x00..0x07 -> { fg = code; if (mosaic) heldMosaic = -1; mosaic = false; conceal = false }
                0x08 -> flash = true
                0x0A -> boxed = false
                0x0B -> boxed = true
                0x0D, 0x0F -> if (allowDoubleHeight) { if (!doubleHeight) heldMosaic = -1; doubleHeight = true; anyDouble = true }
                0x0E -> Unit // double width: shown at normal size
                in 0x10..0x17 -> { fg = code - 0x10; if (!mosaic) heldMosaic = -1; mosaic = true; conceal = false }
                0x1F -> hold = false
            }
            continue
        }
        if (mosaic && (code in 0x20..0x3F || code in 0x60..0x7F)) {
            val bits = (code and 0x1F) or ((code and 0x40) shr 1)
            heldMosaic = bits
            heldSeparated = separated
            cells += TeletextCell(' ', bits, separated, fg, bg, doubleHeight, flash, conceal, boxed)
        } else {
            cells += TeletextCell(teletextChar(code, national), -1, false, fg, bg, doubleHeight, flash, conceal, boxed)
        }
    }
    return TeletextRow(cells, anyDouble, hidden = false)
}

/** Boxed text of a subtitle (or newsflash) page, one entry per non-empty row, top to bottom. */
fun teletextSubtitleLines(page: TeletextPage): List<TeletextSubtitleLine> =
    layoutTeletextPage(page).drop(1).filterNot { it.hidden }.mapNotNull { row ->
        val boxedCells = row.cells.filter { it.boxed && it.mosaic < 0 }
        val text = boxedCells.joinToString("") { it.char.toString() }.trim().replace(Regex(" {2,}"), " ")
        if (text.isEmpty()) null
        else TeletextSubtitleLine(text, boxedCells.firstOrNull { it.char != ' ' }?.foreground ?: 7)
    }

/** Next (or previous) page number: a received one when there is one, else simply ±1. */
fun teletextStepPage(current: Int, received: List<Int>, forward: Boolean): Int {
    val candidate = if (forward) received.firstOrNull { it > current } else received.lastOrNull { it < current }
    if (candidate != null) return candidate
    val decimal = teletextDecimal(current) + if (forward) 1 else -1
    return teletextNumber(if (decimal > 899) 100 else if (decimal < 100) 899 else decimal) ?: 0x100
}

/**
 * The subtitle page for [choice]: a page number as given; "auto" takes the announced subtitle page
 * in a preferred language, then any announced one, then the first page flagged as subtitles.
 */
fun teletextSubtitlePage(
    choice: String,
    announced: List<TeletextAnnouncedPage>,
    seen: List<Int>,
    preferredLanguages: Set<String>,
): Int? = when (choice) {
    "off" -> null
    "auto" -> announced.filter { it.subtitle }.let { pages ->
        pages.firstOrNull { it.language.lowercase() in preferredLanguages }?.number ?: pages.firstOrNull()?.number
    } ?: seen.firstOrNull()
    else -> choice.toIntOrNull()?.let(::teletextNumber)
}
