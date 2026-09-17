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
        // 优先使用题库中显式声明的题型
        when (q.type) {
            "TF" -> return QuestionType.TF
            "MULTI" -> return QuestionType.MULTI
            "SINGLE" -> return QuestionType.SINGLE
        }
        val values = q.options.values.map { it.trim() }.toSet()
        val isTF = q.options.size == 2 && (
            (values.contains("正确") && values.contains("错误")) ||
            (values.contains("对") && values.contains("错")) ||
            (values.contains("是") && values.contains("否"))
        )
        if (isTF) return QuestionType.TF
        if (q.answer.length >= 2) return QuestionType.MULTI
        return QuestionType.SINGLE
    }

    /** 判断题同义词组：屏幕上可能显示 是/否，也可能是 正确/错误、对/错、√/× */
    private val TF_TRUE = setOf("是", "正确", "对", "√", "对的", "正确的")
    private val TF_FALSE = setOf("否", "错误", "错", "×", "x", "不对", "错误的", "不正确", "不正确的")

    private fun tfGroupOf(s: String): Int {
        val t = normalize(s)
        if (TF_TRUE.any { normalize(it) == t }) return 1
        if (TF_FALSE.any { normalize(it) == t }) return -1
        return 0
    }

    /**
     * 从 OCR 文本中解析出屏幕上实际显示的选项：字母 -> 选项文本。
     * 支持 A、 A. A) A: 等多种分隔符，以及跨行排版。
     */
    @JvmStatic
    fun parseOcrOptions(ocr: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        // 找出所有 "字母+分隔符" 的位置，相邻两个之间的内容即为该选项文本
        // 分隔符可能是标点(A、 A. A) A:)，也可能只是换行或空格(ML Kit 常把选项号与内容分行输出)
        val marker = Regex("""(?:^|[\n\r])\s*[(（\[【]?([A-Ha-h])[)）\]】]?\s*(?:[、.．。)）:：,，]|\s)\s*""")
        val hits = marker.findAll(ocr).toList()
        for ((i, m) in hits.withIndex()) {
            val letter = m.groupValues[1].uppercase()
            if (result.containsKey(letter)) continue
            val start = m.range.last + 1
            var end = if (i + 1 < hits.size) hits[i + 1].range.first else ocr.length
            if (start >= end) continue

            var content = ocr.substring(start, end)
            // 长选项在真实界面上会被折成多行(实拍: 危险驾驶罪题 D 选项 30 字折成两行)，
            // 旧实现只取首行会使选项只剩半截，与题库全文相似度跌破 0.45 而丢失该选项字母
            // (ABD 变 AB)。改为: 取到下一个选项标记前的全部行，去掉尾部界面噪声行后顺序拼接。
            val lines = content.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            while (lines.isNotEmpty() && isUiNoise(lines.last())) lines.removeAt(lines.size - 1)
            content = lines.joinToString("")

            content = content
                .trim()
                .trim('、', '.', '．', '。', ')', '）', ':', '：', ',', '，', ';', '；')
                .trim()
            if (content.isNotEmpty() && !isUiNoise(content)) result[letter] = content
        }
        return result
    }

    /** 常见答题界面的按钮/状态文案，不应被当作选项内容 */
    private val UI_NOISE = listOf(
        "提交", "下一题", "上一题", "确定", "取消", "答案", "解析", "收藏",
        "本题", "已选", "未选", "继续", "交卷", "返回", "查看",
        "答题卡", "标记", "作答", "输入", "倒计时"
    )

    private fun isUiNoise(s: String): Boolean {
        // 实拍界面底部导航常带箭头 glyph: "← 上一题"、"下一题 →"
        val t = s.trim().trim('←', '→', '‹', '›', '<', '>', '－', '-')
        return t.length <= 5 && UI_NOISE.any { t == it || t.startsWith(it) }
    }

    /**
     * 选项专用相似度。与题干匹配不同，选项之间常存在互为子串的强干扰
     * （如「机动车」vs「非机动车」、「国家安全观」vs「总体国家安全观」），
     * 因此完全相等优先，子串不再短路为高分，并按长度差惩罚。
     */
    /** 选项里的阿拉伯数字串，如 "1000米" -> ["1000"] */
    private fun digitKey(s: String): List<String> = Regex("""\d+""").findAll(s).map { it.value }.toList()

    @JvmStatic
    fun optionSimilarity(answerText: String, screenText: String): Double {
        val a = normalize(answerText)
        val b = normalize(screenText)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val lenRatio = minOf(a.length, b.length).toDouble() / maxOf(a.length, b.length)
        val base = maxOf(lcsRatio(a, b), bigramJaccard(a, b))
        // 长度差越大越可能是“子串型”干扰项，按比例压低得分
        var score = base * lenRatio
        // 数字往往就是选项的全部语义（1000米/100米、30日/300日），而 bigram/LCS 会把它们判成
        // 高度相似（"1000米"vs"100米"=0.80，越过0.75的选项吻合阈值），使 Q106 在 Q278 的屏幕上
        // 拿到满分吻合度。数字串不一致时降权 ×0.2（0.80→0.16）。
        val ka = digitKey(a)
        val kb = digitKey(b)
        if (ka.isNotEmpty() && kb.isNotEmpty() && ka != kb) score *= 0.2
        return score
    }

    /**
     * 核心：用「答案文本」反查当前屏幕上对应的选项字母。
     * 选项顺序被打乱也能得到正确字母；无法解析屏幕选项时返回 null 由调用方降级。
     */
    @JvmStatic
    fun resolveLettersByText(q: Question, ocr: String): String? {
        val answerTexts = if (q.answerTexts.isNotEmpty()) q.answerTexts
                          else q.answer.mapNotNull { q.options[it.toString()] }
        if (answerTexts.isEmpty()) return null

        val screen = parseOcrOptions(ocr)
        if (screen.isEmpty()) return null

        val isTF = classifyQuestion(q) == QuestionType.TF
        val letters = sortedSetOf<String>()

        val used = HashSet<String>()
        for (ansText in answerTexts) {
            var bestLetter: String? = null
            var bestScore = 0.0
            for ((letter, screenText) in screen) {
                if (letter in used) continue   // 一个屏幕选项只能被认领一次
                val score = if (isTF) {
                    // 判断题按语义分组匹配，兼容 是/否 与 正确/错误 混用
                    val g1 = tfGroupOf(ansText)
                    val g2 = tfGroupOf(screenText)
                    if (g1 != 0 && g1 == g2) 1.0 else optionSimilarity(ansText, screenText)
                } else {
                    optionSimilarity(ansText, screenText)
                }
                if (score > bestScore) { bestScore = score; bestLetter = letter }
            }
            // 阈值偏低以容忍 OCR 误字，但过低说明该选项没出现在屏幕上
            if (bestLetter != null && bestScore >= 0.45) {
                used.add(bestLetter)
                letters.add(bestLetter)
            } else {
                Logger.i("QuestionBank", "resolveLetters: 未匹配到选项 '$ansText' (best=$bestScore)")
            }
        }

        if (letters.isEmpty()) return null
        if (letters.size != answerTexts.size) {
            Logger.i("QuestionBank", "resolveLetters: 部分匹配 ${letters.size}/${answerTexts.size}")
        }
        return letters.joinToString("")
    }

    /**
     * 生成最终展示的答案：只返回选项号（如 "A"、"ABD"），不含选项内容与解析。
     * 字母按屏幕上实际显示的顺序解析，选项乱序时也能给出正确的选项号；
     * 解析不出屏幕选项时退回题库原始答案。
     */
    @JvmStatic
    fun formatAnswer(q: Question, ocr: String): String {
        val resolved = resolveLettersByText(q, ocr)
        if (resolved != null) {
            if (resolved != q.answer) {
                Logger.i("QuestionBank", "选项乱序: 题库答案=${q.answer} -> 屏幕答案=$resolved")
            }
            return resolved
        }
        Logger.i("QuestionBank", "未能解析屏幕选项，回退题库答案=${q.answer}")
        return q.answer
    }

    /**
     * 识别答题界面开头的题型标签，如 [单选] [多选] [判断]、【单选题】、(多选) 等。
     * 这是最可靠的题型信号，优先级高于任何关键词猜测。
     */
    @JvmStatic
    fun detectTypeLabel(ocr: String): QuestionType? {
        // 只看开头部分，避免题干或选项里出现这些字造成误判
        val head = ocr.take(60)
        // 真实界面的题型标签只有两种形态：
        // ① 带括号包裹，如 [单选] 【多选题】 (判断)；
        // ② 独立成行或行首紧跟题号，如 "多选题" 单独一行、"单选题 1、…"。
        // 旧写法不要求括号也不要求行首，选项文字里出现「判断（Orient）」「判断肇事车辆…」
        // 等词会把多选题误判成判断题，候选池被缩小到只剩判断题，导致本题永远匹配不上。
        val bracketed = Regex("""[\[【(（]\s*(单选|多选|不定项|判断|是非)\s*(?:题)?\s*[\]】)）]""")
            .find(head)?.groupValues?.get(1)
        val lineStart = Regex("""(?m)^\s*(单选|多选|不定项|判断|是非)\s*(?:题)?\s*(?:$|\d|[、.．)）])""")
            .find(head)?.groupValues?.get(1)
        val label = bracketed ?: lineStart ?: return null
        return when (label) {
            "单选" -> QuestionType.SINGLE
            "多选", "不定项" -> QuestionType.MULTI
            "判断", "是非" -> QuestionType.TF
            else -> null
        }
    }

    /** 去掉开头的题型标签与题号，避免它们参与题干相似度计算 */
    @JvmStatic
    fun stripTypeLabel(ocr: String): String {
        var s = ocr
        s = Regex("""^\s*[\[\【(（]?\s*(?:单选|多选|不定项|判断|是非)\s*(?:题)?\s*[\]\】)）]?\s*""")
            .replace(s, "")
        return s.trimStart()
    }

    fun detectTypeHint(ocr: String): QuestionType? {
        // 界面上的题型标签最可信，优先采用
        detectTypeLabel(ocr)?.let { return it }

        val tfTrue = Regex("""(?:^|[\n\r\s。？！(（])[A-D]\s*[、.．)）:：]?\s*(?:正\s*确|是)(?:\s|$|[\n\r])""")
        val tfFalse = Regex("""(?:^|[\n\r\s。？！(（])[A-D]\s*[、.．)）:：]?\s*(?:错\s*误|否)(?:\s|$|[\n\r])""")
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

        // 界面题型标签(如 [单选])是硬信号，据此只在同类型题目中匹配
        val label = detectTypeLabel(extractedText)
        val hint = detectTypeHint(extractedText)
        Logger.i("QuestionBank", "detectTypeLabel: $label, detectTypeHint: $hint")

        val body = stripTypeLabel(extractedText)
        val questionOnly = extractQuestionText(body)
        val clean = normalize(questionOnly)
        Logger.i("QuestionBank", "extracted questionOnly len=${questionOnly.length}, clean len=${clean.length}")

        val candidates = when {
            label != null -> questions.filter { classifyQuestion(it) == label }
            hint == QuestionType.TF -> questions.filter { classifyQuestion(it) == QuestionType.TF }
            else -> questions
        }
        Logger.i("QuestionBank", "candidate pool size: ${candidates.size} (label=$label)")

        var best: Question? = null
        var bestScore = 0.0

        // 屏幕上实际显示的选项，用于区分题干高度相似、仅选项不同的题目
        val screenOptions = parseOcrOptions(body).values.map { normalize(it) }.filter { it.isNotEmpty() }

        /** 题库选项与屏幕选项的吻合程度，返回 0..1 */
        fun optionMatchRatio(q: Question): Double {
            if (screenOptions.isEmpty() || q.options.isEmpty()) return 0.0
            var hit = 0
            for (opt in q.options.values) {
                val n = normalize(opt)
                if (n.isEmpty()) continue
                if (screenOptions.any { optionSimilarity(n, it) >= 0.75 }) hit++
            }
            return hit.toDouble() / q.options.size
        }

        fun scoreAgainst(pool: List<Question>, src: String, applyHintWeight: Boolean, label: String) {
            for (q in pool) {
                val cleanQ = normalize(q.text)
                var score = computeSimilarity(src, cleanQ)
                if (applyHintWeight && hint != null && classifyQuestion(q) == hint) {
                    score *= 1.05
                }
                // 题干分数相近时，用选项吻合度拉开差距（题干重复题的关键）
                score *= (1.0 + 0.35 * optionMatchRatio(q))
                if (score > bestScore) {
                    bestScore = score
                    best = q
                    Logger.i("QuestionBank", "New best: Q${q.number} type=${classifyQuestion(q)} score=$score from [$label]")
                }
            }
        }

        scoreAgainst(candidates, clean, applyHintWeight = true, label = "clean")

        // 匹配阈值：真实界面下库内题的匹配分最低约 1.28（题干精确命中 + 选项吻合加权），
        // 而「不在题库里」的题误匹配最高仅约 0.58，两者之间存在巨大空隙。
        // 旧阈值 0.25/0.3 落在误匹配一侧，会把库外题自信地匹配成某道错题并给出错误答案
        // （实测拍照回放：「什么是签证」→误匹配Q267 显示C、「2025年…根本保证是坚持以人民
        //   为中心」→误匹配Q337）。取 0.8 后库内题全部保留（并为 OCR 噪声留足衰减余量），
        // 库外题一律拒答，不再显示错误答案。
        val threshold = 0.8
        Logger.i("QuestionBank", "After clean: bestScore=$bestScore, threshold=$threshold")

        if (bestScore < threshold) {
            val cleanFull = normalize(body)
            scoreAgainst(candidates, cleanFull, applyHintWeight = true, label = "full")
            Logger.i("QuestionBank", "After full: bestScore=$bestScore")
        }

        // 界面已明确题型时不跨类型兜底，避免单选题匹配到多选题
        if (bestScore < threshold && label == null && hint == QuestionType.TF) {
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
        // 完全相同必须是最高分：否则「仅一两字之差」的近似题干（如"一级防护…"/"三级防护…"）
        // 经 LCS 可得 0.97，反超包含关系给出的 0.95，导致 Q278 被匹配成 Q106 而答错。
        if (a == b) return 1.0
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
    val answer: String,
    val answerTexts: List<String> = emptyList(),
    val type: String? = null
)
