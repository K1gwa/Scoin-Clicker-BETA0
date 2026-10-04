package com.example.coinclicker

import android.content.Context
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject

data class Profile(
    var name: String, var targets: String,
    var only: String = "", var avoid: String = "", var scroll: String = ""
)

object Store {
    const val DEFAULT_TARGETS = "check in, check-in, claim, collect, spin, get coins"
    private fun sp(c: Context) = c.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    fun load(c: Context): MutableList<Profile> {
        val out = mutableListOf<Profile>()
        val arr = JSONArray(sp(c).getString("list", "[]"))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Profile(o.getString("name"), o.getString("targets"),
                o.optString("only"), o.optString("avoid"), o.optString("scroll")))
        }
        if (out.isEmpty()) out.add(Profile("Default", DEFAULT_TARGETS))
        return out
    }

    fun save(c: Context, l: List<Profile>) {
        val arr = JSONArray()
        l.forEach {
            arr.put(JSONObject().put("name", it.name).put("targets", it.targets)
                .put("only", it.only).put("avoid", it.avoid).put("scroll", it.scroll))
        }
        sp(c).edit().putString("list", arr.toString()).apply()
    }

    fun active(c: Context): String = sp(c).getString("active", "Default")!!
    fun setActive(c: Context, n: String) { sp(c).edit().putString("active", n).apply() }
    fun current(c: Context): Profile { val l = load(c); return l.find { it.name == active(c) } ?: l[0] }

    fun zones(s: String): MutableList<Rect> = s.split(";").filter { it.isNotBlank() }.map { z ->
        val v = z.split(",").map { it.toInt() }; Rect(v[0], v[1], v[2], v[3])
    }.toMutableList()

    fun str(l: List<Rect>) = l.joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" }
}
