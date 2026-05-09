package com.ai.companion.ai

import com.ai.companion.scene.SceneType

class SceneDetector {

    companion object {
        private val MEETING_KEYWORDS = listOf(
            "meeting", "agenda", "action item", "minutes", "attendees",
            "schedule", "deadline", "project", "task", "assign",
            "quarterly", "review", "standup", "sprint", "budget"
        )
        private val CLASS_KEYWORDS = listOf(
            "professor", "lecture", "homework", "assignment", "exam",
            "textbook", "chapter", "student", "course", "syllabus",
            "grade", "quiz", "semester", "tuition", "campus"
        )
        private val SHOPPING_KEYWORDS = listOf(
            "price", "discount", "buy", "sell", "store", "shop",
            "product", "brand", "order", "delivery", "cart",
            "coupon", "sale", "receipt", "refund", "warranty"
        )
        private val MUSEUM_KEYWORDS = listOf(
            "exhibit", "painting", "sculpture", "gallery", "artist",
            "artifact", "collection", "display", "curator", "museum",
            "history", "ancient", "portrait", "landscape", "masterpiece"
        )
    }

    private var currentScene: SceneType = SceneType.UNKNOWN
    private val contextBuffer: MutableList<String> = mutableListOf()
    private val maxContextSize = 20

    fun detectScene(transcript: String): SceneType {
        contextBuffer.add(transcript)
        if (contextBuffer.size > maxContextSize) {
            contextBuffer.removeAt(0)
        }

        val fullContext = contextBuffer.joinToString(" ").lowercase()

        val scores = mapOf(
            SceneType.MEETING to countKeywordMatches(fullContext, MEETING_KEYWORDS),
            SceneType.CLASS to countKeywordMatches(fullContext, CLASS_KEYWORDS),
            SceneType.SHOPPING to countKeywordMatches(fullContext, SHOPPING_KEYWORDS),
            SceneType.MUSEUM to countKeywordMatches(fullContext, MUSEUM_KEYWORDS)
        )

        val maxEntry = scores.maxByOrNull { it.value }
        val threshold = 2

        val detected = if (maxEntry != null && maxEntry.value >= threshold) {
            maxEntry.key
        } else {
            SceneType.DAILY_CHAT
        }

        currentScene = detected
        return detected
    }

    fun getCurrentScene(): SceneType = currentScene

    fun reset() {
        contextBuffer.clear()
        currentScene = SceneType.UNKNOWN
    }

    private fun countKeywordMatches(text: String, keywords: List<String>): Int {
        return keywords.count { keyword -> text.contains(keyword) }
    }
}
