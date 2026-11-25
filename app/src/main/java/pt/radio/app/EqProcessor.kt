package pt.radio.app

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Equalizador feito pela própria app, dentro do leitor (não usa o do sistema,
 * que em alguns telemóveis choca com os efeitos de som do fabricante).
 * 5 bandas; baixa o volume geral pelo maior reforço para nunca saturar.
 */
class EqProcessor : BaseAudioProcessor() {
    companion object {
        val FREQS = intArrayOf(60, 250, 1000, 4000, 12000)
        val PRESETS = listOf(
            "Normal" to intArrayOf(0, 0, 0, 0, 0),
            "Pop" to intArrayOf(-1, 2, 4, 2, -1),
            "Rock" to intArrayOf(4, 2, -2, 2, 4),
            "Jazz" to intArrayOf(3, 1, -1, 2, 3),
            "Clássica" to intArrayOf(4, 2, 0, 2, 3),
            "Dança" to intArrayOf(5, 3, 0, 2, 3),
            "Hip hop" to intArrayOf(5, 3, 0, 1, 2),
            "Vozes" to intArrayOf(-2, 0, 3, 3, 1),
            "Mais graves" to intArrayOf(6, 3, 0, 0, 0),
            "Mais agudos" to intArrayOf(0, 0, 0, 3, 6)
        )
        const val MIN_MB = -1200
        const val MAX_MB = 1200
    }

    @Volatile private var gainsDb = DoubleArray(FREQS.size)
    @Volatile private var on = false
    @Volatile private var dirty = true
    private var sr = 44100
    private var ch = 2
    private var coef = Array(FREQS.size) { DoubleArray(5) }
    private var st = Array(0) { DoubleArray(4) }
    private var pre = 1.0

    fun set(enabled: Boolean, levelsMb: IntArray) {
        gainsDb = DoubleArray(FREQS.size) { i -> (levelsMb.getOrElse(i) { 0 }).coerceIn(MIN_MB, MAX_MB) / 100.0 }
        on = enabled
        dirty = true
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        sr = inputAudioFormat.sampleRate
        ch = inputAudioFormat.channelCount
        dirty = true
        return inputAudioFormat
    }

    override fun onFlush() { st = Array(FREQS.size * ch) { DoubleArray(4) } }
    override fun onReset() { st = Array(0) { DoubleArray(4) } }

    private fun rebuild() {
        dirty = false
        val g = gainsDb
        for (b in FREQS.indices) {
            val a = 10.0.pow(g[b] / 40.0)
            val f = minOf(FREQS[b].toDouble(), sr * 0.45)
            val w0 = 2 * PI * f / sr
            val q = if (b == 0 || b == FREQS.size - 1) 0.8 else 1.0
            val alpha = sin(w0) / (2 * q)
            val cw = cos(w0)
            val a0 = 1 + alpha / a
            coef[b] = doubleArrayOf((1 + alpha * a) / a0, (-2 * cw) / a0, (1 - alpha * a) / a0, (-2 * cw) / a0, (1 - alpha / a) / a0)
        }
        val boost = max(0.0, g.maxOrNull() ?: 0.0)
        pre = 10.0.pow(-(boost + 1.0) / 20.0) // margem de 1 dB além do maior reforço
        if (st.size != FREQS.size * ch) st = Array(FREQS.size * ch) { DoubleArray(4) }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        val out = replaceOutputBuffer(n)
        if (!on) { out.put(inputBuffer); out.flip(); return }
        if (dirty || st.size != FREQS.size * ch) rebuild()
        val inp = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        out.order(ByteOrder.LITTLE_ENDIAN)
        val frames = n / (2 * ch)
        for (fi in 0 until frames) {
            for (c in 0 until ch) {
                var x = inp.short.toDouble() * pre
                for (b in FREQS.indices) {
                    val k = coef[b]; val s = st[b * ch + c]
                    val y = k[0] * x + k[1] * s[0] + k[2] * s[1] - k[3] * s[2] - k[4] * s[3]
                    s[1] = s[0]; s[0] = x; s[3] = s[2]; s[2] = y
                    x = y
                }
                out.putShort(x.coerceIn(-32768.0, 32767.0).toInt().toShort())
            }
        }
        // bytes que sobram (não deve acontecer)
        while (inp.hasRemaining()) out.put(inp.get())
        out.flip()
    }
}
