package com.nuvio.app.core.ui

import coil3.ImageLoader

internal expect fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder

/** Platform decoders for the dedicated badge loader, which does not share the app loader's components. */
internal expect fun ImageLoader.Builder.configurePlatformBadgeImageLoader(): ImageLoader.Builder

/**
 * Returns `true` when the platform already provides a singleton [ImageLoader]
 * (e.g. via `Application` implementing `SingletonImageLoader.Factory` on Android).
 * When `true`, the composable [setSingletonImageLoaderFactory] must be skipped
 * so it doesn't overwrite the platform-provided loader.
 */
internal expect val platformProvidesImageLoader: Boolean