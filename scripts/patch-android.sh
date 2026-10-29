#!/usr/bin/env bash
# Ajusta o projeto Android gerado pelo Capacitor: plugin nativo, tela ligada e permissões.
set -euo pipefail
PKG_DIR="android/app/src/main/java/br/com/coletoragnss/app"
mkdir -p "$PKG_DIR"
cp native/GnssTcpPlugin.java "$PKG_DIR/"
cp native/MainActivity.java "$PKG_DIR/"
cp native/GnssService.java "$PKG_DIR/"

MANIFEST="android/app/src/main/AndroidManifest.xml"
for perm in ACCESS_NETWORK_STATE ACCESS_WIFI_STATE CHANGE_NETWORK_STATE ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION VIBRATE WAKE_LOCK ACCESS_MOCK_LOCATION FOREGROUND_SERVICE FOREGROUND_SERVICE_CONNECTED_DEVICE POST_NOTIFICATIONS REQUEST_IGNORE_BATTERY_OPTIMIZATIONS; do
  if ! grep -q "android.permission.$perm" "$MANIFEST"; then
    sed -i "s#</manifest>#    <uses-permission android:name=\"android.permission.$perm\" />\n</manifest>#" "$MANIFEST"
  fi
done
# Bluetooth (Android 12+ usa CONNECT/SCAN; os antigos usam BLUETOOTH/ADMIN)
for linha in \
  '<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />' \
  '<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />' \
  '<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />' \
  '<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />' \
  '<uses-feature android:name="android.hardware.bluetooth" android:required="false" />'; do
  nome=$(echo "$linha" | sed -E 's/.*android:name="([^"]+)".*/\1/')
  if ! grep -q "\"$nome\"" "$MANIFEST"; then
    sed -i "s#</manifest>#    $linha\n</manifest>#" "$MANIFEST"
  fi
done
# GPS do celular é opcional (o app funciona sem)
if ! grep -q "android.hardware.location.gps" "$MANIFEST"; then
  sed -i "s#</manifest>#    <uses-feature android:name=\"android.hardware.location.gps\" android:required=\"false\" />\n</manifest>#" "$MANIFEST"
fi
# serviço em primeiro plano (app continua ligado com a tela fechada ou tirado dos recentes)
if ! grep -q "GnssService" "$MANIFEST"; then
  sed -i 's#</application>#    <service android:name=".GnssService" android:exported="false" android:stopWithTask="false" android:foregroundServiceType="connectedDevice" />\n    </application>#' "$MANIFEST"
fi
# tráfego sem criptografia para casters NTRIP e receptor (HTTP simples)
sed -i 's#<application#<application android:usesCleartextTraffic="true"#' "$MANIFEST"

# versão visível no Android
if [ -n "${VERSION_CODE:-}" ]; then
  sed -i "s/versionCode [0-9]*/versionCode ${VERSION_CODE}/" android/app/build.gradle
  sed -i "s/versionName \"[^\"]*\"/versionName \"1.0.${VERSION_CODE}\"/" android/app/build.gradle
fi
# nome e ícone do app (WA Survey)
RES="android/app/src/main/res"
sed -i 's#<string name="app_name">[^<]*</string>#<string name="app_name">WA Survey</string>#; s#<string name="title_activity_main">[^<]*</string>#<string name="title_activity_main">WA Survey</string>#' "$RES/values/strings.xml"
for d in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
  cp resources/android/mipmap-$d/ic_launcher.png "$RES/mipmap-$d/ic_launcher.png"
  cp resources/android/mipmap-$d/ic_launcher_round.png "$RES/mipmap-$d/ic_launcher_round.png"
done
rm -rf "$RES/mipmap-anydpi-v26"
echo "Projeto Android ajustado."
