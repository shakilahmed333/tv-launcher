# TV Launcher

A lightweight home screen for a TCL Google TV (1 GB RAM), written in plain Kotlin with no extra libraries.

## Phase 1 (this version)
- Clock and date card, greeting
- Preview panel that takes the colour of the focused app
- "Most used" row: the 5 apps you open most from this launcher
- "All apps" grid
- Focus animation: tile grows, lifts, shows a light sheen and outline
- Soft background glow that follows the focused app
- Bordeaux Night colours

## Build
Every push to `main` builds the APK on GitHub Actions and publishes it as the `latest` release.

## Install from an Android tablet (Termux)
```
curl -L -o launcher.apk https://github.com/<owner>/<repo>/releases/download/latest/launcher.apk
adb install -r launcher.apk
adb shell am start -n com.shakil.tvlauncher/.MainActivity
```

## Undo
```
adb uninstall com.shakil.tvlauncher
```
