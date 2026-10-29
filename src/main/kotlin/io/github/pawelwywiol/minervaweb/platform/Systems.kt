package io.github.pawelwywiol.minervaweb.platform

class Systems(
    private val names: Map<String, String> = loadNames(),
) {
    // the platform key wins when it is itself a system: FBNeo's "megadrive" lands in arcade but reads Mega Drive
    fun labelFor(
        platformKey: String,
        slug: String,
    ): String = names[platformKey.lowercase()] ?: names[slug] ?: platformKey

    companion object {
        private fun loadNames(): Map<String, String> =
            csvRows("systems.csv").filter { it.size >= 2 }.associate { it[0] to it[1] }
    }
}
