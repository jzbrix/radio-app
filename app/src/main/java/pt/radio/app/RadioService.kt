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
        @Volatile var appVisible = false
        val sources = ConcurrentHashMap<String, Src>()
    }

    data class Src(val name: String, val code: String, val triton: String)

    // ---------- música a tocar, lida pelo próprio serviço (app em segundo plano ou Android Auto) ----------
    private val artCache = ConcurrentHashMap<String, String>()
    private var nowGen = 0
    private val nowLoop = object : Runnable {
        override fun run() {
            ui.removeCallbacks(this)
            val item = player.currentMediaItem
            if (item == null || item.mediaId.startsWith("ep:") || !player.playWhenReady || appVisible) return
            val id = item.mediaId
            val url = item.localConfiguration?.uri?.toString() ?: ""
            val st = Stations.find(this@RadioService, id)
            val src = sources[id] ?: Src(st?.name ?: "", st?.code ?: "", st?.triton ?: "")
            val g = ++nowGen
            io.execute {
                val r = try { fetchNow(src, url) } catch (e: Exception) { null }
                ui.post {
                    if (g != nowGen || player.currentMediaItem?.mediaId != id || appVisible) return@post
                    if (r != null) applyNow(r.first, r.second)
                    ui.postDelayed(this, 15000)
                }
            }
        }
    }

    fun kickNow() { ui.post { ui.removeCallbacks(nowLoop); ui.postDelayed(nowLoop, 1500) } }

    private fun get(u: String, timeout: Int = 12000): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) RadioPT")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun jingle(artist: String, name: String): Boolean {
        val a = artist.lowercase().trim()
        return a.isEmpty() || Regex("^(radio |rádio )?(comercial|m80|cidade|smooth|rayo)\\b").containsMatchIn(a) || a == name.lowercase().trim()
    }

    /** Devolve (título, artista); título vazio = sem música (locução, publicidade). null = não se sabe. */
    private fun fetchNow(src: Src, url: String): Pair<String, String>? {
        if (src.code.isNotEmpty()) {
            val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            f.timeZone = java.util.TimeZone.getTimeZone("Europe/London")
            val p = "events/" + src.code + "/" + URLEncoder.encode(f.format(java.util.Date()), "UTF-8") + "/20"
            val arr = org.json.JSONArray(get(WORKER + "/bauer?p=" + URLEncoder.encode(p, "UTF-8") + "&_=" + System.currentTimeMillis()))
            var best: org.json.JSONObject? = null; var bestAt = 0L
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val t = e.optString("nowPlayingTrack"); val a = e.optString("nowPlayingArtist")
                if (t.isEmpty() || jingle(a, src.name)) continue
                val at = f.parse(e.optString("nowPlayingTime"))?.time ?: continue
                if (at > bestAt) { bestAt = at; best = e }
            }
            val b = best ?: return Pair("", "")
            val dur = b.optString("nowPlayingDuration").toLongOrNull() ?: 240L
            return if (System.currentTimeMillis() < bestAt + (dur + 75) * 1000) Pair(b.optString("nowPlayingTrack"), b.optString("nowPlayingArtist")) else Pair("", "")
        }
        if (src.triton.isNotEmpty()) {
            val x = get("https://np.tritondigital.com/public/nowplaying?mountName=" + URLEncoder.encode(src.triton, "UTF-8") + "&numberToFetch=5&eventType=track&_=" + System.currentTimeMillis())
            val info = Regex("<nowplaying-info[\\s\\S]*?</nowplaying-info>").findAll(x)
            for (m in info) {
                fun prop(n: String) = Regex("<property name=\"" + n + "\"[^>]*>(?:<!\\[CDATA\\[)?([\\s\\S]*?)(?:\\]\\]>)?</property>").find(m.value)?.groupValues?.get(1)?.trim() ?: ""
                val t = tc(prop("cue_title")); val a = tc(prop("track_artist_name"))
                if (t.isEmpty() || jingle(a, src.name)) continue
                val start = prop("cue_time_start").toLongOrNull() ?: 0L
                val dur = (prop("cue_time_duration").toLongOrNull() ?: 240000L)
                return if (start == 0L || System.currentTimeMillis() < start + dur + 75000) Pair(t, a) else Pair("", "")
            }
            return Pair("", "")
        }
        if (url.contains(".m3u8")) return null
        val j = org.json.JSONObject(get(WORKER + "/meta?url=" + URLEncoder.encode(url, "UTF-8") + "&_=" + System.currentTimeMillis(), 15000))
        if (j.optBoolean("unchanged")) return null
        if (j.isNull("title")) return null
        return split(j.optString("title"))
    }

    private fun tc(x: String): String =
        if (x.isNotEmpty() && x == x.uppercase() && x.any { it.isLetter() })
            x.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } } else x

    private fun split(raw: String): Pair<String, String> {
        val r = raw.trim()
        if (r.isEmpty() || r.startsWith("<") || r.startsWith("{")) return Pair("", "")
        val i = r.indexOf(" - ")
        return if (i > 0) Pair(r.substring(i + 3).trim(), r.substring(0, i).trim()) else Pair(r, "")
    }

    private fun applyNow(title: String, artist: String) {
        if (title.isEmpty()) { updateNow("", "", ""); return }
        val key = "$artist|$title".lowercase()
        val known = artCache[key]
        updateNow(title, artist, known ?: "")
        if (known == null) io.execute {
            val art = try { findArt(artist, title) } catch (e: Exception) { "" }
            artCache[key] = art
            if (art.isNotEmpty()) ui.post { if (!appVisible) updateNow(title, artist, art) }
        }
    }

    private fun findArt(artist: String, title: String): String {
        val q = URLEncoder.encode("$artist $title".trim(), "UTF-8")
        try {
            val r = org.json.JSONObject(get("https://itunes.apple.com/search?media=music&entity=song&country=PT&limit=1&term=$q", 8000)).optJSONArray("results")
            val a = r?.optJSONObject(0)?.optString("artworkUrl100") ?: ""
            if (a.isNotEmpty()) return a.replace("100x100bb", "600x600bb")
        } catch (e: Exception) {}
        val d = org.json.JSONObject(get("https://api.deezer.com/search?limit=1&q=$q", 8000)).optJSONArray("data")
        return d?.optJSONObject(0)?.optJSONObject("album")?.optString("cover_xl") ?: ""
    }

    // músicas que vêm dentro do próprio som (ICY ou ID3)
    private fun inStream(md: androidx.media3.common.Metadata) {
        if (appVisible) return
        val id = player.currentMediaItem?.mediaId ?: return
        if (id.startsWith("ep:")) return
        val s = sources[id]
        if (s != null && (s.code.isNotEmpty() || s.triton.isNotEmpty())) return
        var t = ""; var a = ""
        for (i in 0 until md.length()) {
            val e = md.get(i)
            if (e is androidx.media3.extractor.metadata.icy.IcyInfo) { val p = split(e.title ?: ""); t = p.first; a = p.second }
            if (e is androidx.media3.extractor.metadata.id3.TextInformationFrame) {
                val v = e.values.firstOrNull() ?: ""
                if (e.id == "TIT2") t = v else if (e.id == "TPE1") a = v
            }
        }
        if (t.isNotEmpty()) applyNow(t, a)
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
                if (isPlaying && !appVisible) kickNow()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) { nowGen++; if (!appVisible) kickNow() }
            }

            override fun onMetadata(metadata: androidx.media3.common.Metadata) {
                inStream(metadata)
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
        ui.removeCallbacks(nowLoop)
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
