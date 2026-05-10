package com.ai.companion.ai

import android.content.Context
import android.content.SharedPreferences

/**
 * 用户偏好管理类
 *
 * 管理AI伴侣的所有用户可配置偏好，包括关注关键词、忽略关键词、
 * 用户名、学习关键词、响应风格等。
 */
class UserPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "ai_companion_prefs"

        private const val KEY_FOCUS_KEYWORDS = "focus_keywords"
        private const val KEY_IGNORE_KEYWORDS = "ignore_keywords"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_LEARNED_KEYWORDS = "learned_keywords"
        private const val KEY_RESPONSE_STYLE = "response_style"
        private const val KEY_AUTO_RESPOND_QUESTIONS = "auto_respond_questions"
        private const val KEY_AUTO_RESPOND_FACTS = "auto_respond_facts"
        private const val KEY_IMPORTANCE_THRESHOLD = "importance_threshold"

        private const val DEFAULT_IGNORE_KEYWORDS = "嗯,啊,哦,哈哈,嘿嘿,好的,行,对,是"
        private const val DEFAULT_RESPONSE_STYLE = "concise"
        private const val DEFAULT_AUTO_RESPOND_QUESTIONS = true
        private const val DEFAULT_AUTO_RESPOND_FACTS = false
        private const val DEFAULT_IMPORTANCE_THRESHOLD = 30
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ==================== 关注关键词 ====================

    /**
     * 获取用户关注的关键词列表
     * @return 关注关键词列表，默认为空列表
     */
    fun getFocusKeywords(): List<String> {
        val raw = prefs.getString(KEY_FOCUS_KEYWORDS, "") ?: ""
        return parseKeywordList(raw)
    }

    /**
     * 设置用户关注的关键词列表
     * @param keywords 要设置的关键词列表
     */
    fun setFocusKeywords(keywords: List<String>) {
        prefs.edit().putString(KEY_FOCUS_KEYWORDS, keywords.joinToString(",")).apply()
    }

    // ==================== 忽略关键词 ====================

    /**
     * 获取忽略关键词列表
     * @return 忽略关键词列表，包含默认的语气词
     */
    fun getIgnoreKeywords(): List<String> {
        val raw = prefs.getString(KEY_IGNORE_KEYWORDS, DEFAULT_IGNORE_KEYWORDS) ?: DEFAULT_IGNORE_KEYWORDS
        return parseKeywordList(raw)
    }

    /**
     * 设置忽略关键词列表
     * @param keywords 要设置的忽略关键词列表
     */
    fun setIgnoreKeywords(keywords: List<String>) {
        prefs.edit().putString(KEY_IGNORE_KEYWORDS, keywords.joinToString(",")).apply()
    }

    // ==================== 用户名 ====================

    /**
     * 获取用户名
     * @return 用户名，默认为空字符串
     */
    fun getUserName(): String {
        return prefs.getString(KEY_USER_NAME, "") ?: ""
    }

    /**
     * 设置用户名
     * @param name 用户名
     */
    fun setUserName(name: String) {
        prefs.edit().putString(KEY_USER_NAME, name).apply()
    }

    // ==================== 学习关键词 ====================

    /**
     * 获取通过用户交互学习到的关键词集合
     * @return 学习关键词集合，默认为空集合
     */
    fun getLearnedKeywords(): Set<String> {
        val raw = prefs.getString(KEY_LEARNED_KEYWORDS, "") ?: ""
        return parseKeywordList(raw).toSet()
    }

    /**
     * 添加一个学习关键词
     * 如果关键词已存在则不会重复添加
     * @param keyword 要添加的关键词
     */
    fun addLearnedKeyword(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        val current = getLearnedKeywords().toMutableSet()
        current.add(trimmed)
        prefs.edit().putString(KEY_LEARNED_KEYWORDS, current.joinToString(",")).apply()
    }

    /**
     * 批量添加学习关键词
     * @param keywords 要添加的关键词列表
     */
    fun addLearnedKeywords(keywords: Collection<String>) {
        val current = getLearnedKeywords().toMutableSet()
        keywords.forEach { keyword ->
            val trimmed = keyword.trim()
            if (trimmed.isNotEmpty()) {
                current.add(trimmed)
            }
        }
        prefs.edit().putString(KEY_LEARNED_KEYWORDS, current.joinToString(",")).apply()
    }

    // ==================== 响应风格 ====================

    /**
     * 获取AI响应风格
     * @return 响应风格: "concise"(简洁), "detailed"(详细), "friendly"(友好)
     */
    fun getResponseStyle(): String {
        return prefs.getString(KEY_RESPONSE_STYLE, DEFAULT_RESPONSE_STYLE) ?: DEFAULT_RESPONSE_STYLE
    }

    /**
     * 设置AI响应风格
     * @param style 响应风格: "concise", "detailed", "friendly"
     */
    fun setResponseStyle(style: String) {
        prefs.edit().putString(KEY_RESPONSE_STYLE, style).apply()
    }

    // ==================== 自动响应设置 ====================

    /**
     * 是否自动响应问题
     * @return true表示自动响应问题，默认为true
     */
    fun isAutoRespondQuestions(): Boolean {
        return prefs.getBoolean(KEY_AUTO_RESPOND_QUESTIONS, DEFAULT_AUTO_RESPOND_QUESTIONS)
    }

    /**
     * 是否自动响应事实性内容
     * @return true表示自动响应事实性内容，默认为false
     */
    fun isAutoRespondFacts(): Boolean {
        return prefs.getBoolean(KEY_AUTO_RESPOND_FACTS, DEFAULT_AUTO_RESPOND_FACTS)
    }

    /**
     * 设置是否自动响应问题
     * @param autoRespond 是否自动响应
     */
    fun setAutoRespondQuestions(autoRespond: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RESPOND_QUESTIONS, autoRespond).apply()
    }

    /**
     * 设置是否自动响应事实性内容
     * @param autoRespond 是否自动响应
     */
    fun setAutoRespondFacts(autoRespond: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RESPOND_FACTS, autoRespond).apply()
    }

    // ==================== 重要性阈值 ====================

    /**
     * 获取重要性判定阈值
     * 分析分数达到此阈值时认为内容重要
     * @return 重要性阈值，默认为30
     */
    fun getImportanceThreshold(): Int {
        return prefs.getInt(KEY_IMPORTANCE_THRESHOLD, DEFAULT_IMPORTANCE_THRESHOLD)
    }

    /**
     * 设置重要性判定阈值
     * @param threshold 阈值 (0-100)
     */
    fun setImportanceThreshold(threshold: Int) {
        val clamped = threshold.coerceIn(0, 100)
        prefs.edit().putInt(KEY_IMPORTANCE_THRESHOLD, clamped).apply()
    }

    // ==================== 工具方法 ====================

    /**
     * 解析逗号分隔的关键词字符串为列表
     * 自动过滤空白项和去除前后空格
     */
    private fun parseKeywordList(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * 清除所有偏好设置，恢复默认值
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    /**
     * 重置为默认值
     */
    fun resetToDefaults() {
        prefs.edit().apply {
            putString(KEY_FOCUS_KEYWORDS, "")
            putString(KEY_IGNORE_KEYWORDS, DEFAULT_IGNORE_KEYWORDS)
            putString(KEY_USER_NAME, "")
            putString(KEY_LEARNED_KEYWORDS, "")
            putString(KEY_RESPONSE_STYLE, DEFAULT_RESPONSE_STYLE)
            putBoolean(KEY_AUTO_RESPOND_QUESTIONS, DEFAULT_AUTO_RESPOND_QUESTIONS)
            putBoolean(KEY_AUTO_RESPOND_FACTS, DEFAULT_AUTO_RESPOND_FACTS)
            putInt(KEY_IMPORTANCE_THRESHOLD, DEFAULT_IMPORTANCE_THRESHOLD)
            apply()
        }
    }
}
