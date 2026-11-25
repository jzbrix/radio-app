package pt.radio.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import org.json.JSONObject

/** Lista de rádios que a app (parte web) envia, guardada para o Android Auto a poder mostrar mesmo com a app fechada. */
data class Station(val id: String, val name: String, val logo: String, val urls: List<String>, val code: String = "", val triton: String = "")
data class Section(val id: String, val title: String, val items: List<Station>)

object Stations {
    const val ROOT = "root"
    private const val PREFS = "radio"
    private const val KEY = "stations"

    fun save(ctx: Context, json: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json).apply()
    }

    fun sections(ctx: Context): List<Section> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONObject(raw).getJSONArray("sections")
            (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                val its = s.getJSONArray("items")
                Section(s.getString("id"), s.getString("title"), (0 until its.length()).map { j ->
                    val o = its.getJSONObject(j)
                    val u = o.optJSONArray("urls")
                    Station(
                        o.getString("id"), o.getString("name"), o.optString("logo", ""),
                        if (u == null) emptyList() else (0 until u.length()).map { k -> u.getString(k) },
                        o.optString("code", ""), o.optString("triton", "")
                    )
                })
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun find(ctx: Context, id: String): Station? =
        sections(ctx).asSequence().flatMap { it.items.asSequence() }.firstOrNull { it.id == id }

    fun root(): MediaItem = folder(ROOT, "Rádio")

    fun children(ctx: Context, parentId: String): List<MediaItem> {
        val secs = sections(ctx)
        if (parentId == ROOT) {
            return secs.filter { it.items.isNotEmpty() }.map { folder("sec:" + it.id, it.title) }
        }
        val sec = secs.firstOrNull { "sec:" + it.id == parentId } ?: return emptyList()
        return sec.items.map { if (it.id.startsWith("pod:")) podFolder(it) else browseItem(it) }
    }

    private fun podFolder(s: Station): MediaItem {
        val b = MediaMetadata.Builder()
            .setTitle(s.name)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST)
        if (s.logo.isNotEmpty()) b.setArtworkUri(Uri.parse(s.logo))
        return MediaItem.Builder().setMediaId(s.id).setMediaMetadata(b.build()).build()
    }

    fun episodeMeta(title: String, podcast: String, art: String): MediaMetadata {
        val b = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(podcast)
            .setAlbumTitle(podcast)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
        if (art.isNotEmpty()) b.setArtworkUri(Uri.parse(art))
        return b.build()
    }

    private fun folder(id: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                    .build()
            )
            .build()

    fun metadata(name: String, logo: String, title: String = "", artist: String = "", art: String = ""): MediaMetadata {
        val b = MediaMetadata.Builder()
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .setStation(name)
        if (title.isNotEmpty()) {
            b.setTitle(title).setArtist(if (artist.isNotEmpty()) artist else name).setAlbumTitle(name)
        } else {
            b.setTitle(name).setArtist("Em direto")
        }
        val pic = if (art.isNotEmpty()) art else logo
        if (pic.isNotEmpty()) b.setArtworkUri(Uri.parse(pic))
        return b.build()
    }

    private fun browseItem(s: Station): MediaItem =
        MediaItem.Builder().setMediaId(s.id).setMediaMetadata(metadata(s.name, s.logo)).build()

    /** Item pronto a tocar num endereço concreto. */
    fun playable(id: String, url: String, meta: MediaMetadata, pos: Long = 0L): MediaItem {
        val rm = MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(url))
        if (pos > 0) rm.setExtras(Bundle().apply { putLong("pos", pos) })
        val b = MediaItem.Builder()
            .setMediaId(id)
            .setUri(url)
            .setMediaMetadata(meta)
            .setRequestMetadata(rm.build())
        if (url.contains(".m3u8", ignoreCase = true)) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        return b.build()
    }
}
