# WordBridge v1.0.5

An Android app for translating typed, copied, or shared text between English, French, and Arabic. The English-only dictionary contains over a million searchable entries from Simple English Wiktionary and English Wiktionary and is available on first launch in airplane mode.

[Download WordBridge for Android (APK)](https://github.com/Mount1ssir/translator-/releases/download/v1.0.5/WordBridge-v1.0.5.apk) · [Release notes and checksum](https://github.com/Mount1ssir/translator-/releases/tag/v1.0.5)

On your phone, open the APK and allow installation from your browser or file manager if Android prompts you. This is a test build for Android 6 or later.

## Build the debug APK

Use JDK 17 or later and Android SDK 36. Set `ANDROID_HOME` to the SDK path or open the project in Android Studio. The first Gradle build needs internet access.

```sh
./gradlew :app:assembleDebug
```

## Install the debug APK on your Android phone

The phone must run Android 6 or later.

1. On your phone, open **Settings → About phone** and tap **Build number** seven times to enable **Developer options**. The path can vary by phone.
2. Open **Developer options** and turn on **USB debugging**.
3. Connect the unlocked phone to your computer with a USB data cable. Tap **Allow** when the phone asks whether to allow USB debugging from this computer.
4. From the project folder on your computer, run the commands below. Check that `adb devices` lists your phone with the status `device` before installing.

```sh
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

When ADB prints `Success`, open **WordBridge** from the phone's app list. You can rerun the same install command to update the debug build while keeping its data.

- If you see `adb: command not found` and `ANDROID_HOME` is set, add the SDK's tools to your current Bash session with `export PATH="$ANDROID_HOME/platform-tools:$PATH"`, then rerun the commands above. If the SDK doesn't contain `platform-tools/adb`, install **Android SDK Platform-Tools** through Android Studio's SDK Manager.
- If the phone shows as `unauthorized`, unlock it and approve the USB debugging prompt. If it doesn't appear at all, try another USB data cable or port.
- If both a phone and an emulator are listed, use `adb -d install -r app/build/outputs/apk/debug/app-debug.apk` to target the connected phone.

[Android hardware device setup](https://developer.android.com/studio/run/device) · [ADB command reference](https://developer.android.com/tools/adb)

## First launch

Open WordBridge and tap **Download offline languages** before going offline. Choose Wi-Fi or allow mobile data, then follow the transfer percentage and downloaded size and wait for the panel to confirm that all three languages are ready. Use **Settings** to enable automatic clipboard import or set an anonymous HTTPS LibreTranslate base URL. Online translation sends the current text to that server after confirmation and falls back to the offline engine on server failure. On-device translation uses [Google Translate via ML Kit](https://developers.google.com/ml-kit/language/translation). French ↔ Arabic goes through English.

## Dictionary data

The app shows Simple English Wiktionary definitions first where available. English Wiktionary expands coverage of difficult, technical, and uncommon words. Its definitions can use more advanced language. Both sources work offline. The dictionary unpacks its database on first lookup after installation or upgrade.

Each source link credits the contributors to the corresponding Wiktionary entry. The SQLite asset combines the two editions and indexes normalized headwords and forms. It retains one example per Simple English sense where available and omits other upstream metadata. [Simple English Wiktionary extraction and license](https://kaikki.org/simplewiktionary/) · [English Wiktionary compact extraction and license](https://github.com/tdulcet/compact-dictionaries#wiktionary)

Download the [Simple English JSONL export](https://kaikki.org/simplewiktionary/English/kaikki.org-dictionary-English.jsonl) and the [compact English JSONL export](https://gitlab.com/tdulcet/compact-dictionary/-/raw/main/wiktionary/dictionary-en.json?inline=false) to rebuild the asset:

```sh
python3 tools/build_dictionary.py simple-english.jsonl compact-english.jsonl app/src/main/assets/dictionary.db.gzip
```

The upstream URLs change with each snapshot. The v1.0.1 input SHA-256 values are:

| Export | SHA-256 |
| --- | --- |
| Simple English | `1358e23470eb670de247c64cda40da39e4300e9dc3a7fb0f10d3a7febc69435a` |
| Compact English | `a8f8ea90df538fbc42bdb6c02550bce56b630c2d773998be1e5bc3d2464c5e67` |

The implementation uses native Android views, Activity state, and SharedPreferences to keep the first build small. See [architecture and device acceptance checks](ARCHITECTURE.md) for the intended behaviors and translation quality review.

## License

WordBridge's original code is available under the [MIT License](LICENSE). Dictionary data, attribution artwork, and bundled tooling carry their own terms. [Third-party notices](THIRD_PARTY_NOTICES.md)
