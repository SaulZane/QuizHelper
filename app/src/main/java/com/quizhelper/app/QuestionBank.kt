package com.quizhelper.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

object QuestionBank {
    var questions: List<Question> = emptyList()
    private var loaded = false

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

    fun findBestMatch(extractedText: String): Pair<String, String> {
        if (questions.isEmpty()) return Pair("No questions loaded", "")

        val questionOnly = extractQuestionText(extractedText)
        val clean = normalize(questionOnly)

        var best: Question? = null
        var bestScore = 0.0

        for (q in questions) {
            val cleanQ = normalize(q.text)
            val score = computeSimilarity(clean, cleanQ)
            if (score > bestScore) {
                bestScore = score
                best = q
            }
        }

        // Fallback: try with full OCR text if question extraction left too little
        if (bestScore < 0.3) {
            val cleanFull = normalize(extractedText)
            for (q in questions) {
                val cleanQ = normalize(q.text)
                val score = computeSimilarity(cleanFull, cleanQ)
                if (score > bestScore) {
                    bestScore = score
                    best = q
                }
            }
        }

        if (best != null && bestScore > 0.3) {
            val optText = best.options[best.answer] ?: best.answer
            return Pair(
                "Q${best.number}: ${best.text.take(50)}...",
                "Answer: ${best.answer}. $optText"
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
