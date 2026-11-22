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
    const val scan = "Scanner un QR-code"
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
    const val watched = "Vu"
    const val subtitleNone = "Aucun"
    const val playableOnly = "Œuvres lisibles"
    const val wholeCatalogue = "Tout le catalogue"
    const val catalogEmpty = "Catalogue vide : recherche du serveur…"
    const val qrUnknown = "QR-code non reconnu"
    const val qrNotInCatalog = "Œuvre introuvable dans le catalogue"
    const val cameraPermission = "Autorisez l'appareil photo pour scanner les QR-codes"
    const val invalidAddress = "Adresse invalide"
    const val addressUnreachable = "Serveur injoignable à cette adresse"
    const val nothingPlaying = "Lecture en cours"

    fun volumeAbsent(label: String?) = label?.let { "Disque « $it » non branché" } ?: "Disque non branché"
    fun httpError(status: Int) = "Erreur du serveur ($status)"
    fun resumeAt(seconds: Double) = "Reprendre à ${formatHhMm(seconds)}"
    fun started(seconds: Double) = "Entamé — ${formatClock(seconds)}"
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
}
