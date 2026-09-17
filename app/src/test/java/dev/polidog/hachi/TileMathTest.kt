package dev.polidog.hachi

import org.junit.Assert.assertEquals
import org.junit.Test

class TileMathTest {
    @Test
    fun matchesKnownTileCoordinates() {
        // Miyazaki at zoom 8 is tile 221/104 -- the pair used to fetch the tiles by hand.
        assertEquals(221, tileX(131.4375, 8).toInt())
        assertEquals(104, tileY(31.90, 8).toInt())
    }

    @Test
    fun theOriginIsTheMiddleOfTheWorld() {
        // Zoom 0 is a single tile spanning the globe, so null island sits at its centre.
        assertEquals(0.5, tileX(0.0, 0), 1e-9)
        assertEquals(0.5, tileY(0.0, 0), 1e-9)
        // The antimeridian sits at either edge of that one tile.
        assertEquals(0.0, tileX(-180.0, 0), 1e-9)
        assertEquals(1.0, tileX(180.0, 0), 1e-9)
        // One level in, the world is a 2x2 grid and the centre moves with it.
        assertEquals(1.0, tileX(0.0, 1), 1e-9)
        assertEquals(1.0, tileY(0.0, 1), 1e-9)
    }

    @Test
    fun zoomingInQuadruplesTheGrid() {
        // The same point, one zoom level deeper, lands in the corresponding child tile.
        val x = tileX(139.7671, 8)
        val y = tileY(35.6812, 8)
        assertEquals(x * 2, tileX(139.7671, 9), 1e-9)
        assertEquals(y * 2, tileY(35.6812, 9), 1e-9)
    }
}
