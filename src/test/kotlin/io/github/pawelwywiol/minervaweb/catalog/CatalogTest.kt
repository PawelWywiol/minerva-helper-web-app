package io.github.pawelwywiol.minervaweb.catalog

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogTest {
    private val dir: Path = Files.createTempDirectory("catalog")

    private fun reader() = CatalogReader(TestCatalog.catalog(dir))

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
            assertEquals(listOf(TestCatalog.NES), catalog.platforms("No-Intro").map { it.key })
            assertEquals(listOf("mame"), catalog.platforms("MAME").map { it.key })
            assertEquals(listOf("Commodore/Amiga"), catalog.platforms("TOSEC").map { it.key })
        }

    @Test
    fun `search matches prefixes and glued words`() =
        reader().use { catalog ->
            assertEquals(listOf("Super Mario Bros. (World).zip"), catalog.find("super mar").map { it.name })
            assertEquals(listOf("ZoomAmiga (Europe).zip"), catalog.find("zoom amiga").map { it.name })
            assertEquals(emptyList(), catalog.find("\"-()"))
        }

    @Test
    fun `search ranks by shared words whatever their order, case or punctuation`() {
        val nes = "./No-Intro/${TestCatalog.NES}"
        val extra =
            listOf("Super Mario Bros. 2 (Japan).zip", "Mario Bros. (World).zip", "Pokémon Puzzle (USA).zip")
                .mapIndexed { i, name -> listOf<Any?>("$nes/$name", "No-Intro/nes.torrent", 20 + i, null) }
        CatalogReader(TestCatalog.catalog(dir, extra)).use { catalog ->
            assertEquals(
                listOf("Super Mario Bros. 2 (Japan).zip", "Super Mario Bros. (World).zip", "Mario Bros. (World).zip"),
                catalog.find("2, BROS) (mario -- super ").map { it.name },
            )
            assertEquals(listOf("Pokémon Puzzle (USA).zip"), catalog.find("pokemon").map { it.name })
            assertEquals(emptyList(), catalog.find("tetris attack"))
        }
    }

    @Test
    fun `rom keeps torrent position and hashes`() =
        reader().use { catalog ->
            val rom = catalog.find("mario").single()
            assertEquals("No-Intro/nes.torrent", rom.torrentPath)
            assertEquals(3, rom.soId)
            assertEquals(TestCatalog.MD5, rom.md5)
            assertNull(rom.sha1)
            assertEquals(rom, catalog.rom(rom.id))
            assertEquals(rom, catalog.rom("No-Intro", TestCatalog.NES, rom.name))
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

    @Test
    fun `search within a system puts english releases first and broken dumps last`() {
        val nes = "./No-Intro/${TestCatalog.NES}"
        val extra =
            listOf("Zelda (Japan).zip", "Zelda (USA) [b].zip", "Zelda (Europe).zip").mapIndexed { i, name ->
                listOf<Any?>("$nes/$name", "No-Intro/nes.torrent", 10 + i, null)
            }
        CatalogReader(TestCatalog.catalog(dir, extra)).use { catalog ->
            val nesId = catalog.platforms("No-Intro").single().id
            val names = catalog.rank(listOf("zelda"), prefix = false, listOf(nesId)).map { it.name }
            assertEquals(
                listOf("Zelda (Europe).zip", "Zelda (USA).zip", "Zelda (Japan).zip", "Zelda (USA) [b].zip"),
                names,
            )
        }
    }
}
