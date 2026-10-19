package com.nuvio.app.features.home

import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.catalog.CatalogTarget

/**
 * A still image an addon provides next to animated artwork. It only applies to the exact animated [url]
 * it was sent with, so artwork that is later replaced (custom poster URLs, TMDB artwork) never shows a
 * still that belongs to a different image.
 */
data class StillArtwork(
    val url: String,
    val still: String,
)

/** The still to show for [displayUrl], or null when it is not the animated image this still belongs to. */
fun StillArtwork?.stillFor(displayUrl: String?): String? =
    this?.takeIf { it.url == displayUrl }?.still

data class MetaPreview(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val banner: String? = null,
    val logo: String? = null,
    val posterShape: PosterShape = PosterShape.Poster,
    val description: String? = null,
    val releaseInfo: String? = null,
    val rawReleaseDate: String? = null,
    val popularity: Double? = null,
    val voteCount: Int? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    val rawPosterUrl: String? = null,
    val landscapePoster: String? = null,
    val rawLandscapePosterUrl: String? = null,
    /** Optional still for an animated [poster]; ignored by clients that do not know the field. */
    val stillPoster: StillArtwork? = null,
    /** Optional still for an animated `background` (shown on landscape cards). */
    val stillBackground: StillArtwork? = null,
)

fun MetaPreview.stableKey(): String = "$type:$id"

enum class PosterShape {
    Poster,
    Square,
    Landscape,
}

data class HomeCatalogSection(
    val key: String,
    val title: String,
    val subtitle: String,
    val addonName: String,
    val target: CatalogTarget,
    val items: List<MetaPreview>,
    val availableItemCount: Int = items.size,
    val hasMore: Boolean = false,
)

fun HomeCatalogSection.canOpenCatalog(previewLimit: Int): Boolean =
    availableItemCount > previewLimit || hasMore

data class HomeUiState(
    val isLoading: Boolean = false,
    val heroItems: List<MetaPreview> = emptyList(),
    val sections: List<HomeCatalogSection> = emptyList(),
    val errorMessage: String? = null,
)

internal fun shouldShowInitialHomeLoading(
    hasRenderableHomeRows: Boolean,
    addonManifestsLoading: Boolean,
    homeCatalogLoading: Boolean,
): Boolean = !hasRenderableHomeRows && (addonManifestsLoading || homeCatalogLoading)

internal fun shouldShowHomeHeroSlot(
    heroEnabled: Boolean,
    hasHeroItems: Boolean,
    isResolvingHeroSources: Boolean,
    hasRenderableHomeRows: Boolean,
): Boolean = heroEnabled && (hasHeroItems || isResolvingHeroSources || hasRenderableHomeRows)

internal data class CatalogRequest(
    val addon: ManagedAddon,
    val catalogId: String,
    val catalogName: String,
    val type: String,
    val supportsPagination: Boolean,
)
