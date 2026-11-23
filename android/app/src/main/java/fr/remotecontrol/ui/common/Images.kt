package fr.remotecontrol.ui.common

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import fr.remotecontrol.ui.theme.RcColors
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

enum class AssetSize(val wire: String) { Thumb("thumb"), Tv("tv"), Orig("orig") }

private const val OFFLINE_BASE = "http://offline.invalid/"

fun assetUrl(base: HttpUrl?, hash: String, size: AssetSize): String =
    "${base ?: OFFLINE_BASE}api/assets/$hash/${size.wire}.jpg"

/** Clé de cache disque et mémoire, indépendante de l'hôte : les images sont immuables. */
fun assetCacheKey(hash: String, size: AssetSize): String = "asset/$hash/${size.wire}"

/** Adresse du serveur connecté, fournie à la racine par MainActivity. */
val LocalServerBaseUrl = staticCompositionLocalOf<HttpUrl?> { null }

@Composable
fun Poster(hash: String?, size: AssetSize, modifier: Modifier) {
    if (hash == null) {
        Box(modifier.background(RcColors.surface2))
        return
    }
    val base = LocalServerBaseUrl.current
    val key = assetCacheKey(hash, size)
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(assetUrl(base, hash, size))
            .diskCacheKey(key)
            .memoryCacheKey(key)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.TopCenter,
        modifier = modifier.background(RcColors.surface2),
    )
}

fun buildImageLoader(context: Context, okHttp: OkHttpClient): ImageLoader =
    ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { okHttp })) }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("images"))
                .maxSizeBytes(300L * 1024 * 1024)
                .build()
        }
        .build()
