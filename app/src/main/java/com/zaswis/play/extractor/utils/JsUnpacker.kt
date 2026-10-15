package com.zaswis.play.extractor.utils

/**
 * Dean Edwards' JavaScript Packer Unpacker
 * Used across Cloudstream and streaming extractors to decode obfuscated scripts
 * Example format: eval(function(p,a,c,k,e,d){...}('payload', 36, 12, 'a|b|c'.split('|')))
 */
object JsUnpacker {

    private val PACKER_REGEX = Regex(
        """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[dr]?\s*\)\s*\{[\s\S]*?\}\s*\(\s*['"]([\s\S]*?)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"]([\s\S]*?)['"]\.split\(['"]\|['"]\)""",
        RegexOption.MULTILINE
    )

    fun detect(script: String): Boolean {
        return PACKER_REGEX.containsMatchIn(script)
    }

    fun unpack(packedScript: String): String? {
        val match = PACKER_REGEX.find(packedScript) ?: return null
        return try {
            val payload = match.groupValues[1]
            val radix = match.groupValues[2].toIntOrNull() ?: 10
            val count = match.groupValues[3].toIntOrNull() ?: 0
            val keywords = match.groupValues[4].split("|")

            val unbaser = Unbaser(radix)
            val tokenRegex = Regex("""\b\w+\b""")

            tokenRegex.replace(payload) { tokenMatch ->
                val word = tokenMatch.value
                val index = unbaser.unbase(word)
                if (index in keywords.indices && keywords[index].isNotEmpty()) {
                    keywords[index]
                } else {
                    word
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private class Unbaser(private val radix: Int) {
        private val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        fun unbase(str: String): Int {
            if (radix <= 36) {
                return str.toIntOrNull(radix) ?: -1
            }
            var result = 0
            for (char in str) {
                val index = alphabet.indexOf(char)
                if (index == -1 || index >= radix) return -1
                result = result * radix + index
            }
            return result
        }
    }
}
