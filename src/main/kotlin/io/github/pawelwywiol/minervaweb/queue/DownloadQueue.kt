package io.github.pawelwywiol.minervaweb.queue

import io.github.pawelwywiol.minervaweb.catalog.CatalogReader
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import kotlin.io.path.absolutePathString

enum class DownloadState { QUEUED, DOWNLOADING, VERIFYING, FAILED, PAUSED }

data class QueueItem(
    val id: Long,
    val name: String,
    val size: Long,
    val collection: String,
    val platformKey: String,
    val torrentPath: String,
    val soId: Int,
    val md5: String?,
    val sha1: String?,
    val state: DownloadState,
    val error: String?,
)

data class DownloadedItem(
    val id: Long,
    val name: String,
    val size: Long,
    val collection: String,
    val platformKey: String,
    val folder: String,
)

data class Active(
    val label: String,
    val percent: Int,
    val rate: Int,
    val peers: Int,
)

// separate from catalog.db, which is rebuilt on every catalog update
class DownloadQueue(
    file: Path,
) : AutoCloseable {
    private val db: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePathString()}")

    @Volatile
    var active: Map<String, Active> = emptyMap()
        private set

    init {
        listOf(
            "PRAGMA journal_mode=WAL",
            "CREATE TABLE IF NOT EXISTS item(id INTEGER PRIMARY KEY, name TEXT NOT NULL, size INTEGER NOT NULL," +
                "collection TEXT NOT NULL, platform_key TEXT NOT NULL, torrent_path TEXT NOT NULL," +
                "so_id INTEGER NOT NULL, md5 TEXT, sha1 TEXT, state TEXT NOT NULL, error TEXT," +
                "added_at INTEGER NOT NULL, position INTEGER NOT NULL DEFAULT 0)",
            "CREATE UNIQUE INDEX IF NOT EXISTS ux_item ON item(torrent_path, so_id)",
            "CREATE TABLE IF NOT EXISTS downloaded(id INTEGER PRIMARY KEY, name TEXT NOT NULL, size INTEGER NOT NULL," +
                "collection TEXT NOT NULL, platform_key TEXT NOT NULL, torrent_path TEXT NOT NULL," +
                "so_id INTEGER NOT NULL, folder TEXT NOT NULL, completed_at INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS ux_downloaded ON downloaded(torrent_path, so_id)",
        ).forEach { sql -> db.createStatement().use { it.execute(sql) } }
    }

    @Synchronized
    fun setActive(
        torrentPath: String,
        value: Active?,
    ) {
        active = if (value == null) active - torrentPath else active + (torrentPath to value)
    }

    // nothing downloads right after a restart, so interrupted rows go back to the queue
    @Synchronized
    fun resetInterrupted() =
        update(
            "UPDATE item SET state = ? WHERE state IN (?, ?)",
            DownloadState.QUEUED,
            DownloadState.DOWNLOADING,
            DownloadState.VERIFYING,
        )

    @Synchronized
    fun contains(rom: CatalogReader.Rom): Boolean =
        query(
            "SELECT 1 FROM item WHERE torrent_path = ? AND so_id = ?",
            rom.torrentPath,
            rom.soId,
        ) { true }.isNotEmpty()

    // returns true when the ROM was added
    @Synchronized
    fun toggle(rom: CatalogReader.Rom): Boolean {
        val removed = update("DELETE FROM item WHERE torrent_path = ? AND so_id = ?", rom.torrentPath, rom.soId)
        if (removed == 0) {
            update(
                "INSERT INTO item(name,size,collection,platform_key,torrent_path,so_id,md5,sha1," +
                    "state,added_at,position) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                rom.name,
                rom.size,
                rom.collection,
                rom.platformKey,
                rom.torrentPath,
                rom.soId,
                rom.md5,
                rom.sha1,
                DownloadState.QUEUED,
                System.currentTimeMillis(),
                positionFor(rom.torrentPath),
            )
        }
        return removed == 0
    }

    // a ROM joins its torrent's position, so a second file from the front torrent is not sent to the back
    private fun positionFor(torrentPath: String): Int =
        query("SELECT MIN(position) FROM item WHERE torrent_path = ?", torrentPath) { nullableInt() }.firstOrNull()
            ?: query("SELECT MAX(position) FROM item") { nullableInt() }.firstOrNull()?.plus(1)
            ?: 0

    private fun ResultSet.nullableInt(): Int? = getInt(1).takeUnless { wasNull() }

    // name is what landed in the folder, which differs from the item when an archive was unpacked
    @Synchronized
    fun markDownloaded(
        item: QueueItem,
        folder: String,
        name: String = item.name,
    ) {
        db.autoCommit = false
        try {
            insertDownloaded(name, item.size, item.collection, item.platformKey, item.torrentPath, item.soId, folder)
            update("DELETE FROM item WHERE id = ?", item.id)
            db.commit()
        } catch (e: java.sql.SQLException) {
            db.rollback()
            throw e
        } finally {
            db.autoCommit = true
        }
    }

    @Synchronized
    fun recordDownloaded(
        rom: CatalogReader.Rom,
        folder: String,
    ) = insertDownloaded(rom.name, rom.size, rom.collection, rom.platformKey, rom.torrentPath, rom.soId, folder)

    private fun insertDownloaded(
        name: String,
        size: Long,
        collection: String,
        platformKey: String,
        torrentPath: String,
        soId: Int,
        folder: String,
    ) = update(
        "INSERT OR REPLACE INTO downloaded(name,size,collection,platform_key,torrent_path,so_id,folder,completed_at) " +
            "VALUES(?,?,?,?,?,?,?,?)",
        name,
        size,
        collection,
        platformKey,
        torrentPath,
        soId,
        folder,
        System.currentTimeMillis(),
    )

    @Synchronized
    fun isDownloaded(rom: CatalogReader.Rom): Boolean =
        query(
            "SELECT 1 FROM downloaded WHERE torrent_path = ? AND so_id = ?",
            rom.torrentPath,
            rom.soId,
        ) { true }.isNotEmpty()

    @Synchronized
    fun downloadedKeys(): Set<String> =
        query("SELECT torrent_path, so_id FROM downloaded") {
            key(getString(1), getInt(2))
        }.toSet()

    @Synchronized
    fun queuedKeys(): Set<String> =
        query("SELECT torrent_path, so_id FROM item") { key(getString(1), getInt(2)) }.toSet()

    @Synchronized
    fun downloaded(): List<DownloadedItem> =
        query(
            "SELECT id, name, size, collection, platform_key, folder FROM downloaded ORDER BY completed_at DESC, id DESC",
        ) { DownloadedItem(getLong(1), getString(2), getLong(3), getString(4), getString(5), getString(6)) }

    // forgets the record only; the file is never touched
    @Synchronized
    fun removeDownloaded(id: Long) = update("DELETE FROM downloaded WHERE id = ?", id)

    @Synchronized
    fun remove(id: Long) = update("DELETE FROM item WHERE id = ?", id)

    @Synchronized
    fun setState(
        id: Long,
        state: DownloadState,
        error: String? = null,
    ) = update("UPDATE item SET state = ?, error = ? WHERE id = ?", state, error, id)

    @Synchronized
    fun setPaused(
        id: Long,
        paused: Boolean,
    ) = update("$PAUSE_SQL WHERE id = ? AND state IN (?, ?, ?)", *pauseArgs(paused, id))

    @Synchronized
    fun setGroupPaused(
        torrentPath: String,
        paused: Boolean,
    ) = update("$PAUSE_SQL WHERE torrent_path = ? AND state IN (?, ?, ?)", *pauseArgs(paused, torrentPath))

    private fun pauseArgs(
        paused: Boolean,
        target: Any,
    ): Array<Any> =
        arrayOf(
            if (paused) DownloadState.PAUSED else DownloadState.QUEUED,
            target,
            DownloadState.QUEUED,
            DownloadState.PAUSED,
            DownloadState.DOWNLOADING,
        )

    @Synchronized
    fun pausedIds(): Set<Long> =
        query("SELECT id FROM item WHERE state = ?", DownloadState.PAUSED) { getLong(1) }.toSet()

    // whole torrents move, so a ROM cannot overtake its siblings that are fetched in the same pass
    @Synchronized
    fun moveGroup(
        from: Int,
        to: Int,
    ) {
        val order =
            query(
                "SELECT torrent_path FROM item GROUP BY torrent_path ORDER BY MIN(position), MIN(added_at), MIN(id)",
            ) { getString(1) }.toMutableList()
        if (from !in order.indices || to !in order.indices || from == to) return
        order.add(to, order.removeAt(from))
        order.forEachIndexed {
            index,
            path,
            ->
            update("UPDATE item SET position = ? WHERE torrent_path = ?", index, path)
        }
    }

    @Synchronized
    fun retryFailed() =
        update("UPDATE item SET state = ?, error = NULL WHERE state = ?", DownloadState.QUEUED, DownloadState.FAILED)

    fun byTorrent(): Map<String, List<QueueItem>> = all().groupBy { it.torrentPath }

    @Synchronized
    fun all(): List<QueueItem> =
        query(
            "SELECT id, name, size, collection, platform_key, torrent_path, so_id, md5, sha1, state, error " +
                "FROM item ORDER BY position, added_at, id",
        ) {
            QueueItem(
                id = getLong(1),
                name = getString(2),
                size = getLong(3),
                collection = getString(4),
                platformKey = getString(5),
                torrentPath = getString(6),
                soId = getInt(7),
                md5 = getString(8),
                sha1 = getString(9),
                state = DownloadState.valueOf(getString(10)),
                error = getString(11),
            )
        }

    private fun update(
        sql: String,
        vararg args: Any?,
    ): Int = db.prepareStatement(sql).use { st -> bind(st, args).executeUpdate() }

    private fun <T> query(
        sql: String,
        vararg args: Any?,
        map: ResultSet.() -> T,
    ): List<T> =
        db.prepareStatement(sql).use { st ->
            bind(st, args).executeQuery().use { rs -> buildList { while (rs.next()) add(rs.map()) } }
        }

    private fun bind(
        st: java.sql.PreparedStatement,
        args: Array<out Any?>,
    ) = st.apply { args.forEachIndexed { i, arg -> setObject(i + 1, if (arg is Enum<*>) arg.name else arg) } }

    @Synchronized
    override fun close() = db.close()

    companion object {
        private const val PAUSE_SQL = "UPDATE item SET state = ?, error = NULL"

        fun key(
            torrentPath: String,
            soId: Int,
        ) = "$torrentPath\u0000$soId"
    }
}
