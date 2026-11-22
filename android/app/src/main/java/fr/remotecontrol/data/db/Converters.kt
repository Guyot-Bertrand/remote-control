package fr.remotecontrol.data.db

import androidx.room.TypeConverter
import fr.remotecontrol.net.RcJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

class Converters {
    private val serializer = ListSerializer(String.serializer())

    @TypeConverter
    fun fromList(list: List<String>): String = RcJson.encodeToString(serializer, list)

    @TypeConverter
    fun toList(json: String): List<String> = RcJson.decodeFromString(serializer, json)
}
