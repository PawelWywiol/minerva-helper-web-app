package io.github.pawelwywiol.minervaweb.catalog

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogTest {
    private val dir: Path = Files.createTempDirectory("catalog")
    private val source = dir.resolve("hashes.db")
    private val target = dir.resolve("catalog.db")
    private val md5 = "0123456789abcdef0123456789abcdef"
    private val btih = "a".repeat(40)

    private val rows =
        listOf(
            listOf("./No-Intro/Nintendo - NES/Super Mario Bros. (World).zip", "No-Intro/nes.torrent", 3, md5),
            listOf("./No-Intro/Nintendo - NES/Zelda (USA).zip", "No-Intro/nes.torrent", 4, null),
            listOf("./Redump/Commodore - Amiga CD32/ZoomAmiga (Europe).zip", "Redump/cd32.torrent", 0, null),
            listOf("./MAME/roms/pacman.zip", "MAME/roms.torrent", 7, null),
            listOf("./TOSEC/Commodore/Amiga/Games/Foo (1990).adf", "TOSEC/amiga.torrent", 1, null),
        )

    private fun buildSource() {
        DriverManager.getConnection("jdbc:sqlite:$source").use { db ->
            db.exec(
                "CREATE TABLE files(id INTEGER PRIMARY KEY, full_path TEXT, file_name TEXT, size INTEGER, " +
                    "md5 TEXT, sha1 TEXT, crc32 TEXT, torrents TEXT, so_id INTEGER, magnet TEXT)",
            )
            val insert = db.prepareStatement("INSERT INTO files VALUES(?,?,?,?,?,?,?,?,?,?)")
            rows.forEachIndexed { i, (path, torrent, soId, hash) ->
                insert.setInt(1, i + 1)
                insert.setString(2, path as String)
                insert.setString(3, path.substringAfterLast('/'))
                insert.setLong(4, 1000L + i)
                insert.setString(5, hash as String?)
                insert.setString(6, null)
                insert.setString(7, "1f")
                insert.setString(8, torrent as String)
                insert.setInt(9, soId as Int)
                insert.setString(10, "magnet:?xt=urn:btih:$btih&dn=x")
                insert.executeUpdate()
            }
            db.exec(
                "INSERT INTO files VALUES(99,'./No-Intro/x/.DS_Store','.DS_Store',1,NULL,NULL,NULL,'t',NULL,'m')",
            )
        }
    }

    private fun reader(): CatalogReader {
        buildSource()
        runBlocking { CatalogImporter().import(source, target, "etag:1") {} }
        return CatalogReader(target)
    }

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `import keeps addressable files and derives platforms`() =
        reader().use { catalog ->
            assertTrue(catalog.isComplete())
            assertEquals("etag:1", catalog.sourceVersion())
            assertEquals(5, catalog.count())
            assertEquals(listOf("MAME", "No-Intro", "Redump", "TOSEC"), catalog.collections().map { it.name })
            assertEquals(listOf("Nintendo - NES"), catalog.platforms("No-Intro").map { it.key })
            assertEquals(listOf("mame"), catalog.platforms("MAME").map { it.key })
            assertEquals(listOf("Commodore/Amiga"), catalog.platforms("TOSEC").map { it.key })
        }

    @Test
    fun `search matches prefixes and glued words`() =
        reader().use { catalog ->
            assertEquals(listOf("Super Mario Bros. (World).zip"), catalog.search("super mar").map { it.name })
            assertEquals(listOf("ZoomAmiga (Europe).zip"), catalog.search("zoom amiga").map { it.name })
            assertEquals(emptyList(), catalog.search("\"-()"))
        }

    @Test
    fun `rom keeps torrent position and hashes`() =
        reader().use { catalog ->
            val rom = catalog.search("mario").single()
            assertEquals("No-Intro/nes.torrent", rom.torrentPath)
            assertEquals(3, rom.soId)
            assertEquals(md5, rom.md5)
            assertNull(rom.sha1)
            assertEquals(rom, catalog.rom(rom.id))
            assertEquals(rom, catalog.rom("No-Intro", "Nintendo - NES", rom.name))
        }

    @Test
    fun `browse pages by name then id`() =
        reader().use { catalog ->
            val platform = catalog.platforms("No-Intro").single()
            val first = catalog.browse(platform.id, limit = 1).single()
            val second = catalog.browse(platform.id, first.name, first.id, limit = 1).single()
            assertEquals("Super Mario Bros. (World).zip", first.name)
            assertEquals("Zelda (USA).zip", second.name)
            assertEquals(emptyList(), catalog.browse(platform.id, second.name, second.id, limit = 1))
        }
}
