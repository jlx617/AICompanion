package com.ai.companion.ai

class InterventionEngine {

    companion object {
        // 中文疑问词
        private val QUESTION_WORDS_CN = listOf(
            "什么", "为什么", "怎么", "如何", "什么时候", "哪里", "谁", "哪个",
            "你能", "你可以", "你会", "是不是", "有没有", "对吗", "对吗",
            "请问", "咨询", "疑问", "问题", "？", "?"
        )
        // 英文疑问词（保留兼容）
        private val QUESTION_WORDS = listOf(
            "what", "why", "how", "when", "where", "who", "which",
            "can you", "could you", "do you", "is it", "are there"
        )
        
        // 中文犹豫词
        private val HESITATION_WORDS_CN = listOf(
            "那个", "这个", "嗯", "啊", "呃", "就是", "那个啥", "怎么说呢",
            "好像", "可能", "大概", "应该", "也许"
        )
        // 英文犹豫词
        private val HESITATION_WORDS = listOf(
            "um", "uh", "like", "you know", "i mean", "sort of",
            "kind of", "well", "actually", "basically"
        )
        
        // 中文困惑词
        private val CONFUSION_WORDS_CN = listOf(
            "不懂", "不明白", "不清楚", "不知道", "困惑", "迷茫", "什么意思",
            "没听懂", "不理解", "疑惑", "疑问", "难", "复杂"
        )
        // 英文困惑词
        private val CONFUSION_WORDS = listOf(
            "confused", "not sure", "don't understand", "unclear",
            "what do you mean", "i don't get", "doesn't make sense",
            "lost", "puzzled", "uncertain"
        )
        
        // 中文求助词
        private val HELP_KEYWORDS_CN = listOf(
            "帮助", "帮忙", "建议", "推荐", "意见", "看法", "指导", "教教我",
            "怎么办", "怎么做", "帮帮我", "求助", "指点"
        )
        // 英文求助词
        private val HELP_KEYWORDS = listOf(
            "help", "assist", "support", "advice", "suggest",
            "recommend", "opinion", "think about", "guide"
        )
    }

    data class InterventionScore(
        val shouldIntervene: Boolean,
        val score: Double,
        val reason: String
    )

    fun evaluate(text: String): InterventionScore {
        val lowerText = text.lowercase()
        var score = 0.0
        val reasons = mutableListOf<String>()

        // 检测疑问（中文+英文）
        val questionMatchesCN = QUESTION_WORDS_CN.count { text.contains(it) }
        val questionMatchesEN = QUESTION_WORDS.count { lowerText.contains(it) }
        val totalQuestions = questionMatchesCN + questionMatchesEN
        if (totalQuestions > 0) {
            score += totalQuestions * 0.3
            reasons.add("检测到提问")
        }

        // 检测犹豫（中文+英文）
        val hesitationMatchesCN = HESITATION_WORDS_CN.count { text.contains(it) }
        val hesitationMatchesEN = HESITATION_WORDS.count { lowerText.contains(it) }
        val totalHesitations = hesitationMatchesCN + hesitationMatchesEN
        if (totalHesitations >= 1) {
            score += totalHesitations * 0.15
            reasons.add("检测到犹豫")
        }

        // 检测困惑（中文+英文）
        val confusionMatchesCN = CONFUSION_WORDS_CN.count { text.contains(it) }
        val confusionMatchesEN = CONFUSION_WORDS.count { lowerText.contains(it) }
        val totalConfusions = confusionMatchesCN + confusionMatchesEN
        if (totalConfusions > 0) {
            score += totalConfusions * 0.4
            reasons.add("检测到困惑")
        }

        // 检测求助（中文+英文）
        val helpMatchesCN = HELP_KEYWORDS_CN.count { text.contains(it) }
        val helpMatchesEN = HELP_KEYWORDS.count { lowerText.contains(it) }
        val totalHelps = helpMatchesCN + helpMatchesEN
        if (totalHelps > 0) {
            score += totalHelps * 0.35
            reasons.add("检测到求助")
        }

        // 降低阈值，让 AI 更容易介入
        val shouldIntervene = score >= 0.3
        val reason = if (reasons.isNotEmpty()) reasons.joinToString(", ") else "无需干预"

        return InterventionScore(
            shouldIntervene = shouldIntervene,
            score = score,
            reason = reason
        )
    }
}
