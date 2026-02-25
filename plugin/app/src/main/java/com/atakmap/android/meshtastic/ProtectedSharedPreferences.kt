package com.atakmap.android.meshtastic

import android.content.SharedPreferences

class ProtectedSharedPreferences(
    private val preferences: SharedPreferences
) : SharedPreferences by preferences {
    override fun getInt(key: String?, defaultValue: Int) = try {
        preferences.getInt(key, defaultValue)
    } catch (exception: ClassCastException) {
        val strValue = preferences.getString(key, defaultValue.toString())
        try {
            strValue?.toInt() ?: defaultValue
        } catch (exception: NumberFormatException) {
            defaultValue
        }
    }

}
