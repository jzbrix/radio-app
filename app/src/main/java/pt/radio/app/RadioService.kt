package pt.radio.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import com.google.common.util.concurrent.MoreExecutors
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Leitor de rádio em segundo plano. Toca para a app no telemóvel e para o Android Auto,
 * mostra a notificação com os controlos e entrega ao carro a lista de rádios.
 */
class RadioService : MediaLibraryService() {

    companion object {
        @Volatile var instance: RadioService? = null
        const val WORKER = "https://radio-meta.foot4.workers.dev"
    }

    private data class Ep(val url: String, val title: String, val pod: String, val art: String)
    private val episodes = ConcurrentHashMap<String, Ep>()
    private val io = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool())
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("radio", Context.MODE_PRIVATE) }

    // guarda onde ficaste em cada episódio, para retomar
    private val saver = object : Runnable {
        override fun run() {
            savePos()
            if (player.isPlaying) ui.postDelayed(this, 5000)
        }
    }

    private fun savePos() {
        val item = player.currentMediaItem ?: return
        if (!item.mediaId.startsWith("ep:")) return
        val pos = player.currentPosition
        if (pos > 0) prefs.edit().putLong("pos:" + item.mediaId, pos).apply()
    }

    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null

    // rádios escolhidas no carro: endereços alternativos se um falhar
    private var fallbackId: String? = null
    private var fallbackUrls: List<String> = emptyList()
    private var fallbackIndex = 0
    private var lastNow = ""

    override fun onCreate() {
        super.onCreate()
        instance = this
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                tryNextUrl()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                ui.removeCallbacks(saver)
                if (isPlaying) ui.postDelayed(saver, 5000) else savePos()
            }
        })
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(open)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        instance = null
        ui.removeCallbacks(saver)
        io.shutdown()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** A app no telemóvel diz qual a música a tocar: aparece na notificação e no ecrã do carro. */
    fun updateNow(title: String, artist: String, art: String) {
        val key = "$title|$artist|$art"
        if (key == lastNow || player.mediaItemCount == 0) return
        lastNow = key
        val item = player.currentMediaItem ?: return
        val st = Stations.find(this, item.mediaId)
        val name = st?.name ?: (item.mediaMetadata.station?.toString() ?: "")
        val logo = st?.logo ?: ""
        val meta = Stations.metadata(name, logo, title, artist, art)
        player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setMediaMetadata(meta).build())
    }

    private fun tryNextUrl() {
        val id = fallbackId ?: return
        val cur = player.currentMediaItem ?: return
        if (cur.mediaId != id || fallbackIndex >= fallbackUrls.size - 1) return
        fallbackIndex++
        player.setMediaItem(Stations.playable(id, fallbackUrls[fallbackIndex], cur.mediaMetadata))
        player.prepare()
        player.play()
    }

    /** Transforma o pedido (do carro ou do telemóvel) num item com endereço para tocar. */
    private fun resolve(item: MediaItem): MediaItem {
        lastNow = ""
        val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
        if (uri != null) {
            fallbackId = null
            return Stations.playable(item.mediaId, uri.toString(), item.mediaMetadata)
        }
        episodes[item.mediaId]?.let { e ->
            fallbackId = null
            return Stations.playable(item.mediaId, e.url, Stations.episodeMeta(e.title, e.pod, e.art))
        }
        val st = Stations.find(this, item.mediaId) ?: return item
        if (st.urls.isEmpty()) return item
        fallbackId = st.id
        fallbackUrls = st.urls
        fallbackIndex = 0
        return Stations.playable(st.id, st.urls[0], Stations.metadata(st.name, st.logo))
    }

    /** Episódios mais recentes de um podcast seguido (para o Android Auto). */
    private fun fetchEpisodes(podId: String): List<MediaItem> {
        val st = Stations.find(this, "pod:$podId")
        val feed = st?.urls?.firstOrNull() ?: ""
        val u = WORKER + "/pod?id=" + podId + (if (feed.isNotEmpty()) "&feed=" + URLEncoder.encode(feed, "UTF-8") else "")
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        val txt = conn.inputStream.bufferedReader().use { it.readText() }
        val j = org.json.JSONObject(txt)
        val name = j.optString("name", st?.name ?: "")
        val art = j.optString("art", st?.logo ?: "")
        val arr = j.getJSONArray("episodes")
        return (0 until minOf(arr.length(), 40)).map { i ->
            val e = arr.getJSONObject(i)
            val id = "ep:" + e.getString("id")
            val ea = e.optString("art", "").ifEmpty { art }
            val title = e.optString("title", "")
            episodes[id] = Ep(e.getString("url"), title, name, ea)
            MediaItem.Builder().setMediaId(id).setMediaMetadata(Stations.episodeMeta(title, name, ea)).build()
        }
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(Stations.root(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId.startsWith("pod:")) {
                val podId = parentId.removePrefix("pod:")
                return io.submit<LibraryResult<ImmutableList<MediaItem>>> {
                    try {
                        LibraryResult.ofItemList(ImmutableList.copyOf(fetchEpisodes(podId)), params)
                    } catch (e: Exception) {
                        LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
                    }
                }
            }
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(Stations.children(this@RadioService, parentId)), params)
            )
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            episodes[mediaId]?.let { e ->
                val epItem = MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(Stations.episodeMeta(e.title, e.pod, e.art)).build()
                return Futures.immediateFuture(LibraryResult.ofItem(epItem, null))
            }
            val st = Stations.find(this@RadioService, mediaId)
                ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            val item = MediaItem.Builder().setMediaId(st.id).setMediaMetadata(Stations.metadata(st.name, st.logo)).build()
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { resolve(it) }.toMutableList())

        // episódios começam onde ficaste
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val first = mediaItems.firstOrNull()
            var pos = startPositionMs
            if (first != null && first.mediaId.startsWith("ep:")) {
                val asked = first.requestMetadata.extras?.getLong("pos", 0L) ?: 0L
                pos = if (asked > 0) asked else prefs.getLong("pos:" + first.mediaId, 0L)
                if (pos <= 0) pos = C.TIME_UNSET
            }
            val items = mediaItems.map { resolve(it) }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(items, if (startIndex == C.INDEX_UNSET) 0 else startIndex, pos)
            )
        }
    }
}
