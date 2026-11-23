package fr.remotecontrol.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.remotecontrol.data.db.WorkSummary
import fr.remotecontrol.ui.theme.Fraunces
import fr.remotecontrol.ui.theme.RcColors

/**
 * Liste d'œuvres des maquettes « Collection » et « Recherche » : vignette (entamée, vue), titre,
 * « année · type · réalisation »,
 * et « Non lisible » à droite (ligne estompée) quand l'œuvre n'a aucun fichier présent.
 * Avec [ranks] (collection), le rang s'affiche en laiton devant la vignette.
 * [header] ajoute des éléments défilant avec la liste, avant les œuvres.
 */
@Composable
fun WorkList(
    works: List<WorkSummary>,
    onOpen: (WorkSummary) -> Unit,
    modifier: Modifier = Modifier,
    ranks: Map<Long, String> = emptyMap(),
    header: LazyListScope.() -> Unit = {},
) {
    val ranked = ranks.isNotEmpty()
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 16.dp)) {
        header()
        items(works, key = { it.id }, contentType = { "work" }) { work ->
            WorkRow(work, if (ranked) ranks[work.id].orEmpty() else null, onOpen)
        }
    }
}

@Composable
private fun WorkRow(work: WorkSummary, rank: String?, onOpen: (WorkSummary) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (rank != null) 76.dp else 80.dp)
            .clickable(onClickLabel = Texts.openWork, role = Role.Button) { onOpen(work) }
            .padding(horizontal = 16.dp)
            .alpha(if (work.playable) 1f else 0.6f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (rank != null) {
            Text(
                rank,
                style = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Medium, fontSize = 20.sp),
                color = RcColors.accent,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 30.dp),
            )
        }
        PosterThumb(
            hash = work.poster,
            title = work.titleFr,
            year = work.year,
            progress = work.progress?.toFloat(),
            watched = work.watched,
            modifier = Modifier.width(if (rank != null) 40.dp else 44.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(work.titleFr, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                Texts.workLine(work.year, work.type, work.directors),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
                color = RcColors.text2,
                maxLines = 1,
            )
        }
        if (!work.playable) {
            Text(Texts.notAvailable, style = MaterialTheme.typography.bodySmall, color = RcColors.text3)
        }
    }
}
