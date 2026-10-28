package io.github.pawelwywiol.minervaweb.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists

private const val BATCH = 10_000
private const val HEX_RADIX = 16
private const val BTIH_HEX_LENGTH = 40

class CatalogImporter {
    data class Progress(
        val imported: Long,
        val total: Long,
    )

    // always a clean build: journalling is off for speed, so a resumed import could be silently corrupt
    suspend fun import(
        source: Path,
        target: Path,
        sourceVersion: String?,
        onProgress: suspend (Progress) -> Unit,
    ) = withContext(Dispatchers.IO) {
        target.deleteIfExists()
        DriverManager.getConnection("jdbc:sqlite:${target.absolutePathString()}").use { db ->
            db.exec("PRAGMA journal_mode=OFF")
            db.exec("PRAGMA synchronous=OFF")
            CatalogSchema.DDL.forEach(db::exec)
            db.prepareStatement("ATTACH DATABASE ? AS src").use {
                it.setString(1, source.absolutePathString())
                it.execute()
            }
            db.autoCommit = false

            val total = db.long("SELECT count(*) FROM src.files WHERE ${CatalogSchema.SOURCE_FILTER}")
            val state = ImportState()
            Inserts(db).use { insert ->
                var lastId = 0L
                var imported = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val batch = importBatch(db, lastId, insert, state) { lastId = it }
                    db.commit()
                    if (batch == 0) break
                    imported += batch
                    onProgress(Progress(imported, total))
                }
            }

            db.writeFileCounts(state.platformCounts)
            CatalogSchema.INDEXES.forEach(db::exec)
            db.exec(CatalogSchema.CREATE_FTS)
            db.exec("INSERT INTO file_fts(rowid, name) SELECT id, name FROM file")
            db.exec("INSERT INTO file_fts(file_fts) VALUES('optimize')")
            db.setMeta(CatalogSchema.KEY_VERSION, CatalogSchema.VERSION.toString())
            sourceVersion?.let { db.setMeta(CatalogSchema.KEY_SOURCE, it) }
            db.setMeta(CatalogSchema.KEY_STAGE, CatalogSchema.STAGE_DONE)
            db.commit()
            db.autoCommit = true
            db.exec("DETACH DATABASE src")
        }
    }

    private fun importBatch(
        db: Connection,
        fromId: Long,
        insert: Inserts,
        state: ImportState,
        onId: (Long) -> Unit,
    ): Int {
        var count = 0
        db
            .prepareStatement(
                "SELECT id, full_path, file_name, size, md5, sha1, crc32, torrents, so_id, magnet " +
                    "FROM src.files WHERE ${CatalogSchema.SOURCE_FILTER} AND id > ? ORDER BY id LIMIT $BATCH",
            ).use { query ->
                query.setLong(1, fromId)
                query.executeQuery().use { row ->
                    while (row.next()) {
                        insertRow(row, insert, state)
                        count++
                        onId(row.getLong(1))
                    }
                }
            }
        return count
    }

    private fun insertRow(
        row: ResultSet,
        insert: Inserts,
        state: ImportState,
    ) {
        val fullPath = row.getString(2)
        val name = row.getString(3)
        val torrentId =
            state.torrents.getOrPut(row.getString(8)) {
                val id = state.torrents.size + 1L
                insert.torrent.run {
                    setLong(1, id)
                    setString(2, row.getString(8))
                    setBytes(3, row.getString(10).btih())
                    executeUpdate()
                }
                id
            }
        val dirPath = fullPath.dropLast(name.length + 1)
        val dirId = state.dirs.getOrPut(dirPath) { insertDir(dirPath, insert, state) }
        val platformId = state.dirPlatform.getValue(dirId)
        state.platformCounts[platformId] = (state.platformCounts[platformId] ?: 0L) + 1L

        insert.file.run {
            setLong(1, dirId)
            setString(2, name)
            setLong(3, row.getLong(4))
            setLong(4, torrentId)
            setLong(5, row.getLong(9))
            setHex(6, row.getString(5))
            setHex(7, row.getString(6))
            row.getString(7)?.toLongOrNull(HEX_RADIX)?.let { setLong(8, it) } ?: setNull(8, Types.INTEGER)
            setLong(9, platformId)
            executeUpdate()
        }
    }

    private fun insertDir(
        dirPath: String,
        insert: Inserts,
        state: ImportState,
    ): Long {
        val (collection, key) = CatalogSchema.platformOf(dirPath)
        val platformId =
            state.platforms.getOrPut("$collection\u0000$key") {
                val id = state.platforms.size + 1L
                insert.platform.run {
                    setLong(1, id)
                    setString(2, collection)
                    setString(3, key)
                    executeUpdate()
                }
                id
            }
        val dirId = state.dirs.size + 1L
        insert.dir.run {
            setLong(1, dirId)
            setString(2, dirPath)
            setLong(3, platformId)
            executeUpdate()
        }
        state.dirPlatform[dirId] = platformId
        return dirId
    }

    private class ImportState(
        val torrents: MutableMap<String, Long> = HashMap(),
        val dirs: MutableMap<String, Long> = HashMap(),
        val platforms: MutableMap<String, Long> = HashMap(),
        val dirPlatform: MutableMap<Long, Long> = HashMap(),
        val platformCounts: MutableMap<Long, Long> = HashMap(),
    )

    private class Inserts(
        db: Connection,
    ) : AutoCloseable {
        val torrent: PreparedStatement = db.prepareStatement("INSERT INTO torrent VALUES(?,?,?)")
        val platform: PreparedStatement =
            db.prepareStatement("INSERT INTO platform(id,collection,platform_key) VALUES(?,?,?)")
        val dir: PreparedStatement = db.prepareStatement("INSERT INTO dir VALUES(?,?,?)")
        val file: PreparedStatement =
            db.prepareStatement(
                "INSERT INTO file(dir_id,name,size,torrent_id,so_id,md5,sha1,crc32,platform_id) " +
                    "VALUES(?,?,?,?,?,?,?,?,?)",
            )

        override fun close() = listOf(torrent, platform, dir, file).forEach(PreparedStatement::close)
    }
}

private fun PreparedStatement.setHex(
    index: Int,
    hex: String?,
) {
    val bytes = hex?.hexToBytes()
    if (bytes == null) setNull(index, Types.BLOB) else setBytes(index, bytes)
}

private fun String.btih(): ByteArray =
    substringAfter("btih:").take(BTIH_HEX_LENGTH).hexToBytes() ?: error("no btih in magnet: $this")

internal fun String.hexToBytes(): ByteArray? {
    if (length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = Character.digit(this[i * 2], HEX_RADIX)
        val lo = Character.digit(this[i * 2 + 1], HEX_RADIX)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

internal fun Connection.exec(sql: String) {
    createStatement().use { it.execute(sql) }
}

private fun Connection.long(sql: String): Long =
    createStatement().use { st -> st.executeQuery(sql).use { if (it.next()) it.getLong(1) else 0L } }

private fun Connection.writeFileCounts(counts: Map<Long, Long>) {
    prepareStatement("UPDATE platform SET file_count = ? WHERE id = ?").use { update ->
        counts.forEach { (platformId, count) ->
            update.setLong(1, count)
            update.setLong(2, platformId)
            update.executeUpdate()
        }
    }
    commit()
}

private fun Connection.setMeta(
    key: String,
    value: String,
) {
    prepareStatement("INSERT OR REPLACE INTO meta VALUES(?,?)").use {
        it.setString(1, key)
        it.setString(2, value)
        it.executeUpdate()
    }
}
