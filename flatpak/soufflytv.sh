#!/bin/sh
# Lanceur de SoufflyTV dans le Flatpak.
# libVLC est dans /app/lib (variables VLC_PLUGIN_PATH et JAVA_TOOL_OPTIONS posées par le manifeste).
exec /app/soufflytv/bin/SoufflyTV "$@"
