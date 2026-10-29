package io.github.pawelwywiol.minervaweb.platform

import io.github.pawelwywiol.minervaweb.catalog.CatalogReader
import io.github.pawelwywiol.minervaweb.catalog.queryWords
import io.github.pawelwywiol.minervaweb.catalog.words
import io.github.pawelwywiol.minervaweb.settings.Settings

private const val MAX_SYSTEM_WORDS = 3
private const val MIN_PREFIX = 2
private const val MAX_SUGGESTIONS = 6
private const val MIN_DERIVED_ALIAS = 4

// "nes mario", "mario nes", "amiga cd32": a system named at either end of the query narrows it to that folder
class SystemMatcher(
    private val settings: Settings,
    private val systems: Systems,
) {
    data class System(
        val folder: String,
        val label: String,
        val family: String,
        val platformIds: List<Long>,
        val games: Long,
    )

    data class Query(
        val system: System?,
        val terms: List<String>,
        val suggestions: List<System>,
    )

    fun parse(
        platforms: List<CatalogReader.Platform>,
        query: String,
        anywhere: Boolean = false,
    ): Query {
        val terms = words(query)
        val all = group(platforms)
        val byAlias = all.flatMap { system -> aliases(system).map { it to system } }.toMap()
        val (system, rest) = if (anywhere) null to terms else split(terms, byAlias)
        val suggestions = if (system != null) listOf(system) else startingWith(all, terms.joinToString(""))
        // "NES / Famicom - Mario": the second name of the same system is not part of the title
        val title = if (system == null) rest else rest.filterNot { it in aliases(system) }
        return Query(system, queryWords(title), suggestions)
    }

    private fun startingWith(
        all: List<System>,
        prefix: String,
    ): List<System> =
        if (prefix.length < MIN_PREFIX) {
            emptyList()
        } else {
            all
                .filter { system -> aliases(system).any { it.startsWith(prefix) } }
                .sortedByDescending { it.games }
                .take(MAX_SUGGESTIONS)
        }

    private fun split(
        terms: List<String>,
        byAlias: Map<String, System>,
    ): Pair<System?, List<String>> {
        for (n in minOf(MAX_SYSTEM_WORDS, terms.size) downTo 1) {
            byAlias[terms.take(n).joinToString("")]?.let { return it to terms.drop(n) }
            byAlias[terms.takeLast(n).joinToString("")]?.let { return it to terms.dropLast(n) }
        }
        return null to terms
    }

    private fun group(platforms: List<CatalogReader.Platform>): List<System> =
        platforms
            .groupBy { settings.folderFor(it.collection, it.key) }
            .map { (folder, members) ->
                val slug = settings.slugFor(members.first().collection, members.first().key)
                System(
                    folder = folder,
                    label = systems.labelFor(folder, slug),
                    family = systems.familyFor(slug),
                    platformIds = members.map { it.id },
                    games = members.sumOf { it.fileCount },
                )
            }

    // the label minus its maker catches "genesis" and "playstation" without a hand-written alias each;
    // too short a remainder ("ii" of Apple II) would swallow game titles
    private fun aliases(system: System): Set<String> =
        buildSet {
            add(normalise(system.folder))
            listOf(system.label, system.label.substringAfter(' '))
                .map(::normalise)
                .filterTo(this) { it.length >= MIN_DERIVED_ALIAS }
            addAll(systems.aliasesFor(system.folder))
        }

    private fun normalise(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
}
