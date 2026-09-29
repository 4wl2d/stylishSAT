package com.tomilov.stylishsat.domain

import com.tomilov.stylishsat.domain.Calculator.Angle
import com.tomilov.stylishsat.domain.Calculator.Reason
import com.tomilov.stylishsat.domain.Calculator.Result
import org.junit.Assert.*
import org.junit.Test

class WorkingToolsTest {
    private fun value(expression: String, angle: Angle = Angle.DEG, ans: Double = 0.0): Double =
        (Calculator.evaluate(expression, angle, ans) as Result.Value).value

    private fun error(expression: String, angle: Angle = Angle.DEG): Reason = (Calculator.evaluate(expression, angle) as Result.Error).reason

    @Test fun arithmeticFollowsConventionalPrecedence() {
        assertEquals(14.0, value("2+3×4"), 0.0)
        assertEquals(20.0, value("(2+3)*4"), 0.0)
        assertEquals(-4.0, value("-2^2"), 0.0)
        assertEquals(512.0, value("2^3^2"), 0.0)
        assertEquals(0.5, value("2^-1"), 0.0)
        assertEquals(2.0, value("8÷4"), 0.0)
        assertEquals(-1.0, value("3−4"), 0.0)
        assertEquals(0.3, value("0.1+0.2"), 1e-15)
        assertEquals(1500.0, value("1.5E3"), 0.0)
    }

    @Test fun implicitMultiplicationConstantsAndAnswerRecall() {
        assertEquals(2 * Math.PI, value("2π"), 1e-12)
        assertEquals(12.0, value("3(4)"), 0.0)
        assertEquals(6.0, value("(2)(3)"), 0.0)
        assertEquals(1.0, value("2sin(30)"), 1e-12)
        assertEquals(Math.E * Math.E, value("e^2"), 1e-12)
        assertEquals(10.0, value("ans+3", ans = 7.0), 0.0)
        assertEquals(120.0, value("5!"), 0.0)
        assertEquals(0.25, value("25%"), 0.0)
        assertEquals(7.0, value("√4+5"), 0.0)
        assertEquals(3.0, value("√(4+5)"), 0.0)
    }

    @Test fun trigonometryRespectsTheAngleMode() {
        assertEquals(0.5, value("sin(30)"), 0.0)
        assertEquals(0.5, value("cos(60)"), 0.0)
        assertEquals(1.0, value("tan(45)"), 1e-12)
        assertEquals(30.0, value("asin(0.5)"), 1e-9)
        assertEquals(1.0, value("sin(pi/2)", Angle.RAD), 1e-12)
        assertEquals(Math.PI / 4, value("atan(1)", Angle.RAD), 1e-12)
        assertEquals(2.0, value("log(100)"), 1e-12)
        assertEquals(1.0, value("ln(e)"), 1e-12)
        assertEquals(5.0, value("abs(-5)"), 0.0)
    }

    @Test fun errorsAreNamedInsteadOfShowingNonsense() {
        assertEquals(Reason.DIVIDE_BY_ZERO, error("1/0"))
        assertEquals(Reason.DOMAIN, error("√-4"))
        assertEquals(Reason.DOMAIN, error("ln(0)"))
        assertEquals(Reason.DOMAIN, error("asin(2)"))
        assertEquals(Reason.DOMAIN, error("tan(90)"))
        assertEquals(Reason.DOMAIN, error("2.5!"))
        assertEquals(Reason.DOMAIN, error("(-8)^(1/3)"))
        assertEquals(Reason.OVERFLOW, error("171!"))
        listOf("", "2+", "(1+2", "1+2)", "sin 30", "x+1", "3..4", "*2").forEach { assertEquals(it, Reason.SYNTAX, error(it)) }
    }

    @Test fun resultsAreShownReadablyWithAnExactFractionWhenOneExists() {
        assertEquals("0.3333333333", Calculator.format(1.0 / 3))
        assertEquals("0.3", Calculator.format(0.1 + 0.2))
        assertEquals("1500", Calculator.format(1500.0))
        assertEquals("-2.5", Calculator.format(-2.5))
        assertEquals(1L to 3L, Calculator.fraction(1.0 / 3))
        assertEquals(-3L to 4L, Calculator.fraction(-0.75))
        assertEquals(22L to 7L, Calculator.fraction(22.0 / 7))
        assertNull(Calculator.fraction(Math.PI))
        assertNull(Calculator.fraction(4.0))
    }

    @Test fun sentencesCoverTheWholePassageForHighlighting() {
        val text = "A. Plants cannot walk. Wind carries seeds!\n\nB. \"Ants help,\" she said. Dr. Olsen agreed."
        val ranges = Passages.sentences(text)
        assertEquals(text, ranges.joinToString("") { text.substring(it) })
        assertTrue(ranges.any { text.substring(it) == "Plants cannot walk. " })
        assertTrue(ranges.any { text.substring(it).startsWith("Wind carries seeds!") })
        assertTrue(ranges.all { !it.isEmpty() })
        assertEquals(emptyList<IntRange>(), Passages.sentences(""))
    }
}
