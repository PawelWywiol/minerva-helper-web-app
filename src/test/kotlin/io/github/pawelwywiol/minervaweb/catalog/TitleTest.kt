package io.github.pawelwywiol.minervaweb.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TitleTest {
    @Test
    fun `versions of one game share a title whichever archive named them`() {
        assertEquals(
            titleOf("Legend of Zelda, The (Europe) (Rev 1).zip"),
            titleOf("The Legend of Zelda (1987)(Nintendo)(US).zip"),
        )
        assertEquals(titleOf("Alter Ego (USA).zip"), titleOf("Alter Ego v1.04 (2024-03-27)(Kusfo).zip"))
        assertEquals(
            titleOf("Super Mario Bros. 3 (Europe).zip"),
            titleOf("Super Mario Bros. 3 (1991-08-29)(Nintendo)(EU).zip"),
        )
        assertNotEquals(titleOf("Super Mario Bros. (Europe).zip"), titleOf("Super Mario Bros. 3 (Europe).zip"))
    }
}
