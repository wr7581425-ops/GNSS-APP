#!/usr/bin/env bash
# Ajusta o projeto Android gerado pelo Capacitor: plugin nativo, tela ligada e permissões.
set -euo pipefail
PKG_DIR="android/app/src/main/java/br/com/coletoragnss/app"
mkdir -p "$PKG_DIR"
cp native/GnssTcpPlugin.java "$PKG_DIR/"
cp native/MainActivity.java "$PKG_DIR/"

MANIFEST="android/app/src/main/AndroidManifest.xml"
for perm in ACCESS_NETWORK_STATE ACCESS_WIFI_STATE CHANGE_NETWORK_STATE ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION VIBRATE WAKE_LOCK; do
  if ! grep -q "android.permission.$perm" "$MANIFEST"; then
    sed -i "s#</manifest>#    <uses-permission android:name=\"android.permission.$perm\" />\n</manifest>#" "$MANIFEST"
  fi
done
# GPS do celular é opcional (o app funciona sem)
if ! grep -q "android.hardware.location.gps" "$MANIFEST"; then
  sed -i "s#</manifest>#    <uses-feature android:name=\"android.hardware.location.gps\" android:required=\"false\" />\n</manifest>#" "$MANIFEST"
fi
# tráfego sem criptografia para casters NTRIP e receptor (HTTP simples)
sed -i 's#<application#<application android:usesCleartextTraffic="true"#' "$MANIFEST"

# versão visível no Android
if [ -n "${VERSION_CODE:-}" ]; then
  sed -i "s/versionCode [0-9]*/versionCode ${VERSION_CODE}/" android/app/build.gradle
  sed -i "s/versionName \"[^\"]*\"/versionName \"1.0.${VERSION_CODE}\"/" android/app/build.gradle
fi
echo "Projeto Android ajustado."
