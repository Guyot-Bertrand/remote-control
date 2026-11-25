package fr.remotecontrol.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fr.remotecontrol.ui.theme.Inter
import fr.remotecontrol.ui.theme.RcColors
import java.util.Locale

/** Style des boutons pleine largeur des fiches (maquettes : 16/600 en 56 dp, 15 en 48 dp). */
private fun buttonText(height: Dp, weight: FontWeight = FontWeight.SemiBold) =
    TextStyle(fontFamily = Inter, fontWeight = weight, fontSize = if (height >= 56.dp) 16.sp else 15.sp)

private val disabledBg = RcColors.text.copy(alpha = 0.12f)
private val disabledFg = RcColors.text.copy(alpha = 0.38f)

/**
 * Bouton en pilule des fiches : [Tone.Primary] (laiton plein), [Tone.Soft] (laiton doux, avec la barre
 * d'avancement [progress] de la reprise) ou [Tone.Outlined] (contour, « Depuis le début »).
 */
@Composable
fun PillButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Primary,
    enabled: Boolean = true,
    height: Dp = 56.dp,
    progress: Float? = null,
) {
    val (bg, fg) = when {
        !enabled -> disabledBg to disabledFg
        tone == Tone.Primary -> RcColors.accent to RcColors.onAccent
        tone == Tone.Soft -> RcColors.accentSoft to RcColors.accentStrong
        else -> Color.Transparent to RcColors.text
    }
    val shape = RoundedCornerShape(height / 2)
    Box(
        modifier
            .height(height)
            .clip(shape)
            .background(bg)
            .then(if (tone == Tone.Outlined && enabled) Modifier.border(1.dp, RcColors.text3, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (height >= 56.dp) 10.dp else 8.dp),
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(if (height >= 56.dp) 22.dp else 20.dp))
            Text(text, style = buttonText(height, if (tone == Tone.Outlined) FontWeight.Medium else FontWeight.SemiBold), color = fg, maxLines = 1)
        }
        if (progress != null && enabled) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 32.dp, end = 32.dp, bottom = 9.dp)
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(RcColors.accentStrong.copy(alpha = 0.25f)),
            ) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(RcColors.accentStrong))
            }
        }
    }
}

enum class Tone { Primary, Soft, Outlined }

/**
 * Pastille de qualité, cerclée (« FHD ») ; estompée quand la version n'est pas sur un disque branché.
 * [detail] (la résolution, « 704 × 576 ») suit la qualité dans la même pastille, après un filet.
 */
@Composable
fun QualityPill(text: String, modifier: Modifier = Modifier, dimmed: Boolean = false, detail: String? = null) {
    Row(
        modifier
            .alpha(if (dimmed) 0.5f else 1f)
            .border(1.5.dp, RcColors.text2, RoundedCornerShape(4.dp))
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.06.em, lineHeight = 14.sp),
            color = RcColors.text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        )
        if (detail != null) {
            Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 3.dp).background(RcColors.text3))
            Text(
                detail,
                style = TextStyle(fontFamily = Inter, fontSize = 11.sp, lineHeight = 14.sp),
                color = RcColors.text2,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

/** Pastille de langue, sur fond atténué (« Anglais », « ST français »). */
@Composable
fun LanguagePill(text: String, modifier: Modifier = Modifier, dimmed: Boolean = false) {
    Text(
        text,
        style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 14.sp),
        color = RcColors.text2,
        maxLines = 1,
        modifier = modifier
            .alpha(if (dimmed) 0.5f else 1f)
            .clip(RoundedCornerShape(4.dp))
            .background(RcColors.surface2)
            .padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

/** Pastilles d'un fichier (maquette 4c) : qualité et résolution, puis langues. */
data class FilePills(val quality: String, val resolution: String? = null, val languages: List<String> = emptyList())

/** Rangée des pastilles d'un fichier, en tête de la télécommande et de « Version et pistes ». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilePillsRow(pills: FilePills, modifier: Modifier = Modifier) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        QualityPill(pills.quality, detail = pills.resolution)
        pills.languages.forEach { LanguagePill(it) }
    }
}

/** Titre de section en petites capitales (« AVEC », « COLLECTIONS »). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(Locale.FRENCH),
        style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.12.em),
        color = RcColors.text3,
        modifier = modifier,
    )
}

/** Surtitre laiton (« FILM », « SÉRIE »). */
@Composable
fun Overline(text: String) {
    Text(
        text.uppercase(Locale.FRENCH),
        style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.14.em),
        color = RcColors.accent,
    )
}

/** Mention « Affichée sur la télé ». */
@Composable
fun ShownOnTv(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(RcIcons.Tv, contentDescription = null, tint = RcColors.text3, modifier = Modifier.size(16.dp))
        Text(Texts.shownOnTv, style = TextStyle(fontFamily = Inter, fontSize = 12.sp), color = RcColors.text3)
    }
}

/**
 * Encart d'indisponibilité des fiches : [warn] (disque non branché, terracotta) ou neutre (non numérisé),
 * avec une action facultative (« Réessayer »).
 */
@Composable
fun NoticeCard(
    icon: ImageVector,
    title: String,
    body: String,
    warn: Boolean,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (warn) RcColors.warnSoft else RcColors.surface2)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (warn) RcColors.warn else RcColors.text, modifier = Modifier.padding(top = 1.dp).size(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                color = if (warn) RcColors.warn else RcColors.text,
            )
            Text(body, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp), color = RcColors.text2)
            if (action != null) {
                TextButton(onClick = onAction, modifier = Modifier.height(40.dp)) {
                    Text(action, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp), color = RcColors.accentStrong)
                }
            }
        }
    }
}

/** Message d'erreur d'une commande (lecture refusée, serveur injoignable), refermable. */
@Composable
fun MessageBanner(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RcColors.warnSoft)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp),
            color = RcColors.warn,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(RcIcons.Clear, contentDescription = Texts.dismiss, tint = RcColors.text2)
        }
    }
}

/** Fiche demandée absente du catalogue local (supprimée, ou QR-code périmé). */
@Composable
fun NotFound(text: String) {
    Text(
        text,
        style = TextStyle(fontFamily = Inter, fontSize = 15.sp),
        color = RcColors.text2,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
    )
}

/**
 * Bandeau persistant « lecteur absent du PC » (terre cuite) : fiches, écran de choix et accueil. Les boutons
 * de lecture sont désactivés à côté ; ce bandeau en donne la raison.
 */
@Composable
fun PlayerMissingBanner(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RcColors.warnSoft)
            .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(RcIcons.Tv, contentDescription = null, tint = RcColors.warn, modifier = Modifier.size(22.dp))
        Text(
            Texts.playerUnavailable,
            style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.sp),
            color = RcColors.text,
            modifier = Modifier.weight(1f),
        )
        // mpv installé entre-temps : nouvelle sonde du serveur.
        TextButton(onClick = onRetry, modifier = Modifier.height(40.dp)) {
            Text(Texts.retry, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp), color = RcColors.accentStrong)
        }
    }
}

/**
 * La cible de la fiche joue déjà sur la télé : « Ouvrir la télécommande » (sans relancer la lecture, la télé
 * ne clignote pas) et la mention « En cours sur la télé ».
 */
@Composable
fun OpenRemoteAction(onOpenRemote: () -> Unit, modifier: Modifier = Modifier, height: Dp = 56.dp) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton(Texts.openRemote, RcIcons.Remote, onOpenRemote, Modifier.fillMaxWidth(), height = height)
        PlayingOnTv()
    }
}

/** Mention « En cours sur la télé » (petite, icône télé). */
@Composable
fun PlayingOnTv(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(RcIcons.Tv, contentDescription = null, tint = RcColors.accentStrong, modifier = Modifier.size(16.dp))
        Text(Texts.playingOnTv, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp), color = RcColors.accentStrong)
    }
}
