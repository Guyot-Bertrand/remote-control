package fr.remotecontrol.ui.common

import java.util.Locale

/** Tous les textes visibles par l'utilisateur, en français. */
object Texts {
    // Libellés d'écran
    const val appName = "Remote-Control"
    const val back = "Retour"
    const val sectionRecent = "Récemment ajoutés"
    const val sectionBrowse = "Parcourir"
    const val sectionCollections = "Collections"
    const val search = "Rechercher"
    const val searchHint = "Titre, réalisateur, acteur…"
    const val scan = "Scanner une fiche"
    const val remote = "Télécommande"
    const val settings = "Réglages"
    const val notAvailable = "Non lisible"
    const val settingsServer = "Serveur"
    const val manualAddress = "Adresse manuelle (secours)"
    const val save = "Enregistrer"
    const val rediscover = "Rechercher le serveur"
    const val resync = "Resynchroniser"
    const val seasons = "Saisons"
    const val versions = "Versions"
    const val version = "Version"
    const val audio = "Audio"
    const val subtitles = "Sous-titres"
    const val startPlayback = "Lancer la lecture"
    const val play = "Lecture"
    const val pause = "Pause"
    const val restart = "Début"
    const val volume = "Volume"
    const val stop = "Arrêter"
    const val directors = "Réalisation"
    const val cast = "Avec"
    const val work = "Fiche de l'œuvre"
    const val season = "Saison"

    // Parcours et recherche
    const val allTypes = "Tout"
    const val allWorks = "Toutes les œuvres"
    const val sortTitle = "Titre"
    const val sortRecent = "Ajout récent"
    const val changeSort = "Changer le tri"
    const val byRank = "par rang"
    const val nothingToShow = "Aucune œuvre à afficher"
    const val noResults = "Aucun résultat"
    const val searchHelp = "Titres, titres originaux et alternatifs, réalisation, casting — sans tenir compte des accents."
    const val clear = "Effacer"
    const val openWork = "Ouvrir la fiche"

    // Barre de navigation
    const val navHome = "Accueil"
    const val navBrowse = "Parcourir"
    const val navSearch = "Recherche"
    const val navScan = "Scanner"

    // Pastille de connexion
    const val chipConnected = "PC connecté"
    const val chipSearching = "Recherche…"
    const val chipOffline = "Hors ligne"

    // Messages
    const val serverUnreachable = "Serveur injoignable"
    const val noServer = "Aucun serveur trouvé"
    const val searching = "Recherche du serveur…"
    const val notPlayable = "Aucun fichier lisible pour cette œuvre"
    const val notFound = "Fichier introuvable"
    const val badResponse = "Réponse inattendue du serveur."
    const val idle = "Aucune lecture en cours"
    const val notDigitized = "Non numérisé"
    const val playFromStart = "Lire depuis le début"
    const val fromStart = "Depuis le début"
    const val resume = "Reprendre"
    const val tracksAndVersion = "Version et pistes…"
    const val prePlayTitle = "Avant la lecture"
    const val choicesRemembered = "Ces choix seront repris la prochaine fois."
    const val playOnTv = "Lire sur la télé"
    const val badgeDefault = "Par défaut"
    const val badgeLastChoice = "Dernier choix"
    const val close = "Fermer"
    const val watched = "Vu"
    const val subtitleNone = "Aucun"
    const val playableOnly = "Œuvres lisibles"
    const val wholeCatalogue = "Tout le catalogue"
    const val seeAll = "Tout voir"
    const val catalogEmptySearching = "Catalogue vide : recherche du serveur…"
    const val catalogEmptySyncing = "Catalogue vide : synchronisation…"
    const val qrUnknown = "QR-code non reconnu"
    const val cameraPermission = "Autorisez l'appareil photo pour scanner les QR-codes"

    // Scan des QR-codes
    const val scanHint = "Visez le QR-code d'une fiche imprimée : l'œuvre ou la saison s'ouvre aussitôt."
    const val qrUnknownDetail = "QR-code non reconnu. Ce n'est pas une fiche Remote-Control."
    const val ok = "OK"
    const val torch = "Lampe"
    const val cameraView = "Vue de la caméra"
    const val cameraDenied = "Appareil photo non autorisé"
    const val allowCamera = "Autoriser"
    const val openAppSettings = "Ouvrir les réglages"
    const val cameraUnavailable = "Appareil photo indisponible"
    const val cameraUnavailableHelp = "Aucune caméra arrière utilisable sur cet appareil."
    const val invalidAddress = "Adresse invalide"
    const val addressUnreachable = "Serveur injoignable à cette adresse"
    const val nothingPlaying = "Lecture en cours"
    const val playerNotResponding = "Le lecteur ne répond pas."

    // Télécommande
    const val onTv = "Sur la télé"
    const val minimize = "Réduire"
    const val seekPosition = "Position"
    const val pausedNow = "En pause"
    const val volumeDown = "Baisser le volume"
    const val volumeUp = "Monter le volume"
    const val playbackEnded = "Lecture terminée"
    const val playbackInterrupted = "Lecture interrompue"
    const val backToSheet = "Retour à la fiche"
    const val unknownTrack = "—"

    // Fiches œuvre et saison
    const val shownOnTv = "Affichée sur la télé"
    const val volumeAbsentHelp = "Branchez-le sur le PC, puis réessayez."
    const val notDigitizedHelp = "Cette œuvre n'est pas encore numérisée. Une fois numérisée, elle deviendra lisible ici."
    const val retry = "Réessayer"
    const val workNotFound = "Œuvre introuvable."
    const val seasonNotFound = "Saison introuvable."
    const val dismiss = "Fermer"
    const val inProgress = "En cours"
    const val showMore = "Afficher plus"
    const val showLess = "Afficher moins"

    fun volumeAbsent(label: String?) = label?.let { "Disque « $it » non branché" } ?: "Disque non branché"
    fun httpError(status: Int) = "Erreur du serveur ($status)"
    /** « Reprendre à 01:12 » à partir d'une heure, « Reprendre à 3 min » ou « Reprendre à 45 s » avant. */
    fun resumeAt(seconds: Double) = "Reprendre à ${formatResumePosition(seconds)}"
    fun started(seconds: Double) = "Entamé — ${formatClock(seconds)}"
    fun workCount(count: Int) = if (count == 1) "1 œuvre" else "$count œuvres"
    fun collectionCounts(count: Int, playable: Int) = "${workCount(count)} · $playable ${if (playable > 1) "lisibles" else "lisible"}"
    fun remaining(minutes: Int) = "reste ${formatDuration(minutes)}"
    /** « 2017 · 1 h 39 » : éléments connus, séparés par un point médian. */
    fun details(vararg parts: String?) = parts.filterNotNull().joinToString(" · ")
    fun genres(list: List<String>) = list.joinToString(", ") { g -> g.replaceFirstChar { it.titlecase(Locale.FRENCH) } }
    /** Libellé accessible d'un bouton d'épisode : « Reprendre S01E02 ». */
    fun episodeAction(action: String, episode: String) = "$action $episode"
    fun directorsDescription(names: List<String>) = "$directors : ${names.joinToString(", ")}"
    fun versionDescription(label: String, absent: String) = "$label, $absent"
    fun alsoKnownAs(title: String) = "Aussi : $title"
    fun seasonCount(count: Int) = if (count == 1) "1 saison" else "$count saisons"
    fun episodesSummary(count: Int, watched: Int) =
        "${if (count == 1) "1 épisode" else "$count épisodes"} · $watched ${if (watched > 1) "vus" else "vu"}"
    fun synced(count: Int) = "Catalogue synchronisé ($count œuvres)"

    // Fonctions de libellé
    fun typeLabel(type: String): String = when (type) {
        "film" -> "Films"
        "serie" -> "Séries"
        "doc" -> "Documentaires"
        "theatre" -> "Théâtre"
        "tele" -> "Téléfilms"
        "spectacle" -> "Spectacles"
        else -> type
    }

    /** Type au singulier, pour la ligne de détail d'une œuvre (« 2017 · Film »). */
    fun typeSingular(type: String): String = when (type) {
        "film" -> "Film"
        "serie" -> "Série"
        "doc" -> "Documentaire"
        "theatre" -> "Théâtre"
        "tele" -> "Téléfilm"
        "spectacle" -> "Spectacle"
        else -> type
    }

    /** « 1 912 films », « 1 série », « 3 œuvres » (type nul : tous types). */
    fun countOfType(type: String?, count: Int): String {
        val plural = count > 1
        val noun = when (type) {
            "film" -> if (plural) "films" else "film"
            "serie" -> if (plural) "séries" else "série"
            "doc" -> if (plural) "documentaires" else "documentaire"
            "theatre" -> if (plural) "pièces de théâtre" else "pièce de théâtre"
            "tele" -> if (plural) "téléfilms" else "téléfilm"
            "spectacle" -> if (plural) "spectacles" else "spectacle"
            else -> if (plural) "œuvres" else "œuvre"
        }
        return "${formatCount(count)}\u00A0$noun"
    }

    fun sortLabel(recent: Boolean) = if (recent) sortRecent else sortTitle
    fun browseSummary(type: String?, count: Int, recent: Boolean) = "${countOfType(type, count)} · ${sortLabel(recent)}"
    fun collectionSummary(count: Int, playable: Int, ranked: Boolean) =
        if (ranked) "${collectionCounts(count, playable)} · $byRank" else collectionCounts(count, playable)
    fun workLine(year: Int?, type: String, directors: List<String>) =
        listOfNotNull(year?.toString(), typeSingular(type), directors.joinToString(", ").ifEmpty { null }).joinToString(" · ")

    fun languageName(code: String?): String = when (code) {
        null -> "Langue inconnue"
        "fr" -> "Français"
        "en" -> "Anglais"
        "de" -> "Allemand"
        "es" -> "Espagnol"
        "it" -> "Italien"
        "ja" -> "Japonais"
        "ru" -> "Russe"
        "pt" -> "Portugais"
        "nl" -> "Néerlandais"
        else -> code.uppercase()
    }

    fun episodeCode(season: Int, number: Int): String =
        if (season == 0) "Épisode $number" else "S%02dE%02d".format(Locale.ROOT, season, number)

    /** « S01E02 », suivi de « — titre » si l'épisode a un titre. */
    fun episodeLabel(season: Int, number: Int, title: String?): String =
        episodeCode(season, number).let { code -> title?.let { "$code — $it" } ?: code }

    fun episodeTitle(work: String, season: Int, number: Int): String =
        if (season == 0) "$work — épisode $number" else "$work — ${episodeCode(season, number)}"

    fun versionLabel(quality: String, size: String?): String =
        if (size == null) quality else "$quality · $size"

    fun serverDetected(display: String) = "$display (détecté)"
    fun serverManual(display: String) = "$display (saisi à la main)"
    fun catalogInfo(revision: Long?, count: Int) = "Révision du catalogue : ${revision ?: "—"} · $count œuvres"
    fun externalSub(lang: String?) = "${languageName(lang)} (fichier)"
    fun embeddedSub(lang: String?) = "${languageName(lang)} (intégré)"
    fun skipLabel(seconds: Int) = if (seconds < 0) "−${-seconds} s" else "+$seconds s"
    /** Libellé accessible des boutons de saut : « Reculer de 10 secondes ». */
    fun skipDescription(seconds: Int) =
        "${if (seconds < 0) "Reculer" else "Avancer"} de ${kotlin.math.abs(seconds)} secondes"
    /** Temps restant, précédé d'un signe moins : « −0:26:56 ». */
    fun remainingClock(seconds: Double) = "−${formatClock(seconds)}"
    fun volumeLevel(level: Int) = "$volume $level\u00A0%"
}
