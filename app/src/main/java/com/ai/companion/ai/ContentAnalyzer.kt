package com.ai.companion.ai

import android.content.Context
import android.util.Log
import java.util.regex.Pattern

/**
 * 内容分析器
 *
 * 分析转录文本，判断其重要性是否足以触发AI响应。
 * 支持中英文问题检测、情感分析、关键词匹配和用户偏好集成。
 */
class ContentAnalyzer(context: Context) {

    companion object {
        private const val TAG = "ContentAnalyzer"

        // ==================== 中文疑问词 ====================
        private val CHINESE_QUESTION_ENDINGS = charArrayOf('？')
        private val CHINESE_QUESTION_WORDS = listOf(
            "什么", "为什么", "怎么", "如何", "哪", "谁",
            "多少", "几个", "是不是", "有没有", "能否", "可以吗",
            "吗", "呢", "吧"
        )

        // ==================== 英文疑问词 ====================
        private val ENGLISH_QUESTION_WORDS = listOf(
            "what", "why", "how", "when", "where", "who",
            "which", "can", "could", "would", "should", "is", "are",
            "do", "does", "did"
        )

        // ==================== 情感关键词 ====================
        private val EMOTIONAL_WORDS = listOf(
            // 中文情感词
            "帮助", "帮帮我", "困惑", "不懂", "不理解", "生气", "愤怒",
            "开心", "高兴", "难过", "伤心", "害怕", "担心", "焦虑",
            "紧张", "郁闷", "烦躁", "崩溃", "绝望", "感动", "惊喜",
            "失望", "无奈", "尴尬", "后悔", "孤独", "寂寞",
            // 英文情感词
            "help", "confused", "angry", "happy", "sad", "scared",
            "worried", "anxious", "frustrated", "disappointed", "lonely"
        )

        // ==================== 数字/事实模式 ====================
        private val NUMBER_PATTERNS = listOf(
            // 数字模式: 纯数字、百分比、金额等
            Pattern.compile("\\d+\\.?\\d*%?"),
            // 日期模式
            Pattern.compile("\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}"),
            // 时间模式
            Pattern.compile("\\d{1,2}[::时]\\d{2}"),
            // 金额模式
            Pattern.compile("[\\d.]+[万亿元美元欧元英镑日元港币]"),
        )

        // ==================== 中文停用词（用于提取关键词时过滤） ====================
        private val STOP_WORDS = setOf(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一",
            "一个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着",
            "没有", "看", "好", "自己", "这", "他", "她", "它", "们", "那", "些",
            "什么", "怎么", "为什么", "如何", "这个", "那个", "可以", "能", "会",
            "把", "被", "从", "对", "让", "用", "比", "但", "而", "或", "如果",
            "因为", "所以", "但是", "然后", "虽然", "已经", "还", "又", "再",
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "shall", "can", "need", "dare", "ought",
            "to", "of", "in", "for", "on", "with", "at", "by", "from", "as",
            "into", "through", "during", "before", "after", "above", "below",
            "and", "but", "or", "nor", "not", "so", "yet", "both", "either",
            "i", "me", "my", "we", "our", "you", "your", "he", "him", "his",
            "she", "her", "it", "its", "they", "them", "their", "this", "that"
        )

        // ==================== "我"相关短语模式 ====================
        private val SELF_REFERENTIAL_PATTERNS = listOf(
            Pattern.compile("我[觉得认为想感觉希望需要想要]"),
            Pattern.compile("帮我"),
            Pattern.compile("告诉我"),
            Pattern.compile("教我"),
            Pattern.compile("我的"),
            Pattern.compile("I (think|feel|want|need|hope|wish|believe|guess)"),
            Pattern.compile("(help|teach|tell) me"),
            Pattern.compile("my (name|problem|question|issue)"),
        )

        // ==================== 中文分词用的简单模式（2-4字名词提取） ====================
        private val CHINESE_NOUN_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]{2,6}")
    }

    /**
     * 分析结果数据类
     *
     * @property isImportant 内容是否重要（分数 >= 阈值）
     * @property score 重要性分数 (0-100)
     * @property reason 判定原因描述
     * @property suggestedAction 建议的AI动作类型
     */
    data class AnalysisResult(
        val isImportant: Boolean,
        val score: Int,
        val reason: String,
        val suggestedAction: String
    )

    private val userPreferences: UserPreferences = UserPreferences(context)

    /**
     * 分析文本内容，判断其重要性
     *
     * @param text 要分析的转录文本
     * @return 分析结果，包含重要性判定、分数、原因和建议动作
     */
    fun analyze(text: String): AnalysisResult {
        if (text.isBlank()) {
            return AnalysisResult(
                isImportant = false,
                score = 0,
                reason = "文本为空",
                suggestedAction = "ignore"
            )
        }

        val reasons = mutableListOf<String>()
        var score = 0

        // 1. 检查是否为忽略内容
        val ignoreKeywords = userPreferences.getIgnoreKeywords()
        val ignoredWords = ignoreKeywords.filter { keyword ->
            text.contains(keyword, ignoreCase = true)
        }
        for (ignoredWord in ignoredWords) {
            score -= 50
            reasons.add("包含忽略关键词: \"$ignoredWord\"")
        }

        // 如果分数已经很低且全是忽略词，直接返回
        if (score < -40 && reasons.size == ignoredWords.size) {
            return AnalysisResult(
                isImportant = false,
                score = score.coerceAtLeast(0),
                reason = reasons.joinToString("; "),
                suggestedAction = "ignore"
            )
        }

        // 2. 问题检测 (+40)
        val isQuestion = detectQuestion(text)
        if (isQuestion) {
            score += 40
            reasons.add("检测到问题")
        }

        // 3. 用户名或"我"相关短语检测 (+20)
        val containsSelfReference = detectSelfReference(text)
        if (containsSelfReference) {
            score += 20
            val userName = userPreferences.getUserName()
            if (userName.isNotEmpty() && text.contains(userName)) {
                reasons.add("包含用户名: \"$userName\"")
            } else {
                reasons.add("包含自我指涉短语")
            }
        }

        // 4. 数字/数据/事实检测 (+15)
        val containsNumbers = detectNumbersOrFacts(text)
        if (containsNumbers) {
            score += 15
            reasons.add("包含数字或事实数据")
        }

        // 5. 情感关键词检测 (+25)
        val emotionalWords = detectEmotionalWords(text)
        if (emotionalWords.isNotEmpty()) {
            score += 25
            reasons.add("包含情感词汇: ${emotionalWords.joinToString(", ")}")
        }

        // 6. 关注关键词检测 (+30 each)
        val focusKeywords = userPreferences.getFocusKeywords()
        val matchedFocusKeywords = focusKeywords.filter { keyword ->
            text.contains(keyword, ignoreCase = true)
        }
        for (keyword in matchedFocusKeywords) {
            score += 30
            reasons.add("包含关注关键词: \"$keyword\"")
        }

        // 7. 学习关键词检测 (+20 each)
        val learnedKeywords = userPreferences.getLearnedKeywords()
        val matchedLearnedKeywords = learnedKeywords.filter { keyword ->
            text.contains(keyword, ignoreCase = true)
        }
        for (keyword in matchedLearnedKeywords) {
            score += 20
            reasons.add("包含学习关键词: \"$keyword\"")
        }

        // 8. 句子长度加分 (+5)
        if (text.length > 10) {
            score += 5
        }

        // 限制分数范围
        score = score.coerceIn(0, 100)

        // 判断是否重要
        val threshold = userPreferences.getImportanceThreshold()
        val isImportant = score >= threshold

        // 确定建议动作
        val suggestedAction = determineSuggestedAction(
            isQuestion = isQuestion,
            emotionalWords = emotionalWords,
            containsNumbers = containsNumbers,
            matchedFocusKeywords = matchedFocusKeywords,
            isImportant = isImportant
        )

        // 构建原因描述
        val reasonText = if (reasons.isEmpty()) {
            "未检测到显著特征"
        } else {
            reasons.joinToString("; ")
        }

        Log.d(TAG, "分析结果: score=$score, important=$isImportant, reason=$reasonText, action=$suggestedAction")

        return AnalysisResult(
            isImportant = isImportant,
            score = score,
            reason = reasonText,
            suggestedAction = suggestedAction
        )
    }

    /**
     * 记录用户交互
     *
     * 当用户点击浮动窗口建议时调用，从文本中提取关键名词/主题，
     * 添加到学习关键词集合中，以便未来分析时提高类似内容的优先级。
     *
     * @param text 用户交互时的文本内容
     */
    fun recordUserInteraction(text: String) {
        if (text.isBlank()) return

        val keywords = extractKeywords(text)
        if (keywords.isEmpty()) return

        userPreferences.addLearnedKeywords(keywords)

        Log.d(TAG, "记录用户交互，提取关键词: $keywords")
    }

    // ==================== 私有方法 ====================

    /**
     * 检测文本中是否包含问题
     * 支持中文和英文问题检测
     */
    private fun detectQuestion(text: String): Boolean {
        val trimmed = text.trim()

        // 中文：以问号结尾
        if (CHINESE_QUESTION_ENDINGS.any { trimmed.endsWith(it) }) {
            return true
        }

        // 英文：以问号结尾
        if (trimmed.endsWith("?")) {
            return true
        }

        // 中文疑问词检测
        val lowerText = trimmed.lowercase()
        for (word in CHINESE_QUESTION_WORDS) {
            if (lowerText.contains(word)) {
                return true
            }
        }

        // 英文疑问词检测（需在句首或独立出现）
        for (word in ENGLISH_QUESTION_WORDS) {
            // 检查是否为独立单词（前后有空格或在句首）
            val regex = Regex("\\b$word\\b", RegexOption.IGNORE_CASE)
            if (regex.containsMatchIn(lowerText)) {
                // 额外检查：对于常见词(is/are/do等)，需要确保在句首
                val commonVerbWords = setOf("is", "are", "do", "does", "did")
                if (word in commonVerbWords) {
                    if (lowerText.startsWith(word) || lowerText.startsWith("is ") ||
                        lowerText.startsWith("are ") || lowerText.startsWith("do ") ||
                        lowerText.startsWith("does ") || lowerText.startsWith("did ")
                    ) {
                        return true
                    }
                } else {
                    return true
                }
            }
        }

        return false
    }

    /**
     * 检测自我指涉（用户名或"我"相关短语）
     */
    private fun detectSelfReference(text: String): Boolean {
        val userName = userPreferences.getUserName()
        if (userName.isNotEmpty() && text.contains(userName, ignoreCase = true)) {
            return true
        }

        for (pattern in SELF_REFERENTIAL_PATTERNS) {
            if (pattern.matcher(text).find()) {
                return true
            }
        }

        return false
    }

    /**
     * 检测文本中是否包含数字或事实性数据
     */
    private fun detectNumbersOrFacts(text: String): Boolean {
        for (pattern in NUMBER_PATTERNS) {
            if (pattern.matcher(text).find()) {
                return true
            }
        }
        return false
    }

    /**
     * 检测文本中的情感关键词
     * @return 匹配到的情感词列表
     */
    private fun detectEmotionalWords(text: String): List<String> {
        val lowerText = text.lowercase()
        return EMOTIONAL_WORDS.filter { word ->
            lowerText.contains(word.lowercase())
        }
    }

    /**
     * 从文本中提取关键词
     * 使用简单的基于规则的方法提取中文名词和英文单词
     */
    private fun extractKeywords(text: String): List<String> {
        val keywords = mutableListOf<String>()

        // 提取中文词组（2-6字）
        val chineseMatcher = CHINESE_NOUN_PATTERN.matcher(text)
        while (chineseMatcher.find()) {
            val word = chineseMatcher.group()
            if (word !in STOP_WORDS && word.length >= 2) {
                keywords.add(word)
            }
        }

        // 提取英文单词（长度 >= 3）
        val englishWords = text.split(Regex("[\\s\\p{Punct}]+"))
            .filter { word ->
                word.length >= 3 && word.all { it.isLetter() } && word.lowercase() !in STOP_WORDS
            }
            .map { it.lowercase() }
        keywords.addAll(englishWords)

        // 去重并限制数量
        return keywords.distinct().take(10)
    }

    /**
     * 根据分析特征确定建议的AI动作
     */
    private fun determineSuggestedAction(
        isQuestion: Boolean,
        emotionalWords: List<String>,
        containsNumbers: Boolean,
        matchedFocusKeywords: List<String>,
        isImportant: Boolean
    ): String {
        if (!isImportant) {
            return "ignore"
        }

        // 情感支持优先级最高
        if (emotionalWords.isNotEmpty()) {
            return "emotional_support"
        }

        // 问题回答次之
        if (isQuestion) {
            return "answer_question"
        }

        // 关注关键词相关
        if (matchedFocusKeywords.isNotEmpty()) {
            return "provide_info"
        }

        // 事实性内容
        if (containsNumbers) {
            return "provide_info"
        }

        // 默认提供信息
        return "provide_info"
    }
}
