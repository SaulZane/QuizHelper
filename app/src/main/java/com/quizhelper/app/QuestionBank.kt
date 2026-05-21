package com.quizhelper.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

object QuestionBank {
    var questions: List<Question> = emptyList()
    private var loaded = false

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

    @JvmStatic
    fun detectTypeHint(ocr: String): QuestionType? {
        val tfTrue = Regex("""(?:^|[\n\r\s。？！(（])[A-D]\s*[、.．)）:：]?\s*正\s*确""")
        val tfFalse = Regex("""(?:^|[\n\r\s。？！(（])[A-D]\s*[、.．)）:：]?\s*错\s*误""")
        if (tfTrue.containsMatchIn(ocr) || tfFalse.containsMatchIn(ocr)) return QuestionType.TF

        val multiKeywords = listOf("正确的有", "错误的有", "下列哪些", "下面哪些", "包括哪些", "下列各项中")
        if (multiKeywords.any { ocr.contains(it) }) return QuestionType.MULTI

        return null
    }

    data class MatchResult(
        val question: Question?,
        val score: Double,
        val typeHint: QuestionType?,
        val matchedType: QuestionType?
    )

    fun findBestMatch(extractedText: String): MatchResult {
        if (questions.isEmpty()) {
            Logger.e("QuestionBank", "No questions loaded")
            return MatchResult(null, 0.0, null, null)
        }

        val hint = detectTypeHint(extractedText)
        Logger.i("QuestionBank", "detectTypeHint: $hint")

        val questionOnly = extractQuestionText(extractedText)
        val clean = normalize(questionOnly)
        Logger.i("QuestionBank", "extracted questionOnly len=${questionOnly.length}, clean len=${clean.length}")

        val candidates = if (hint == QuestionType.TF)
            questions.filter { classifyQuestion(it) == QuestionType.TF }
        else
            questions
        Logger.i("QuestionBank", "candidate pool size: ${candidates.size}")

        var best: Question? = null
        var bestScore = 0.0

        fun scoreAgainst(pool: List<Question>, src: String, applyHintWeight: Boolean, label: String) {
            for (q in pool) {
                val cleanQ = normalize(q.text)
                var score = computeSimilarity(src, cleanQ)
                if (applyHintWeight && hint != null && classifyQuestion(q) == hint) {
                    score *= 1.05
                }
                if (score > bestScore) {
                    bestScore = score
                    best = q
                    Logger.i("QuestionBank", "New best: Q${q.number} type=${classifyQuestion(q)} score=$score from [$label]")
                }
            }
        }

        scoreAgainst(candidates, clean, applyHintWeight = true, label = "clean")

        val threshold = if (hint == QuestionType.TF) 0.25 else 0.3
        Logger.i("QuestionBank", "After clean: bestScore=$bestScore, threshold=$threshold")

        if (bestScore < threshold) {
            val cleanFull = normalize(extractedText)
            scoreAgainst(candidates, cleanFull, applyHintWeight = true, label = "full")
            Logger.i("QuestionBank", "After full: bestScore=$bestScore")
        }

        if (bestScore < threshold && hint == QuestionType.TF) {
            val nonTf = questions.filter { classifyQuestion(it) != QuestionType.TF }
            scoreAgainst(nonTf, clean, applyHintWeight = false, label = "tf_fallback")
            Logger.i("QuestionBank", "After tf_fallback: bestScore=$bestScore")
        }

        val matched = best
        if (matched != null && bestScore > threshold) {
            val qType = classifyQuestion(matched)
            Logger.i("QuestionBank", "MATCH: Q${matched.number} type=$qType answer='${matched.answer}' score=${"%.0f".format(bestScore * 100)}%")
            return MatchResult(matched, bestScore, hint, qType)
        }
        Logger.i("QuestionBank", "NO MATCH: bestScore=$bestScore < threshold=$threshold")
        return MatchResult(null, bestScore, hint, null)
    }

    private fun extractQuestionText(ocrText: String): String {
        var text = ocrText.trim()

        val numPattern = Regex("""^\d+[、.．)）:：\s]+""")
        text = numPattern.replace(text, "").trim()

        val optionPatterns = listOf(
            Regex("""\n\s*[A-D][、.．)）:：]"""),
            Regex("""[。？！]\s*[A-D][、.．)）:：]"""),
            Regex("""\s{3,}[A-D][、.．)）:：]"""),
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

    @JvmStatic
    fun computeSimilarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a.contains(b)) return 0.95
        if (b.contains(a)) return 0.9
        val lcsScore = lcsRatio(a, b)
        val bigramScore = bigramJaccard(a, b)
        return maxOf(lcsScore, bigramScore)
    }

    @JvmStatic
    fun lcsRatio(a: String, b: String): Double {
        val m = a.length
        val n = b.length
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
