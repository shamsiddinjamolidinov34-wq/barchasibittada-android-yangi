package uz.barchasibittada

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Qurilma ovozi (Android TextToSpeech): internet va kalit kerak emas. Matn WAV faylga yoziladi va 16-bit mono PCM ga
 * aylantiriladi, shundan so'ng boshqa ovozlar kabi MP3 ga kodlanadi. O'zbek tili qurilmada o'rnatilmagan bo'lsa,
 * qurilmaning standart tili ishlatiladi (o'zbekcha matn noto'g'ri o'qilishi mumkin).
 */
object DeviceTts {
    class Res(
        val ok: Boolean,
        val cancelled: Boolean = false,
        val fatal: Boolean = false,
        val pcm: ByteArray? = null,
        val rate: Int = 22050,
        val message: String = ""
    )

    private val lock = Any()
    private var tts: TextToSpeech? = null

    /** 0 — yaratilmagan, 1 — tayyorlanmoqda, 2 — tayyor, 3 — xato. */
    private var state = 0
    private var initLatch: CountDownLatch? = null

    @Volatile
    private var current: TextToSpeech? = null

    /** Ketayotgan o'qishni to'xtatadi (foydalanuvchi «Pauza» bosganda). */
    fun cancel() {
        val t = current ?: return
        try {
            t.stop()
        } catch (e: Throwable) {
        }
    }

    private fun ensure(ctx: Context): TextToSpeech? {
        var latch: CountDownLatch? = null
        synchronized(lock) {
            if (state == 2) return tts
            if (state == 3) {
                try {
                    tts?.shutdown()
                } catch (e: Throwable) {
                }
                tts = null
                state = 0
            }
            if (state == 0) {
                state = 1
                val l = CountDownLatch(1)
                initLatch = l
                tts = TextToSpeech(ctx.applicationContext) { status ->
                    synchronized(lock) { state = if (status == TextToSpeech.SUCCESS) 2 else 3 }
                    l.countDown()
                }
            }
            latch = initLatch
        }
        try {
            latch?.await(20L, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            return null
        }
        synchronized(lock) {
            return if (state == 2) tts else null
        }
    }

    private fun le16(b: ByteArray, p: Int): Int = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, p: Int): Long =
        ((b[p].toInt() and 0xFF).toLong()) or ((b[p + 1].toInt() and 0xFF).toLong() shl 8) or
            ((b[p + 2].toInt() and 0xFF).toLong() shl 16) or ((b[p + 3].toInt() and 0xFF).toLong() shl 24)

    /** WAV (16-bit; mono yoki stereo) → 16-bit mono PCM. Ba'zi dvigatellar data hajmini 0 yoki noto'g'ri yozadi: shunda fayl oxirigacha olinadi. */
    private fun wavToPcm(b: ByteArray): Pcm? {
        if (b.size < 44 || b[0] != 'R'.code.toByte() || b[1] != 'I'.code.toByte() || b[2] != 'F'.code.toByte() || b[3] != 'F'.code.toByte()) return null
        var pos = 12
        var rate = 22050
        var channels = 1
        var bits = 16
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.ISO_8859_1)
            val sz = le32(b, pos + 4)
            if (id == "fmt " && pos + 24 <= b.size) {
                channels = le16(b, pos + 10)
                rate = le32(b, pos + 12).toInt()
                bits = le16(b, pos + 22)
            } else if (id == "data") {
                val start = pos + 8
                var len = sz
                if (len <= 0L || start.toLong() + len > b.size.toLong()) len = (b.size - start).toLong()
                if (bits != 16 || (channels != 1 && channels != 2)) return null
                val total = (len.toInt()) and 1.inv()
                if (total <= 0) return null
                if (channels == 1) return Pcm(b.copyOfRange(start, start + total), rate)
                val frames = total / 4
                val mono = ByteArray(frames * 2)
                for (i in 0 until frames) {
                    val l = le16(b, start + i * 4).toShort().toInt()
                    val r = le16(b, start + i * 4 + 2).toShort().toInt()
                    val m = (l + r) / 2
                    mono[i * 2] = (m and 0xFF).toByte()
                    mono[i * 2 + 1] = ((m shr 8) and 0xFF).toByte()
                }
                return Pcm(mono, rate)
            }
            if (sz < 0L || pos.toLong() + 8L + sz > b.size.toLong()) break
            pos += 8 + sz.toInt() + (sz.toInt() and 1)
        }
        return null
    }

    private val synthLock = Any()

    /** Matnni PCM ga aylantiradi. percent — tezlik (100 = oddiy). Fon oqimida chaqiriladi; bitta dvigatel bo'lgani uchun so'rovlar navbat bilan bajariladi. */
    fun synthesize(ctx: Context, text: String, percent: Int, cancelled: () -> Boolean): Res {
        synchronized(synthLock) {
            return synthesizeLocked(ctx, text, percent, cancelled)
        }
    }

    private fun synthesizeLocked(ctx: Context, text: String, percent: Int, cancelled: () -> Boolean): Res {
        val t = ensure(ctx) ?: return Res(
            ok = false,
            fatal = true,
            message = "Qurilma ovozi ishga tushmadi. Telefonda matnni o'qish (TTS) dvigateli o'rnatilganini tekshiring."
        )
        val f = File(Store.inDir(), "dev-" + System.nanoTime() + ".wav")
        try {
            current = t
            var langOk = false
            try {
                val r = t.setLanguage(Locale.forLanguageTag("uz-UZ"))
                langOk = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            } catch (e: Throwable) {
            }
            if (!langOk) {
                try {
                    t.setLanguage(Locale.getDefault())
                } catch (e: Throwable) {
                }
            }
            try {
                t.setSpeechRate(percent.coerceIn(50, 250) / 100f)
            } catch (e: Throwable) {
            }
            val uid = "d" + System.nanoTime()
            val done = CountDownLatch(1)
            val failed = AtomicBoolean(false)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == uid) done.countDown()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == uid) {
                        failed.set(true)
                        done.countDown()
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == uid) {
                        failed.set(true)
                        done.countDown()
                    }
                }
            })
            val rc = t.synthesizeToFile(text, null, f, uid)
            if (rc != TextToSpeech.SUCCESS) {
                return Res(ok = false, message = "Qurilma ovozi matnni qabul qilmadi")
            }
            var waited = 0L
            var finished = false
            while (waited < 180000L) {
                if (cancelled()) {
                    try {
                        t.stop()
                    } catch (e: Throwable) {
                    }
                    return Res(ok = false, cancelled = true)
                }
                if (done.await(250L, TimeUnit.MILLISECONDS)) {
                    finished = true
                    break
                }
                waited += 250L
            }
            if (!finished) {
                try {
                    t.stop()
                } catch (e: Throwable) {
                }
                return Res(ok = false, message = "Qurilma ovozi vaqtida tugamadi")
            }
            if (failed.get()) return Res(ok = false, message = "Qurilma ovozi matnni o'qiy olmadi")
            val bytes = try {
                f.readBytes()
            } catch (e: Throwable) {
                return Res(ok = false, message = "Qurilma ovozi fayli o'qilmadi")
            }
            val pcm = wavToPcm(bytes) ?: return Res(ok = false, message = "Qurilma ovozi formati qo'llab-quvvatlanmaydi")
            if (pcm.bytes.size < 2000) return Res(ok = false, message = "Qurilma ovozi juda qisqa audio berdi")
            return Res(ok = true, pcm = pcm.bytes, rate = pcm.rate)
        } catch (e: OutOfMemoryError) {
            return Res(ok = false, message = "Xotira yetmadi (OutOfMemory) — bo'lak kichraytiriladi")
        } catch (e: Throwable) {
            if (cancelled()) return Res(ok = false, cancelled = true)
            return Res(ok = false, message = Gemini.describe(e))
        } finally {
            current = null
            try {
                f.delete()
            } catch (e: Throwable) {
            }
        }
    }
}
