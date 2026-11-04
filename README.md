# ZaladinTV 2.0 — Android TV

Projet Kotlin/Jetpack Compose, identité ZaladinTV, interface cinéma sombre.

## Fonctions
- Import M3U local et distant HTTPS
- Connexion Xtream-compatible HTTPS avec identifiants fournis par l’utilisateur
- Catalogues TV, VOD et séries via API du fournisseur
- Sélection des épisodes par saison et lecture Media3 ExoPlayer
- Recherche, favoris, historique, navigation TV
- Aucune source de contenus intégrée

## Limites actuelles
- Pas d’EPG ni de Trakt
- Pas encore d’affiches distantes ou de fiches détaillées
- Certains fournisseurs Xtream utilisent des variantes d’API et de formats non gérées
- La session Xtream n’est pas sauvegardée : reconnecter après redémarrage
- Utiliser uniquement un fournisseur autorisé et HTTPS

## Compilation Windows
`& "$HOME\.gradle\dist\gradle-8.11.1\bin\gradle.bat" assembleDebug`

L’APK se trouve dans `app/build/outputs/apk/debug/app-debug.apk`.

## Licence

Code sous licence MIT (voir `LICENSE`). L'app ne fournit aucun contenu : elle lit tes sources autorisées (M3U, Xtream) et affiche les métadonnées TMDB.

La clé TMDB se met dans `local.properties` (`tmdb.key=...`), ne la publie pas.
