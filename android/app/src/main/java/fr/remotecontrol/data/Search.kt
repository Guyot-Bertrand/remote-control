package fr.remotecontrol.data

import fr.remotecontrol.data.db.WorkEntity
import fr.remotecontrol.net.WorkDto
import java.text.Normalizer
import java.util.Locale

private val DIACRITICS = Regex("\\p{Mn}+")
private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

fun fold(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .replace("œ", "oe").replace("Œ", "oe")
        .replace("æ", "ae").replace("Æ", "ae")
        .lowercase(Locale.ROOT)

fun ftsText(w: WorkDto): String =
    (listOfNotNull(w.titleFr, w.titleVo, w.titleAlt) + w.directors + w.cast)
        .joinToString(" ") { fold(it) }
        .replace(NON_ALNUM, " ").trim()

fun buildFtsQuery(input: String): String? {
    val tokens = fold(input).split(NON_ALNUM).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return null
    return tokens.joinToString(" ") { "$it*" }
}

fun WorkDto.toEntity(): WorkEntity = WorkEntity(
    id = id, type = type, titleFr = titleFr, titleVo = titleVo, titleAlt = titleAlt, year = year,
    durationMin = durationMin, episodeCount = episodeCount, directors = directors, genres = genres,
    cast = cast, synopsis = synopsis, tags = tags, episodic = episodic, poster = poster,
    sortKey = fold(titleFr), playable = false, medium = medium,
    ratings = ratings, tmdbUrl = tmdbUrl,
)
