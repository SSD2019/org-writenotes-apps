package com.orgwritenotes.ink

/**
 * E3: rule-based post-processing of handwriting-OCR text.
 *  - a hand-drawn checkbox (`[ ]`, `[]`, `[x]`, `☐`, `☑`) becomes a checklist item
 *  - plain-text maths (`x^2 + a/b = sqrt(y)`) becomes LaTeX
 *
 * This is deliberately heuristic: it cleans up what the OCR engine already recognised; it is not a
 * handwriting-to-maths model.
 */
object OcrSmartInsert {
    data class Checkbox(val checked: Boolean, val text: String)

    private val checkboxRegex = Regex("""^\s*(?:[\[\(\{]\s*([xXvV✓✔]?)\s*[\]\)\}]|([☐☑☒]))\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)

    /** Returns the parsed checkbox if [text] starts with a drawn checkbox, else null. */
    fun parseCheckbox(text: String): Checkbox? {
        val m = checkboxRegex.matchEntire(text.trim()) ?: return null
        val rest = m.groupValues[3].trim()
        if (rest.isEmpty()) return null
        val checked = m.groupValues[1].isNotEmpty() || m.groupValues[2] == "☑" || m.groupValues[2] == "☒"
        return Checkbox(checked, rest)
    }

    private val greek = mapOf(
        "alpha" to "\\alpha", "beta" to "\\beta", "gamma" to "\\gamma", "delta" to "\\delta",
        "theta" to "\\theta", "lambda" to "\\lambda", "mu" to "\\mu", "pi" to "\\pi",
        "sigma" to "\\sigma", "omega" to "\\omega", "infinity" to "\\infty", "inf" to "\\infty"
    )

    /** True when [text] looks like a formula rather than prose. */
    fun looksLikeMath(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 200) return false
        val hasOperator = Regex("""[=^]|sqrt\(|\d\s*[/*+\-]\s*\d|[a-z]\s*[/^]\s*[a-z0-9(]""").containsMatchIn(t)
        val wordCount = t.split(Regex("\\s+")).count { it.length > 3 && it.all(Char::isLetter) && it !in greek }
        return hasOperator && wordCount <= 2
    }

    private const val TOKEN = """(\([^()]*\)|[A-Za-z0-9.\\]+)"""

    private fun group(s: String): String =
        if (s.startsWith("(") && s.endsWith(")")) s.substring(1, s.length - 1) else s

    /** Converts simple plain-text maths to a LaTeX expression (without delimiters). */
    fun toLatex(text: String): String {
        var s = text.trim()
        s = s.replace("<=", "\\le ").replace(">=", "\\ge ").replace("!=", "\\ne ")
        s = Regex("""sqrt\(([^()]*)\)""").replace(s) { "\\sqrt{${it.groupValues[1]}}" }
        for ((word, cmd) in greek) {
            s = Regex("""\b$word\b""", RegexOption.IGNORE_CASE).replace(s) { "$cmd " }
        }
        // x^2, x^(n+1) -> x^{2}, x^{n+1}
        s = Regex("""\^\s*$TOKEN""").replace(s) { "^{${group(it.groupValues[1])}}" }
        // a/b, (a+b)/c -> \frac{a}{b}; applied repeatedly for chained fractions
        val frac = Regex("""$TOKEN\s*/\s*$TOKEN""")
        var prev: String
        do {
            prev = s
            s = frac.replace(s) { "\\frac{${group(it.groupValues[1])}}{${group(it.groupValues[2])}}" }
        } while (s != prev)
        s = Regex("""\s*\*\s*""").replace(s) { " \\cdot " }
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    fun toLatexParagraph(text: String): String = "\\(${toLatex(text)}\\)"
}
