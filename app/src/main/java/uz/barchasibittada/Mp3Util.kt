package uz.barchasibittada

import java.io.ByteArrayOutputStream

/**
 * MP3 baytlarini tekshiradi: ID3 sarlavhasi va Xing/Info (ovoz bo'lmagan) kadrni tashlaydi, faqat to'liq kadrlarni qoldiradi
 * va davomiylikni kadrlar soni bo'yicha aniq hisoblaydi.
 *
 * Microsoft va Grok ovozi MP3 ko'rinishida keladi. Bunday bo'laklar qayta kodlanmasdan (sifat yo'qolmasdan) ish faylining
 * oxiriga ketma-ket yoziladi; davomiylik esa progress va «shubhali audio» tekshiruvi uchun kerak.
 */
object Mp3Util {
    class Info(val data: ByteArray, val ms: Long, val rate: Int, val frames: Int)

    private class Head(val len: Int, val samples: Int, val rate: Int, val sideInfo: Int, val crc: Boolean)

    private val BR_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val BR_V2_L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private val SR_V1 = intArrayOf(44100, 48000, 32000)
    private val SR_V2 = intArrayOf(22050, 24000, 16000)
    private val SR_V25 = intArrayOf(11025, 12000, 8000)

    /** pos dagi MP3 (Layer III) kadr sarlavhasi; yaroqsiz bo'lsa null. */
    private fun head(b: ByteArray, pos: Int): Head? {
        if (pos < 0 || pos + 4 > b.size) return null
        val b0 = b[pos].toInt() and 0xFF
        val b1 = b[pos + 1].toInt() and 0xFF
        val b2 = b[pos + 2].toInt() and 0xFF
        val b3 = b[pos + 3].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
        val ver = (b1 shr 3) and 3
        val layer = (b1 shr 1) and 3
        if (ver == 1 || layer != 1) return null
        val brIdx = (b2 shr 4) and 0xF
        val srIdx = (b2 shr 2) and 3
        if (brIdx == 0 || brIdx == 15 || srIdx == 3) return null
        val v1 = ver == 3
        val br = (if (v1) BR_V1_L3[brIdx] else BR_V2_L3[brIdx]) * 1000
        val rate = if (v1) SR_V1[srIdx] else if (ver == 2) SR_V2[srIdx] else SR_V25[srIdx]
        val pad = (b2 shr 1) and 1
        val samples = if (v1) 1152 else 576
        val len = (if (v1) 144 else 72) * br / rate + pad
        if (len < 24) return null
        val mono = ((b3 shr 6) and 3) == 3
        val side = if (v1) (if (mono) 17 else 32) else (if (mono) 9 else 17)
        val crc = (b1 and 1) == 0
        return Head(len, samples, rate, side, crc)
    }

    private fun isInfoFrame(b: ByteArray, pos: Int, h: Head): Boolean {
        val at = pos + 4 + (if (h.crc) 2 else 0) + h.sideInfo
        if (at + 4 > b.size) return false
        val x = String(b, at, 4, Charsets.ISO_8859_1)
        return x == "Xing" || x == "Info"
    }

    /**
     * src ichidagi MP3 kadrlarini ajratib oladi. Birorta ham yaroqli kadr topilmasa — null.
     * Birinchi kadr faqat o'zidan keyin yana yaroqli kadr (yoki fayl oxiri) kelsa qabul qilinadi: tasodifiy mos kelgan baytlar o'tib ketmaydi.
     */
    fun analyze(src: ByteArray): Info? {
        var pos = 0
        if (src.size >= 10 && src[0] == 'I'.code.toByte() && src[1] == 'D'.code.toByte() && src[2] == '3'.code.toByte()) {
            val sz = ((src[6].toInt() and 0x7F) shl 21) or ((src[7].toInt() and 0x7F) shl 14) or
                ((src[8].toInt() and 0x7F) shl 7) or (src[9].toInt() and 0x7F)
            pos = 10 + sz
        }
        val out = ByteArrayOutputStream(src.size)
        var samples = 0L
        var rate = 0
        var frames = 0
        var seen = 0
        while (pos + 4 <= src.size) {
            val h = head(src, pos)
            var ok = false
            if (h != null && pos + h.len <= src.size && (rate == 0 || h.rate == rate)) {
                if (rate == 0) {
                    // Birinchi kadr: tasodifiy mos kelgan baytlardan saqlanish uchun undan keyin ham yaroqli kadr bo'lishi shart.
                    val next = pos + h.len
                    ok = next == src.size || next + 4 > src.size || head(src, next) != null
                } else {
                    // Keyingi kadrlar: sarlavha yaroqli, sig'adi va chastota birinchisiniki bilan bir xil bo'lsa yetarli
                    // (oxirgi kadrdan keyin ID3v1/axlat bayt kelsa ham kadr yo'qolmaydi).
                    ok = true
                }
            }
            if (!ok || h == null) {
                pos++
                continue
            }
            val skip = seen == 0 && isInfoFrame(src, pos, h)
            seen++
            if (rate == 0) rate = h.rate
            if (!skip) {
                out.write(src, pos, h.len)
                samples += h.samples.toLong()
                frames++
            }
            pos += h.len
        }
        if (frames == 0 || rate == 0) return null
        return Info(out.toByteArray(), samples * 1000L / rate.toLong(), rate, frames)
    }
}
