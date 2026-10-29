package io.github.pawelwywiol.minervaweb.catalog

import kotlin.test.Test
import kotlin.test.assertEquals

class RegionsTest {
    private val noJapan = setOf("japan")

    @Test
    fun `a name is hidden only when every region it names is hidden`() {
        assertEquals(false, Regions.visible("Zelda (Japan).zip", noJapan))
        assertEquals(false, Regions.visible("Zelda (1987)(Nintendo)(JP).zip", noJapan))
        assertEquals(true, Regions.visible("Zelda (USA, Japan).zip", noJapan))
        assertEquals(true, Regions.visible("Zelda.zip", noJapan))
        assertEquals(true, Regions.visible("Japan Pro Golf (USA).zip", noJapan))
        assertEquals(false, Regions.visible("Clue USA (Europe).zip", setOf("europe")))
    }

    @Test
    fun `unknown region names are ignored`() {
        assertEquals(setOf("japan", "other"), Regions.parse("japan, OTHER,mars"))
    }
}
