package com.puretv.player.data.local

import androidx.room.TypeConverter
import org.json.JSONArray
import org.json.JSONObject

/** Room 类型转换器：Map<String,String> / List<String> 与 JSON 互转 */
class Converters {

    @TypeConverter
    fun fromStringMap(value: Map<String, String>): String {
        val json = JSONObject()
        value.forEach { (k, v) -> json.put(k, v) }
        return json.toString()
    }

    @TypeConverter
    fun toStringMap(value: String): Map<String, String> {
        if (value.isBlank()) return emptyMap()
        return try {
            val json = JSONObject(value)
            val map = mutableMapOf<String, String>()
            json.keys().forEach { key -> map[key] = json.optString(key, "") }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    @TypeConverter
    fun fromStringList(value: List<String>): String {
        val json = JSONArray()
        value.forEach { json.put(it) }
        return json.toString()
    }

    @TypeConverter
    fun toStringList(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        return try {
            val json = JSONArray(value)
            (0 until json.length()).map { json.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
