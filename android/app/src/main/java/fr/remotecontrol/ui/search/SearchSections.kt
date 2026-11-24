package fr.remotecontrol.ui.search

import fr.remotecontrol.data.db.SearchHit
import fr.remotecontrol.data.db.WorkSummary
import fr.remotecontrol.data.fold

/** Personne dont le nom répond à toute la saisie : [directing] si elle réalise au moins une des œuvres trouvées. */
data class PersonHit(val name: String, val directing: Boolean, val workCount: Int)

enum class AltKind { Original, Alternative }

/** Œuvre trouvée par un autre titre que le sien ([title], original ou alternatif). */
data class AkaHit(val work: WorkSummary, val kind: AltKind, val title: String)

/** Sections de la maquette « Recherche » : PERSONNES, ŒUVRES, AUSSI CONNU SOUS. */
data class SearchSections(
    val people: List<PersonHit> = emptyList(),
    val works: List<SearchHit> = emptyList(),
    val aka: List<AkaHit> = emptyList(),
)

private const val MAX_PEOPLE = 5
// Les diacritiques combinants (\\p{M}) font partie du mot : un titre en forme décomposée (NFD) reste d'un tenant.
private val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+")

private fun tokens(query: String): List<String> = WORD.findAll(fold(query)).map { it.value }.toList()

/** Chaque mot de la saisie commence un mot de [text] (sans tenir compte des accents ni de la casse). */
private fun matchesAll(text: String?, tokens: List<String>): Boolean {
    if (text == null || tokens.isEmpty()) return false
    val words = WORD.findAll(fold(text)).map { it.value }.toList()
    return tokens.all { t -> words.any { it.startsWith(t) } }
}

/**
 * Répartit les résultats (dans leur ordre) : une personne dont le nom répond à toute la saisie va dans
 * PERSONNES (avec le nombre d'œuvres trouvées où elle figure) ; une œuvre trouvée seulement par son titre
 * original ou alternatif va dans AUSSI CONNU SOUS ; toutes les autres, dans ŒUVRES.
 */
fun searchSections(query: String, hits: List<SearchHit>): SearchSections {
    val t = tokens(query)
    if (t.isEmpty()) return SearchSections(works = hits)
    val people = linkedMapOf<String, PersonHit>()
    val works = mutableListOf<SearchHit>()
    val aka = mutableListOf<AkaHit>()
    for (hit in hits) {
        val directors = hit.work.directors.filter { matchesAll(it, t) }
        val actors = hit.cast.filter { matchesAll(it, t) && it !in directors }
        directors.forEach { name -> people.merge(name, PersonHit(name, true, 1)) { a, _ -> a.copy(directing = true, workCount = a.workCount + 1) } }
        actors.forEach { name -> people.merge(name, PersonHit(name, false, 1)) { a, _ -> a.copy(workCount = a.workCount + 1) } }
        val byPerson = directors.isNotEmpty() || actors.isNotEmpty()
        when {
            byPerson || matchesAll(hit.work.titleFr, t) -> works += hit
            matchesAll(hit.titleAlt, t) -> aka += AkaHit(hit.work, AltKind.Alternative, hit.titleAlt!!)
            matchesAll(hit.titleVo, t) -> aka += AkaHit(hit.work, AltKind.Original, hit.titleVo!!)
            else -> works += hit // correspondance répartie sur plusieurs champs
        }
    }
    val ranked = people.values.sortedWith(compareByDescending<PersonHit> { it.workCount }.thenBy { fold(it.name) })
    return SearchSections(ranked.take(MAX_PEOPLE), works, aka)
}

/**
 * Passages de [text] à mettre en évidence pour [query] : le début de chaque mot qui commence par un mot de la
 * saisie (sans accents ni casse, « œ » valant « oe »). Deux passages séparés seulement par des espaces n'en font
 * qu'un (« Léa Mysi »). Intervalles inclusifs, en indices de [text].
 */
fun matchRanges(text: String, query: String): List<IntRange> {
    val t = tokens(query)
    if (t.isEmpty()) return emptyList()
    val ranges = mutableListOf<IntRange>()
    for (word in WORD.findAll(text)) {
        val folded = fold(word.value)
        val token = t.filter { folded.startsWith(it) }.maxByOrNull { it.length } ?: continue
        // Nombre de caractères du mot d'origine qu'il faut pour couvrir le mot de la saisie.
        var covered = 0
        var length = 0
        while (covered < token.length && length < word.value.length) {
            covered += fold(word.value[length].toString()).length
            length++
        }
        // Les accents combinants qui suivent la dernière lettre couverte en font partie.
        while (length < word.value.length && Character.getType(word.value[length]) == Character.NON_SPACING_MARK.toInt()) length++
        val range = word.range.first until word.range.first + length
        val last = ranges.lastOrNull()
        if (last != null && text.substring(last.last + 1, range.first).isBlank()) {
            ranges[ranges.lastIndex] = last.first..range.last
        } else {
            ranges += range
        }
    }
    return ranges
}
