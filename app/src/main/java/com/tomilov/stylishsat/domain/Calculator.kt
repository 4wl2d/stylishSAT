package com.tomilov.stylishsat.domain

import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * Scientific calculator for SAT Math, where a calculator is allowed on every question. A working tool only:
 * it never checks or submits an answer.
 *
 * Grammar: expression := term (('+' | '-') term)*; term := unary (('*' | '/' | implicit) unary)*;
 * unary := ('-' | '+') unary | power; power := postfix ('^' unary)?; postfix := primary ('!' | '%')*;
 * primary := number | constant | function '(' expression ')' | '(' expression ')' | '√' unary.
 */
object Calculator {
    enum class Angle { DEG, RAD }

    sealed interface Result {
        data class Value(val value: Double) : Result
        data class Error(val reason: Reason) : Result
    }
    enum class Reason { SYNTAX, DIVIDE_BY_ZERO, DOMAIN, OVERFLOW }

    private class Failure(val reason: Reason) : RuntimeException()

    private val functions = setOf("sin", "cos", "tan", "asin", "acos", "atan", "ln", "log", "sqrt", "abs")

    fun evaluate(expression: String, angle: Angle = Angle.DEG, ans: Double = 0.0): Result = try {
        val parser = Parser(tokens(expression), angle, ans)
        val value = parser.expression()
        if (!parser.done()) throw Failure(Reason.SYNTAX)
        when {
            value.isNaN() -> Result.Error(Reason.DOMAIN)
            value.isInfinite() -> Result.Error(Reason.OVERFLOW)
            else -> Result.Value(if (value == 0.0) 0.0 else value)
        }
    } catch (failure: Failure) { Result.Error(failure.reason) }

    internal fun tokens(expression: String): List<String> {
        val text = expression.replace('×', '*').replace('÷', '/').replace('−', '-').replace("π", "pi").replace(" ", "")
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch.isDigit() || ch == '.' -> {
                    val start = i
                    while (i < text.length && (text[i].isDigit() || text[i] == '.')) i++
                    // Scientific notation such as 1.5E-3.
                    if (i < text.length && text[i] == 'E' && i + 1 < text.length && (text[i + 1].isDigit() || text[i + 1] == '-' || text[i + 1] == '+')) {
                        i++; if (text[i] == '-' || text[i] == '+') i++
                        while (i < text.length && text[i].isDigit()) i++
                    }
                    tokens += text.substring(start, i)
                }
                ch.isLetter() -> {
                    val start = i
                    while (i < text.length && text[i].isLetter()) i++
                    val word = text.substring(start, i).lowercase(Locale.ROOT)
                    if (word !in functions && word !in setOf("pi", "e", "ans")) throw Failure(Reason.SYNTAX)
                    tokens += word
                }
                ch in "+-*/^()!%√" -> { tokens += ch.toString(); i++ }
                else -> throw Failure(Reason.SYNTAX)
            }
        }
        return tokens
    }

    private class Parser(val tokens: List<String>, val angle: Angle, val ans: Double) {
        var position = 0
        fun done() = position == tokens.size
        fun peek() = tokens.getOrNull(position)
        fun take(): String = tokens.getOrNull(position++) ?: throw Failure(Reason.SYNTAX)

        fun expression(): Double {
            var value = term()
            while (peek() == "+" || peek() == "-") value = if (take() == "+") value + term() else value - term()
            return value
        }

        fun term(): Double {
            var value = unary()
            while (true) {
                val next = peek() ?: break
                value = when {
                    next == "*" -> { take(); value * unary() }
                    next == "/" -> { take(); val divisor = unary(); if (divisor == 0.0) throw Failure(Reason.DIVIDE_BY_ZERO); value / divisor }
                    // Implicit multiplication: 2π, 3(4), 2sin(30), (1)(2).
                    next == "(" || next == "√" || next in functions || next == "pi" || next == "e" || next == "ans" || next.first().isDigit() || next.first() == '.' -> value * unary()
                    else -> break
                }
            }
            return value
        }

        fun unary(): Double = when (peek()) {
            "-" -> { take(); -unary() }
            "+" -> { take(); unary() }
            else -> power()
        }

        fun power(): Double {
            val base = postfix()
            if (peek() != "^") return base
            take()
            val exponent = unary()
            val value = Math.pow(base, exponent)
            if (value.isNaN() && !base.isNaN()) throw Failure(Reason.DOMAIN)
            return value
        }

        fun postfix(): Double {
            var value = primary()
            while (peek() == "!" || peek() == "%") {
                value = if (take() == "!") factorial(value) else value / 100
            }
            return value
        }

        fun primary(): Double {
            val token = take()
            return when {
                token.first().isDigit() || token.first() == '.' -> token.toDoubleOrNull() ?: throw Failure(Reason.SYNTAX)
                token == "pi" -> Math.PI
                token == "e" -> Math.E
                token == "ans" -> ans
                token == "(" -> expression().also { if (take() != ")") throw Failure(Reason.SYNTAX) }
                token == "√" -> root(unary())
                token in functions -> {
                    if (take() != "(") throw Failure(Reason.SYNTAX)
                    val argument = expression()
                    if (take() != ")") throw Failure(Reason.SYNTAX)
                    apply(token, argument)
                }
                else -> throw Failure(Reason.SYNTAX)
            }
        }

        fun root(value: Double): Double = if (value < 0) throw Failure(Reason.DOMAIN) else Math.sqrt(value)

        fun toRadians(value: Double) = if (angle == Angle.DEG) Math.toRadians(value) else value
        fun fromRadians(value: Double) = if (angle == Angle.DEG) Math.toDegrees(value) else value

        fun apply(name: String, x: Double): Double = when (name) {
            "sin" -> clean(Math.sin(toRadians(x)))
            "cos" -> clean(Math.cos(toRadians(x)))
            "tan" -> {
                // tan is undefined at odd multiples of 90 degrees.
                if (angle == Angle.DEG && abs(((x % 180) + 180) % 180 - 90) < 1e-12) throw Failure(Reason.DOMAIN)
                clean(Math.tan(toRadians(x)))
            }
            "asin" -> if (x < -1 || x > 1) throw Failure(Reason.DOMAIN) else fromRadians(Math.asin(x))
            "acos" -> if (x < -1 || x > 1) throw Failure(Reason.DOMAIN) else fromRadians(Math.acos(x))
            "atan" -> fromRadians(Math.atan(x))
            "ln" -> if (x <= 0) throw Failure(Reason.DOMAIN) else Math.log(x)
            "log" -> if (x <= 0) throw Failure(Reason.DOMAIN) else Math.log10(x)
            "sqrt" -> root(x)
            "abs" -> abs(x)
            else -> throw Failure(Reason.SYNTAX)
        }

        /** sin 30° is exactly 0.5 on paper; remove binary rounding residue near whole ratios. */
        fun clean(value: Double): Double {
            val rounded = Math.round(value * 1e12) / 1e12
            return if (abs(value - rounded) < 1e-13) rounded else value
        }

        fun factorial(value: Double): Double {
            if (value < 0 || value != floor(value)) throw Failure(Reason.DOMAIN)
            if (value > 170) throw Failure(Reason.OVERFLOW)
            var result = 1.0
            for (n in 2..value.toInt()) result *= n
            return result
        }
    }

    /** Up to ten significant digits, without trailing zeros or exponent noise for ordinary magnitudes. */
    fun format(value: Double): String {
        if (value == 0.0) return "0"
        val magnitude = abs(value)
        if (magnitude >= 1e12 || magnitude < 1e-9) return String.format(Locale.ROOT, "%.9E", value).replace(Regex("\\.?0+E"), "E")
        return BigDecimal(value).round(MathContext(10)).stripTrailingZeros().toPlainString()
    }

    /** The simplest fraction within 1e-9 of [value] with a denominator up to [maxDenominator], if any. */
    fun fraction(value: Double, maxDenominator: Long = 1000): Pair<Long, Long>? {
        if (!value.isFinite() || abs(value) > 1e9 || value == floor(value)) return null
        var h0 = 0L; var h1 = 1L; var k0 = 1L; var k1 = 0L
        var x = abs(value)
        repeat(40) {
            val a = floor(x).toLong()
            val h2 = a * h1 + h0; val k2 = a * k1 + k0
            if (k2 > maxDenominator) return null
            h0 = h1; h1 = h2; k0 = k1; k1 = k2
            if (abs(abs(value) - h1.toDouble() / k1) < 1e-9) return (if (value < 0) -h1 else h1) to k1
            val rest = x - a
            if (rest < 1e-12) return null
            x = 1 / rest
        }
        return null
    }
}
