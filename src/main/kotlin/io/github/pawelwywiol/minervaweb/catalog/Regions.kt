package io.github.pawelwywiol.minervaweb.catalog

/**
 * Release regions as the archives write them: No-Intro spells them out in one bracket
 * ("(USA, Europe)"), TOSEC uses country codes ("(US)", "(DE)"). A name whose regions are all
 * hidden is left out; a name with no recognisable region always stays.
 */
object Regions {
    private val NAMES =
        mapOf(
            "usa" to listOf("USA", "US"),
            "europe" to
                listOf(
                    "Europe",
                    "EU",
                    "UK",
                    "GB",
                    "Germany",
                    "DE",
                    "France",
                    "FR",
                    "Spain",
                    "ES",
                    "Italy",
                    "IT",
                    "Netherlands",
                    "NL",
                    "Sweden",
                    "SE",
                    "Scandinavia",
                    "Poland",
                    "PL",
                    "Portugal",
                    "PT",
                ),
            "world" to listOf("World"),
            "japan" to listOf("Japan", "JP"),
            "other" to
                listOf(
                    "Asia",
                    "Korea",
                    "KR",
                    "China",
                    "CN",
                    "Taiwan",
                    "TW",
                    "Hong Kong",
                    "HK",
                    "Brazil",
                    "BR",
                    "Australia",
                    "AU",
                    "Canada",
                    "CA",
                    "Russia",
                    "RU",
                    "Latin America",
                ),
        )

    val KEYS: Set<String> = NAMES.keys

    // a region word only counts as a whole bracketed item: "USA" in "(USA, Europe)", not in a title
    private val TAGS = NAMES.mapValues { (_, words) -> words.flatMap { listOf("($it)", "($it,", ", $it,", ", $it)") } }

    fun parse(hidden: String?): Set<String> =
        hidden
            .orEmpty()
            .split(',')
            .map { it.trim().lowercase() }
            .filterTo(HashSet()) { it in KEYS }

    fun visible(
        name: String,
        hidden: Set<String>,
    ): Boolean {
        if (hidden.isEmpty()) return true
        val found = TAGS.filterValues { tags -> tags.any { it in name } }.keys
        return found.isEmpty() || found.any { it !in hidden }
    }

    // the same rule in SQL, for listings that page in the database; tags are constants, never input
    fun sql(hidden: Set<String>): String {
        if (hidden.isEmpty()) return ""

        fun any(keys: Set<String>) =
            keys
                .flatMap {
                    TAGS.getValue(it)
                }.joinToString(" OR ", "(", ")") { "instr(f.name, '$it') > 0" }
        val shown = KEYS - hidden
        return if (shown.isEmpty()) {
            " AND NOT ${any(KEYS)}"
        } else {
            " AND (NOT ${any(hidden)} OR ${any(shown)})"
        }
    }
}
