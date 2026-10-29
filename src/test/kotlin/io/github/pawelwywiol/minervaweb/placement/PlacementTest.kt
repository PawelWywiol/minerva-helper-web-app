package io.github.pawelwywiol.minervaweb.placement

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlacementTest {
    private val dir: Path = Files.createTempDirectory("placement")
    private val roms = dir.resolve("roms")
    private val placement = FilePlacement(roms)

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun zip(
        name: String,
        vararg entries: String,
    ): Path {
        val file = dir.resolve(name)
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach {
                out.putNextEntry(ZipEntry(it))
                out.write(it.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `places a file and finds it by size`() {
        val source = dir.resolve("game.nes").also { it.writeText("rom") }
        assertEquals("game.nes", placement.place("nes", "game.nes", source))
        assertFalse(source.exists())
        assertEquals("rom", roms.resolve("nes/game.nes").readText())
        assertTrue(placement.exists("nes", "game.nes", 3))
        assertFalse(placement.exists("nes", "game.nes", 4))
    }

    @Test
    fun `discs are unpacked, cartridges stay zipped`() {
        val disc = zip("disc.zip", "Game (USA).cue", "Game (USA) (Track 1).bin", "__MACOSX/._x")
        val cartridge = zip("cart.zip", "Game.nes")
        assertTrue(needsUnpacking("disc.zip", disc))
        assertFalse(needsUnpacking("cart.zip", cartridge))

        val written = placement.unpack("psx", disc)
        assertEquals("Game (USA).cue", playableName(written))
        assertEquals(
            setOf(
                "Game (USA).cue",
                "Game (USA) (Track 1).bin",
            ),
            roms
                .resolve("psx")
                .listDirectoryEntries()
                .map {
                    it.name
                }.toSet(),
        )
    }

    @Test
    fun `nothing escapes the library`() {
        placement.unpack("psx", zip("slip.zip", "../../evil.cue"))
        assertTrue(roms.resolve("psx/evil.cue").exists())
        assertFalse(dir.resolve("evil.cue").exists())

        val source = dir.resolve("x.bin").also { it.writeText("x") }
        assertFailsWith<IllegalArgumentException> { placement.place("../outside", "x.bin", source) }
        assertFailsWith<IllegalArgumentException> { placement.place("nes", "..", source) }
        assertFalse(isValidFolder("a/b"))
        assertTrue(isValidFolder("megadrive"))
    }
}
