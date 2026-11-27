package fr.remotecontrol.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** [playerAvailable] : le lecteur (mpv) est installé sur le PC (arbitrage 11) ; vrai pour un serveur plus ancien. */
@Serializable
data class InfoDto(
    val name: String,
    val api: Int,
    val revision: Long,
    @SerialName("player_available") val playerAvailable: Boolean = true,
)

@Serializable
data class SyncDto(
    val revision: Long,
    val full: Boolean,
    val works: List<WorkDto> = emptyList(),
    val seasons: List<SeasonDto> = emptyList(),
    val episodes: List<EpisodeDto> = emptyList(),
    @SerialName("media_files") val mediaFiles: List<MediaFileDto> = emptyList(),
    val collections: List<CollectionDto> = emptyList(),
    @SerialName("work_collections") val workCollections: List<WorkCollectionDto> = emptyList(),
    val progress: List<ProgressDto> = emptyList(),
    val volumes: List<VolumeDto> = emptyList(),
    val deleted: DeletedDto = DeletedDto(),
    /** Réponse complète seulement : nombre d'œuvres de la réponse (progression de la première synchro). */
    @SerialName("total_works") val totalWorks: Int? = null,
)

@Serializable
data class DeletedDto(
    val work: List<Long> = emptyList(),
    val season: List<Long> = emptyList(),
    val episode: List<Long> = emptyList(),
    @SerialName("media_file") val mediaFile: List<Long> = emptyList(),
    val collection: List<Long> = emptyList(),
)

@Serializable
data class WorkDto(
    val id: Long,
    val type: String,
    val key: String,
    @SerialName("title_fr") val titleFr: String,
    @SerialName("title_vo") val titleVo: String? = null,
    @SerialName("title_alt") val titleAlt: String? = null,
    val year: Int? = null,
    @SerialName("duration_min") val durationMin: Int? = null,
    @SerialName("episode_count") val episodeCount: String? = null,
    val directors: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val synopsis: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    val tags: List<String> = emptyList(),
    val episodic: Boolean = false,
    val poster: String? = null,
    /** Support d'origine quand l'œuvre n'a pas de fichier lisible (arbitrage 13) ; nul sinon. */
    val medium: String? = null,
    /** Notes ordonnées (arbitrage 24) ; vide avec un serveur plus ancien. */
    val ratings: List<RatingDto> = emptyList(),
    @SerialName("tmdb_url") val tmdbUrl: String? = null,
)

@Serializable
data class RatingDto(val code: String, val name: String, val value: Double? = null, val url: String? = null)

@Serializable
data class SeasonDto(
    val id: Long,
    @SerialName("work_id") val workId: Long,
    val number: Int,
    val label: String,
)

@Serializable
data class EpisodeDto(
    val id: Long,
    @SerialName("season_id") val seasonId: Long,
    val number: Int,
    val title: String? = null,
    @SerialName("duration_min") val durationMin: Int? = null,
)

@Serializable
data class MediaFileDto(
    val id: Long,
    @SerialName("work_id") val workId: Long? = null,
    @SerialName("episode_id") val episodeId: Long? = null,
    val quality: String,
    val size: String? = null,
    val relpath: String,
    @SerialName("volume_uuid") val volumeUuid: String? = null,
    val present: Boolean = false,
    /** Analyse du fichier (arbitrage 13) ; nuls tant que le fichier n'a pas été analysé. */
    @SerialName("audio_langs") val audioLangs: List<String>? = null,
    @SerialName("subtitle_langs") val subtitleLangs: List<String>? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("duration_s") val durationS: Double? = null,
)

@Serializable
data class CollectionDto(
    val id: Long,
    val name: String,
    val description: String? = null,
    val link: String? = null,
)

@Serializable
data class WorkCollectionDto(
    @SerialName("work_id") val workId: Long,
    @SerialName("collection_id") val collectionId: Long,
    val rank: String? = null,
)

@Serializable
data class ProgressDto(
    @SerialName("target_kind") val targetKind: String,
    @SerialName("target_id") val targetId: Long,
    @SerialName("media_file_id") val mediaFileId: Long? = null,
    val position: Double,
    val duration: Double? = null,
    val watched: Boolean = false,
    @SerialName("updated_at") val updatedAt: Double,
    /** Dernier choix de pistes pour la cible (arbitrage 7) ; nul si aucun choix explicite. */
    val audio: Int? = null,
    val subtitle: SubSel? = null,
)

@Serializable
data class VolumeDto(
    val uuid: String,
    val label: String? = null,
    val mount: String? = null,
    val present: Boolean = false,
)

@Serializable
data class TrackDto(
    val index: Int,
    val lang: String? = null,
    val title: String? = null,
    val codec: String? = null,
    val default: Boolean = false,
)

@Serializable
data class ExternalSubDto(val path: String, val lang: String? = null)

@Serializable
data class TrackDefaultsDto(val audio: Int? = null, val subtitle: SubSel = SubSel.None)

@Serializable
data class MediaTracksDto(
    val audio: List<TrackDto> = emptyList(),
    val subtitles: List<TrackDto> = emptyList(),
    val external: List<ExternalSubDto> = emptyList(),
    @SerialName("video_codec") val videoCodec: String? = null,
    val height: Int? = null,
    val default: TrackDefaultsDto = TrackDefaultsDto(),
)

@Serializable
sealed interface SubSel {
    @Serializable
    @SerialName("none")
    data object None : SubSel

    @Serializable
    @SerialName("embedded")
    data class Embedded(val index: Int) : SubSel

    @Serializable
    @SerialName("external")
    data class External(val path: String) : SubSel
}

@Serializable
data class PlayerStateDto(
    val position: Double = 0.0,
    val duration: Double? = null,
    val paused: Boolean = false,
    val volume: Int = 100,
    val audio: Int? = null,
    val subtitle: SubSel = SubSel.None,
    val ended: Boolean = false,
    val error: String? = null,
    val running: Boolean = false,
)

@Serializable
data class CurrentDto(
    @SerialName("target_kind") val targetKind: String,
    @SerialName("target_id") val targetId: Long,
    @SerialName("media_file_id") val mediaFileId: Long,
    @SerialName("work_id") val workId: Long,
    @SerialName("season_id") val seasonId: Long? = null,
    val title: String? = null,
)

@Serializable
data class PlayerSnapshotDto(
    val state: PlayerStateDto = PlayerStateDto(),
    val current: CurrentDto? = null,
)

/**
 * État de la télé (arbitrage 15) : `screen` ∈ home, work, season, view, playing, paused, message. [view] : la vue
 * du téléphone reproduite (`screen` `view`) ; [pause] : la vidéo en pause mise de côté. Un serveur de l'itération 1
 * n'envoie que `screen`, `work_id`, `season_id` et `message`.
 */
@Serializable
data class TvStateDto(
    val screen: String,
    @SerialName("work_id") val workId: Long? = null,
    @SerialName("season_id") val seasonId: Long? = null,
    val message: String? = null,
    val view: TvViewStateDto? = null,
    val pause: PauseDto? = null,
)

/** Vue reproduite par la télé, telle que renvoyée par le serveur ; seuls l'écran et la fiche montrée servent ici. */
@Serializable
data class TvViewStateDto(
    val screen: String,
    @SerialName("work_id") val workId: Long? = null,
    @SerialName("season_id") val seasonId: Long? = null,
)

/** Vidéo en pause mise de côté ; [image] est nulle si la capture a échoué. */
@Serializable
data class PauseDto(
    val image: String? = null,
    val title: String? = null,
    val position: Double? = null,
    val duration: Double? = null,
    @SerialName("work_id") val workId: Long? = null,
)

@Serializable
data class PlayRequestDto(
    @SerialName("target_kind") val targetKind: String,
    @SerialName("target_id") val targetId: Long,
    val start: String,
    @SerialName("media_file_id") val mediaFileId: Long? = null,
    val audio: Int? = null,
    val subtitle: SubSel? = null,
)

@Serializable
data class ItemRefDto(val kind: String, val id: Long)

@Serializable
data class PersonDto(val name: String, val role: String? = null, val count: Int = 1)

@Serializable
data class SectionDto(
    val label: String,
    val people: List<PersonDto> = emptyList(),
    val items: List<ItemRefDto> = emptyList(),
    val kind: String? = null,
)

@Serializable
data class PositionDto(val letter: String, val index: Int, val total: Int)

/** Vue affichée par le téléphone (arbitrage 14) ; `phoneId` et `seq` sont posés par `TvMirror`. */
@Serializable
data class TvViewDto(
    @SerialName("phone_id") val phoneId: String = "",
    val seq: Long = 0,
    val screen: String,
    val title: String? = null,
    val subtitle: String? = null,
    val filter: String? = null,
    val query: String? = null,
    @SerialName("work_id") val workId: Long? = null,
    @SerialName("season_id") val seasonId: Long? = null,
    @SerialName("episode_id") val episodeId: Long? = null,
    @SerialName("media_file_id") val mediaFileId: Long? = null,
    @SerialName("collection_id") val collectionId: Long? = null,
    @SerialName("audio_track") val audioTrack: Int? = null,
    @SerialName("subtitle_track") val subtitleTrack: SubSel? = null,
    val items: List<ItemRefDto> = emptyList(),
    val anchor: Long? = null,
    val position: PositionDto? = null,
    val sections: List<SectionDto> = emptyList(),
)

@Serializable
data class TvViewResultDto(val accepted: Boolean)

@Serializable
data class ErrorDto(
    val error: String,
    @SerialName("volume_label") val volumeLabel: String? = null,
    /** Phrase en français prête à afficher (arbitrage 9). */
    val message: String? = null,
)
