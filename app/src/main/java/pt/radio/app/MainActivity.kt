package pt.radio.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import org.json.JSONObject

/** Ecrã da app: mostra a app web (radiopt.pages.dev) e passa a reprodução para o leitor nativo. */
class MainActivity : ComponentActivity() {

    companion object {
        const val HOME = "https://radiopt.pages.dev/"
        const val HOST = "radiopt.pages.dev"
    }

    private lateinit var web: WebView
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()
    private var webStation: String? = null
    private val ui = Handler(Looper.getMainLooper())
    // posição do episódio para a barra de progresso da app
    private val ticker = object : Runnable {
        override fun run() {
            val c = controller
            if (c != null && c.isPlaying) {
                val d = c.duration
                emit("time", "${c.currentPosition},${if (d == C.TIME_UNSET) -1 else d}")
                ui.postDelayed(this, 1000)
            }
        }
    }

    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0B0D10"))
        setContentView(web)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        web.addJavascriptInterface(Bridge(), "RadioNative")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == HOST) return false
                // YouTube Music e outros links abrem fora da app
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) { }
                return true
            }
        }
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(HOME)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)

        connect()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        web.destroy()
        super.onDestroy()
    }

    private fun connect() {
        val token = SessionToken(this, ComponentName(this, RadioService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        controllerFuture = f
        f.addListener({
            val c = try { f.get() } catch (e: Exception) { null } ?: return@addListener
            controller = c
            c.addListener(PlayerEvents())
            pending.forEach { it(c) }
            pending.clear()
        }, MoreExecutors.directExecutor())
    }

    private fun withController(block: (MediaController) -> Unit) {
        runOnUiThread {
            val c = controller
            if (c != null) block(c) else pending.add(block)
        }
    }

    private fun emit(ev: String, arg: String = "") {
        runOnUiThread {
            web.evaluateJavascript("window.__native&&window.__native(${JSONObject.quote(ev)},${JSONObject.quote(arg)})", null)
        }
    }

    private inner class PlayerEvents : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val c = controller ?: return
            if (isPlaying) {
                emit("playing")
                ui.removeCallbacks(ticker); ui.post(ticker)
            }
            else if (!c.playWhenReady || c.playbackState == Player.STATE_IDLE || c.playbackState == Player.STATE_ENDED) emit("pause")
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_BUFFERING) emit("waiting")
            if (state == Player.STATE_ENDED) emit("ended")
        }

        override fun onPlayerError(error: PlaybackException) {
            emit("error")
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // rádio escolhida no carro ou na notificação: a app acompanha
            val id = mediaItem?.mediaId ?: return
            if (id != webStation) {
                webStation = id
                emit("station", id)
            }
        }
    }

    /** Funções chamadas pela parte web (window.RadioNative). */
    private inner class Bridge {
        @JavascriptInterface
        fun play(url: String, meta: String) {
            val o = try { JSONObject(meta) } catch (e: Exception) { JSONObject() }
            val id = o.optString("id", url)
            val name = o.optString("name", "Rádio")
            val logo = o.optString("logo", "")
            val pos = o.optLong("pos", 0L)
            val meta = if (o.optBoolean("podcast", false))
                Stations.episodeMeta(name, o.optString("artist", ""), logo)
            else Stations.metadata(name, logo)
            webStation = id
            withController { c ->
                c.setMediaItem(Stations.playable(id, url, meta, pos))
                c.prepare()
                c.play()
            }
        }

        @JavascriptInterface
        fun stop() {
            withController { c -> c.stop() }
        }

        @JavascriptInterface
        fun pause() {
            withController { c -> c.pause() }
        }

        @JavascriptInterface
        fun resume() {
            withController { c -> c.play() }
        }

        @JavascriptInterface
        fun seek(ms: Double) {
            withController { c -> c.seekTo(ms.toLong()) ; emit("time", "${ms.toLong()},${if (c.duration == C.TIME_UNSET) -1 else c.duration}") }
        }

        @JavascriptInterface
        fun speed(rate: Double) {
            withController { c -> c.setPlaybackSpeed(rate.toFloat()) }
        }

        @JavascriptInterface
        fun setNow(title: String, artist: String, art: String) {
            runOnUiThread { RadioService.instance?.updateNow(title, artist, art) }
        }

        @JavascriptInterface
        fun setStations(json: String) {
            Stations.save(this@MainActivity, json)
        }
    }
}
