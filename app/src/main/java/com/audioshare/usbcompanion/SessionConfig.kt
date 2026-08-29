package com.audioshare.usbcompanion

import android.content.Intent

data class SessionConfig(
    val socketName: String,
    val token: ByteArray,
    val generation: Long,
) {
    companion object {
        const val EXTRA_SOCKET_NAME = "socket_name"
        const val EXTRA_TOKEN_HEX = "token_hex"
        const val EXTRA_GENERATION = "generation"

        private val socketPattern = Regex("^as_1_[A-Za-z0-9_-]{8,32}$")
        private val tokenPattern = Regex("^[0-9a-fA-F]{64}$")

        fun fromIntent(intent: Intent?): SessionConfig? {
            if (intent == null) return null
            val socketName = intent.getStringExtra(EXTRA_SOCKET_NAME) ?: return null
            val tokenHex = intent.getStringExtra(EXTRA_TOKEN_HEX) ?: return null
            val generation = intent.getLongExtra(EXTRA_GENERATION, -1L)
            return parse(socketName, tokenHex, generation)
        }

        internal fun parse(socketName: String, tokenHex: String, generation: Long): SessionConfig? {
            if (!socketPattern.matches(socketName)) return null
            if (!tokenPattern.matches(tokenHex)) return null
            if (generation < 0) return null
            return SessionConfig(socketName, decodeHex(tokenHex), generation)
        }

        internal fun decodeHex(value: String): ByteArray {
            require(value.length % 2 == 0)
            return ByteArray(value.length / 2) { index ->
                value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is SessionConfig &&
            socketName == other.socketName &&
            token.contentEquals(other.token) &&
            generation == other.generation

    override fun hashCode(): Int =
        31 * (31 * socketName.hashCode() + token.contentHashCode()) + generation.hashCode()
}
