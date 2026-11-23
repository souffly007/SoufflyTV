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
git tag v0.9.8 && git push origin v0.9.8
```
→ une « Release » GitHub avec `SoufflyTV-x.y.z.msi`, `SoufflyTV-x.y.z.exe`, et les APK TV + smartphone
(signés si les secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` sont définis).
Le workflow peut aussi être lancé à la main (onglet Actions → Release → Run workflow).

Mise à jour : lancer le nouvel installeur. Si une ancienne version « 1.0.0 » est installée, la désinstaller d'abord
(Paramètres → Applications) : les comptes et réglages, rangés dans `%APPDATA%\SoufflyTV`, sont conservés.

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

Sur grand écran, une **barre latérale** (repliée en icônes à la télécommande, ◀ pour l'ouvrir) donne accès à l'**Accueil**,
à TV en direct / Films / Séries, aux filtres, aux réglages et au changement de compte. L'**Accueil** présente un film « À la une »,
puis les rangées Continuer à regarder, Chaînes récentes, Nouveaux films, Nouvelles séries, Films les mieux notés et Ma liste.
Retour depuis une rubrique ramène à l'Accueil. Le focus revient toujours sur la catégorie, la carte, la saison, l'épisode ou la source d'où l'on vient.

- **Téléphone** : bouton ☰ dans la barre du lecteur pour la liste des chaînes ; toucher l'image affiche les commandes,
  double-toucher à gauche/droite recule/avance de 10 s.
- **PC** : `Entrée` = liste des chaînes, `Échap` = retour (ou sortie du plein écran), `F` ou **double-clic** = plein écran,
  `↑ ↓` ou `+ −` = zapping, `Espace` = pause. En plein écran, la vidéo occupe tout l'écran et les barres
  reviennent quand la souris bouge. Réglage « Plein écran automatique » pour y passer dès la lecture.

## Fonctions (v0.9)

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

**Interface**
- [x] **Smartphone** façon maquette : en-tête (logo, recherche, compte), barre du bas Accueil · TV Live · Guide · Replay · Films · Séries · Réglages,
  carrousel « À la une » qui défile tout seul, grande carte **Continuer à regarder** (pourcentage, temps restant, Continuer / Reprendre du début), rangées avec « Tout voir »
- [x] **Grand écran / TV** : barre latérale (Recherche, Accueil, TV en direct, Guide TV, Replay, Films, Séries, Filtres, Réglages, compte)
- [x] **Recherche** dans tout le compte (chaînes, films et séries) ; « Tout voir » ouvre l'historique complet

**TV en direct**
- [x] **Aperçu en direct** (TV) : la chaîne sous le curseur se lance en petit en haut de la liste, avec l'émission en cours, la suivante et la progression ; OK pour passer en plein écran (réglage pour le couper)
- [x] **« En ce moment »** : ce qui passe maintenant sur toutes les chaînes, rangé par catégorie, avec le temps restant (quand un guide TV est chargé)

**Guide TV en grille** (menu à part : barre latérale sur TV / PC, barre du bas sur smartphone)
- [x] Grille horaire façon TiviMate : une ligne par chaîne, émissions sur la frise, ligne « maintenant », titre et résumé de l'émission choisie en haut
- [x] Les versions d'une même chaîne (SD, HD, FHD, UHD/4K, HEVC…) sont **regroupées sur une seule ligne** ; au lancement, choix de la qualité (la dernière choisie est proposée en premier), et le zapping garde cette qualité
- [x] Groupes de chaînes en haut (Favoris, Récents, Toutes, catégories), ◀ ▶ d'émission en émission à la télécommande, glisser au doigt / à la souris, boutons ‹ 1 h · Maintenant · 1 h ›

**Affiches** : liens d'images Xtream acceptés sous toutes leurs formes (//…, /t/p/… TMDB, chemin du serveur) ; si l'affiche de la liste manque, celle de la fiche du film la remplace

**Rappels**
- [x] OK sur une émission à venir ou revoyable (ou appui long / touche Menu sur n'importe laquelle) : un seul menu avec le rappel, le replay et le choix de la qualité — « Me rappeler à 16:00 », « Basculer sur la chaîne », ou « Revoir » (replay) pour une émission terminée
- [x] ⏰ sur les émissions avec rappel ; à l'heure, bandeau « Ça commence » par-dessus l'écran (même pendant une autre chaîne) avec « Regarder » / « Ignorer »
- [x] Android : notification 2 minutes avant, même appli fermée (autorisation demandée au premier rappel)

**Replay** (si le fournisseur garde des archives ; sinon le menu n'apparaît pas)
- [x] Menu **Replay** : chaîne → jour (Aujourd'hui, Hier…) → émission ; « Depuis le début » pour l'émission en cours
- [x] Dans le guide TV : ⟲ sur les émissions terminées qu'on peut revoir, OK pour les relire
- [x] Lecture comme un film (avance, recul, pause, vitesse), choix SD / HD / FHD si plusieurs versions ont des archives
- [x] Xtream (`tv_archive`, lien `timeshift`, fuseau du serveur), M3U (`catchup` default / append / shift / flussonic / xc, `catchup-days`, `catchup-source`), Stalker (archives du portail)

**Image (TV)**
- [x] **Fréquence d'image automatique (AFR)** : l'écran passe à la fréquence de la vidéo (25 i/s → 50 Hz, 29,97 → 59,94 Hz, 23,976 → 23,976 Hz…), annoncée par le flux ou mesurée sur les premières images ; retour au mode d'origine en quittant le lecteur (réglage, désactivé par défaut)

**Réseau**
- [x] **DNS sécurisé** (DNS-over-HTTPS) : Cloudflare, Quad9 ou Google au lieu du DNS de la box, pour joindre les serveurs bloqués par l'opérateur ;
  s'applique aux listes, au guide TV, aux affiches et à la vidéo (sur PC, VLC passe par un petit relais local), repli automatique sur le DNS de l'appareil si le DNS chiffré ne répond pas
- [x] **Test** depuis les réglages : compare, pour chaque compte, l'adresse donnée par la box et par le DNS sécurisé

**Bibliothèque**
- [x] **Bandeau façon TiviMate** (TV) : l'affiche sélectionnée est présentée en grand en haut (image, titre, année · genre · durée · note, distribution, résumé), compteur « 3 / 1017 », note sur chaque affiche
- [x] **Bande-annonce** (Xtream) : bouton sur la fiche film et la série, ouvre YouTube (appli sur Android / TV, navigateur sur PC)
- [x] **Fiches film et série** façon maquette : grande image de fond, pastilles d'infos, boutons empilés (Lire / Continuer, Reprendre du début, Ma liste, Bande-annonce), colonne Informations ; séries avec infos à gauche, saisons et épisodes à droite
- [x] **Fiche film** : affiche, année, genre, durée, note, résumé, réalisation et distribution, puis **Lecture** (ou **Reprendre** / **Depuis le début**)
- [x] **Masquer des catégories** par source et par rubrique : par pays/groupe d'un coup (FR, UK, DE…), tout masquer / tout afficher, recherche, une par une
- [x] Favoris et **Récents** par source (catégories en tête de liste), ligne « Reprendre » sur l'accueil
- [x] **Reprise** des films et épisodes là où tu t'étais arrêté : fiche film avec **Continuer** / **Reprendre du début** et barre de progression ;
  séries avec bouton **Reprendre** (ou **Épisode suivant**) sous l'image, épisodes **vus** ✓ et **en cours** (barre de progression)

**Lecteur**
- [x] Commandes par-dessus l'image, masquées après 5 s (Android)
- [x] **Gros boutons ronds** par-dessus l'image (Android, films et séries) : −10 s, lecture / pause, +10 s
- [x] **Vitesse de lecture** (×0,75 à ×2) pour les films et séries
- [x] Pause, barre de progression utilisable à la télécommande, sauts −10 min / −1 min / −10 s / +10 s / +1 min / +10 min, épisode précédent/suivant, **épisode suivant automatique**
- [x] **Son Dolby (AC3, E-AC3), DTS, TrueHD** sur tous les appareils Android (décodeur FFmpeg en secours de ceux de l'appareil)
- [x] **Audiodescription** (voix qui décrit l'image) : désactivée par défaut, bouton **AD** dans le lecteur quand la chaîne en propose une, réglage pour l'avoir toujours
- [x] Choix de la **piste audio** et des **sous-titres**, **format d'image** adapté à l'écran, 16:9, 16:10, 21:9… (Original, Remplir, Zoom)
- [x] **Image dans l'image** (bouton ou touche Accueil), paysage automatique, écran toujours allumé
- [x] **Reconnexion automatique** du direct (3 essais), mémoire tampon réglable

**Réglages** (par rubriques sur grand écran : Lecture, Affichage, Réseau, Comptes, Données, À propos) : couleur du curseur de la télécommande (jaune, orange, blanc, cyan), démarrage sur la dernière chaîne, tampon, format par défaut, paysage, image dans l'image, programme dans les listes,
masquage des catégories adultes, effacement de l'historique.

**À venir** : grille du guide TV sur plusieurs heures, contrôle parental par code.

## Avertissement

SoufflyTV est un lecteur multimédia. Il n'héberge, ne fournit et ne recommande aucun contenu.
L'utilisateur est seul responsable des sources qu'il ajoute et doit disposer des droits nécessaires.
