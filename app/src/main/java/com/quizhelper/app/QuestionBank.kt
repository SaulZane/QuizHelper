package com.quizhelper.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

object QuestionBank {
    var questions: List<Question> = emptyList()
    private var loaded = false

    /** Question type used by the OCR-text hint flow to filter / weight candidates. */
    enum class QuestionType { TF, MULTI, SINGLE }

    fun load(context: Context) {
        if (loaded) return
        try {
            val input = context.assets.open("questions.json")
            val reader = InputStreamReader(input, "UTF-8")
            val type = object : TypeToken<List<Question>>() {}.type
            questions = Gson().fromJson(reader, type)
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        loaded = true
    }

    /**
     * Classify a question by inspecting its options and answer.
     * - TF when there are exactly 2 options forming the pair 正确/错误 (or 对/错).
     * - MULTI when the answer is 2+ characters (e.g. "ABD").
     * - SINGLE otherwise.
     */
    @JvmStatic
    fun classifyQuestion(q: Question): QuestionType {
        val values = q.options.values.map { it.trim() }.toSet()
        val isTF = q.options.size == 2 && (
            (values.contains("正确") && values.contains("错误")) ||
            (values.contains("对") && values.contains("错"))
        )
        if (isTF) return QuestionType.TF
        if (q.answer.length >= 2) return QuestionType.MULTI
        return QuestionType.SINGLE
    }

    /**
     * Infer the question type from raw OCR text. Returns null if no clear signal.
     *
     * TF is detected by an option letter followed (possibly across whitespace /
     * separator chars) by "正确" or "错误", e.g. "A、正确", "A.正确", "B 错误".
     * In this dataset, "正确"/"错误" never appear as option text outside TF
     * questions, so a single positive match is treated as a strong TF signal.
     *
     * MULTI is detected by phrases like "正确的有" / "错误的有" / "下列哪些" — these
     * only appear in multi-choice stems in this dataset.
     */
    @JvmStatic
    fun detectTypeHint(ocr: String): QuestionType? {
        // Boundary class avoids in-word matches like "结果A正确"; permits start-of-text,
        // whitespace, Chinese end punctuation, and bracket-style option openers.
        val tfTrue = Regex("""(?:^|[\n\r\s。？！(（])[A-D]\s*[、.．)）:：]?\s*正\s*确""")
        val tfFalse = Regex("""(?:^|[\n\r\s。？！(（])[A-D]\s*[、.．)）:：]?\s*错\s*误""")
        if (tfTrue.containsMatchIn(ocr) || tfFalse.containsMatchIn(ocr)) return QuestionType.TF

        val multiKeywords = listOf("正确的有", "错误的有", "下列哪些", "下面哪些", "包括哪些", "下列各项中")
        if (multiKeywords.any { ocr.contains(it) }) return QuestionType.MULTI

        return null
    }

    fun findBestMatch(extractedText: String): Pair<String, String> {
        if (questions.isEmpty()) return Pair("No questions loaded", "")

        val hint = detectTypeHint(extractedText)
        val questionOnly = extractQuestionText(extractedText)
        val clean = normalize(questionOnly)

        // When TF is detected, restrict the candidate pool. TF stems are short and
        // would otherwise lose to a coincidentally overlapping single/multi question
        // via the contains() shortcut in computeSimilarity (returns 0.9). Detection
        // from the option pattern is essentially deterministic for this dataset.
        val candidates = if (hint == QuestionType.TF)
            questions.filter { classifyQuestion(it) == QuestionType.TF }
        else
            questions

        var best: Question? = null
        var bestScore = 0.0

        fun scoreAgainst(pool: List<Question>, src: String, applyHintWeight: Boolean) {
            for (q in pool) {
                val cleanQ = normalize(q.text)
                var score = computeSimilarity(src, cleanQ)
                // Slight type-match weighting; mainly helps MULTI vs SINGLE since TF
                // is already filtered above.
                if (applyHintWeight && hint != null && classifyQuestion(q) == hint) {
                    score *= 1.05
                }
                if (score > bestScore) {
                    bestScore = score
                    best = q
                }
            }
        }

        scoreAgainst(candidates, clean, applyHintWeight = true)

        // TF stems are shorter, so OCR noise has bigger relative impact → lower threshold.
        val threshold = if (hint == QuestionType.TF) 0.25 else 0.3

        // Fallback 1: question-stem extraction may have stripped too much; retry with full text.
        if (bestScore < threshold) {
            val cleanFull = normalize(extractedText)
            scoreAgainst(candidates, cleanFull, applyHintWeight = true)
        }

        // Fallback 2: TF restriction produced no good match → search the non-TF pool too,
        // without the hint weight (since the TF assumption may have been wrong).
        if (bestScore < threshold && hint == QuestionType.TF) {
            val nonTf = questions.filter { classifyQuestion(it) != QuestionType.TF }
            scoreAgainst(nonTf, clean, applyHintWeight = false)
        }

        val matched = best
        if (matched != null && bestScore > threshold) {
            val optText = matched.options[matched.answer] ?: matched.answer
            return Pair(
                "Q${matched.number}: ${matched.text.take(50)}...",
                "Answer: ${matched.answer}. $optText"
            )
        }
        return Pair("No match found", "Try again (score: ${"%.2f".format(bestScore)})")
    }

    /**
     * Extract the question stem from OCR text by removing option markers (A/B/C/D)
     * and their content. Options typically start with letter + separator patterns.
     */
    private fun extractQuestionText(ocrText: String): String {
        var text = ocrText.trim()

        // Strip leading question number like "1." "1、" "1）" etc.
        val numPattern = Regex("""^\d+[、.．)）:：\s]+""")
        text = numPattern.replace(text, "").trim()

        // Try multiple patterns to find where options start (from most specific to least)
        val optionPatterns = listOf(
            // Option on its own line: "\nA、" "\nA." "\nA）"
            Regex("""\n\s*[A-D][、.．)）:：]"""),
            // Option after Chinese end punctuation: "。A、" "？A."
            Regex("""[。？！]\s*[A-D][、.．)）:：]"""),
            // Option after multiple spaces on same line
            Regex("""\s{3,}[A-D][、.．)）:：]"""),
            // Sequence of options: "A、xxx B、xxx" pattern
            Regex("""[A-D][、.．)）:：]\s*\S{2,30}\s*[B-D][、.．)）:：]"""),
        )
        for (pattern in optionPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                val before = text.substring(0, match.range.first).trim()
                if (before.length >= 4) return before
            }
        }
        return text
    }

    @JvmStatic
    fun normalize(s: String): String {
        return s.replace(Regex("[\\s\\p{Punct}]"), "").lowercase()
    }

    /**
     * Compute similarity between two strings using a combination of
     * LCS ratio and bigram Jaccard, taking the max of both.
     * LCS is more robust for OCR errors; bigram Jaccard handles
     * character-level reordering.
     */
    @JvmStatic
    fun computeSimilarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0

        // Strongest: one string is fully contained in the other
        if (a.contains(b)) return 0.95
        if (b.contains(a)) return 0.9

        val lcsScore = lcsRatio(a, b)
        val bigramScore = bigramJaccard(a, b)
        return maxOf(lcsScore, bigramScore)
    }

    /**
     * Longest Common Subsequence ratio: 2 * LCS / (lenA + lenB).
     * Returns 1.0 for identical strings, approaches 0 for unrelated strings.
     */
    @JvmStatic
    fun lcsRatio(a: String, b: String): Double {
        val m = a.length
        val n = b.length
        // Skip if strings differ too much in length (performance optimization)
        if (m > 5 * n || n > 5 * m) return 0.0

        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1] + 1
                           else maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
        val lcsLen = dp[m][n]
        return 2.0 * lcsLen / (m + n)
    }

    /**
     * Bigram Jaccard similarity: |bigrams(a) ∩ bigrams(b)| / |bigrams(a) ∪ bigrams(b)|
     * Good at matching even with some character reordering.
     */
    @JvmStatic
    fun bigramJaccard(a: String, b: String): Double {
        val bigramsA = a.windowed(2, 1).toSet()
        val bigramsB = b.windowed(2, 1).toSet()
        val intersection = bigramsA.intersect(bigramsB).size
        val union = bigramsA.union(bigramsB).size
        return if (union == 0) 0.0 else intersection.toDouble() / union.toDouble()
    }
}

data class Question(
    val number: Int,
    val text: String,
    val options: Map<String, String>,
    val answer: String
)
