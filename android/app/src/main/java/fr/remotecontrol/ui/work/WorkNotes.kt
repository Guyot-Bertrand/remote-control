package fr.remotecontrol.ui.work

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.remotecontrol.net.RatingDto
import fr.remotecontrol.ui.common.Texts
import fr.remotecontrol.ui.theme.Inter
import fr.remotecontrol.ui.theme.RcColors
import java.util.Locale

data class RatingPill(val label: String, val value: String, val accent: Boolean)

data class NoteLink(val label: String, val url: String)

data class NotesUi(val personal: List<Pair<String, String>>, val pills: List<RatingPill>, val links: List<NoteLink>)

private fun decimal(v: Double): String =
    if (v == Math.rint(v)) v.toInt().toString() else String.format(Locale.FRANCE, "%s", v).replace('.', ',')

private fun personalWord(v: Double): String = Texts.personalScale[v.toInt()]?.takeIf { v == Math.rint(v) } ?: decimal(v)

private fun shortName(name: String): String {
    val parts = name.trim().split(Regex("\\s+"))
    return if (parts.size < 2) name.trim() else "${parts.first().first()}. ${parts.drop(1).joinToString(" ")}"
}

/** Données de la section « notes et liens » ; nul si rien à afficher. */
fun notesUi(ratings: List<RatingDto>, tmdbUrl: String?): NotesUi? {
    val byCode = ratings.associateBy { it.code }
    val personal = listOf("NP" to Texts.myRating, "NT" to Texts.technicalRating)
        .mapNotNull { (code, label) -> byCode[code]?.value?.let { label to personalWord(it) } }
    val pills = buildList {
        byCode["IM"]?.value?.let { add(RatingPill(Texts.scaleImdb, String.format(Locale.FRANCE, "%.1f", it), true)) }
        byCode["SC"]?.value?.let { add(RatingPill(Texts.scaleSensCritique, String.format(Locale.FRANCE, "%.1f", it), true)) }
        ratings.filter { it.code.startsWith("SC@") }.forEach { c ->
            c.value?.let { add(RatingPill(shortName(c.name), decimal(it), false)) }
        }
    }
    val links = listOfNotNull(
        byCode["SC"]?.url?.let { NoteLink(Texts.scaleSensCritique, it) },
        byCode["IM"]?.url?.let { NoteLink(Texts.scaleImdb, it) },
        byCode["WK"]?.url?.let { NoteLink(Texts.scaleWikipedia, it) },
        tmdbUrl?.let { NoteLink(Texts.scaleTmdb, it) },
    )
    if (personal.isEmpty() && pills.isEmpty() && links.isEmpty()) return null
    return NotesUi(personal, pills, links)
}

private val small = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.sp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkNotes(ui: NotesUi, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (ui.personal.isNotEmpty()) {
            Text(personalLine(ui.personal), style = small, color = RcColors.text2)
        }
        if (ui.pills.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ui.pills.forEach { p ->
                    Text(
                        pillText(p),
                        style = small,
                        color = RcColors.text2,
                        modifier = Modifier
                            .border(BorderStroke(1.dp, RcColors.line), RoundedCornerShape(999.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
        if (ui.links.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.links.forEach { l ->
                    Text(
                        "${l.label} ↗",
                        style = small.copy(fontWeight = FontWeight.Medium),
                        color = RcColors.accent,
                        modifier = Modifier
                            .heightIn(min = 40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(RcColors.surface2)
                            .clickable(role = Role.Button) { onOpen(l.url) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

private fun personalLine(items: List<Pair<String, String>>): AnnotatedString = buildAnnotatedString {
    items.forEachIndexed { i, (label, word) ->
        if (i > 0) append(" · ")
        append("$label ")
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = RcColors.text)) { append(word) }
    }
}

private fun pillText(p: RatingPill): AnnotatedString = buildAnnotatedString {
    append("${p.label} ")
    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = if (p.accent) RcColors.accent else RcColors.text)) { append(p.value) }
}
