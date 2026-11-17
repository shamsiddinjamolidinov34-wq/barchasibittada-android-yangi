package uz.barchasibittada

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

object AudioInfo {
    fun durationSec(path: String): Int {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(path)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            return (ms / 1000L).toInt()
        } catch (e: Exception) {
            return 0
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
                // e'tiborsiz
            }
        }
    }

    fun clock(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d soat %d daqiqa".format(h, m) else if (m > 0) "%d daqiqa %d soniya".format(m, s) else "%d soniya".format(s)
    }
}

object Player {
    private var mp: MediaPlayer? = null
    var current by mutableStateOf("")
        private set

    fun toggle(path: String) {
        if (current == path) stop() else play(path)
    }

    fun play(path: String) {
        stop()
        try {
            val m = MediaPlayer()
            m.setDataSource(path)
            m.setOnCompletionListener { stop() }
            m.setOnErrorListener { _, _, _ ->
                stop()
                true
            }
            m.prepare()
            m.start()
            mp = m
            current = path
        } catch (e: Exception) {
            stop()
            Ui.toast("Ovozni ijro etib bo'lmadi.")
        }
    }

    fun stop() {
        val m = mp
        mp = null
        if (m != null) {
            try {
                m.stop()
            } catch (e: Exception) {
                // e'tiborsiz
            }
            try {
                m.release()
            } catch (e: Exception) {
                // e'tiborsiz
            }
        }
        current = ""
    }
}

object Recorder {
    private var rec: MediaRecorder? = null
    private var target: File? = null

    @Suppress("DEPRECATION")
    fun start(ctx: Context): Boolean {
        try {
            val f = File(ctx.cacheDir, "ovoz-" + System.currentTimeMillis() + ".m4a")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64000)
            r.setAudioSamplingRate(44100)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            rec = r
            target = f
            return true
        } catch (e: Exception) {
            cleanup()
            return false
        }
    }

    /** Yozuvni to'xtatadi va faylni qaytaradi (juda qisqa bo'lsa null). */
    fun stop(): File? {
        val r = rec
        val f = target
        rec = null
        target = null
        if (r == null || f == null) return null
        var ok = true
        try {
            r.stop()
        } catch (e: RuntimeException) {
            ok = false
        }
        try {
            r.release()
        } catch (e: Exception) {
            // e'tiborsiz
        }
        if (!ok || !f.exists() || f.length() < 800L) {
            try {
                f.delete()
            } catch (e: Exception) {
                // e'tiborsiz
            }
            return null
        }
        return f
    }

    private fun cleanup() {
        try {
            rec?.release()
        } catch (e: Exception) {
            // e'tiborsiz
        }
        rec = null
        try {
            target?.delete()
        } catch (e: Exception) {
            // e'tiborsiz
        }
        target = null
    }
}

object ImageKit {
    /** Rasmni JPEG ga keltiradi (burilishni to'g'rilaydi, kattaligini cheklaydi). */
    fun prepare(file: File, maxSide: Int = 2048): ByteArray? {
        try {
            val path = file.absolutePath
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > maxSide * 2) sample *= 2
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            var bmp = BitmapFactory.decodeFile(path, opts) ?: return null

            val deg = rotationOf(path)
            if (deg != 0) {
                val m = Matrix()
                m.postRotate(deg.toFloat())
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (rotated !== bmp) bmp.recycle()
                bmp = rotated
            }
            val big = maxOf(bmp.width, bmp.height)
            if (big > maxSide) {
                val k = maxSide.toFloat() / big.toFloat()
                val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
                if (scaled !== bmp) bmp.recycle()
                bmp = scaled
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
            bmp.recycle()
            return out.toByteArray()
        } catch (e: Throwable) {
            return null
        }
    }

    @Suppress("DEPRECATION")
    private fun rotationOf(path: String): Int {
        try {
            val exif = android.media.ExifInterface(path)
            val o = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
            return when (o) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Throwable) {
            return 0
        }
    }
}

object PdfKit {
    fun pageCount(file: File): Int {
        var pfd: ParcelFileDescriptor? = null
        var r: PdfRenderer? = null
        try {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd = fd
            val rr = PdfRenderer(fd)
            r = rr
            return rr.pageCount
        } catch (e: Throwable) {
            return 0
        } finally {
            try {
                r?.close()
            } catch (e: Exception) {
                // e'tiborsiz
            }
            try {
                pfd?.close()
            } catch (e: Exception) {
                // e'tiborsiz
            }
        }
    }

    /** PDF sahifasini JPEG ga aylantiradi (skanerlangan PDF ni o'qish uchun). */
    fun renderPage(file: File, index: Int, width: Int = 1600): ByteArray? {
        var pfd: ParcelFileDescriptor? = null
        var r: PdfRenderer? = null
        try {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd = fd
            val rr = PdfRenderer(fd)
            r = rr
            if (index < 0 || index >= rr.pageCount) return null
            val page = rr.openPage(index)
            try {
                val h = (page.height.toFloat() / page.width.toFloat() * width.toFloat()).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
                bmp.recycle()
                return out.toByteArray()
            } finally {
                page.close()
            }
        } catch (e: Throwable) {
            return null
        } finally {
            try {
                r?.close()
            } catch (e: Exception) {
                // e'tiborsiz
            }
            try {
                pfd?.close()
            } catch (e: Exception) {
                // e'tiborsiz
            }
        }
    }
}
