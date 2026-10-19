package com.nuvio.app.features.home

import com.nuvio.app.features.details.MetaDetailsParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StillArtworkParsingTest {

    @Test
    fun `catalog meta pairs each still with the animated artwork it was sent with`() {
        val item = HomeCatalogParser.parseCatalogResponse(
            """
            {
              "metas": [{
                "id": "tt1", "type": "movie", "name": "One",
                "poster": "https://cdn/poster.webp", "stillPoster": "https://cdn/poster-still.jpg",
                "background": "https://cdn/bg.webp", "stillBackground": "https://cdn/bg-still.jpg"
              }]
            }
            """.trimIndent(),
        ).items.single()

        assertEquals(StillArtwork("https://cdn/poster.webp", "https://cdn/poster-still.jpg"), item.stillPoster)
        assertEquals(StillArtwork("https://cdn/bg.webp", "https://cdn/bg-still.jpg"), item.stillBackground)
    }

    @Test
    fun `addons without still fields behave exactly as before`() {
        val item = HomeCatalogParser.parseCatalogResponse(
            """{"metas":[{"id":"tt1","type":"movie","name":"One","poster":"https://cdn/p.webp"}]}""",
        ).items.single()

        assertNull(item.stillPoster)
        assertNull(item.stillBackground)
    }

    @Test
    fun `a still without the artwork it belongs to is ignored`() {
        val item = HomeCatalogParser.parseCatalogResponse(
            """
            {"metas":[{"id":"tt1","type":"movie","name":"One",
              "stillPoster":"https://cdn/poster-still.jpg",
              "poster":"https://cdn/same.jpg","stillBackground":"https://cdn/same.jpg","background":"https://cdn/same.jpg"}]}
            """.trimIndent(),
        ).items.single()

        // No poster to pair the first still with would be a bug in the addon; a still equal to its own
        // animated URL adds nothing.
        assertNull(item.stillBackground)
    }

    @Test
    fun `a still only applies to the exact animated url it was paired with`() {
        val still = StillArtwork(url = "https://cdn/poster.webp", still = "https://cdn/poster-still.jpg")

        assertEquals("https://cdn/poster-still.jpg", still.stillFor("https://cdn/poster.webp"))
        // Replaced by a custom poster or TMDB artwork: the still must not be misapplied.
        assertNull(still.stillFor("https://image.tmdb.org/t/p/w500/other.jpg"))
        assertNull(still.stillFor(null))
        val missing: StillArtwork? = null
        assertNull(missing.stillFor("https://cdn/poster.webp"))
    }

    @Test
    fun `video thumbnails pair their still with the animated thumbnail`() {
        val videos = MetaDetailsParser.parse(
            """
            {"meta":{"id":"tt1","type":"series","name":"S","videos":[
              {"id":"tt1:1:1","title":"E1","season":1,"episode":1,
               "thumbnail":"https://cdn/e1.webp","stillThumbnail":"https://cdn/e1-still.jpg"},
              {"id":"tt1:1:2","title":"E2","season":1,"episode":2,"thumbnail":"https://cdn/e2.jpg"}
            ]}}
            """.trimIndent(),
        ).videos

        assertEquals(StillArtwork("https://cdn/e1.webp", "https://cdn/e1-still.jpg"), videos[0].stillThumbnail)
        assertNull(videos[1].stillThumbnail)
    }
}
