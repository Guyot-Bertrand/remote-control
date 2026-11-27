package fr.remotecontrol.data.db

import androidx.room.TypeConverter
import fr.remotecontrol.net.RatingDto
import fr.remotecontrol.net.RcJson
import fr.remotecontrol.net.SubSel
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

class Converters {
    private val serializer = ListSerializer(String.serializer())

    @TypeConverter
    fun fromList(list: List<String>?): String? = list?.let { RcJson.encodeToString(serializer, it) }

    @TypeConverter
    fun toList(json: String?): List<String>? = json?.let { RcJson.decodeFromString(serializer, it) }

    private val ratingsSerializer: KSerializer<List<RatingDto>> = ListSerializer(RatingDto.serializer())

    @TypeConverter
    fun fromRatings(list: List<RatingDto>?): String? = list?.let { RcJson.encodeToString(ratingsSerializer, it) }

    @TypeConverter
    fun toRatings(json: String?): List<RatingDto>? = json?.let { RcJson.decodeFromString(ratingsSerializer, it) }

    @TypeConverter
    fun fromSubSel(sel: SubSel?): String? = sel?.let { RcJson.encodeToString(SubSel.serializer(), it) }

    @TypeConverter
    fun toSubSel(json: String?): SubSel? = json?.let { RcJson.decodeFromString(SubSel.serializer(), it) }
}
