package io.github.pawelwywiol.minervaweb.catalog

import org.sqlite.SQLiteConfig
import java.nio.file.Path
import java.sql.Connection
import kotlin.io.path.absolutePathString

private const val DEFAULT_PAGE = 50

// candidates come from the rarest words: common ones ("super", "2") would pull in a third of the archive
private const val CANDIDATE_BUDGET = 50_000L
private const val MAX_CANDIDATES = 150_000

class CatalogReader(
    file: Path,
) : AutoCloseable {
    data class Rom(
        val id: Long,
        val name: String,
        val size: Long,
        val collection: String,
        val platformKey: String,
        val torrentPath: String,
        val soId: Int,
        val md5: String?,
        val sha1: String?,
    )

    data class Collection(
        val name: String,
        val fileCount: Long,
        val platformCount: Int,
    )

    data class Platform(
        val id: Long,
        val collection: String,
        val key: String,
        val fileCount: Long,
    )

    private val db: Connection =
        SQLiteConfig()
            .apply { setReadOnly(true) }
            .createConnection("jdbc:sqlite:${file.absolutePathString()}")

    fun isComplete(): Boolean =
        runCatching {
            meta(CatalogSchema.KEY_STAGE) == CatalogSchema.STAGE_DONE &&
                meta(CatalogSchema.KEY_VERSION) == CatalogSchema.VERSION.toString()
        }.getOrDefault(false)

    fun sourceVersion(): String? = runCatching { meta(CatalogSchema.KEY_SOURCE) }.getOrNull()

    private fun meta(key: String): String? =
        query("SELECT value FROM meta WHERE key = ?", key) {
            getString(1)
        }.firstOrNull()

    fun collections(): List<Collection> =
        query(
            "SELECT collection, sum(file_count), count(*) FROM platform " +
                "GROUP BY collection HAVING sum(file_count) > 0 ORDER BY collection",
        ) { Collection(getString(1), getLong(2), getInt(3)) }

    fun platforms(collection: String): List<Platform> =
        query(
            "SELECT id, collection, platform_key, file_count FROM platform " +
                "WHERE collection = ? AND file_count > 0 ORDER BY platform_key",
            collection,
        ) { Platform(getLong(1), getString(2), getString(3), getLong(4)) }

    fun platforms(): List<Platform> =
        query("SELECT id, collection, platform_key, file_count FROM platform WHERE file_count > 0") {
            Platform(getLong(1), getString(2), getString(3), getLong(4))
        }

    // keyset paging: OFFSET over a 750k-row platform is slow, seeking to (name, id) is flat
    fun browse(
        platformId: Long,
        afterName: String? = null,
        afterId: Long? = null,
        limit: Int = DEFAULT_PAGE,
    ): List<Rom> =
        if (afterName != null && afterId != null) {
            roms(
                "FROM file f",
                "f.platform_id = ? AND (f.name > ? OR (f.name = ? AND f.id > ?)) ORDER BY f.name, f.id",
                listOf(platformId, afterName, afterName, afterId),
                limit,
            )
        } else {
            roms("FROM file f", "f.platform_id = ? ORDER BY f.name, f.id", listOf(platformId), limit)
        }

    fun rom(id: Long): Rom? = roms("FROM file f", "f.id = ?", listOf(id), limit = 1).firstOrNull()

    fun rom(
        collection: String,
        platformKey: String,
        name: String,
    ): Rom? =
        roms(
            "FROM file f",
            "p.collection = ? AND p.platform_key = ? AND f.name = ?",
            listOf(collection, platformKey, name),
            limit = 1,
        ).firstOrNull()

    fun count(): Long = query("SELECT count(*) FROM file") { getLong(1) }.first()

    /**
     * Files sharing the most words with the query, best first; files sharing none are left out. The
     * index only gathers candidates, holding any of the query's rarer words, and [WordMatch] ranks
     * them. FTS goes first (CROSS JOIN): checking MATCH row by row inside a platform takes seconds.
     */
    fun rank(
        terms: List<String>,
        prefix: Boolean,
        platformIds: List<Long>? = null,
        limit: Int = DEFAULT_PAGE,
        offset: Int = 0,
        hiddenRegions: Set<String> = emptySet(),
        keep: (Rom) -> Boolean = { true },
    ): List<Rom> {
        if (terms.isEmpty()) return emptyList()
        val match = WordMatch(terms, prefix)
        val expression = candidates(terms, match) ?: return emptyList()
        val filter = platformIds?.let { " AND f.platform_id IN (${it.joinToString(",")})" }.orEmpty()
        return roms(
            "FROM file_fts j CROSS JOIN file f ON f.id = j.rowid",
            "j.name MATCH ?$filter",
            listOf(expression),
            MAX_CANDIDATES,
        ).map { it to match.score(it) }
            .filter { it.second.shared > 0 && Regions.visible(it.first.name, hiddenRegions) && keep(it.first) }
            .sortedWith(WordMatch.ORDER)
            .drop(offset)
            .take(limit)
            .map { it.first }
    }

    // FTS4 operators are neutralised: terms are bare letters and digits, joined with OR only
    private fun candidates(
        terms: List<String>,
        match: WordMatch,
    ): String? {
        val options = terms.map { if (match.isPrefix(it)) "$it*" else it } + terms.zipWithNext { a, b -> a + b }
        val counted = options.map { it to documents(it) }.filter { it.second > 0 }.sortedBy { it.second }
        if (counted.isEmpty()) return null
        val picked = mutableListOf(counted.first().first)
        var total = counted.first().second
        for ((option, documents) in counted.drop(1)) {
            if (total + documents > CANDIDATE_BUDGET) break
            picked += option
            total += documents
        }
        return picked.joinToString(" OR ")
    }

    // how many files hold a word, straight from the index; a temp table works on the read-only file
    private val termsTable: String by lazy {
        db.createStatement().use {
            it.execute(
                "CREATE VIRTUAL TABLE IF NOT EXISTS temp.terms USING fts4aux(main, file_fts)",
            )
        }
        "temp.terms"
    }

    private fun documents(option: String): Long {
        val word = option.removeSuffix("*")
        val sql = "SELECT coalesce(sum(documents), 0) FROM $termsTable WHERE col = '*' AND "
        return if (option.endsWith("*")) {
            query(sql + "term >= ? AND term < ?", word, word + Char.MAX_VALUE) { getLong(1) }.first()
        } else {
            query(sql + "term = ?", word) { getLong(1) }.first()
        }
    }

    fun list(
        platformIds: List<Long>,
        limit: Int = DEFAULT_PAGE,
        offset: Int = 0,
        hiddenRegions: Set<String> = emptySet(),
    ): List<Rom> =
        if (platformIds.isEmpty()) {
            emptyList()
        } else {
            roms(
                "FROM file f",
                "f.platform_id IN (${platformIds.joinToString(
                    ",",
                )})${Regions.sql(hiddenRegions)} ORDER BY f.name, f.id",
                emptyList(),
                limit,
                offset,
            )
        }

    private fun roms(
        from: String,
        where: String,
        args: List<Any>,
        limit: Int,
        offset: Int = 0,
    ): List<Rom> =
        query(
            "SELECT f.id, f.name, f.size, p.collection, p.platform_key, t.path, f.so_id, f.md5, f.sha1 " +
                "$from JOIN platform p ON p.id = f.platform_id JOIN torrent t ON t.id = f.torrent_id " +
                "WHERE $where LIMIT ? OFFSET ?",
            *(args + listOf(limit, offset)).toTypedArray(),
        ) {
            Rom(
                id = getLong(1),
                name = getString(2),
                size = getLong(3),
                collection = getString(4),
                platformKey = getString(5),
                torrentPath = getString(6),
                soId = getInt(7),
                md5 = getBytes(8)?.toHexString(),
                sha1 = getBytes(9)?.toHexString(),
            )
        }

    private fun <T> query(
        sql: String,
        vararg args: Any,
        map: java.sql.ResultSet.() -> T,
    ): List<T> =
        db.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, arg -> st.setObject(i + 1, arg) }
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.map()) } }
        }

    override fun close() = db.close()
}
