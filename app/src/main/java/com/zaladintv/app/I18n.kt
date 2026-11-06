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
    "Films favoris" to "Favorite movies", "Séries favorites" to "Favorite shows", "Reprendre le visionnage" to "Continue watching", "À la une" to "Featured", "Mettre à jour" to "Update", "Mise à jour…" to "Updating…",
    "Ma liste" to "My list", "Ajouter une source" to "Add a source", "Bienvenue dans ZaladinTV" to "Welcome to ZaladinTV",
    "Films, séries et chaînes depuis tes sources autorisées" to "Movies, shows and channels from your authorized sources",
    "Chargement des épisodes…" to "Loading episodes…", "Aucun épisode disponible" to "No episodes available", "Choisis un épisode" to "Choose an episode",
    "Recherche de la fiche…" to "Looking up details…", "Aucune fiche trouvée" to "No details found", "Pas de synopsis disponible." to "No synopsis available.",
    "Pas de résumé disponible." to "No summary available.", "Recherche de la bande-annonce…" to "Looking for the trailer…",
    "Pas de bande-annonce disponible" to "No trailer available", "Aucune appli pour lire la vidéo" to "No app to play the video",
    "Absent de ta bibliothèque. Ajoute une source dans Réglages." to "Not in your library. Add a source in Settings.",
    "Chargement du catalogue…" to "Loading catalog…", "Rechercher une chaîne, un film ou une série" to "Search a channel, movie or show", "Aucun résultat en français." to "No French results.", "Nouvelle version" to "New version", "Version installée" to "Installed version", "Installer" to "Install", "Plus tard" to "Later", "VPN Cloudflare WARP intégré, gratuit et sans compte. Il ne concerne que ZaladinTV : le reste de la TV garde sa connexion normale." to "Built-in Cloudflare WARP VPN, free and without an account. It only covers ZaladinTV: the rest of the TV keeps its normal connection.", "Tape le nom d'une chaîne, d'un film ou d'une série." to "Type the name of a channel, movie or show.",
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
    "Passer l'intro" to "Skip intro", "Explorer" to "Explore", "Plateformes" to "Platforms", "Voir plus" to "See more", "Ajoute d'abord une clé." to "Add a key first.", "Mon adresse IP" to "My IP address",
    "DNS" to "DNS", "VPN" to "VPN", "Choisis le serveur DNS utilisé par ZaladinTV (chiffré). Il ne change que le trafic de l'appli, pas le reste de la TV." to "Pick the DNS server ZaladinTV uses (encrypted). It only changes the app's traffic, not the rest of the TV.",
    "Système (par défaut)" to "System (default)", "AdGuard (bloque pubs et traqueurs)" to "AdGuard (blocks ads and trackers)", "AdGuard Famille (filtre adulte)" to "AdGuard Family (adult filter)", "AdGuard sans filtre" to "AdGuard unfiltered",
    "Activer le VPN" to "Turn on VPN", "Ajoute d'abord une clé WireGuard ci-dessous." to "Add a WireGuard key below first.", "Désactivé" to "Off", "Connexion…" to "Connecting…", "Connecté" to "Connected",
    "Autorisation Android requise" to "Android permission needed", "Échec" to "Failed", "Mon adresse IP vue de l'extérieur" to "My public IP address", "Appuie pour vérifier" to "Press to check", "Vérification…" to "Checking…",
    "VPN WireGuard réservé à ZaladinTV : le reste de la TV garde sa connexion normale. Tu fournis ta propre clé (par exemple un fichier WireGuard gratuit de Proton VPN)." to "WireGuard VPN for ZaladinTV only: the rest of the TV keeps its normal connection. You bring your own key (for example a free Proton VPN WireGuard file).",
    "Ajouter la clé WireGuard" to "Add the WireGuard key", "Scanne avec ton téléphone (même Wi-Fi), puis colle le fichier WireGuard." to "Scan with your phone (same Wi-Fi), then paste the WireGuard file.",
    "clé(s) reçue(s)" to "key(s) received", "Aucune clé WireGuard valide dans ce texte." to "No valid WireGuard key in this text.", "Clé WireGuard (VPN)" to "WireGuard key (VPN)",
    "Rien reçu : colle le contenu du fichier WireGuard." to "Nothing received: paste the content of the WireGuard file.", "Serveur (http://…)" to "Server (http://…)", "Ajoute uniquement des sources auxquelles tu as droit." to "Only add sources you are entitled to.", "Sources" to "Sources", "Ajouter avec le téléphone" to "Add with your phone", "Ajout manuel" to "Add manually", "Confirmer ?" to "Confirm?",
    "Scanne avec ton téléphone (même Wi-Fi), puis saisis ta source Xtream." to "Scan with your phone (same Wi-Fi), then enter your Xtream source.", "Menu" to "Menu", "Onglets du menu" to "Menu tabs", "Active les onglets supplémentaires du menu du haut." to "Turn on the extra tabs of the top menu.", "Contenu arabe" to "Arabic content", "Affiche l'onglet Arabe dans le menu du haut." to "Shows the Arabic tab in the top menu.", "activé" to "on", "désactivé" to "off", "Arabe" to "Arabic", "Dans tes sources" to "In your sources", "Aucun contenu arabe détecté dans tes sources (catégories AR, Arabic, Maghreb…)." to "No Arabic content found in your sources (AR, Arabic, Maghreb categories…).", "Français seulement : oui" to "French only: yes", "Français seulement : non" to "French only: no", "Mes sources Xtream" to "My Xtream sources", "Aucune source pour l'instant." to "No source yet.", "Supprimer" to "Remove",
    "Ajouter une source Xtream" to "Add an Xtream source", "Nom (facultatif)" to "Name (optional)", "Ajouter la source" to "Add the source", "Chargement…" to "Loading…",
    "Ajoute une source Xtream pour commencer" to "Add an Xtream source to get started", "Oublier toutes les sources" to "Forget all sources", "Cette source est déjà ajoutée" to "This source is already added",
    "Aucun contenu n’est fourni. Ajoute uniquement des sources auxquelles tu as droit. Tu peux en ajouter plusieurs : leurs films et séries sont regroupés." to "No content is provided. Only add sources you are entitled to. You can add several: their movies and shows are merged.",
    "C'est envoyé à la TV. Tu peux fermer cette page ou en ajouter une autre." to "Sent to the TV. You can close this page or add another one.", "Mise à jour" to "Update", "Vérifier les mises à jour" to "Check for updates", "Recherche de mise à jour…" to "Checking for updates…", "Nouvelle version disponible" to "New version available", "Tu as la dernière version" to "You have the latest version", "Impossible de vérifier : vérifie ta connexion." to "Could not check: check your connection.", "Téléchargement…" to "Downloading…", "Installation lancée : valide sur l'écran." to "Installation started: confirm on screen.", "Autorise ZaladinTV à installer des applis, puis reviens et relance." to "Allow ZaladinTV to install apps, then come back and retry.", "Échec du téléchargement." to "Download failed.", "Télécharger et installer" to "Download and install",
    "Ajouter depuis ton téléphone" to "Add from your phone", "Scanne le QR code avec ton téléphone (même Wi-Fi) et saisis ta playlist sur le téléphone au lieu de la télécommande." to "Scan the QR code with your phone (same Wi-Fi) and enter your playlist on the phone instead of the remote.",
    "Afficher le QR code" to "Show QR code", "Masquer le QR code" to "Hide QR code", "Ou ouvre cette adresse sur le téléphone :" to "Or open this address on your phone:",
    "Le code change à chaque affichage et ne marche que sur ton réseau local." to "The code changes every time and only works on your local network.",
    "Reçu, chargement…" to "Received, loading…", "Pas de réseau local détecté. Connecte la TV au Wi-Fi." to "No local network found. Connect the TV to Wi-Fi.",
    "C'est envoyé à la TV. Tu peux fermer cette page." to "Sent to the TV. You can close this page.", "Lien invalide : il doit commencer par http:// ou https://" to "Invalid link: it must start with http:// or https://",
    "Playlist M3U (lien http ou https)" to "M3U playlist (http or https link)", "Importer une playlist (lien http ou https)" to "Import a playlist (http or https link)", "Serveur (ex. http://monserveur.example:8080)" to "Server (e.g. http://myserver.example:8080)", "Adresse du serveur invalide (http:// ou https://)" to "Invalid server address (http:// or https://)", "Utilise une URL http:// ou https://" to "Use an http:// or https:// URL", "Source Xtream autorisée" to "Authorised Xtream source", "Envoyer à la TV" to "Send to the TV",
     "Synopsis" to "Synopsis", "Quitter" to "Quit", "Quitter ZaladinTV ?" to "Quit ZaladinTV?", "L'application va se fermer." to "The app will close.", "Annuler" to "Cancel", "Thriller / Mystère" to "Thriller / Mystery", "Mieux notés" to "Top rated", "Récents" to "Recent",
    "Tous les genres" to "All genres", "Fantastique" to "Fantasy", "Sélectionne une affiche" to "Select a poster",
    "Genres" to "Genres", "Réalisation" to "Directors", "Création" to "Created by", "Distribution" to "Cast",
    "Retour : fermer   •   OK maintenu : favori" to "Back: close   •   Hold OK: favorite", "Confirmer ?" to "Confirm?", "Fait" to "Done", "Licence MIT" to "MIT license",
    "Les fiches, affiches et bandes-annonces viennent de TMDB. Ce produit utilise l'API TMDB mais n'est ni approuvé ni certifié par TMDB." to
        "Details, posters and trailers come from TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB.",
)

/** Translates a known UI string when the app language is English; otherwise returns it unchanged. */
fun uiLabel(s: String): String = if (Lang.code == "en") en[s] ?: s else s
