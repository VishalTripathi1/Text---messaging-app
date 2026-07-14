package com.messaging.data.datasource.local

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.messaging.domain.entity.Reaction

/**
 * Converters - Type converters for Room Database
 */
class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromReactionList(value: List<Reaction>?): String? {
        return gson.toJson(value)
    }

    @TypeConverter
    fun toReactionList(value: String?): List<Reaction>? {
        if (value == null) return null
        val type = object : TypeToken<List<Reaction>>() {}.type
        return gson.fromJson(value, type)
    }
}
