# SoufflyTV

Lecteur IPTV libre pour **Android** (téléphone et Android TV), **Linux** et **Windows**.

SoufflyTV est un **lecteur vide** : il ne contient et ne propose aucune liste ni aucune chaîne.
L'utilisateur ajoute ses propres sources :

| Type | Ce qu'il faut saisir |
|---|---|
| **Playlist M3U / M3U8** | URL de la playlist (User-Agent facultatif) |
| **Xtream Codes** | Serveur, identifiant, mot de passe (ou un lien `get.php?username=…` complet) |
| **Portail Stalker / Ministra** | URL du portail (`http://hôte:port/c/`) + adresse MAC |

Licence : **GPL v3**. Aucune dépendance propriétaire (Compose, Ktor, Coil, Media3 : Apache 2.0 ; vlcj, décodeur FFmpeg Jellyfin : GPL v3).

## Architecture

Kotlin Multiplatform + Compose Multiplatform, un seul module `composeApp` :

```
composeApp/src/
├── commonMain/     ← 95 % du code, partagé
│   ├── data/       M3uParser, XtreamProvider, StalkerProvider, SourceRepository
│   ├── ui/         écrans (sources, ajout, catégories/chaînes, lecteur) + thème Cyanogen
│   └── player/     interface du lecteur (expect)
├── androidMain/    MainActivity, lecteur Media3/ExoPlayer, stockage
└── desktopMain/    fenêtre, lecteur libVLC (vlcj), stockage
```

Les sources sont enregistrées dans `sources.json` :
- Android : dossier privé de l'app (sauvegarde Android désactivée)
- Linux : `~/.local/share/soufflytv/`
- Windows : `%APPDATA%\SoufflyTV\`

## Compiler

Prérequis : JDK 17 ou plus récent (celui d'Android Studio convient).

### Android : deux APK

| APK | Pour | Particularités |
|---|---|---|
| **tv** | Android TV, box, clé TV | toujours en grand écran (barre de menus en haut), télécommande, pas de rotation ni d'image dans l'image ; exige Android TV |
| **mobile** | smartphone, tablette | s'adapte à l'écran (portrait / paysage), image dans l'image, paysage auto ; exige un écran tactile |

Les deux ont le même identifiant (`fr.bonobo.soufflytv`) : installer l'un par-dessus l'autre conserve
sources, favoris et réglages.

Dans Android Studio : panneau **Build Variants** → `tvDebug` ou `mobileDebug`. En ligne de commande :
```bash
./gradlew :composeApp:assembleTvDebug       # composeApp/build/outputs/apk/tv/debug/composeApp-tv-debug.apk
./gradlew :composeApp:assembleMobileDebug   # composeApp/build/outputs/apk/mobile/debug/composeApp-mobile-debug.apk
./gradlew :composeApp:assembleDebug         # les deux
adb install -r composeApp/build/outputs/apk/tv/debug/composeApp-tv-debug.apk
```

### Version release signée (TV + smartphone)

1. Créer la clé **une seule fois** (et la sauvegarder : sans elle, plus de mises à jour possibles) :
   ```bash
   mkdir -p ~/AndroidStudioProjects/keystores
   keytool -genkeypair -v -keystore ~/AndroidStudioProjects/keystores/souffly.jks \
           -alias souffly -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Copier `keystore.properties.example` en `keystore.properties` et le remplir (ignoré par Git).
3. Construire les deux APK :
   ```bash
   ./gradlew :composeApp:releaseApks
   ```
   → `composeApp/build/outputs/apk/tv/release/SoufflyTV-tv-<version>.apk`
   → `composeApp/build/outputs/apk/mobile/release/SoufflyTV-mobile-<version>.apk`

Un APK release ne s'installe pas par-dessus une version debug (signatures différentes) :
désinstaller la version debug d'abord.

### Linux
```bash
./gradlew :composeApp:run                       # lancer
./gradlew :composeApp:packageDistributionForCurrentOS   # .deb + .rpm
```
La lecture utilise **libVLC 3**, installée sur le système (vlcj ne voit pas le VLC Flatpak).
Sur un système immuable (Aurora, Silverblue…) :
```bash
rpm-ostree install vlc-libs     # puis redémarrer
# ou travailler dans une distrobox Fedora avec « sudo dnf install vlc »
```

### Flatpak (Linux)

Le Flatpak embarque sa propre libVLC (avec FFmpeg : H.264, HEVC…) : rien à installer sur le système,
fonctionne sur Aurora / Silverblue / Kinoite comme sur toute distribution.

```bash
./flatpak/build-flatpak.sh
```

Le script : remplit les empreintes SHA-256 des sources (une fois), construit l'application Java
(dans la distrobox `soufflytv` si Java n'est pas sur le système), installe les outils Flatpak,
compile FFmpeg + libVLC (15 à 30 min la première fois, ensuite en cache), installe l'application
et produit `SoufflyTV.flatpak` à partager.

```bash
flatpak run app.souffly.SoufflyTV              # lancer
flatpak install --user SoufflyTV.flatpak       # installer le fichier sur un autre PC
```

Fichiers : `flatpak/app.souffly.SoufflyTV.yml` (manifeste), `.desktop`, `.metainfo.xml`, icône SVG.
Données de l'app : `~/.var/app/app.souffly.SoufflyTV/data/soufflytv/`.

### Windows

L'installeur embarque **libVLC** (VLC 3, 64 bits) : rien d'autre à installer. Installation pour
l'utilisateur, sans droits administrateur, avec raccourci et entrée dans le menu Démarrer.

**Sans PC Windows : GitHub Actions** (`.github/workflows/release.yml`)
```bash
git tag v0.6.1 && git push origin v0.6.1
```
→ une « Release » GitHub avec `SoufflyTV-x.y.z.msi`, `SoufflyTV-x.y.z.exe`, et les APK TV + smartphone
(signés si les secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` sont définis).
Le workflow peut aussi être lancé à la main (onglet Actions → Release → Run workflow).

**Sur un PC Windows** (JDK 21 : `winget install EclipseAdoptium.Temurin.21.JDK`, et Android Studio
ou le SDK Android, car le projet contient aussi l'app Android) :
```bat
powershell -ExecutionPolicy Bypass -File windows\build-windows.ps1
```
→ `composeApp\build\compose\binaries\main\msi\` et `exe\`.

## Utilisation

**Navigation façon TiviMate, esprit Souffly** — la vidéo reste au centre, les listes viennent par-dessus.

| Télécommande (direct) | Action |
|---|---|
| **OK** | liste des chaînes du groupe en cours, chaîne regardée en surbrillance |
| **◀ gauche** | colonne des groupes (Favoris, Récents, TV HD, CINEMA…) |
| **▶ droite** | commandes : favori, audio, sous-titres, format, image dans l'image |
| **▲ ▼**, **CH+ / CH−**, **+ / −** | chaîne précédente / suivante |
| **chiffres** | aller directement à une chaîne (1 puis 2 → 12) |
| **Retour** | referme les groupes, puis la liste, puis les commandes, puis quitte le lecteur |

Films et séries : ◀ ▶ = −10 s / +10 s, OK = commandes (barre de temps, sauts −10 min à +10 min).

Champs de saisie à la télécommande : **▲ ▼** passent dessus comme sur un bouton, **OK** ouvre le clavier,
« Terminé » ou **Retour** le referme ; appui long sur OK dans une recherche pour l'effacer.
Mot de passe : case « Afficher le mot de passe » sous le champ.

Sur grand écran, une **barre en haut** donne accès à l'Accueil, TV / Films / Séries, aux filtres de catégories
et aux réglages (flèche haut depuis les catégories). Le focus revient toujours sur la catégorie, la saison, l'épisode ou la source d'où l'on vient.

- **Téléphone** : bouton ☰ dans la barre du lecteur pour la liste des chaînes ; toucher l'image affiche les commandes,
  double-toucher à gauche/droite recule/avance de 10 s.
- **PC** : `Entrée` = liste des chaînes (à gauche de la vidéo), `Échap` = retour, `F` = plein écran,
  `↑ ↓` ou `+ −` = zapping, `Espace` = pause.

## Fonctions (v0.6)

**Sources**
- [x] M3U par **lien ou fichier importé** (attributs `tvg-*`, `group-title`, `#EXTGRP`, `#EXTVLCOPT`)
- [x] Xtream : TV en direct, **films**, **séries** (saisons et épisodes)
- [x] Stalker : TV en direct, **films (VOD)**, **séries** (module « series » ou séries rangées dans la VOD), reconnexion si le jeton expire, **options avancées** (SN, device ID, signature)
- [x] Test de connexion, **modification** d'une source existante
- [x] **Sauvegarde des comptes** dans un fichier chiffré par mot de passe (PBKDF2 + AES-256-GCM), restauration depuis un fichier ou un lien (pratique pour une TV sans explorateur de fichiers) ; même format sur Android, TV et PC
- [x] Identifiants **chiffrés** sur Android (AES-256-GCM, clé dans l'Android Keystore) ; fichier en droits 600 sur PC

**Guide TV**
- [x] XMLTV pour les playlists M3U (lien saisi ou `url-tvg` de la playlist), gzip pris en charge, lu en flux
- [x] Xtream : guide complet `xmltv.php` (émission en cours dans les listes), repli sur `get_short_epg` ; Stalker : `get_short_epg`
- [x] « Maintenant / Ensuite » avec résumé de l'émission et barre de progression dans le lecteur, émission en cours dans les listes
- [x] Guide **enregistré sur l'appareil** (affiché dès l'ouverture), rafraîchi en arrière-plan toutes les 6 h
- [x] Guide **partagé entre les versions** d'une même chaîne (TF1 SD / HD / FHD / 4K…)

**Bibliothèque**
- [x] **Bandeau façon TiviMate** (TV) : l'affiche sélectionnée est présentée en grand en haut (image, titre, année · genre · durée · note, distribution, résumé), compteur « 3 / 1017 », note sur chaque affiche
- [x] **Fiche film** : affiche, année, genre, durée, note, résumé, réalisation et distribution, puis **Lecture** (ou **Reprendre** / **Depuis le début**)
- [x] **Masquer des catégories** par source et par rubrique : par pays/groupe d'un coup (FR, UK, DE…), tout masquer / tout afficher, recherche, une par une
- [x] Favoris et **Récents** par source (catégories en tête de liste), ligne « Reprendre » sur l'accueil
- [x] **Reprise** des films et épisodes là où tu t'étais arrêté

**Lecteur**
- [x] Commandes par-dessus l'image, masquées après 5 s (Android)
- [x] Pause, barre de progression utilisable à la télécommande, sauts −10 min / −1 min / −10 s / +10 s / +1 min / +10 min, épisode précédent/suivant, **épisode suivant automatique**
- [x] **Son Dolby (AC3, E-AC3), DTS, TrueHD** sur tous les appareils Android (décodeur FFmpeg en secours de ceux de l'appareil)
- [x] **Audiodescription** (voix qui décrit l'image) : désactivée par défaut, bouton **AD** dans le lecteur quand la chaîne en propose une, réglage pour l'avoir toujours
- [x] Choix de la **piste audio** et des **sous-titres**, **format d'image** (Original, 16:9, Zoom)
- [x] **Image dans l'image** (bouton ou touche Accueil), paysage automatique, écran toujours allumé
- [x] **Reconnexion automatique** du direct (3 essais), mémoire tampon réglable

**Réglages** : démarrage sur la dernière chaîne, tampon, format par défaut, paysage, image dans l'image, programme dans les listes,
masquage des catégories adultes, effacement de l'historique.

**À venir** : grille du guide TV sur plusieurs heures, contrôle parental par code.

## Avertissement

SoufflyTV est un lecteur multimédia. Il n'héberge, ne fournit et ne recommande aucun contenu.
L'utilisateur est seul responsable des sources qu'il ajoute et doit disposer des droits nécessaires.
