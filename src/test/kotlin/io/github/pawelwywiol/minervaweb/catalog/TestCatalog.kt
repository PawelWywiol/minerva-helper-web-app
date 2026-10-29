package io.github.pawelwywiol.minervaweb.catalog

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.sql.DriverManager

internal object TestCatalog {
    const val MD5 = "0123456789abcdef0123456789abcdef"
    const val NES = "Nintendo - Nintendo Entertainment System (Headerless)"
    private val btih = "a".repeat(40)

    private val rows =
        listOf(
            listOf("./No-Intro/$NES/Super Mario Bros. (World).zip", "No-Intro/nes.torrent", 3, MD5),
            listOf("./No-Intro/$NES/Zelda (USA).zip", "No-Intro/nes.torrent", 4, null),
            listOf("./Redump/Commodore - Amiga CD32/ZoomAmiga (Europe).zip", "Redump/cd32.torrent", 0, null),
            listOf("./MAME/roms/pacman.zip", "MAME/roms.torrent", 7, null),
            listOf("./TOSEC/Commodore/Amiga/Games/Foo (1990).adf", "TOSEC/amiga.torrent", 1, null),
        )

    fun source(path: Path): Path {
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.exec(
                "CREATE TABLE files(id INTEGER PRIMARY KEY, full_path TEXT, file_name TEXT, size INTEGER, " +
                    "md5 TEXT, sha1 TEXT, crc32 TEXT, torrents TEXT, so_id INTEGER, magnet TEXT)",
            )
            db.prepareStatement("INSERT INTO files VALUES(?,?,?,?,?,?,?,?,?,?)").use { insert ->
                rows.forEachIndexed { i, (fullPath, torrent, soId, hash) ->
                    insert.setInt(1, i + 1)
                    insert.setString(2, fullPath as String)
                    insert.setString(3, fullPath.substringAfterLast('/'))
                    insert.setLong(4, 1000L + i)
                    insert.setString(5, hash as String?)
                    insert.setString(6, null)
                    insert.setString(7, "1f")
                    insert.setString(8, torrent as String)
                    insert.setInt(9, soId as Int)
                    insert.setString(10, "magnet:?xt=urn:btih:$btih&dn=x")
                    insert.executeUpdate()
                }
            }
            db.exec("INSERT INTO files VALUES(99,'./No-Intro/x/.DS_Store','.DS_Store',1,NULL,NULL,NULL,'t',NULL,'m')")
        }
        return path
    }

    fun catalog(dir: Path): Path {
        val target = dir.resolve("catalog.db")
        runBlocking { CatalogImporter().import(source(dir.resolve("hashes.db")), target, "etag:1") {} }
        return target
    }
}
