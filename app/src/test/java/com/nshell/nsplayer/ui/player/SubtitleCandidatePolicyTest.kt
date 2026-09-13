package com.nshell.nsplayer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleCandidatePolicyTest {
    @Test
    fun koreanCandidateWinsRegardlessOfMediaStoreOrder() {
        val forward = select(
            names = listOf("movie.ja.srt", "movie.ko.srt"),
            selectedLanguage = "ko"
        )
        val reversed = select(
            names = listOf("movie.ko.srt", "movie.ja.srt"),
            selectedLanguage = "ko"
        )

        assertEquals("movie.ko.srt", forward)
        assertEquals("movie.ko.srt", reversed)
    }

    @Test
    fun systemKoreanLocaleSelectsKoreanCandidate() {
        val selected = select(
            names = listOf("movie.ja.srt", "movie.ko.srt"),
            selectedLanguage = null,
            effectiveLanguageTags = listOf("ko-KR", "en-US")
        )

        assertEquals("movie.ko.srt", selected)
    }

    @Test
    fun explicitKoreanOverridesJapaneseSystemLocale() {
        val selected = select(
            names = listOf("movie.ja.srt", "movie.ko.srt"),
            selectedLanguage = "ko",
            effectiveLanguageTags = listOf("ja-JP")
        )

        assertEquals("movie.ko.srt", selected)
    }

    @Test
    fun regionalKoreanCandidateWinsOverPrimaryLanguageCandidate() {
        val selected = select(
            names = listOf("movie.ko.srt", "movie.ko_KR.srt"),
            selectedLanguage = null,
            effectiveLanguageTags = listOf("ko-KR")
        )

        assertEquals("movie.ko_KR.srt", selected)
    }

    @Test
    fun commonThreeLetterAliasesAreNormalized() {
        val korean = requireNotNull(
            SubtitleCandidatePolicy.match("movie.mp4", "movie.kor.srt")
        )
        val japanese = requireNotNull(
            SubtitleCandidatePolicy.match("movie.mp4", "movie.jpn.srt")
        )
        val english = requireNotNull(
            SubtitleCandidatePolicy.match("movie.mp4", "movie.eng.srt")
        )

        assertEquals("ko", korean.languageTag)
        assertEquals("ja", japanese.languageTag)
        assertEquals("en", english.languageTag)
    }

    @Test
    fun preferredLanguageWinsOverExactUntaggedSubtitle() {
        val selected = select(
            names = listOf("movie.srt", "movie.ko.srt"),
            selectedLanguage = "ko"
        )

        assertEquals("movie.ko.srt", selected)
    }

    @Test
    fun exactUntaggedSubtitleIsFallbackBeforeOtherLanguage() {
        val selected = select(
            names = listOf("movie.ja.srt", "movie.srt"),
            selectedLanguage = "ko"
        )

        assertEquals("movie.srt", selected)
    }

    @Test
    fun videoNamesWithDotsAreMatchedWithoutFalsePrefixMatches() {
        val matching = SubtitleCandidatePolicy.match(
            "movie.2026.mp4",
            "movie.2026.ko.srt"
        )
        val differentPrefix = SubtitleCandidatePolicy.match(
            "movie.2026.mp4",
            "movie.20260.ko.srt"
        )
        val unsupportedExtension = SubtitleCandidatePolicy.match(
            "movie.2026.mp4",
            "movie.2026.ko.txt"
        )

        assertNotNull(matching)
        assertEquals("ko", matching?.languageTag)
        assertNull(differentPrefix)
        assertNull(unsupportedExtension)
    }

    @Test
    fun staleLegacySidecarNameStillMatchesTheSameVideo() {
        val currentCandidate = requireNotNull(
            SubtitleCandidatePolicy.match("movie.mp4", "movie.ko.srt")
        )

        assertTrue(
            SubtitleCandidatePolicy.matchesSameVideo(currentCandidate, "movie.ja.srt")
        )
        assertFalse(
            SubtitleCandidatePolicy.matchesSameVideo(currentCandidate, "different.ja.srt")
        )
    }

    private fun select(
        names: List<String>,
        selectedLanguage: String?,
        effectiveLanguageTags: List<String> = listOf("en-US")
    ): String? {
        val candidates = names.map { name ->
            Candidate(
                name = name,
                match = requireNotNull(SubtitleCandidatePolicy.match("movie.mp4", name))
            )
        }
        val preferredLanguages = SubtitleCandidatePolicy.resolvePreferredLanguages(
            selectedLanguage = selectedLanguage,
            effectiveLanguageTags = effectiveLanguageTags
        )
        return SubtitleCandidatePolicy.preferredCandidate(
            candidates = candidates,
            preferredLanguages = preferredLanguages,
            matchOf = Candidate::match
        )?.name
    }

    private data class Candidate(
        val name: String,
        val match: SubtitleNameMatch
    )
}
