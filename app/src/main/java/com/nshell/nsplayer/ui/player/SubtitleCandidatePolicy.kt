package com.nshell.nsplayer.ui.player

import java.util.Locale

internal enum class SubtitleNameMatchKind {
    EXACT,
    TAGGED
}

internal data class SubtitleNameMatch(
    val kind: SubtitleNameMatchKind,
    val languageTag: String?,
    val normalizedVideoBase: String,
    val normalizedFileName: String
)

internal object SubtitleCandidatePolicy {
    internal val supportedExtensions = setOf("srt", "vtt", "ass", "ssa", "sub")
    private val languageAliases = mapOf(
        "eng" to "en",
        "english" to "en",
        "kor" to "ko",
        "korean" to "ko",
        "kr" to "ko",
        "jpn" to "ja",
        "japanese" to "ja",
        "jp" to "ja"
    )
    private val isoLanguages = Locale.getISOLanguages()
        .map { it.lowercase(Locale.ROOT) }
        .toSet()

    fun match(videoFileName: String, subtitleFileName: String): SubtitleNameMatch? {
        val videoBase = videoFileName
            .substringBeforeLast('.', videoFileName)
            .lowercase(Locale.ROOT)
        if (videoBase.isEmpty()) {
            return null
        }
        return matchVideoBase(videoBase, subtitleFileName)
    }

    fun matchesSameVideo(
        referenceMatch: SubtitleNameMatch,
        subtitleFileName: String
    ): Boolean {
        return matchVideoBase(referenceMatch.normalizedVideoBase, subtitleFileName) != null
    }

    private fun matchVideoBase(
        normalizedVideoBase: String,
        subtitleFileName: String
    ): SubtitleNameMatch? {
        val normalizedFileName = subtitleFileName.lowercase(Locale.ROOT)
        val extensionSeparator = normalizedFileName.lastIndexOf('.')
        if (extensionSeparator <= 0 || extensionSeparator == normalizedFileName.lastIndex) {
            return null
        }
        val extension = normalizedFileName.substring(extensionSeparator + 1)
        if (extension !in supportedExtensions) {
            return null
        }

        val subtitleBase = normalizedFileName.substring(0, extensionSeparator)
        if (subtitleBase == normalizedVideoBase) {
            return SubtitleNameMatch(
                kind = SubtitleNameMatchKind.EXACT,
                languageTag = null,
                normalizedVideoBase = normalizedVideoBase,
                normalizedFileName = normalizedFileName
            )
        }

        val taggedPrefix = "$normalizedVideoBase."
        if (!subtitleBase.startsWith(taggedPrefix)) {
            return null
        }
        val suffix = subtitleBase.substring(taggedPrefix.length)
        return SubtitleNameMatch(
            kind = SubtitleNameMatchKind.TAGGED,
            languageTag = detectLanguageTag(suffix),
            normalizedVideoBase = normalizedVideoBase,
            normalizedFileName = normalizedFileName
        )
    }

    fun resolvePreferredLanguages(
        selectedLanguage: String?,
        effectiveLanguageTags: List<String>
    ): List<String> {
        val source = if (selectedLanguage.isNullOrBlank()) {
            effectiveLanguageTags
        } else {
            listOf(selectedLanguage)
        }
        return source.mapNotNull(::normalizeLanguageTag).distinct()
    }

    fun <T> preferredCandidate(
        candidates: List<T>,
        preferredLanguages: List<String>,
        matchOf: (T) -> SubtitleNameMatch
    ): T? {
        return orderedCandidates(candidates, preferredLanguages, matchOf).firstOrNull()
    }

    fun <T> orderedCandidates(
        candidates: List<T>,
        preferredLanguages: List<String>,
        matchOf: (T) -> SubtitleNameMatch
    ): List<T> {
        val normalizedLanguages = preferredLanguages
            .mapNotNull(::normalizeLanguageTag)
            .distinct()
        return candidates.sortedWith(
            compareBy<T> { candidate ->
                priority(matchOf(candidate), normalizedLanguages)
            }.thenBy { candidate ->
                matchOf(candidate).normalizedFileName
            }
        )
    }

    internal fun detectLanguageTag(taggedSuffix: String): String? {
        return taggedSuffix
            .split('.')
            .asSequence()
            .mapNotNull(::normalizeFileLanguageToken)
            .firstOrNull()
    }

    internal fun normalizeLanguageTag(languageTag: String): String? {
        val cleaned = languageTag.trim().replace('_', '-')
        if (cleaned.isEmpty()) {
            return null
        }
        val parts = cleaned.split('-').toMutableList()
        val primary = parts.first().lowercase(Locale.ROOT)
        parts[0] = languageAliases[primary] ?: primary
        val locale = Locale.forLanguageTag(parts.joinToString("-"))
        if (locale.language.isBlank() || locale.language == "und") {
            return null
        }
        return locale.toLanguageTag().lowercase(Locale.ROOT)
    }

    private fun normalizeFileLanguageToken(token: String): String? {
        val normalized = normalizeLanguageTag(token) ?: return null
        val primary = primaryLanguage(normalized)
        return normalized.takeIf {
            primary in isoLanguages || token.lowercase(Locale.ROOT) in languageAliases
        }
    }

    private fun priority(
        match: SubtitleNameMatch,
        preferredLanguages: List<String>
    ): Int {
        val detectedLanguage = match.languageTag
        if (detectedLanguage != null) {
            preferredLanguages.forEachIndexed { index, preferredLanguage ->
                if (detectedLanguage == preferredLanguage) {
                    return index * 2
                }
                if (primaryLanguage(detectedLanguage) == primaryLanguage(preferredLanguage)) {
                    return index * 2 + 1
                }
            }
        }

        val fallbackStart = preferredLanguages.size * 2
        return when {
            match.kind == SubtitleNameMatchKind.EXACT -> fallbackStart
            detectedLanguage == null -> fallbackStart + 1
            else -> fallbackStart + 2
        }
    }

    private fun primaryLanguage(languageTag: String): String {
        return languageTag.substringBefore('-')
    }
}
