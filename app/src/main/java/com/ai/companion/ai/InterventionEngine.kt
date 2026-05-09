package com.ai.companion.ai

class InterventionEngine {

    companion object {
        private val QUESTION_WORDS = listOf(
            "what", "why", "how", "when", "where", "who", "which",
            "can you", "could you", "do you", "is it", "are there"
        )
        private val HESITATION_WORDS = listOf(
            "um", "uh", "like", "you know", "i mean", "sort of",
            "kind of", "well", "actually", "basically"
        )
        private val CONFUSION_WORDS = listOf(
            "confused", "not sure", "don't understand", "unclear",
            "what do you mean", "i don't get", "doesn't make sense",
            "lost", "puzzled", "uncertain"
        )
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

        // Check for questions
        val questionMatches = QUESTION_WORDS.count { lowerText.contains(it) }
        if (questionMatches > 0) {
            score += questionMatches * 0.3
            reasons.add("Question detected")
        }

        // Check for hesitation
        val hesitationMatches = HESITATION_WORDS.count { lowerText.contains(it) }
        if (hesitationMatches >= 2) {
            score += hesitationMatches * 0.15
            reasons.add("Hesitation detected")
        }

        // Check for confusion
        val confusionMatches = CONFUSION_WORDS.count { lowerText.contains(it) }
        if (confusionMatches > 0) {
            score += confusionMatches * 0.4
            reasons.add("Confusion detected")
        }

        // Check for help requests
        val helpMatches = HELP_KEYWORDS.count { lowerText.contains(it) }
        if (helpMatches > 0) {
            score += helpMatches * 0.35
            reasons.add("Help request detected")
        }

        val shouldIntervene = score >= 0.5
        val reason = if (reasons.isNotEmpty()) reasons.joinToString(", ") else "No intervention needed"

        return InterventionScore(
            shouldIntervene = shouldIntervene,
            score = score,
            reason = reason
        )
    }
}
