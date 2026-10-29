package io.github.pawelwywiol.minervaweb.platform

class Systems(
    private val rows: Map<String, List<String>> = loadRows(),
    private val aliases: Map<String, List<String>> = loadAliases(),
) {
    // the platform key wins when it is itself a system: FBNeo's "megadrive" lands in arcade but reads Mega Drive
    fun labelFor(
        platformKey: String,
        slug: String,
    ): String = (rows[platformKey.lowercase()] ?: rows[slug])?.get(1) ?: platformKey

    fun familyFor(slug: String): String = rows[slug]?.getOrNull(3).orEmpty().ifEmpty { "other" }

    fun aliasesFor(slug: String): List<String> = aliases[slug].orEmpty()

    companion object {
        private fun loadRows(): Map<String, List<String>> =
            csvRows("systems.csv").filter { it.size >= 2 }.associateBy { it[0] }

        private fun loadAliases(): Map<String, List<String>> =
            csvRows("system_aliases.csv").filter { it.size >= 2 }.groupBy({ it[1] }, { it[0] })
    }
}
