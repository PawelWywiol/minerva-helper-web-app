package io.github.pawelwywiol.minervaweb.catalog

import java.text.Normalizer

private val MARKS = Regex("\\p{M}+")
private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
private val EXTENSION = Regex("\\.[A-Za-z0-9]{1,4}$")
private val STOPWORDS = setOf("the", "of", "a", "an", "and")
private const val MIN_PREFIX = 2

// No-Intro and TOSEC release tags: English-speaking or Polish releases first, modified or unfinished dumps last
private val PREFERRED_TAGS =
    listOf("(USA", "(Europe", "(World", "(US)", "(EU)", "(GB)", "(PL)") +
        listOf("En", "Pl").flatMap { listOf("($it)", "($it,", ",$it,", ",$it)") }
private val UNWANTED_TAGS =
    listOf(
        "[b",
        "[h",
        "[a",
        "[t",
        "[f",
        "[cr",
        "[o",
        "[p",
        "[m",
        "[u",
        "(Beta",
        "(Proto",
        "(Demo",
        "(Sample",
        "(Pirate",
        "(Hack",
        " Hack)",
    )
private val CURATED = setOf("No-Intro", "Redump")

// "Pokémon", "POKEMON" and "pokemon," are the same word, as in the catalogue's unicode61 index
fun words(text: String): List<String> =
    Normalizer
        .normalize(text, Normalizer.Form.NFD)
        .replace(MARKS, "")
        .lowercase()
        .split(SEPARATORS)
        .filter { it.isNotEmpty() }

// repeats and filler words do not decide a match, unless filler is all there is
fun queryWords(words: List<String>): List<String> =
    words.distinct().filterNot { it in STOPWORDS }.ifEmpty { words.distinct() }

/**
 * How well a file name answers a query: the words they share, whatever their order, case or
 * punctuation. The last word may be half typed, and two query words may be one glued word in the
 * name ("zoom amiga" finds "ZoomAmiga").
 */
class WordMatch(
    private val terms: List<String>,
    private val prefix: Boolean,
) {
    data class Score(
        val shared: Int,
        val extra: Int,
        val unwanted: Boolean,
        val preferred: Boolean,
        val curated: Boolean,
    )

    fun score(rom: CatalogReader.Rom): Score {
        val name = rom.name
        val base = name.replace(EXTENSION, "")
        val tokens = words(base)
        val shared = terms.filterTo(HashSet()) { term -> tokens.any { matches(it, term) } }
        terms.zipWithNext().forEach { (a, b) -> if ("$a$b" in tokens) shared += listOf(a, b) }
        val title = words(base.substringBefore(" (").substringBefore(" ["))
        return Score(
            shared = shared.size,
            extra = title.count { token -> terms.none { matches(token, it) } },
            unwanted = UNWANTED_TAGS.any { it in name },
            preferred = PREFERRED_TAGS.any { it in name },
            curated = rom.collection in CURATED,
        )
    }

    fun matches(
        token: String,
        term: String,
    ): Boolean = token == term || (isPrefix(term) && token.startsWith(term))

    fun isPrefix(term: String): Boolean = prefix && term == terms.last() && term.length >= MIN_PREFIX

    companion object {
        val ORDER: Comparator<Pair<CatalogReader.Rom, Score>> =
            compareByDescending<Pair<CatalogReader.Rom, Score>> { it.second.shared }
                .thenBy { it.second.extra }
                .thenBy { it.second.unwanted }
                .thenByDescending { it.second.preferred }
                .thenByDescending { it.second.curated }
                .thenBy { it.first.name }
    }
}
