package fr.remotecontrol.net

import kotlinx.serialization.json.Json

val RcJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
    classDiscriminator = "kind"
}
