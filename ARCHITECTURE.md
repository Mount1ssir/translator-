# Offline translator and simple English dictionary

## Scope and platform

Start with an Android app for typed, pasted, and shared text. Android is the first platform because the reference screens show Android and the copied-text workflow depends on platform clipboard behavior. The translator supports all six directions among English, French, and Arabic. The dictionary explains English words and common English expressions in simple English. The first release supports text from other apps through copy and paste or Android's Share action. Camera, voice, files, and live screen translation would each need a separate input design.

Use Kotlin, Jetpack Compose, a ViewModel for screen state, coroutines for asynchronous work, and Preferences DataStore for settings. Keep one Gradle application module initially. Use two main views, **Translator** and **Dictionary**, plus settings for offline downloads and clipboard behavior. The reference screens inform the searchable list, compact definitions, and stacked input and output. Give Arabic input and output their own text direction so mixed Arabic, Latin text, and numbers render correctly.

## Components

| Component | Responsibility |
| --- | --- |
| App entry | Restore settings, receive shared text, and optionally read the clipboard on focus and listen for new clips while focused. |
| Translator | Resolve the source language, choose the engine, expose download or translation progress, and keep the target language selected by the user. |
| Model manager | Query installed language packs, deduplicate downloads, and expose download readiness and retry. |
| Dictionary | Find headwords, inflected forms, and indexed phrases in a read-only SQLite database bundled with the app. |
| Preferences | Persist the last active mode, target language, source preference, and clipboard setting. |
| Translation engines | Use ML Kit on the device by default and support an optional configured LibreTranslate server. |

## Offline translation

Use Google's free ML Kit on-device Translation SDK. Offer a single setup action for downloading the packs required for English, French, and Arabic. Show which languages are ready and keep successful downloads if another download fails. Query the SDK for installed models at startup and before use. Treat storage or model errors as recoverable and keep the text visible for retry. A new installation needs internet for the one-time model download. The bundled dictionary works from the first launch in airplane mode. Revalidate model availability after app data is cleared or models are removed. [Google ML Kit overview](https://developers.google.com/ml-kit/guides) · [Android translation and model management](https://developers.google.com/ml-kit/language/translation/android) · [Supported languages](https://developers.google.com/ml-kit/language/translation/translation-language-support)

Give the user a source selector with English, French, Arabic, and Auto. Use on-device language identification in Auto mode. If a short word is ambiguous or the result falls outside the three supported languages, keep the target and ask the user to choose the source. For an explicitly selected source equal to the target, show the original. Keep line breaks when handling longer text. Cancel superseded requests where possible and ignore any late result that belongs to old input or language settings. [ML Kit language identification](https://developers.google.com/ml-kit/language/identification)

Google describes ML Kit translation as suitable for casual text. French and Arabic translations go through English, so bilingual review of both directions is a release requirement. Evaluate direct-pair alternatives behind the same engine interface if this pair misses the quality target. Include Google's required translation attribution. [ML Kit translation limitations](https://developers.google.com/ml-kit/language/translation) · [Translation usage guidelines](https://developers.google.com/ml-kit/language/translation/translation-terms)

## Online mode and cost

Use one translation workflow whether the device is online or offline. When a user configures an existing LibreTranslate server over HTTPS and explicitly chooses **Translate online** for the current text, check that the server supports the selected pair and attempt the request. Fall back to a downloaded model on timeout, service error, or unsupported pair. If neither route can run, keep the input and show model setup or retry. Automatically imported clipboard text stays on-device until the user explicitly requests an online translation. Keep any endpoint credentials in OS-protected storage and keep input out of logs and analytics.

The core app requires no account, hosted backend, or per-request fee. LibreTranslate's software is free to self-host. It requires a reachable machine and ongoing maintenance, so online translation is optional and depends on an existing server. Compare its output with the offline engine before describing it as a quality upgrade. [LibreTranslate project](https://github.com/LibreTranslate/LibreTranslate) · [Server language support](https://docs.libretranslate.com/guides/supported_languages/)

## Simple English dictionary

Build a versioned SQLite asset from a pinned Simple English Wiktionary snapshot extracted by Wiktextract, with reviewed definitions for frequent words. Include a headword, part of speech, ordered senses, optional short examples, inflected forms, and source attribution. Normalize Unicode, casing, and surrounding punctuation for lookup while preserving original spelling in the UI. Search exact words and phrases first, then known inflections. Show prefix or spelling suggestions separately when there is no match. A pasted sentence can prompt the user to select a word or expression. Preserve upstream attribution and license notices in the app and distributed dataset, and document any edits and applicable share-alike terms for derived entries. [Simple English Wiktionary](https://simple.wiktionary.org/wiki/Wiktionary:Simple_English_Wiktionary) · [Extracted snapshots](https://kaikki.org/simplewiktionary/index.html) · [Wikimedia terms of use](https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use)

Show the most common sense first in one short sentence using everyday words. Give other common senses their own lines. For example, show the noun senses of **bank** as “a place that keeps money for people” and “the land beside a river.” Review high-frequency definitions for circular wording, difficult vocabulary, and missing senses. If a word has no entry, show that result and available suggestions. Keep answers grounded in the bundled data.

## Clipboard behavior while the app is open

1. Restore the last active mode and translator target before processing incoming text. On first launch, use Translator with English as the target and Auto as the source.
2. Process explicitly shared text first. Offer **Automatically use copied text** during setup. If enabled, register an Android clipboard change listener while the app window has focus and check the current clip when focus is gained. Process each new text clip immediately while focused, using the current mode and target language. If another app has focus when the text is copied, import the latest clip when the user switches back. The user can leave this app open and switch back without relaunching it. An empty, unavailable, or non-text clipboard leaves the current screen as it is. Keep a manual **Paste** action available. Android restricts clipboard access to the focused app or keyboard and can show its own access notification. [Android clipboard access rules](https://developer.android.com/about/versions/10/privacy/changes#clipboard-data) · [Android copy and paste](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste)
3. In Dictionary, run the English lookup immediately and show the simplest matching definition. In Translator, translate immediately to the saved target when the source can be resolved and the model is ready. If either needs attention, keep the imported text in the input field and explain the next action.
4. Write mode and language choices when the user changes them, so process restarts restore the latest saved choices. Deduplicate repeat callbacks and focus checks for the same clip. Process a fresh copy of identical text when Android exposes it as a new clip. Explicit Paste and Share can reuse the same text. Keep clipboard contents and temporary duplicate checks in memory.
5. Detach the clipboard listener on focus loss and cancel pending clipboard-triggered work when the app goes into the background. Use a window-scoped listener so process removal or force-stop ends all clipboard processing. Clipboard activity cannot wake or restart the app. Restore preferences and resume monitoring when the user opens the app again.

## Release checks

| Area | Acceptance check |
| --- | --- |
| Airplane mode | On a clean install the English dictionary works and the Translator explains model setup. After downloading the packs and restarting in airplane mode, all six directions work. |
| Reliability | Interrupt setup, simulate low storage, restart during a download, and remove a model. Verify that installed packs remain usable and missing packs can be retried. |
| Translation quality | Have fluent reviewers score at least 30 everyday samples per direction, including single words, phrases, negation, names, numbers, and Arabic text. Aim for at least 90% of samples in each direction to preserve the intended meaning, with no meaning reversal in the negation cases. Test French ↔ Arabic separately before claiming high quality. |
| Dictionary quality | Review common and ambiguous English words, inflections, phrases, typos, and absent entries. Check that first senses are understandable to a beginner. |
| Copied text | Verify cold launch, successive copies while focused, copying in another app and switching back without relaunching, both modes, target restoration, disabled automatic import, repeat callbacks, and explicit Share and Paste. Verify that copying after process removal or force-stop leaves the app stopped. |
| Online fallback | Test a reachable endpoint, timeout, unsupported pair, and connection loss. Confirm that automatic clipboard import makes no translation request to the server. |
| Device behavior | Check Arabic text direction, accessibility, responsiveness, and memory use on a representative low-end Android phone. |
