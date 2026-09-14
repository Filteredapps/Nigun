/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.metrolist.innertube.utils.YouTubeBlockedSongFilter
import com.metrolist.music.BuildConfig
import com.metrolist.music.constants.RemoteBlockedSongsCacheKey
import com.metrolist.music.constants.RemoteBlockedSongsLastUpdatedKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

object RemoteBlockedSongsUpdater {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

    private val videoIdRegex = Regex("^[A-Za-z0-9_-]{11}$")

    suspend fun initialize(context: Context) {
        loadCachedList(context)
        refreshFromRemote(context)
    }

    suspend fun loadCachedList(context: Context): Int =
        withContext(Dispatchers.IO) {
            val cachedRaw = context.dataStore.data.first()[RemoteBlockedSongsCacheKey].orEmpty()
            val ids = parseBlockedSongIds(cachedRaw)
            YouTubeBlockedSongFilter.replaceBlockedSongIds(ids)
            ids.size
        }

    suspend fun refreshFromRemote(context: Context): Int =
        withContext(Dispatchers.IO) {
            val url = BuildConfig.BLOCKED_SONGS_URL.trim()
            if (url.isBlank()) return@withContext YouTubeBlockedSongFilter.activeBlockedSongCount

            try {
                val raw = download(url)
                val meaningfulLines = raw.lineSequence()
                    .map { it.substringBefore('#').trim() }
                    .filter { it.isNotBlank() && !it.startsWith("//") }
                    .toList()
                val ids = parseBlockedSongIds(raw)

                // Keep last-known-good data if a non-empty file is malformed.
                if (meaningfulLines.any { !videoIdRegex.matches(it) }) {
                    Timber.w("Remote blocked songs file contained an invalid YouTube ID; retaining the last valid list")
                    return@withContext YouTubeBlockedSongFilter.activeBlockedSongCount
                }

                YouTubeBlockedSongFilter.replaceBlockedSongIds(ids)
                context.dataStore.edit { settings ->
                    settings[RemoteBlockedSongsCacheKey] = raw
                    settings[RemoteBlockedSongsLastUpdatedKey] = System.currentTimeMillis()
                }
                ids.size
            } catch (e: Exception) {
                Timber.e(e, "Failed to refresh remote blocked songs")
                reportException(e)
                YouTubeBlockedSongFilter.activeBlockedSongCount
            }
        }

    fun parseBlockedSongIds(raw: String): Set<String> =
        raw.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotBlank() && !it.startsWith("//") }
            .filter(videoIdRegex::matches)
            .toSet()

    private fun download(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Blocked songs request failed: HTTP ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }
}
