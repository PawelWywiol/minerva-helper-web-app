package io.github.pawelwywiol.minervaweb.platform

// archive platform -> ES-DE folder; unmapped platforms keep a cleaned-up name instead of being refused
class PlatformFolders(
    private val slugs: Map<String, String> = loadSlugs(),
) {
    fun folderFor(
        collection: String,
        platformKey: String,
    ): String = slugs[key(collection, platformKey)] ?: fallback(platformKey)

    fun isMapped(
        collection: String,
        platformKey: String,
    ): Boolean = key(collection, platformKey) in slugs

    companion object {
        private fun key(
            collection: String,
            platformKey: String,
        ) = "$collection/$platformKey"

        private fun fallback(platformKey: String) =
            platformKey
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifEmpty { "unsorted" }

        private fun loadSlugs(): Map<String, String> =
            csvRows("platform_map.csv")
                .filter { it.size >= 3 && it[2].isNotEmpty() }
                .associate { key(it[0], it[1]) to it[2] }
    }
}

internal fun csvRows(resource: String): List<List<String>> =
    checkNotNull(PlatformFolders::class.java.getResourceAsStream("/data/$resource")) { "missing $resource" }
        .bufferedReader()
        .useLines { lines ->
            lines
                .drop(1)
                .filter { it.isNotBlank() }
                .map(::splitCsv)
                .toList()
        }

// platform keys contain commas ("Commodore/C16, C116 & Plus-4"), those fields are quoted
internal fun splitCsv(line: String): List<String> =
    buildList {
        var i = 0
        while (i <= line.length) {
            if (i < line.length && line[i] == '"') {
                val end = line.indexOf('"', i + 1).takeIf { it >= 0 } ?: line.length
                add(line.substring(i + 1, end))
                i = end + 2
            } else {
                val end = line.indexOf(',', i).takeIf { it >= 0 } ?: line.length
                add(line.substring(i, end))
                i = end + 1
            }
        }
    }
