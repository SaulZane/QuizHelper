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

        val clean = normalize(extractedText)
        var best: Question? = null
        var bestScore = 0.0

        for (q in questions) {
            val cleanQ = normalize(q.text)
            val score = partialSimilarity(clean, cleanQ)
            if (score > bestScore) {
                bestScore = score
                best = q
            }
        }

        if (best != null && bestScore > 0.4) {
            val optText = best.options[best.answer] ?: best.answer
            return Pair(
                "Q${best.number}: ${best.text.take(50)}...",
                "Answer: ${best.answer}. $optText"
            )
        }
        return Pair("No match found", "Try again with a clearer capture")
    }

    @JvmStatic
    fun normalize(s: String): String {
        // Remove spaces, punctuation, lowercase; keep only Chinese chars and ASCII letters/digits
        return s.replace(Regex("[\\s\\p{Punct}]"), "").lowercase()
    }

    @JvmStatic
    fun partialSimilarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        // Check substring match
        if (a.contains(b) || b.contains(a)) return 0.9
        // Jaccard on character bigrams
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
