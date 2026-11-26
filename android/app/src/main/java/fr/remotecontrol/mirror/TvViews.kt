package fr.remotecontrol.mirror

import fr.remotecontrol.data.db.WorkSummary
import fr.remotecontrol.net.ItemRefDto
import fr.remotecontrol.net.PersonDto
import fr.remotecontrol.net.PositionDto
import fr.remotecontrol.net.SectionDto
import fr.remotecontrol.net.SubSel
import fr.remotecontrol.net.TvViewDto
import fr.remotecontrol.player.PlayTarget
import fr.remotecontrol.player.TargetKind
import fr.remotecontrol.ui.common.EPISODE
import fr.remotecontrol.ui.common.Texts
import fr.remotecontrol.ui.common.indexLetter
import fr.remotecontrol.ui.home.HomeUi
import fr.remotecontrol.ui.search.SearchSections

/** Limite d'éléments par liste, imposée par le serveur (arbitrage 14) ; au-delà, la vue est refusée (422). */
private const val MAX_ITEMS = 40

const val KIND_RESUME = "resume"
const val KIND_RECENT = "recent"
const val KIND_COLLECTIONS = "collections"
const val KIND_PEOPLE = "people"
const val KIND_WORKS = "works"
const val KIND_AKA = "aka"

/** Fenêtre de [max] éléments au plus autour de l'index visible [first] : [before] avant lui, bornée aux extrémités. */
fun windowAround(ids: List<Long>, first: Int, before: Int = 10, max: Int = MAX_ITEMS): List<Long> {
    if (ids.isEmpty()) return emptyList()
    val end = (first.coerceIn(0, ids.lastIndex) - before).coerceAtLeast(0).let { minOf(ids.size, it + max) }
    return ids.subList((end - max).coerceAtLeast(0), end)
}

private fun workRefs(ids: List<Long>) = ids.take(MAX_ITEMS).map { ItemRefDto("work", it) }

private fun listView(
    screen: String,
    title: String,
    subtitle: String,
    works: List<WorkSummary>,
    firstVisible: Int,
    base: TvViewDto = TvViewDto(screen = screen),
): TvViewDto {
    val first = firstVisible.coerceIn(0, (works.size - 1).coerceAtLeast(0))
    val anchor = works.getOrNull(first)
    return base.copy(
        title = title,
        subtitle = subtitle,
        items = workRefs(windowAround(works.map { it.id }, first)),
        anchor = anchor?.id,
        position = anchor?.let { PositionDto(indexLetter(it.titleFr), first + 1, works.size) },
    )
}

fun browseView(
    titleText: String,
    subtitleText: String,
    playableOnly: Boolean,
    works: List<WorkSummary>,
    firstVisible: Int,
): TvViewDto = listView(
    "browse", titleText, subtitleText, works, firstVisible,
    TvViewDto(screen = "browse", filter = if (playableOnly) "playable" else "all"),
)

fun collectionView(
    collectionId: Long,
    name: String,
    subtitleText: String,
    works: List<WorkSummary>,
    firstVisible: Int,
): TvViewDto = listView(
    "collection", name, subtitleText, works, firstVisible,
    TvViewDto(screen = "collection", collectionId = collectionId),
)

fun homeView(ui: HomeUi): TvViewDto {
    val sections = listOf(
        SectionDto(
            Texts.resume,
            items = ui.resume.take(MAX_ITEMS).map {
                if (it.targetKind == EPISODE) ItemRefDto("episode", it.targetId) else ItemRefDto("work", it.targetId)
            },
            kind = KIND_RESUME,
        ),
        SectionDto(Texts.sectionRecent, items = workRefs(ui.recent.map { it.id }), kind = KIND_RECENT),
        SectionDto(Texts.sectionCollections, items = ui.collections.take(MAX_ITEMS).map { ItemRefDto("collection", it.id) }, kind = KIND_COLLECTIONS),
    ).filter { it.items.isNotEmpty() }
    return TvViewDto(screen = "home", title = Texts.appName, sections = sections)
}

fun searchView(query: String, sections: SearchSections): TvViewDto {
    val out = listOf(
        SectionDto(
            Texts.sectionPeople,
            people = sections.people.take(MAX_ITEMS).map {
                PersonDto(it.name, if (it.directing) Texts.directors else Texts.acting, it.workCount)
            },
            kind = KIND_PEOPLE,
        ),
        SectionDto(Texts.sectionWorks, items = workRefs(sections.works.map { it.work.id }), kind = KIND_WORKS),
        SectionDto(Texts.sectionAka, items = workRefs(sections.aka.map { it.work.id }), kind = KIND_AKA),
    ).filter { it.people.isNotEmpty() || it.items.isNotEmpty() }
    return TvViewDto(screen = "search", title = Texts.navSearch, query = query, sections = out)
}

fun workView(workId: Long) = TvViewDto(screen = "work", workId = workId)

fun seasonView(seasonId: Long, workId: Long, selectedEpisodeId: Long?) =
    TvViewDto(screen = "season", seasonId = seasonId, workId = workId, episodeId = selectedEpisodeId)

/**
 * Choix avant lecture : [workId] toujours (l'œuvre, ou la série d'un épisode), et pour un épisode sa saison
 * [seasonId] et l'épisode lui-même ; la télé en a besoin pour afficher la fiche.
 */
fun preplayView(target: PlayTarget, workId: Long?, seasonId: Long?, mediaFileId: Long?, audio: Int?, subtitle: SubSel) = TvViewDto(
    screen = "preplay",
    workId = if (target.kind == TargetKind.Work) target.id else workId,
    seasonId = seasonId.takeIf { target.kind == TargetKind.Episode },
    episodeId = target.id.takeIf { target.kind == TargetKind.Episode },
    mediaFileId = mediaFileId,
    audioTrack = audio,
    subtitleTrack = subtitle.takeUnless { it == SubSel.None },
)

fun remoteView() = TvViewDto(screen = "remote")
