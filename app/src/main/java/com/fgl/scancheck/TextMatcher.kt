package com.fgl.scancheck

data class MatchSettings(
    val targets: List<String>,
    val requireAll: Boolean,
    val ignoreCase: Boolean,
    val ignoreSpace: Boolean,
    val fuzzy: Boolean,
)

data class MatchResult(
    val hasText: Boolean,
    val passed: Boolean,
    val perTarget: List<Pair<String, Boolean>>,
)

object TextMatcher {

    private val separators = Regex("[\\s\\-_./]+")

    fun normalize(input: String, s: MatchSettings): String {
        var r = input
        if (s.ignoreCase || s.fuzzy) r = r.uppercase()
        if (s.ignoreSpace) r = r.replace(separators, "")
        if (s.fuzzy) {
            r = buildString(r.length) {
                for (c in r) {
                    append(
                        when (c) {
                            'O' -> '0'
                            'I', 'L', '|', '!' -> '1'
                            'S' -> '5'
                            'B' -> '8'
                            'Z' -> '2'
                            else -> c
                        }
                    )
                }
            }
        }
        return r
    }

    fun check(ocrText: String, s: MatchSettings): MatchResult {
        val hasText = ocrText.isNotBlank()
        if (s.targets.isEmpty()) return MatchResult(hasText, false, emptyList())

        // Compare against each line and against the whole text joined,
        // so a target split across lines can still be found.
        val haystackLines = ocrText.lines().map { normalize(it, s) }
        val haystackAll = normalize(ocrText.replace('\n', ' '), s)

        val perTarget = s.targets.map { target ->
            val t = normalize(target, s)
            val found = t.isNotEmpty() &&
                (haystackLines.any { it.contains(t) } || haystackAll.contains(t))
            target to found
        }
        val passed = if (s.requireAll) perTarget.all { it.second } else perTarget.any { it.second }
        return MatchResult(hasText, passed, perTarget)
    }
}
