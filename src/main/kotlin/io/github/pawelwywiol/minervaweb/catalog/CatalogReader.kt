package io.github.pawelwywiol.minervaweb.catalog

import org.sqlite.SQLiteConfig
import java.nio.file.Path
import java.sql.Connection
import kotlin.io.path.absolutePathString

private const val DEFAULT_PAGE = 50

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
            "SELECT id, platform_key, file_count FROM platform " +
                "WHERE collection = ? AND file_count > 0 ORDER BY platform_key",
            collection,
        ) { Platform(getLong(1), getString(2), getLong(3)) }

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

    fun search(
        query: String,
        limit: Int = DEFAULT_PAGE,
        offset: Int = 0,
    ): List<Rom> {
        val match = toMatchExpression(query) ?: return emptyList()
        val fts = "FROM file_fts j JOIN file f ON f.id = j.rowid"
        val hits = roms(fts, "j.name MATCH ?", listOf(match), limit, offset)
        // names are not always split into words: "zoom amiga" must also find "ZoomAmiga"
        val joined = toJoinedMatchExpression(query)
        if (joined == null || hits.size >= limit) return hits
        val seen = hits.mapTo(HashSet()) { it.id }
        val extra = roms(fts, "j.name MATCH ?", listOf(joined), limit - hits.size)
        return hits + extra.filterNot { it.id in seen }
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

// FTS4 operators are neutralised: only bare lowercase terms, unquoted, last one as prefix
private fun toMatchExpression(query: String): String? {
    val terms = searchTerms(query)
    if (terms.isEmpty()) return null
    return terms.mapIndexed { index, term -> if (index == terms.lastIndex) "$term*" else term }.joinToString(" ")
}

private fun toJoinedMatchExpression(query: String): String? {
    val terms = searchTerms(query)
    if (terms.size < 2) return null
    return terms.joinToString("") + "*"
}

private fun searchTerms(query: String): List<String> =
    query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
