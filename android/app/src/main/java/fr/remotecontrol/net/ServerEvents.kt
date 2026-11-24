package fr.remotecontrol.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

sealed interface ServerEvent {
    data class Hello(val revision: Long, val tv: TvStateDto, val player: PlayerSnapshotDto) : ServerEvent
    data class Player(val snapshot: PlayerSnapshotDto) : ServerEvent
    data class Tv(val state: TvStateDto) : ServerEvent
    data class Catalog(val revision: Long) : ServerEvent
}

@Serializable
private data class HelloMsg(val revision: Long, val tv: TvStateDto, val player: PlayerSnapshotDto)

@Serializable
private data class TvMsg(val state: TvStateDto)

@Serializable
private data class CatalogMsg(val revision: Long)

/** Décode un message WebSocket du serveur ; `null` si le type est inconnu ou le JSON invalide. */
fun parseServerEvent(text: String): ServerEvent? = try {
    val obj: JsonObject = RcJson.parseToJsonElement(text).jsonObject
    when ((obj["type"] as? JsonPrimitive)?.content) {
        "hello" -> RcJson.decodeFromJsonElement(HelloMsg.serializer(), obj).let {
            ServerEvent.Hello(it.revision, it.tv, it.player)
        }
        "player" -> ServerEvent.Player(RcJson.decodeFromJsonElement(PlayerSnapshotDto.serializer(), obj))
        "tv" -> ServerEvent.Tv(RcJson.decodeFromJsonElement(TvMsg.serializer(), obj).state)
        "catalog" -> ServerEvent.Catalog(RcJson.decodeFromJsonElement(CatalogMsg.serializer(), obj).revision)
        else -> null
    }
} catch (_: Exception) {
    null
}
