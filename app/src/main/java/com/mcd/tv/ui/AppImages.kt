package com.mcd.tv.ui

import android.content.Context
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * One image loader for the whole app, sized for Fire TV sticks with 1 GB of RAM.
 *  - JPEGs (TMDB posters and backdrops) decode as RGB565: half the memory of full color. Images with
 *    transparency (logos, PNGs) keep full color.
 *  - Memory cache: 15% of the app's memory. Disk cache: 64 MB in the app's cache folder.
 * Every AsyncImage and Context.imageLoader uses it.
 */
object AppImages {
    private const val DISK_BYTES = 64L * 1024 * 1024
    @Volatile private var installed = false

    /** Call once from MainActivity.onCreate, before any image loads. Later calls do nothing. */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        Coil.setImageLoader(
            object : ImageLoaderFactory {
                override fun newImageLoader(): ImageLoader =
                    ImageLoader.Builder(app)
                        .allowRgb565(true)
                        .memoryCache { MemoryCache.Builder(app).maxSizePercent(0.15).build() }
                        .diskCache { DiskCache.Builder().directory(app.cacheDir.resolve("image_cache")).maxSizeBytes(DISK_BYTES).build() }
                        .build()
            },
        )
    }
}
