package com.sysu.go.utils

object Sanitizer {
    private val DANGEROUS_CHARS = Regex("[<>&\"|'\\\\;/]")

    fun sanitize(input: String?, maxLen: Int = 50): String {
        if (input.isNullOrBlank()) return ""
        return input
            .replace(DANGEROUS_CHARS, "")
            .replace(Regex("\\s+"), " ") // Keep spaces for multi-part names like "Dela Cruz"
            .trim()
            .take(maxLen)
            .split(" ")                                              // Split into words
            .joinToString(" ") { it.lowercase().replaceFirstChar { it.uppercase() } } // Title case each word
    }

    fun sanitizeMI(input: String?): String {
        if (input.isNullOrBlank()) return ""
        return input.trim().uppercase().take(1) + "." // returns "P." with dot
    }

    fun sanitizePlatePrefix(input: String?): String {
        return input
            ?.uppercase()
            ?.filter { it in 'A'..'Z' }
            ?.take(3)
            ?: ""
    }

    fun sanitizePlateSuffix(input: String?): String {
        return input?.filter { it.isDigit() }?.take(4) ?: ""
    }

    fun sanitizePhone(input: String?): String {
        if (input.isNullOrBlank()) return ""
        // Keep only digits and leading +
        return input.filter { it.isDigit() || it == '+' }.take(15)
    }

    fun sanitizeName(input: String?): String {
        return sanitize(input, 50)
    }
}