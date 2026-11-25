package pt.radio.app

import android.app.PendingIntent
import android.content.Intent
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
        val st = Stations.find(this, item.mediaId) ?: return item
        if (st.urls.isEmpty()) return item
        fallbackId = st.id
        fallbackUrls = st.urls
        fallbackIndex = 0
        return Stations.playable(st.id, st.urls[0], Stations.metadata(st.name, st.logo))
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
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(Stations.children(this@RadioService, parentId)), params)
            )

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
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
    }
}
