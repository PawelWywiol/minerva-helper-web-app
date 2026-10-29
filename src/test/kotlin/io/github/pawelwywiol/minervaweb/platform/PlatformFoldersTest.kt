package io.github.pawelwywiol.minervaweb.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformFoldersTest {
    private val folders = PlatformFolders()
    private val systems = Systems()

    @Test
    fun `major consoles land in es-de folders`() {
        assertEquals("mastersystem", folders.folderFor("TOSEC", "Sega/Mark III & Master System"))
        assertEquals("mastersystem", folders.folderFor("No-Intro", "Sega - Master System - Mark III"))
        assertEquals("genesis", folders.folderFor("No-Intro", "Sega - Mega Drive - Genesis"))
        assertEquals("snes", folders.folderFor("TOSEC", "Nintendo/Super Famicom & Super Entertainment System"))
        assertEquals("plus4", folders.folderFor("TOSEC", "Commodore/C16, C116 & Plus-4"))
        assertEquals("xbox360", folders.folderFor("Redump", "Microsoft - Xbox 360"))
        assertEquals("Microsoft Xbox 360", systems.labelFor("Microsoft - Xbox 360", "xbox360"))
    }

    @Test
    fun `unmapped platforms keep a cleaned-up name`() {
        assertEquals(
            "nintendo-nintendo-music-tracks",
            folders.folderFor("No-Intro", "Nintendo - Nintendo Music (Tracks)"),
        )
    }

    @Test
    fun `common shorthands name their systems`() {
        assertEquals(listOf("sms"), systems.aliasesFor("mastersystem"))
        assertEquals(listOf("pce", "tg16", "turbografx16"), systems.aliasesFor("pcengine"))
    }
}
