package fr.remotecontrol.data.db

import androidx.room.TypeConverter
import fr.remotecontrol.net.RcJson
import fr.remotecontrol.net.SubSel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

class Converters {
    private val serializer = ListSerializer(String.serializer())

    @TypeConverter
    fun fromList(list: List<String>?): String? = list?.let { RcJson.encodeToString(serializer, it) }

    @TypeConverter
    fun toList(json: String?): List<String>? = json?.let { RcJson.decodeFromString(serializer, it) }

    @TypeConverter
    fun fromSubSel(sel: SubSel?): String? = sel?.let { RcJson.encodeToString(SubSel.serializer(), it) }

    @TypeConverter
    fun toSubSel(json: String?): SubSel? = json?.let { RcJson.decodeFromString(SubSel.serializer(), it) }
}
