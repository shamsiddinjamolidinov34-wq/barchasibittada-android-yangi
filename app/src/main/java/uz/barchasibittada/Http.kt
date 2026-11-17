package uz.barchasibittada

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Provayder xatosi. `msg` — foydalanuvchiga ko'rsatiladigan tushunarli matn.
 * kind: "kunlik", "kredit", "daqiqalik", "tarmoq", "filtr", "uzun", "katta" yoki "".
 */
class ProviderError(
    val msg: String,
    val code: Int = 0,
    val retryable: Boolean = false,
    val kind: String = "",
    val retryAfter: Double = 0.0,
    val keyBad: Boolean = false
) : Exception(msg)

class HttpResult(val code: Int, val body: ByteArray, val headers: Headers) {
    fun text(): String = String(body, Charsets.UTF_8)
}

object Safe {
    private val secret = Regex("(AIza[0-9A-Za-z_\\-]{20,}|sk-[A-Za-z0-9_\\-]{16,}|xai-[A-Za-z0-9_\\-]{16,}|Bearer\\s+[A-Za-z0-9._\\-]+)")

    fun mask(s: String): String = secret.replace(s, "***")
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation {
        try {
            this@await.cancel()
        } catch (e: Throwable) {
            // e'tiborsiz
        }
    }
    this.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}

object Http {
    val JSON = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    private fun netMessage(e: IOException): String {
        if (e is SocketTimeoutException) return "Javob vaqtida kelmadi (tarmoq sekin yoki xizmat band). Qayta urinib ko'ring."
        if (e is UnknownHostException) return "Internetga ulanib bo'lmadi. Tarmoqni tekshiring."
        val m = Safe.mask(e.message ?: "")
        return "Tarmoq xatosi: " + m.take(120)
    }

    suspend fun exec(req: Request, readTimeoutSec: Long = 120): HttpResult {
        val c = client.newBuilder().readTimeout(readTimeoutSec, TimeUnit.SECONDS).build()
        return withContext(Dispatchers.IO) {
            try {
                val resp = c.newCall(req).await()
                resp.use { r ->
                    val bytes = r.body?.bytes() ?: ByteArray(0)
                    HttpResult(r.code, bytes, r.headers)
                }
            } catch (e: IOException) {
                Journal.w("Tarmoq", "Tarmoq xatosi: " + e.javaClass.simpleName + ": " + (e.message ?: ""))
                throw ProviderError(netMessage(e), 0, true, "tarmoq")
            }
        }
    }

    private suspend fun send(
        url: String,
        headers: Map<String, String>,
        body: JSONObject,
        timeoutSec: Long,
        attempts: Int
    ): HttpResult {
        var wait = 1000L
        var last: ProviderError? = null
        for (i in 0 until attempts) {
            try {
                val rq = Request.Builder().url(url).post(body.toString().toRequestBody(JSON))
                for ((k, v) in headers) rq.header(k, v)
                val res = exec(rq.build(), timeoutSec)
                if (res.code in 200..299) return res
                throw errorFrom(res)
            } catch (e: ProviderError) {
                last = e
                Journal.w(
                    "Provayder",
                    "HTTP " + e.code + " (" + e.kind + (if (e.keyBad) ", kalit yaroqsiz" else "") + "), urinish " + (i + 1) + "/" + attempts +
                        ": " + e.msg.take(200)
                )
                val stop = !e.retryable || i == attempts - 1 || e.kind == "kunlik" || e.kind == "kredit" ||
                    e.code == 503 || e.code == 504
                if (stop) throw e
                var w = wait
                if (e.retryAfter > 0.0) {
                    if (e.retryAfter > 20.0) throw e
                    w = maxOf(w, (e.retryAfter * 1000.0).toLong() + 500L)
                }
                delay(w)
                wait = wait * 2 + 1000L
            }
        }
        throw last ?: ProviderError("Javob olinmadi.", 0, true)
    }

    suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: JSONObject,
        timeoutSec: Long = 120,
        attempts: Int = 3
    ): JSONObject {
        val res = send(url, headers, body, timeoutSec, attempts)
        try {
            return JSONObject(res.text())
        } catch (e: Exception) {
            throw ProviderError("Xizmat noto'g'ri formatda javob berdi.", res.code, true)
        }
    }

    suspend fun postRaw(
        url: String,
        headers: Map<String, String>,
        body: JSONObject,
        timeoutSec: Long = 120,
        attempts: Int = 3
    ): HttpResult = send(url, headers, body, timeoutSec, attempts)

    suspend fun getJson(url: String, headers: Map<String, String>, timeoutSec: Long = 60): JSONObject {
        val rq = Request.Builder().url(url).get()
        for ((k, v) in headers) rq.header(k, v)
        val res = exec(rq.build(), timeoutSec)
        if (res.code !in 200..299) throw errorFrom(res)
        try {
            return JSONObject(res.text())
        } catch (e: Exception) {
            throw ProviderError("Xizmat noto'g'ri formatda javob berdi.", res.code, true)
        }
    }

    suspend fun postMultipart(
        url: String,
        headers: Map<String, String>,
        fields: Map<String, String>,
        fileField: String,
        fileName: String,
        mime: String,
        file: File,
        timeoutSec: Long = 600
    ): HttpResult {
        val mb = MultipartBody.Builder().setType(MultipartBody.FORM)
        for ((k, v) in fields) mb.addFormDataPart(k, v)
        val mt = (if (mime.isBlank()) "application/octet-stream" else mime).toMediaType()
        mb.addFormDataPart(fileField, fileName, file.asRequestBody(mt))
        val rq = Request.Builder().url(url).post(mb.build())
        for ((k, v) in headers) rq.header(k, v)
        val res = exec(rq.build(), timeoutSec)
        if (res.code !in 200..299) throw errorFrom(res)
        return res
    }

    fun errorFrom(res: HttpResult): ProviderError {
        val raw = res.text()
        var message = raw
        try {
            val j = JSONObject(raw)
            val e = j.opt("error")
            if (e is JSONObject) {
                message = e.optString("message", raw)
            } else if (e is String && e.isNotEmpty()) {
                message = e
            } else if (j.has("message")) {
                message = j.optString("message", raw)
            }
        } catch (ex: Exception) {
            // xom matn qoladi
        }
        val code = res.code
        val low = (message + " " + raw).lowercase()

        var kind = ""
        if (code == 429 || low.contains("quota") || low.contains("rate limit") || low.contains("rate_limit")) {
            kind = if (low.contains("perday") || low.contains("per day") || low.contains("daily")) {
                "kunlik"
            } else if (low.contains("insufficient_quota") || low.contains("credit balance") ||
                low.contains("any credits") || low.contains("no credits") || low.contains("insufficient credit")
            ) {
                "kredit"
            } else {
                "daqiqalik"
            }
        } else if (low.contains("credit balance is too low") || low.contains("doesn't have any credits")) {
            kind = "kredit"
        }

        val keyBad = code == 401 || low.contains("api key not valid") || low.contains("invalid api key") ||
            low.contains("incorrect api key") || low.contains("invalid x-api-key") || low.contains("invalid_api_key")

        var retryAfter = 0.0
        val h = res.headers["retry-after"]
        if (h != null) retryAfter = h.toDoubleOrNull() ?: 0.0
        if (retryAfter == 0.0) {
            val m = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"").find(raw)
            if (m != null) retryAfter = m.groupValues[1].toDoubleOrNull() ?: 0.0
        }

        val retryable = code == 429 || code == 500 || code == 502 || code == 503 || code == 504 || code == 529
        return ProviderError(humanize(code, kind, message, keyBad), code, retryable, kind, retryAfter, keyBad)
    }

    private fun humanize(code: Int, kind: String, message: String, keyBad: Boolean): String {
        val short = Safe.mask(message.replace("\n", " ")).take(220)
        if (keyBad) return "Kalit qabul qilinmadi. Sozlamalar bo'limida kalitni tekshiring yoki yangisini kiriting."
        if (kind == "kunlik") return "Bugungi bepul limit tugadi. Ertaga qayta urinib ko'ring yoki boshqa model yoki kalit tanlang."
        if (kind == "kredit") return "Hisobda mablag' yoki kredit yetarli emas. Provayder sahifasida hisobni to'ldiring."
        if (code == 429) return "So'rovlar juda tez-tez yuborildi. Bir daqiqadan so'ng qayta urinib ko'ring."
        if (code == 403) return "Ruxsat yo'q (403): kalit bu modelga yoki xizmatga ruxsat bermaydi. " + short
        if (code == 404) return "Model yoki xizmat topilmadi (404). Boshqa model tanlab ko'ring. " + short
        if (code == 413) return "Fayl yoki matn juda katta (413)."
        if (code == 503 || code == 529) return "Xizmat hozir band (" + code + "). Birozdan so'ng qayta urinib ko'ring yoki boshqa model tanlang."
        if (code >= 500) return "Xizmat tomonida xatolik (" + code + "). Qayta urinib ko'ring."
        if (code == 400) return "So'rov qabul qilinmadi (400): " + short
        return "Xatolik (" + code + "): " + short
    }
}
