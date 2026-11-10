package dev.isaakrichards.tasktemplar.engine

import java.math.BigDecimal

/** Variable types, value normalisation, comparisons and a small arithmetic evaluator. Pure Kotlin. */
object VarTypes {
    const val INTEGER = "integer"
    const val DECIMAL = "decimal"
    const val BOOLEAN = "boolean"
    const val STRING = "string"

    fun truthy(s: String): Boolean = s.trim().lowercase() in setOf("true", "1", "yes", "on", "y")

    fun format(d: Double, type: String): String = when {
        type == INTEGER -> d.toLong().toString()
        d == Math.floor(d) && Math.abs(d) < 1e15 -> d.toLong().toString()
        else -> BigDecimal(d.toString()).stripTrailingZeros().toPlainString()
    }

    /** Converts a raw value to the canonical form for [type]. Numbers accept math expressions. */
    fun normalize(type: String, raw: String): String = when (type) {
        INTEGER, DECIMAL -> eval(raw)?.let { format(it, type) } ?: "0"
        BOOLEAN -> truthy(raw).toString()
        else -> raw
    }

    fun compare(left: String, op: String, right: String): Boolean {
        val a = left.trim().toDoubleOrNull()
        val b = right.trim().toDoubleOrNull()
        val numeric = a != null && b != null
        return when (op) {
            "eq" -> if (numeric) a == b else left.trim().equals(right.trim(), ignoreCase = true)
            "ne" -> !compare(left, "eq", right)
            "lt" -> if (numeric) a!! < b!! else left.compareTo(right, ignoreCase = true) < 0
            "le" -> if (numeric) a!! <= b!! else left.compareTo(right, ignoreCase = true) <= 0
            "gt" -> if (numeric) a!! > b!! else left.compareTo(right, ignoreCase = true) > 0
            "ge" -> if (numeric) a!! >= b!! else left.compareTo(right, ignoreCase = true) >= 0
            "contains" -> left.contains(right, ignoreCase = true)
            "not_contains" -> !left.contains(right, ignoreCase = true)
            "empty" -> left.isBlank()
            "not_empty" -> left.isNotBlank()
            "is_true" -> truthy(left)
            "is_false" -> !truthy(left)
            else -> false
        }
    }

    /** Evaluates + - * / % with parentheses and unary minus. Returns null if [s] isn't valid math. */
    fun eval(s: String): Double? = runCatching {
        val v = MathParser(s).parse()
        if (v.isNaN() || v.isInfinite()) null else v
    }.getOrNull()

    private class MathParser(private val s: String) {
        private var i = 0

        fun parse(): Double {
            val v = expr()
            skip()
            require(i == s.length) { "Unexpected '${s.substring(i)}'" }
            return v
        }

        private fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun expr(): Double {
            var v = term()
            while (true) {
                skip()
                if (i < s.length && (s[i] == '+' || s[i] == '-')) {
                    val op = s[i++]
                    val r = term()
                    v = if (op == '+') v + r else v - r
                } else return v
            }
        }

        private fun term(): Double {
            var v = factor()
            while (true) {
                skip()
                if (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '%')) {
                    val op = s[i++]
                    val r = factor()
                    v = when (op) {
                        '*' -> v * r
                        '/' -> v / r
                        else -> v % r
                    }
                } else return v
            }
        }

        private fun factor(): Double {
            skip()
            require(i < s.length) { "Unexpected end" }
            return when (s[i]) {
                '-' -> { i++; -factor() }
                '+' -> { i++; factor() }
                '(' -> {
                    i++
                    val v = expr()
                    skip()
                    require(i < s.length && s[i] == ')') { "Missing )" }
                    i++
                    v
                }
                else -> number()
            }
        }

        private fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            require(i > start) { "Expected a number" }
            return s.substring(start, i).toDouble()
        }
    }
}

/** Random numbers within a range, kept pure so it can be unit tested. */
object RandomRange {
    fun pick(lo: Double, hi: Double, type: String, rng: kotlin.random.Random = kotlin.random.Random): String =
        if (type == VarTypes.INTEGER) {
            val a = Math.ceil(lo).toLong()
            val b = Math.floor(hi).toLong()
            if (a > b) error("No whole number between ${VarTypes.format(lo, VarTypes.DECIMAL)} and ${VarTypes.format(hi, VarTypes.DECIMAL)}")
            (if (b == Long.MAX_VALUE) rng.nextLong(a, b) else rng.nextLong(a, b + 1)).toString()
        } else {
            val d = if (lo == hi) lo else rng.nextDouble(lo, hi)
            VarTypes.format(java.math.BigDecimal(d).setScale(2, java.math.RoundingMode.HALF_UP).toDouble().coerceIn(lo, hi), VarTypes.DECIMAL)
        }
}
