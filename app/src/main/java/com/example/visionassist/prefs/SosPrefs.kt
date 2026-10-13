package com.example.visionassist.prefs

import android.content.Context

class SosPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("visionassist_prefs", Context.MODE_PRIVATE)
    private val KEY = "sos_number"

    fun get(): String? = prefs.getString(KEY, null)

    fun set(number: String) {
        prefs.edit().putString(KEY, number).apply()
    }

    fun isSet(): Boolean = !get().isNullOrBlank()
}
