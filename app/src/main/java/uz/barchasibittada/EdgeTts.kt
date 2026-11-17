package uz.barchasibittada

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Microsoft Edge «o'qib berish» xizmati (Microsoft ovozi). Kalit talab qilmaydi.
 * O'zbekcha ovozlar: Sardor (erkak) va Madina (ayol); boshqa tillar ham bor (VEngine.MS_VOICES).
 * Natija — MP3 baytlari. Rasmiy API emas: Microsoft o'zgartirsa ishlamay qolishi mumkin.
 *
 * Audiolashtirish yadrosi (VoiceCore) uchun fon oqimida ishlaydi: qayta urinish va kutish VoiceCore da, bu yerda — bitta so'rov.
 */
object EdgeTts {
    private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val BASE = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
    private const val CHROMIUM = "143.0.3650.75"
    private const val MAJOR = "143"
    private const val WAIT_MS = 90000L

    class Res(val kind: Gemini.Kind, val bytes: ByteArray? = null, val message: String = "")

    @Volatile
    private var skew: Long = 0L

    @Volatile
    private var active: WebSocket? = null

    private class Holder {
        @Volatile
        var done = false

        @Volatile
        var closed = false

        @Volatile
        var error: String? = null

        @Volatile
        var status = 0

        @Volatile
        var dateHeader: String? = null
    }

    /** Ketayotgan so'rovni uzadi (foydalanuvchi «Pauza» bosganda). */
    fun cancel() {
        val w = active ?: return
        try {
            w.cancel()
        } catch (t: Throwable) {
        }
    }

    /** Tezlik foizi (100 = oddiy) → SSML prosody rate: 120 → «+20%», 80 → «-20%». */
    fun rateFor(percent: Int): String {
        val d = percent.coerceIn(50, 200) - 100
        return (if (d >= 0) "+" else "") + d + "%"
    }

    private fun longVoice(full: String): String {
        val m = Regex("^([a-z]{2,})-([A-Z]{2,})-(.+Neural)$").find(full) ?: return full
        val lang = m.groupValues[1]
        val region = m.groupValues[2]
        val name = m.groupValues[3]
        return "Microsoft Server Speech Text to Speech Voice (" + lang + "-" + region + ", " + name + ")"
    }

    private fun secMsGec(): String {
        var ticks = System.currentTimeMillis() / 1000L + skew + 11644473600L
        ticks -= ticks % 300L
        val value = ticks * 10000000L
        val src = value.toString() + TOKEN
        val digest = MessageDigest.getInstance("SHA-256").digest(src.toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder()
        for (b in digest) sb.append("%02X".format(b.toInt() and 0xFF))
        return sb.toString()
    }

    private fun dateString(): String {
        val f = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date()) + " GMT+0000 (Coordinated Universal Time)"
    }

    private fun randomHex(n: Int): String {
        val chars = "0123456789ABCDEF"
        val sb = StringBuilder()
        val rnd = java.util.Random()
        for (i in 0 until n) sb.append(chars[rnd.nextInt(16)])
        return sb.toString()
    }

    private fun xmlEscape(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            val c = ch.code
            if (c in 0..8 || c == 11 || c == 12 || c in 14..31) {
                sb.append(' ')
                continue
            }
            if (ch == '&') sb.append("&amp;")
            else if (ch == '<') sb.append("&lt;")
            else if (ch == '>') sb.append("&gt;")
            else if (ch == '"') sb.append("&quot;")
            else if (ch == '\'') sb.append("&apos;")
            else sb.append(ch)
        }
        return sb.toString()
    }

    private fun adjustSkew(dateHeader: String?) {
        if (dateHeader == null) return
        try {
            val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            val server = f.parse(dateHeader) ?: return
            skew = server.time / 1000L - System.currentTimeMillis() / 1000L
        } catch (e: Exception) {
            // e'tiborsiz
        }
    }

    /** Matnni MP3 ga aylantiradi. voice — to'liq nom (masalan, uz-UZ-SardorNeural), rate — «+0%» ko'rinishida. */
    fun synth(text: String, voice: String, rate: String, cancelled: () -> Boolean): Res {
        var ws: WebSocket? = null
        try {
            val connId = UUID.randomUUID().toString().replace("-", "")
            val url = BASE + "?TrustedClientToken=" + TOKEN + "&ConnectionId=" + connId +
                "&Sec-MS-GEC=" + secMsGec() + "&Sec-MS-GEC-Version=1-" + CHROMIUM
            val req = Request.Builder().url(url)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" +
                        MAJOR + ".0.0.0 Safari/537.36 Edg/" + MAJOR + ".0.0.0"
                )
                .header("Accept-Encoding", "gzip, deflate, br, zstd")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "muid=" + randomHex(32) + ";")
                .build()

            val out = ByteArrayOutputStream()
            val latch = CountDownLatch(1)
            val h = Holder()
            val ts = dateString()
            val reqId = UUID.randomUUID().toString().replace("-", "")
            val configMsg = "X-Timestamp:" + ts + "\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"true\"," +
                "\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
            val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                "<voice name='" + longVoice(voice) + "'><prosody pitch='+0Hz' rate='" + rate + "' volume='+0%'>" +
                xmlEscape(text) + "</prosody></voice></speak>"
            val ssmlMsg = "X-RequestId:" + reqId + "\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:" + ts +
                "Z\r\nPath:ssml\r\n\r\n" + ssml

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(configMsg)
                    webSocket.send(ssmlMsg)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("Path:turn.end")) {
                        h.done = true
                        webSocket.close(1000, null)
                        latch.countDown()
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val data = bytes.toByteArray()
                    if (data.size < 2) return
                    val headLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                    if (headLen + 2 > data.size) return
                    val head = String(data, 2, headLen, Charsets.UTF_8)
                    if (head.contains("Path:audio")) {
                        val start = 2 + headLen
                        if (data.size > start) out.write(data, start, data.size - start)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    h.error = t.message ?: t.javaClass.simpleName
                    h.status = response?.code ?: 0
                    h.dateHeader = response?.header("Date")
                    latch.countDown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    h.closed = true
                    latch.countDown()
                }
            }

            val sock = Http.client.newWebSocket(req, listener)
            ws = sock
            active = sock
            var waited = 0L
            var finished = false
            while (waited < WAIT_MS) {
                if (cancelled()) {
                    try {
                        sock.cancel()
                    } catch (t: Throwable) {
                    }
                    return Res(Gemini.Kind.CANCELLED)
                }
                if (latch.await(250L, TimeUnit.MILLISECONDS)) {
                    finished = true
                    break
                }
                waited += 250L
            }
            try {
                sock.cancel()
            } catch (t: Throwable) {
            }
            if (cancelled()) return Res(Gemini.Kind.CANCELLED)
            if (!finished) return Res(Gemini.Kind.NETWORK, message = "Microsoft ovozi: vaqt tugadi")
            if (h.status == 403) {
                adjustSkew(h.dateHeader)
                return Res(Gemini.Kind.NETWORK, message = "Microsoft ovozi: 403, soat farqi sozlandi")
            }
            val bytes = out.toByteArray()
            val complete = h.done || (h.closed && h.error == null)
            if (!complete) {
                return Res(Gemini.Kind.NETWORK, message = "Microsoft ovozi: aloqa uzildi (" + (h.error ?: "xato") + ")")
            }
            if (bytes.isEmpty()) return Res(Gemini.Kind.NO_AUDIO, message = "Microsoft ovozi: ovoz kelmadi")
            return Res(Gemini.Kind.OK, bytes)
        } catch (e: OutOfMemoryError) {
            return Res(Gemini.Kind.BAD_CHUNK, message = "Xotira yetmadi (OutOfMemory) — bo'lak kichraytiriladi")
        } catch (t: Throwable) {
            if (cancelled()) return Res(Gemini.Kind.CANCELLED)
            return Res(Gemini.Kind.NETWORK, message = Gemini.describe(t))
        } finally {
            active = null
            try {
                ws?.cancel()
            } catch (t: Throwable) {
            }
        }
    }
}
