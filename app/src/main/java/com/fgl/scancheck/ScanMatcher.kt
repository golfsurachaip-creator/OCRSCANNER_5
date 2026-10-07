package com.fgl.scancheck

data class ScanSettings(
    val targets: List<String>,
    val exact: Boolean,
    val ignoreCase: Boolean,
    val ignoreSpace: Boolean,
)

object ScanMatcher {

    private val separators = Regex("[\\s\\-_./]+")

    fun normalize(input: String, s: ScanSettings): String {
        var r = input.trim()
        if (s.ignoreCase) r = r.uppercase()
        if (s.ignoreSpace) r = r.replace(separators, "")
        return r
    }

    /** Returns the target that matches the scanned value, or null if none does. */
    fun match(scanned: String, s: ScanSettings): String? {
        val v = normalize(scanned, s)
        if (v.isEmpty()) return null
        return s.targets.firstOrNull { target ->
            val t = normalize(target, s)
            t.isNotEmpty() && (if (s.exact) v == t else v.contains(t))
        }
    }
}
