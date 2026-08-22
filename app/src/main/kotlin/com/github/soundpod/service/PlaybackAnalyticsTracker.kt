package com.github.soundpod.service

import android.database.SQLException
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.github.soundpod.db
import com.github.soundpod.models.Album
import com.github.soundpod.models.Artist
import com.github.soundpod.models.Event
import com.github.soundpod.models.Song
import com.github.soundpod.models.SongAlbumMap
import com.github.soundpod.models.SongArtistMap
import com.github.soundpod.query

@UnstableApi
class PlaybackAnalyticsTracker(
    private val player: Player
) : Player.Listener {

    var currentMediaId by mutableStateOf<String?>(null)
        private set
    var accumulatedPlayMs by mutableLongStateOf(0L)
        private set
    var lastCommittedSongId by mutableStateOf<String?>(null)
        private set
    var lastCommittedPlayMs by mutableLongStateOf(0L)
        private set
    var eventCount by mutableLongStateOf(0L)
        private set

    private var playStartElapsedMs: Long = 0L
    private var currentTitle: String = ""
    private var currentArtist: String? = null
    private var currentArtwork: String? = null
    private var currentArtistIds: List<String> = emptyList()
    private var currentArtistNames: List<String> = emptyList()
    private var currentAlbumId: String? = null
    private var currentAlbumTitle: String? = null

    init {
        player.addListener(this)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        commitPending()
        currentMediaId = mediaItem?.mediaId
        currentTitle = mediaItem?.mediaMetadata?.title?.toString() ?: ""
        currentArtist = mediaItem?.mediaMetadata?.artist?.toString()
        currentArtwork = mediaItem?.mediaMetadata?.artworkUri?.toString()

        val extras = mediaItem?.mediaMetadata?.extras
        currentArtistIds = extras?.getStringArrayList("artistIds")?.toList() ?: emptyList()
        currentArtistNames = extras?.getStringArrayList("artistNames")?.toList() ?: emptyList()
        currentAlbumId = extras?.getString("albumId")
        currentAlbumTitle = mediaItem?.mediaMetadata?.albumTitle?.toString()

        accumulatedPlayMs = 0L
        playStartElapsedMs = if (player.isPlaying) SystemClock.elapsedRealtime() else 0L
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            if (playStartElapsedMs == 0L) {
                playStartElapsedMs = SystemClock.elapsedRealtime()
            }
        } else {
            if (playStartElapsedMs > 0L) {
                accumulatedPlayMs += SystemClock.elapsedRealtime() - playStartElapsedMs
                playStartElapsedMs = 0L
            }
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            commitPending()
        }
    }

    fun release() {
        commitPending()
        player.removeListener(this)
    }

    private fun commitPending() {
        if (playStartElapsedMs > 0L) {
            accumulatedPlayMs += SystemClock.elapsedRealtime() - playStartElapsedMs
            playStartElapsedMs = 0L
        }
        val mediaId = currentMediaId ?: return
        val playMs = accumulatedPlayMs
        if (playMs <= 0L) return

        if (playMs > 5000) {
            query {
                db.incrementTotalPlayTimeMs(mediaId, playMs)
            }
        }
        if (playMs > 30000) {
            val song = Song(
                id = mediaId,
                title = currentTitle,
                artistsText = currentArtist,
                durationText = null,
                thumbnailUrl = currentArtwork
            )
            query {
                try {
                    db.insert(song)
                    db.insert(
                        Event(
                            songId = mediaId,
                            timestamp = System.currentTimeMillis(),
                            playTime = playMs
                        )
                    )

                    if (currentArtistIds.isNotEmpty()) {
                        val artists = currentArtistIds.mapIndexed { i, browseId ->
                            Artist(id = browseId, name = currentArtistNames.getOrNull(i))
                        }
                        db.insertArtists(artists)
                        val maps = currentArtistIds.map { SongArtistMap(songId = mediaId, artistId = it) }
                        db.insertSongArtistMaps(maps)
                    }

                    if (currentAlbumId != null) {
                        val album = Album(id = currentAlbumId!!, title = currentAlbumTitle)
                        db.insertAlbums(listOf(album))
                        db.insertSongAlbumMaps(listOf(SongAlbumMap(songId = mediaId, albumId = currentAlbumId!!, position = null)))
                    }

                    lastCommittedSongId = mediaId
                    lastCommittedPlayMs = playMs
                    eventCount++
                } catch (_: SQLException) {
                }
            }
        }
        accumulatedPlayMs = 0L
    }
}
