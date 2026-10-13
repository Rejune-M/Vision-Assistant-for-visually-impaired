package com.example.visionassist

import android.content.Context

object SosStore {
    private const val PREF = "visionassist_prefs"
    private const val KEY_SOS_NUMBER = "sos_number_wa"

    fun getNumber(context: Context): String? =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_SOS_NUMBER, null)

    fun setNumber(context: Context, numberWaMe: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SOS_NUMBER, numberWaMe)
            .apply()
    }
}
