package com.github.libretube.api

import android.content.Context
import android.util.Log
import com.github.libretube.R
import com.github.libretube.api.obj.Playlist
import com.github.libretube.api.obj.PlaylistId
import com.github.libretube.api.obj.Playlists
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.YOUTUBE_FRONTEND_URL
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.LocalPlaylist
import com.github.libretube.enums.PlaylistType
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.toLocalPlaylistItem
import com.github.libretube.extensions.toStreamItem
import com.github.libretube.extensions.toastFromMainThread
import com.github.libretube.obj.ImportPlaylist
import com.github.libretube.util.PreferenceHelper
import com.github.libretube.util.ProxyHelper
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException

object PlaylistsHelper {
    private val pipedPlaylistRegex =
        "[\\da-fA-F]{8}-[\\da-fA-F]{4}-[\\da-fA-F]{4}-[\\da-fA-F]{4}-[\\da-fA-F]{12}".toRegex()

    private val token get() = PreferenceHelper.getToken()

    val loggedIn: Boolean get() = token.isNotEmpty()

    suspend fun getPlaylists(): List<Playlists> = withContext(Dispatchers.IO) {
        if (loggedIn) {
            RetrofitInstance.authApi.getUserPlaylists(token)
        } else {
            DatabaseHolder.Database.localPlaylistsDao().getAll()
                .map {
                    Playlists(
                        id = it.playlist.id.toString(),
                        name = it.playlist.name,
                        thumbnail = ProxyHelper.rewriteUrl(it.playlist.thumbnailUrl),
                        videos = it.videos.size.toLong()
                    )
                }
        }
    }

    suspend fun getPlaylist(playlistId: String): Playlist {
        // load locally stored playlists with the auth api
        return when (getPrivatePlaylistType(playlistId)) {
            PlaylistType.PRIVATE -> RetrofitInstance.authApi.getPlaylist(playlistId)
            PlaylistType.PUBLIC -> RetrofitInstance.api.getPlaylist(playlistId)
            PlaylistType.LOCAL -> {
                val relation = DatabaseHolder.Database.localPlaylistsDao().getAll()
                    .first { it.playlist.id.toString() == playlistId }
                return Playlist(
                    name = relation.playlist.name,
                    thumbnailUrl = ProxyHelper.rewriteUrl(relation.playlist.thumbnailUrl),
                    videos = relation.videos.size,
                    relatedStreams = relation.videos.map { it.toStreamItem() }
                )
            }
        }
    }

    suspend fun createPlaylist(
        playlistName: String,
        appContext: Context
    ): String? {
        if (!loggedIn) {
            val playlist = LocalPlaylist(name = playlistName, thumbnailUrl = "")
            DatabaseHolder.Database.localPlaylistsDao().createPlaylist(playlist)
            return DatabaseHolder.Database.localPlaylistsDao().getAll()
                .last().playlist.id.toString()
        } else {
            val response = try {
                RetrofitInstance.authApi.createPlaylist(token, Playlists(name = playlistName))
            } catch (e: IOException) {
                appContext.toastFromMainThread(R.string.unknown_error)
                return null
            } catch (e: HttpException) {
                Log.e(TAG(), e.toString())
                appContext.toastFromMainThread(R.string.server_error)
                return null
            }
            if (response.playlistId != null) {
                appContext.toastFromMainThread(R.string.playlistCreated)
            }
            return response.playlistId
        }
    }

    suspend fun addToPlaylist(playlistId: String, vararg videos: StreamItem): Boolean {
        if (!loggedIn) {
            val localPlaylist = DatabaseHolder.Database.localPlaylistsDao().getAll()
                .first { it.playlist.id.toString() == playlistId }

            for (video in videos) {
                val localPlaylistItem = video.toLocalPlaylistItem(playlistId)
                // avoid duplicated videos in a playlist
                DatabaseHolder.Database.localPlaylistsDao()
                    .deletePlaylistItemsByVideoId(playlistId, localPlaylistItem.videoId)

                // add the new video to the database
                DatabaseHolder.Database.localPlaylistsDao().addPlaylistVideo(localPlaylistItem)

                val playlist = localPlaylist.playlist
                if (playlist.thumbnailUrl == "") {
                    // set the new playlist thumbnail URL
                    localPlaylistItem.thumbnailUrl?.let {
                        playlist.thumbnailUrl = it
                        DatabaseHolder.Database.localPlaylistsDao().updatePlaylist(playlist)
                    }
                }
            }
            return true
        }

        val playlist = PlaylistId(playlistId, videoIds = videos.map { it.url!!.toID() })
        return RetrofitInstance.authApi.addToPlaylist(token, playlist).message == "ok"
    }

    suspend fun renamePlaylist(playlistId: String, newName: String): Boolean {
        return if (!loggedIn) {
            val playlist = DatabaseHolder.Database.localPlaylistsDao().getAll()
                .first { it.playlist.id.toString() == playlistId }.playlist
            playlist.name = newName
            DatabaseHolder.Database.localPlaylistsDao().updatePlaylist(playlist)
            true
        } else {
            val playlist = PlaylistId(playlistId, newName = newName)
            RetrofitInstance.authApi.renamePlaylist(token, playlist).playlistId != null
        }
    }

    suspend fun removeFromPlaylist(playlistId: String, index: Int) {
        if (!loggedIn) {
            val transaction = DatabaseHolder.Database.localPlaylistsDao().getAll()
                .first { it.playlist.id.toString() == playlistId }
            DatabaseHolder.Database.localPlaylistsDao().removePlaylistVideo(
                transaction.videos[index]
            )
            if (transaction.videos.size > 1) {
                if (index == 0) {
                    transaction.videos[1].thumbnailUrl?.let {
                        transaction.playlist.thumbnailUrl = it
                    }
                    DatabaseHolder.Database.localPlaylistsDao().updatePlaylist(transaction.playlist)
                }
                return
            }
            // remove thumbnail if playlist now empty
            transaction.playlist.thumbnailUrl = ""
            DatabaseHolder.Database.localPlaylistsDao().updatePlaylist(transaction.playlist)
        } else {
            val playlist = PlaylistId(playlistId = playlistId, index = index)
            RetrofitInstance.authApi.removeFromPlaylist(PreferenceHelper.getToken(), playlist)
        }
    }

    suspend fun importPlaylists(appContext: Context, playlists: List<ImportPlaylist>) {
        for (playlist in playlists) {
            val playlistId = createPlaylist(playlist.name!!, appContext) ?: continue
            // if logged in, add the playlists by their ID via an api call
            val success: Boolean = if (loggedIn) {
                addToPlaylist(
                    playlistId,
                    *playlist.videos.map {
                        StreamItem(url = it)
                    }.toTypedArray()
                )
            } else {
                // if not logged in, all video information needs to become fetched manually
                try {
                    val streamItems = playlist.videos.map {
                        RetrofitInstance.api.getStreams(it).toStreamItem(it)
                    }
                    addToPlaylist(playlistId, *streamItems.toTypedArray())
                } catch (e: Exception) {
                    false
                }
            }
            appContext.toastFromMainThread(
                if (success) R.string.importsuccess else R.string.server_error
            )
        }
    }

    suspend fun exportPlaylists(): List<ImportPlaylist> = withContext(Dispatchers.IO) {
        getPlaylists()
            .map { async { getPlaylist(it.id!!) } }
            .awaitAll()
            .map {
                val videos = it.relatedStreams.map { item ->
                    "$YOUTUBE_FRONTEND_URL/watch?v=${item.url!!.toID()}"
                }
                ImportPlaylist(it.name, "playlist", "private", videos)
            }
    }

    fun clonePlaylist(context: Context, playlistId: String) {
        val appContext = context.applicationContext
        if (!loggedIn) {
            CoroutineScope(Dispatchers.IO).launch {
                val playlist = try {
                    RetrofitInstance.api.getPlaylist(playlistId)
                } catch (e: Exception) {
                    appContext.toastFromMainThread(R.string.server_error)
                    return@launch
                }
                val newPlaylist = createPlaylist(playlist.name ?: "Unknown name", appContext)
                newPlaylist ?: return@launch

                addToPlaylist(newPlaylist, *playlist.relatedStreams.toTypedArray())

                var nextPage = playlist.nextpage
                while (nextPage != null) {
                    nextPage = try {
                        RetrofitInstance.api.getPlaylistNextPage(playlistId, nextPage).apply {
                            addToPlaylist(newPlaylist, *relatedStreams.toTypedArray())
                        }.nextpage
                    } catch (e: Exception) {
                        return@launch
                    }
                }
            }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val response = try {
                RetrofitInstance.authApi.clonePlaylist(
                    token,
                    PlaylistId(playlistId)
                )
            } catch (e: Exception) {
                Log.e(TAG(), e.toString())
                return@launch
            }
            appContext?.toastFromMainThread(
                if (response.playlistId != null) R.string.playlistCloned else R.string.server_error
            )
        }
    }

    fun getPrivatePlaylistType(): PlaylistType {
        return if (loggedIn) PlaylistType.PRIVATE else PlaylistType.LOCAL
    }

    private fun getPrivatePlaylistType(playlistId: String): PlaylistType {
        if (playlistId.all { it.isDigit() }) return PlaylistType.LOCAL
        if (playlistId.matches(pipedPlaylistRegex)) return PlaylistType.PRIVATE
        return PlaylistType.PUBLIC
    }
}
