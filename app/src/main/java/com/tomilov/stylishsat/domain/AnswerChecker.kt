package com.tomilov.stylishsat.domain

import java.math.BigInteger
import java.text.Normalizer
import java.util.Locale

/** Closed-answer marking is entirely deterministic; model feedback cannot alter a key. */
object AnswerChecker {
    fun check(exercise: Exercise, answer: String): AnswerResult {
        val trimmed = answer.trim()
        if (trimmed.isBlank()) return result(AnswerStatus.INVALID, null, "Enter an answer first.", "Сначала введите ответ.", "EMPTY_ANSWER")
        if (exercise.type == ExerciseType.WRITING || exercise.type == ExerciseType.SPEAKING) {
            return result(
                AnswerStatus.NEEDS_REVIEW, null,
                "Saved for training feedback. This response does not change mastery or award an exam score.",
                "Ответ сохранён для тренировочного разбора. Он не меняет освоение навыка и не даёт экзаменационный балл.",
            )
        }
        if (exercise.wordLimit != null && wordCount(trimmed) > exercise.wordLimit) {
            return result(
                AnswerStatus.INCORRECT, false,
                "The answer exceeds the ${exercise.wordLimit}-word limit.",
                "Ответ превышает ограничение: ${exercise.wordLimit} слов.",
                "WORD_LIMIT",
            )
        }
        val matches = when (exercise.type) {
            ExerciseType.NUMERIC -> {
                val value = rational(trimmed) ?: return result(
                    AnswerStatus.INVALID, null,
                    "Enter a number, decimal, or fraction, such as 0.5 or 1/2.",
                    "Введите число, десятичную или обычную дробь, например 0.5 или 1/2.",
                    "NUMERIC_FORMAT",
                )
                exercise.acceptedAnswers.any { rational(it) == value }
            }
            else -> exercise.acceptedAnswers.any { normalize(it) == normalize(trimmed) }
        }
        return if (matches) result(AnswerStatus.CORRECT, true, "Correct. Compare your reasoning with the explanation.", "Верно. Сравните свой ход решения с объяснением.")
        else result(AnswerStatus.INCORRECT, false, "Not yet. Review the explanation and try a fresh question.", "Пока неверно. Разберите объяснение и попробуйте новое задание.", "KEY_MISMATCH")
    }

    /** Observable response constraints and author-supplied possibilities, never a guessed diagnosis. */
    fun reviewChecks(exercise: Exercise, errorType: String?): List<LocalizedText> = when (errorType) {
        "WORD_LIMIT" -> exercise.wordLimit?.let { listOf(LocalizedText(
            "Check the $it-word limit and any words already supplied around the gap.",
            "Проверьте лимит: $it слов, и слова, уже указанные рядом с пропуском.")) }.orEmpty()
        "NUMERIC_FORMAT" -> if (exercise.type == ExerciseType.NUMERIC) listOf(LocalizedText(
            "Use a number, decimal or integer fraction; do not enter a calculation or unit.",
            "Введите число, десятичную или обычную дробь; не вводите вычисление или единицу измерения.")) else emptyList()
        "KEY_MISMATCH", "NEEDS_RULE_REVIEW" -> exercise.typicalErrors
        "SKIPPED", "EMPTY_ANSWER" -> listOf(LocalizedText("Read the rule and worked example before trying again.", "Перед новой попыткой прочитайте правило и разобранный пример."))
        else -> emptyList()
    }

    fun wordCount(answer: String): Int = answer.trim().split(Regex("\\s+")).count { it.isNotBlank() }

    internal fun normalize(answer: String): String = Normalizer.normalize(answer, Normalizer.Form.NFKC)
        .replace('\u2019', '\'').replace('\u2018', '\'')
        .trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    /** Exact rational arithmetic avoids floating point tolerances accepting wrong keys. */
    internal fun rational(answer: String): Pair<BigInteger, BigInteger>? {
        if (answer.length > 128) return null
        val clean = answer.trim().replace('\u2212', '-')
        val number = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")
        fun decimal(text: String): Pair<BigInteger, BigInteger>? {
            if (!number.matches(text)) return null
            val value = text.toBigDecimalOrNull() ?: return null
            val scale = value.scale()
            return value.unscaledValue() to BigInteger.TEN.pow(scale)
        }
        val fractionParts = clean.split('/')
        val (numerator, denominator) = when (fractionParts.size) {
            1 -> decimal(clean) ?: return null
            2 -> {
                // Fractions have integer numerator and denominator; mixed numbers and expressions are not answers.
                if (fractionParts.any { !Regex("[+-]?[0-9]+").matches(it.trim()) }) return null
                (fractionParts[0].trim().toBigIntegerOrNull() ?: return null) to
                    (fractionParts[1].trim().toBigIntegerOrNull() ?: return null)
            }
            else -> return null
        }
        if (denominator == BigInteger.ZERO) return null
        val sign = if (denominator.signum() < 0) BigInteger.valueOf(-1) else BigInteger.ONE
        val divisor = numerator.gcd(denominator)
        return (numerator / divisor * sign) to (denominator / divisor * sign)
    }

    private fun result(status: AnswerStatus, correct: Boolean?, en: String, ru: String, errorType: String? = null) =
        AnswerResult(status, correct, LocalizedText(en, ru), errorType)
}
