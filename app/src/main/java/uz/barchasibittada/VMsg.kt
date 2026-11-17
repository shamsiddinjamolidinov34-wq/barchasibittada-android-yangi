package uz.barchasibittada

import android.content.Context
import org.json.JSONArray

/**
 * Tilga bogʻliq xabarlar.
 *
 * Ish holati diskka yoziladi va kunlab saqlanib qolishi mumkin. Shuning uchun xabar tayyor matn emas,
 * "resurs nomi + argumentlar" (belgi, token) koʻrinishida saqlanadi va koʻrsatilayotgan paytda
 * joriy tizim tilidagi matnga aylantiriladi. Telefon tili oʻzgarsa, saqlangan xabarlar ham yangi tilda chiqadi.
 *
 * Eski (oddiy matn) xabarlar oʻzgarishsiz qaytariladi.
 */
object VMsg {
    private const val PREFIX = '\u0001'

    @Volatile
    private var app: Context? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    fun isToken(s: String): Boolean = s.isNotEmpty() && s[0] == PREFIX

    /** Xabar belgisini yaratadi. Argumentlar: son (Int/Long) yoki matn. */
    fun make(id: Int, vararg args: Any?): String {
        val c = app ?: return ""
        try {
            val a = JSONArray()
            a.put(c.resources.getResourceEntryName(id))
            for (x in args) {
                if (x == null) {
                    a.put("")
                } else if (x is Int) {
                    a.put(x.toInt())
                } else if (x is Long) {
                    a.put(x.toLong())
                } else {
                    a.put(x.toString())
                }
            }
            return PREFIX.toString() + a.toString()
        } catch (t: Throwable) {
            return ""
        }
    }

    /** Saqlangan xabarni ctx ning joriy tilidagi matnga aylantiradi. */
    fun text(ctx: Context, stored: String): String {
        if (!isToken(stored)) return stored
        try {
            val a = JSONArray(stored.substring(1))
            val name = a.getString(0)
            val res = ctx.resources
            val id = res.getIdentifier(name, "string", ctx.packageName)
            if (id == 0) return ""
            val n = a.length() - 1
            val args = arrayOfNulls<Any>(n)
            for (i in 0 until n) args[i] = a.get(i + 1)
            return res.getString(id, *args)
        } catch (t: Throwable) {
            return ""
        }
    }

    /** Saqlangan xabar aynan shu resursdan yaratilganmi (argumentlarga qaramasdan). */
    fun isRes(ctx: Context, stored: String, resId: Int): Boolean {
        if (!isToken(stored)) return false
        return try {
            JSONArray(stored.substring(1)).getString(0) == ctx.resources.getResourceEntryName(resId)
        } catch (t: Throwable) {
            false
        }
    }

    /** Ilova kontekstida (Activity boʻlmagan joylar, masalan jurnal uchun). */
    fun resolve(stored: String): String {
        val c = app ?: return stored
        return text(c, stored)
    }
}
