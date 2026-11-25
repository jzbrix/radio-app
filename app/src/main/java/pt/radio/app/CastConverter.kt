package pt.radio.app

import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.common.images.WebImage

/**
 * O que a TV recebe: rádios como emissão em direto, com a música, o artista e a capa
 * (ou o logótipo), para o ecrã não ficar vazio.
 */
class CastConverter : MediaItemConverter {
    private val base = DefaultMediaItemConverter()

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem = base.toMediaItem(mediaQueueItem)

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val q = base.toMediaQueueItem(mediaItem)
        val mi = q.media ?: return q
        val m = mediaItem.mediaMetadata
        val live = !mediaItem.mediaId.startsWith("ep:")
        val md = MediaMetadata(if (live) MediaMetadata.MEDIA_TYPE_MUSIC_TRACK else MediaMetadata.MEDIA_TYPE_GENERIC)
        val station = m.station?.toString() ?: ""
        val title = m.title?.toString() ?: station
        md.putString(MediaMetadata.KEY_TITLE, title)
        val artist = m.artist?.toString() ?: ""
        if (live) {
            md.putString(MediaMetadata.KEY_ARTIST, if (artist.isNotEmpty() && artist != "Em direto") artist else "Em direto")
            if (station.isNotEmpty()) md.putString(MediaMetadata.KEY_ALBUM_TITLE, station)
        } else {
            md.putString(MediaMetadata.KEY_SUBTITLE, artist)
        }
        m.artworkUri?.let { md.addImage(WebImage(it)) }
        val b = MediaInfo.Builder(mi.contentId)
            .setStreamType(if (live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mi.contentType ?: "audio/mpeg")
            .setMetadata(md)
        mi.contentUrl?.let { b.setContentUrl(it) }
        mi.customData?.let { b.setCustomData(it) }
        return MediaQueueItem.Builder(b.build()).build()
    }
}
