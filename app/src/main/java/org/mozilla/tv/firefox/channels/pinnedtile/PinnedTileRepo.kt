/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.tv.firefox.channels.pinnedtile

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LiveDataReactiveStreams
import io.reactivex.BackpressureStrategy
import io.reactivex.Observable
import io.reactivex.subjects.BehaviorSubject
import org.json.JSONArray
import org.mozilla.tv.firefox.channels.BundleType
import org.mozilla.tv.firefox.channels.BundleTilesStore
import java.util.UUID

private const val CUSTOM_SITES_LIST = "customSitesList"
private const val PREF_HOME_TILES = "homeTiles"

/**
 * Pinned Tile Repository.
 * This class manages and persists pinned tiles data. It should not be aware of View scope.
 *
 * @property applicationContext used to access [SharedPreferences] and [assets] for bundled tiles
 * @constructor loads the initial [_pinnedTiles] (a combination of custom and bundled tiles)
 */
class PinnedTileRepo(
    private val applicationContext: Context,
    private val bundleTilesStore: BundleTilesStore
) {
    private val _pinnedTiles: BehaviorSubject<LinkedHashMap<String, PinnedTile>> =
            BehaviorSubject.create()
    val pinnedTiles: Observable<LinkedHashMap<String, PinnedTile>> = _pinnedTiles.hide()

    val isEmpty: Observable<Boolean> = _pinnedTiles.map { it.size == 0 }
            .distinctUntilChanged()

    @Deprecated(message = "Use PinnedTileRepo.pinnedTiles for new code")
    val legacyPinnedTiles = LiveDataReactiveStreams
            .fromPublisher(pinnedTiles.toFlowable(BackpressureStrategy.LATEST))

    // Persist custom & bundled tiles size for telemetry
    var customTilesSize = 0
    var bundledTilesSize = 0

    private val _sharedPreferences: SharedPreferences = applicationContext.getSharedPreferences(PREF_HOME_TILES, Context.MODE_PRIVATE)

    init {
        _pinnedTiles.onNext(loadTilesCache())
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun loadTilesCache(
        bundledTiles: LinkedHashMap<String, BundledPinnedTile> = loadBundledTilesCache(),
        customTiles: LinkedHashMap<String, CustomPinnedTile> = loadCustomTilesCache()
    ): LinkedHashMap<String, PinnedTile> {
        val featuredIds = setOf("youtube", "googleVideo")

        val featuredBundledTiles = bundledTiles.filter { it.value.id in featuredIds }
        val unfeaturedBundledTiles = bundledTiles.filter { it.value.id !in featuredIds }

        val pinnedTiles = linkedMapOf<String, PinnedTile>().apply {
            putAll(featuredBundledTiles)
            putAll(customTiles)
            putAll(unfeaturedBundledTiles)
        }

        return pinnedTiles
    }

    fun addPinnedTile(url: String, screenshot: Bitmap?) {
        val newPinnedTile = CustomPinnedTile(url, "custom", UUID.randomUUID()) // TODO: titles
        // This method does some dangerous mutation in place.  Be careful when making changes, and
        // if you have the time, please clean this up
        if (_pinnedTiles.value?.put(url, newPinnedTile) != null) return
        persistCustomTiles()

        if (screenshot != null) {
            PinnedTileScreenshotStore.saveAsync(applicationContext, newPinnedTile.id, screenshot)
        }
        ++customTilesSize

        // We reload tiles from the DB in order to avoid duplicating ordering logic in loadTilesCache
        _pinnedTiles.onNext(loadTilesCache())
    }

    /**
     * returns tile id of a Bundled tile or null if
     * it doesn't exist in the cache
     */
    @UiThread
    fun removePinnedTile(url: String): String? {
        val tileToRemove = _pinnedTiles.value?.remove(url) ?: return null
        _pinnedTiles.onNext(_pinnedTiles.value!!)

        when (tileToRemove) {
            is BundledPinnedTile -> {
                bundleTilesStore.addBundleTileToBlackList(BundleType.PINNED_TILES, tileToRemove.id)
                --bundledTilesSize
            }
            is CustomPinnedTile -> {
                persistCustomTiles()
                PinnedTileScreenshotStore.removeAsync(applicationContext, tileToRemove.id)
                --customTilesSize
            }
        }

        return tileToRemove.idToString()
    }

    private fun persistCustomTiles() {
        val tilesJSONArray = JSONArray()
        for (tile in _pinnedTiles.value!!.values) {
            if (tile is CustomPinnedTile) tilesJSONArray.put(tile.toJSONObject())
        }

        _sharedPreferences.edit().putString(CUSTOM_SITES_LIST, tilesJSONArray.toString()).apply()
    }

    private fun loadBundledTilesCache(): LinkedHashMap<String, BundledPinnedTile> {
        val tilesJSONList = bundleTilesStore.getBundledTiles(BundleType.PINNED_TILES)
        val lhm = LinkedHashMap<String, BundledPinnedTile>(tilesJSONList.size)
        for (jsonObject in tilesJSONList) {
            val tile = BundledPinnedTile.fromJSONObject(jsonObject)
            lhm[tile.url] = tile
        }
        bundledTilesSize = lhm.size

        return lhm
    }

    private fun loadCustomTilesCache(): LinkedHashMap<String, CustomPinnedTile> {
        val tilesJSONArray = JSONArray(_sharedPreferences.getString(CUSTOM_SITES_LIST, "[]"))
        val lhm = LinkedHashMap<String, CustomPinnedTile>()
        for (i in 0 until tilesJSONArray.length()) {
            val tileJSON = tilesJSONArray.getJSONObject(i)
            val tile = CustomPinnedTile.fromJSONObject(tileJSON)
            lhm.put(tile.url, tile)
        }
        customTilesSize = lhm.size

        return lhm
    }
}
