#!/usr/bin/env bash
# Construit, installe et emballe le Flatpak de SoufflyTV.
#
#   ./flatpak/build-flatpak.sh
#
# Résultat : SoufflyTV installé (flatpak run app.souffly.SoufflyTV)
#            et un fichier SoufflyTV.flatpak à partager (souffly.app…).
set -euo pipefail

cd "$(dirname "$0")/.."
APP=app.souffly.SoufflyTV
MANIFEST=flatpak/$APP.yml
RUNTIME_VERSION=25.08

echo "==> 1/5 Empreintes des sources"
if grep -q "A_REMPLIR" "$MANIFEST"; then
    python3 flatpak/fill-checksums.py "$MANIFEST"
else
    echo "    déjà remplies"
fi

echo "==> 2/5 Application (Gradle, Java embarqué)"
GRADLE_CMD="./gradlew :composeApp:createDistributable"

# Un JDK complet est nécessaire : java + jpackage (pour embarquer Java dans l'application)
find_jdk() {
    local candidates=(
        "${JAVA_HOME:-}"
        "$HOME/android-studio/jbr"
        "$HOME/.local/share/JetBrains/Toolbox/apps/android-studio/jbr"
        "/opt/android-studio/jbr"
        "/var/lib/flatpak/app/com.google.AndroidStudio/current/active/files/extra/android-studio/jbr"
        "$HOME/.local/share/flatpak/app/com.google.AndroidStudio/current/active/files/extra/android-studio/jbr"
    )
    for dir in "${candidates[@]}"; do
        if [ -n "$dir" ] && [ -x "$dir/bin/java" ] && [ -x "$dir/bin/jpackage" ]; then
            echo "$dir"
            return 0
        fi
    done
    return 1
}

if JDK=$(find_jdk); then
    echo "    JDK : $JDK"
    JAVA_HOME="$JDK" $GRADLE_CMD
elif command -v java >/dev/null 2>&1 && command -v jpackage >/dev/null 2>&1; then
    echo "    JDK : système"
    $GRADLE_CMD
elif command -v distrobox >/dev/null 2>&1 && distrobox list 2>/dev/null | grep -qw soufflytv; then
    if ! distrobox enter soufflytv -- bash -lc "command -v jpackage" >/dev/null 2>&1; then
        echo
        echo "La distrobox « soufflytv » n'a pas de JDK complet. Installe-le puis relance :" >&2
        echo "    distrobox enter soufflytv -- sudo apt install -y openjdk-21-jdk" >&2
        exit 1
    fi
    echo "    JDK : distrobox soufflytv"
    distrobox enter soufflytv -- bash -lc "cd '$PWD' && $GRADLE_CMD"
else
    echo
    echo "Aucun JDK complet (java + jpackage) trouvé. Le plus simple sur Aurora :" >&2
    echo "    distrobox create -n soufflytv -i ubuntu:24.04" >&2
    echo "    distrobox enter soufflytv -- sudo apt update" >&2
    echo "    distrobox enter soufflytv -- sudo apt install -y openjdk-21-jdk" >&2
    echo "puis relance ce script. (Ou : export JAVA_HOME=/chemin/vers/un/jdk-21)" >&2
    exit 1
fi

echo "==> 3/5 Outils Flatpak"
flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
flatpak install --user -y --noninteractive flathub \
    org.flatpak.Builder \
    "org.freedesktop.Platform//$RUNTIME_VERSION" \
    "org.freedesktop.Sdk//$RUNTIME_VERSION"

echo "==> 4/5 Construction et installation (la première fois : FFmpeg + VLC, compter 15 à 30 min)"
flatpak run org.flatpak.Builder --user --install --force-clean \
    --state-dir=flatpak/.flatpak-builder \
    --repo=flatpak/repo \
    flatpak/build "$MANIFEST"

echo "==> 5/5 Fichier à partager"
flatpak build-bundle flatpak/repo SoufflyTV.flatpak "$APP"

echo
echo "Terminé."
echo "  Lancer        : flatpak run $APP"
echo "  À partager    : $PWD/SoufflyTV.flatpak"
echo "  L'installer   : flatpak install --user SoufflyTV.flatpak"
