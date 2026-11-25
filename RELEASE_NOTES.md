WordBridge is an Android translator and offline English dictionary. This is the first public test release.

## Added

- English, French, and Arabic translation with on-device language packs.
- An offline English dictionary with over one million searchable entries. Simple English definitions appear first where available.
- Automatic clipboard lookup or translation while WordBridge has focus, using the last selected mode and translation target.

## Changed

- The offline pack panel shows per-language status, transfer percentage, and bytes downloaded when Android reports them.
- WordBridge now offers a retry after 45 seconds without download progress and keeps packs queued when Android pauses them because the phone is warm.

## Known issue

- French and Arabic require a one-time internet download. Android may defer the downloads while the device is warm, and successful completion has not yet been verified on the test phone.

> [!TIP]
> Download **WordBridge-v1.0.5.apk** from Assets for Android 6 or later. This universal APK is debug-signed for testing. After installation, open **Offline translation** to download the language packs.

The GitHub build uses its own debug signature. Android may require uninstalling an earlier locally signed copy before installing this APK. Uninstalling clears that copy's settings and downloaded packs.
