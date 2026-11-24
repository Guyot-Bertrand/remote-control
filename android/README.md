# Remote-Control — appli Android

Prérequis : JDK 21 et le SDK Android (`ANDROID_HOME=~/Android/Sdk`, ou `sdk.dir` dans `local.properties`).
Aucune dépendance aux services Google ; Android 10 (API 29) minimum.

```bash
./gradlew testDebugUnitTest   # tests, sans émulateur
./gradlew assembleDebug       # APK de debug (checkNoGms tourne avec le build)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Sur l'émulateur, le serveur du PC se joint à `10.0.2.2:8765` (adresse manuelle dans Réglages : mDNS ne traverse pas le NAT de l'émulateur).

## Écarts constatés

- Quand mpv ne peut pas démarrer (absent ou en échec), le serveur répond 409 `not_found` : l'appli affiche « Fichier introuvable », alors que le fichier est bien là.
