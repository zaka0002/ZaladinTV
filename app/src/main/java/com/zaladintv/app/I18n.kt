package com.zaladintv.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** App language ("fr" or "en"). Also drives the TMDB metadata language. */
object Lang {
    var code by mutableStateOf("fr")
    val tmdb: String get() = if (code == "en") "en-US" else "fr-FR"
}

private val en = mapOf(
    "Accueil" to "Home", "Films" to "Movies", "Séries" to "TV Shows", "Recherche" to "Search", "Favoris" to "Favorites", "Réglages" to "Settings",
    "Tout" to "All", "Bibliothèque" to "Library", "Langue" to "Language", "Lecture" to "Playback", "Données" to "Data", "À propos" to "About",
    "Ma bibliothèque" to "My library", "Regarder" to "Watch", "Épisodes" to "Episodes", "Fermer" to "Close", "Bande-annonce" to "Trailer",
    "Ajouter aux favoris" to "Add to favorites", "Retirer des favoris" to "Remove from favorites",
    "Films tendance" to "Trending movies", "Séries tendance" to "Trending shows", "Films populaires" to "Popular movies",
    "Séries les mieux notées" to "Top rated shows", "Populaires" to "Popular",
    "Comédie" to "Comedy", "Drame" to "Drama", "Horreur" to "Horror", "Science-fiction" to "Sci-Fi", "Aventure" to "Adventure", "Famille" to "Family",
    "Action et aventure" to "Action & Adventure", "Mystère" to "Mystery", "Science-fiction et fantastique" to "Sci-Fi & Fantasy", "Documentaire" to "Documentary",
    "Films favoris" to "Favorite movies", "Séries favorites" to "Favorite shows", "Reprendre le visionnage" to "Continue watching", "À la une" to "Featured",
    "Ma liste" to "My list", "Ajouter une source" to "Add a source", "Bienvenue dans ZaladinTV" to "Welcome to ZaladinTV",
    "Films, séries et chaînes depuis tes sources autorisées" to "Movies, shows and channels from your authorized sources",
    "Chargement des épisodes…" to "Loading episodes…", "Aucun épisode disponible" to "No episodes available", "Choisis un épisode" to "Choose an episode",
    "Recherche de la fiche…" to "Looking up details…", "Aucune fiche trouvée" to "No details found", "Pas de synopsis disponible." to "No synopsis available.",
    "Pas de résumé disponible." to "No summary available.", "Recherche de la bande-annonce…" to "Looking for the trailer…",
    "Pas de bande-annonce disponible" to "No trailer available", "Aucune appli pour lire la vidéo" to "No app to play the video",
    "Absent de ta bibliothèque. Ajoute une source dans Réglages." to "Not in your library. Add a source in Settings.",
    "Chargement du catalogue…" to "Loading catalog…", "Rechercher une chaîne, un film ou une série" to "Search a channel, movie or show",
    "Aucun favori ici. Maintiens OK sur une affiche (ou ouvre sa fiche) pour l'ajouter." to "No favorites here. Hold OK on a poster (or open its page) to add it.",
    "Aucune chaîne à afficher." to "Nothing to show.", "← Retour" to "← Back",
    "Importer un fichier M3U / M3U8" to "Import an M3U / M3U8 file", "Importer une playlist HTTPS" to "Import an HTTPS playlist",
    "Charger la playlist" to "Load playlist", "Importe une playlist M3U pour commencer" to "Import an M3U playlist to get started",
    "Connexion à une source Xtream autorisée" to "Connect to an authorized Xtream source",
    "Serveur HTTPS (ex. https://monserveur.example)" to "HTTPS server (e.g. https://myserver.example)",
    "Identifiant" to "Username", "Mot de passe" to "Password", "Charger films et séries" to "Load movies and shows",
    "Aucun contenu n’est fourni. Les catégories Films et Séries sont détectées à partir des groupes M3U (VOD, Films, Series…). Utilise uniquement des sources autorisées." to
        "No content is provided. Movie and show categories are detected from M3U groups (VOD, Movies, Series…). Use authorized sources only.",
    "Langue de l'application et des fiches" to "App and details language",
    "Reprendre là où je me suis arrêté" to "Resume where I left off", "Activé" to "On", "Désactivé" to "Off",
    "Vider les favoris" to "Clear favorites", "Effacer « Reprendre le visionnage »" to "Clear \"Continue watching\"",
    "Oublier la source (M3U / Xtream)" to "Forget source (M3U / Xtream)", "Vider le cache des images" to "Clear image cache",
    "Passer l'intro" to "Skip intro", "Explorer" to "Explore", "Plateformes" to "Platforms", "Voir plus" to "See more", "Synopsis" to "Synopsis", "Quitter" to "Quit", "Thriller / Mystère" to "Thriller / Mystery", "Mieux notés" to "Top rated", "Récents" to "Recent",
    "Tous les genres" to "All genres", "Fantastique" to "Fantasy", "Sélectionne une affiche" to "Select a poster",
    "Genres" to "Genres", "Réalisation" to "Directors", "Création" to "Created by", "Distribution" to "Cast",
    "Retour : fermer   •   OK maintenu : favori" to "Back: close   •   Hold OK: favorite", "Confirmer ?" to "Confirm?", "Fait" to "Done", "Licence MIT" to "MIT license",
    "Les fiches, affiches et bandes-annonces viennent de TMDB. Ce produit utilise l'API TMDB mais n'est ni approuvé ni certifié par TMDB." to
        "Details, posters and trailers come from TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB.",
)

/** Translates a known UI string when the app language is English; otherwise returns it unchanged. */
fun uiLabel(s: String): String = if (Lang.code == "en") en[s] ?: s else s
