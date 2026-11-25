package org.localtranslator

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.text.Editable
import android.text.TextWatcher
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : Activity() {
    private enum class Mode { TRANSLATOR, DICTIONARY }

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private val dictionary by lazy { Dictionary(applicationContext) }
    private val engine by lazy { TranslationEngine(applicationContext) }
    private val worker = Executors.newSingleThreadExecutor()
    private val modelWorker = Executors.newSingleThreadExecutor()
    private var request: Future<*>? = null
    private var requestNumber = 0
    private var clipboardRequest = false
    private var downloading = false
    private var focused = false
    private var listening = false
    private var lastClip: Pair<Long, String>? = null
    private var skipFocusRead = false
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (listening && hasWindowFocus()) readClipboard(automatic = true)
    }

    private lateinit var mode: Mode
    private var source: Language? = null
    private lateinit var target: Language
    private var endpoint = ""
    private var autoClipboard = false
    private val drafts = mutableMapOf<Mode, String>()
    private lateinit var content: LinearLayout
    private lateinit var editor: EditText
    private lateinit var status: TextView
    private lateinit var result: LinearLayout
    private lateinit var packStatus: TextView
    private lateinit var downloadButton: Button
    private lateinit var packProgress: ProgressBar
    private var installedPacks: Set<Language>? = null
    private var activePack: Language? = null
    private var packTransfer: PackDownloads.Transfer? = null
    private var packError: String? = null
    private var checkingPacks = true
    private var translationToCopy: String? = null
    private var editingProgrammatically = false

    private val blue = Color.rgb(31, 71, 153)
    private val ink = Color.rgb(20, 38, 62)
    private val muted = Color.rgb(74, 92, 119)
    private val paper = Color.rgb(242, 246, 252)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = if (prefs.getBoolean("dictionary", false)) Mode.DICTIONARY else Mode.TRANSLATOR
        source = savedLanguage("source")
        target = savedLanguage("target") ?: Language.ENGLISH
        endpoint = prefs.getString("endpoint", "").orEmpty()
        autoClipboard = prefs.getBoolean("clipboard", false)
        render()
        refreshPacks()
        if (savedInstanceState == null) handleShare(intent)
        if (!prefs.contains("clipboard") && intent.action != Intent.ACTION_SEND) showClipboardSetup()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() } ?: return
        skipFocusRead = !hasWindowFocus()
        importText(text, fromClipboard = false)
    }

    private fun savedLanguage(key: String) =
        Language.entries.firstOrNull { it.code == prefs.getString(key, null) }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        focused = hasFocus
        updateListener()
        if (hasFocus) {
            if (skipFocusRead) skipFocusRead = false else if (autoClipboard) readClipboard(automatic = true)
        }
    }

    override fun onStop() {
        focused = false
        updateListener()
        if (clipboardRequest) {
            cancelRequest()
            status.text = if (mode == Mode.DICTIONARY) "Tap Look up to continue." else "Tap Translate to continue."
        }
        super.onStop()
    }

    override fun onDestroy() {
        focused = false
        updateListener()
        cancelRequest()
        worker.execute { dictionary.close() }
        worker.shutdown()
        modelWorker.shutdownNow()
        super.onDestroy()
    }

    private fun updateListener() {
        val shouldListen = focused && autoClipboard
        if (shouldListen == listening) return
        listening = shouldListen
        if (listening) clipboard.addPrimaryClipChangedListener(clipboardListener)
        else clipboard.removePrimaryClipChangedListener(clipboardListener)
    }

    private fun readClipboard(automatic: Boolean) {
        if (!hasWindowFocus()) return
        val clip = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
        val text = clip?.getItemAt(0)?.text?.toString()
        if (text.isNullOrBlank()) {
            lastClip = null
            if (!automatic) status.text = "No copied text is available."
            return
        }
        val signature = (if (Build.VERSION.SDK_INT >= 26) clip!!.description.timestamp else 0L) to text
        if (automatic && signature == lastClip) return
        lastClip = signature
        importText(text, fromClipboard = true)
    }

    private fun importText(text: String, fromClipboard: Boolean) {
        editingProgrammatically = true
        editor.setText(text)
        editor.setSelection(editor.length())
        editingProgrammatically = false
        runInput(fromClipboard = fromClipboard)
    }

    private fun showClipboardSetup() {
        AlertDialog.Builder(this)
            .setTitle("Use copied text automatically?")
            .setMessage("When WordBridge has focus, it can look up or translate copied text on this device.")
            .setPositiveButton("Enable") { _, _ -> setAutoClipboard(true) }
            .setNegativeButton("Use manual paste") { _, _ -> setAutoClipboard(false) }
            .setCancelable(false)
            .show()
    }

    private fun setAutoClipboard(value: Boolean) {
        autoClipboard = value
        prefs.edit().putBoolean("clipboard", value).apply()
        updateListener()
        if (value && hasWindowFocus()) readClipboard(automatic = true)
    }

    private fun changeMode(next: Mode) {
        if (next == mode) return
        drafts[mode] = editor.text.toString()
        cancelRequest()
        mode = next
        prefs.edit().putBoolean("dictionary", next == Mode.DICTIONARY).apply()
        render()
    }

    private fun render() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(paper)
        }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            }
            insets
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(28))
            isFocusableInTouchMode = true
        }
        scroll.addView(content)
        setContentView(scroll)
        content.requestFocus()
        scroll.requestApplyInsets()

        val header = row(content)
        header.addView(label(getString(R.string.app_name), 28, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        button(header, "Settings") { showSettings() }
        val tabs = row(content)
        button(tabs, "Translate", selected = mode == Mode.TRANSLATOR, expand = true) { changeMode(Mode.TRANSLATOR) }
        button(tabs, "Dictionary", selected = mode == Mode.DICTIONARY, expand = true) { changeMode(Mode.DICTIONARY) }

        if (mode == Mode.TRANSLATOR) {
            val packs = card()
            packs.addView(label("Offline translation", 17, bold = true))
            packStatus = label("", 14).apply {
                setTextColor(muted)
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            packs.addView(packStatus)
            packProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
            }
            packs.addView(packProgress, LinearLayout.LayoutParams(-1, dp(8)))
            downloadButton = button(packs, "Download offline languages") { offerDownload() }
            updatePackPanel()
        }

        val inputCard = card()
        if (mode == Mode.TRANSLATOR) {
            val languages = row(inputCard)
            button(languages, "From  ${source?.label ?: "Auto"}", expand = true) { chooseLanguage(true) }
            button(languages, "To  ${target.label}", expand = true) { chooseLanguage(false) }
        } else {
            inputCard.addView(label("English word or phrase", 16, bold = true))
        }
        editor = EditText(this).apply {
            hint = if (mode == Mode.DICTIONARY) "Enter a word or phrase" else "Enter text"
            contentDescription = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = if (mode == Mode.TRANSLATOR) 3 else 1
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setTextColor(ink)
            setHintTextColor(muted)
            textSize = 19f
            isSaveEnabled = false
            setText(drafts[mode].orEmpty())
        }
        inputCard.addView(editor, LinearLayout.LayoutParams(-1, -2))
        val actions = row(inputCard)
        button(actions, "Paste") { readClipboard(automatic = false) }
        button(actions, if (mode == Mode.DICTIONARY) "Look up" else "Translate with Google", selected = true) { runInput() }

        if (mode == Mode.TRANSLATOR && endpoint.isNotEmpty()) {
            button(inputCard, "Translate online") {
                AlertDialog.Builder(this)
                    .setTitle("Translate this text online?")
                    .setMessage("This will send the current input to ${java.net.URI(endpoint).host}.")
                    .setPositiveButton("Translate online") { _, _ -> runInput(online = true) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        result = card()
        status = label("", 15).apply {
            setTextColor(muted)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(status)
        resetResult()
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!editingProgrammatically) {
                    cancelRequest()
                    resetResult()
                    if (mode == Mode.DICTIONARY && !s.isNullOrBlank()) runInput()
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })


    }

    private fun chooseLanguage(chooseSource: Boolean) {
        val options = Language.entries.map { it.label }.let { if (chooseSource) listOf("Auto") + it else it }
        AlertDialog.Builder(this)
            .setTitle(if (chooseSource) "Source language" else "Target language")
            .setItems(options.toTypedArray()) { _, index ->
                if (chooseSource) source = Language.entries.getOrNull(index - 1) else target = Language.entries[index]
                prefs.edit().putString("source", source?.code).putString("target", target.code).apply()
                drafts[mode] = editor.text.toString()
                cancelRequest()
                render()
                if (editor.text.isNotBlank()) runInput()
            }.show()
    }

    private fun showSettings() {
        val settings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        val switch = Switch(this).apply {
            text = "Automatically use copied text"
            isChecked = autoClipboard
        }
        settings.addView(switch)
        val server = EditText(this).apply {
            hint = "LibreTranslate HTTPS server (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(endpoint)
        }
        settings.addView(server)
        settings.addView(label("Definitions from Simple English and English Wiktionary · CC BY-SA", 14))
        button(settings, "Simple English Wiktionary") {
            openLink("https://simple.wiktionary.org/wiki/Wiktionary:Simple_English_Wiktionary")
        }
        button(settings, "English Wiktionary data and license") {
            openLink("https://github.com/tdulcet/compact-dictionaries#wiktionary")
        }
        button(settings, "Creative Commons license") {
            openLink("https://creativecommons.org/licenses/by-sa/4.0/")
        }
        button(settings, "About offline translation") {
            AlertDialog.Builder(this).setTitle("Google Translate")
                .setMessage("Google provides the on-device translations and disclaims all express and implied warranties, including accuracy, reliability, merchantability, fitness for a particular purpose, and noninfringement.")
                .setPositiveButton("OK", null).show()
        }
        val settingsScroll = ScrollView(this).apply { addView(settings) }
        val dialog = AlertDialog.Builder(this).setTitle("Settings").setView(settingsScroll)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = server.text.toString().trim()
            if (value.isNotEmpty() && !TranslationEngine.validEndpoint(value)) {
                server.error = "Enter an HTTPS server URL."
                return@setOnClickListener
            }
            drafts[mode] = editor.text.toString()
            if (value != endpoint) {
                cancelRequest()
                endpoint = value
                prefs.edit().putString("endpoint", endpoint).apply()
                render()
            }
            setAutoClipboard(switch.isChecked)
            dialog.dismiss()
        }
    }

    private fun openLink(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }

    private fun resetResult() {
        result.removeAllViews()
        result.visibility = View.GONE
        translationToCopy = null
        status.text = ""
    }

    private fun cancelRequest() {
        requestNumber++
        request?.cancel(true)
        request = null
        clipboardRequest = false
    }

    private fun runInput(online: Boolean = false, fromClipboard: Boolean = false) {
        cancelRequest()
        resetResult()
        val input = editor.text.toString().trim()
        if (input.isEmpty()) {
            status.text = "Enter or paste some text."
            return
        }
        if (input.length > 5_000) {
            status.text = "Use up to 5,000 characters at a time."
            return
        }
        clipboardRequest = fromClipboard
        val number = requestNumber
        val modeAtStart = mode
        val sourceAtStart = source
        val targetAtStart = target
        val server = endpoint.takeIf { online && it.isNotEmpty() }
        status.text = if (mode == Mode.DICTIONARY) "Looking up…" else "Translating…"
        request = worker.submit {
            try {
                if (modeAtStart == Mode.DICTIONARY) {
                    val lookup = dictionary.find(input)
                    deliver(number) { showDefinitions(lookup) }
                } else {
                    val translated = engine.translate(input, sourceAtStart, targetAtStart, server)
                    deliver(number) { showTranslation(translated, targetAtStart) }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: TranslationEngine.InputError) {
                deliver(number) { status.text = error.message }
            } catch (_: Exception) {
                deliver(number) {
                    status.text = if (modeAtStart == Mode.DICTIONARY) "Dictionary could not open. Try again."
                        else "Could not translate. Check your offline languages and try again."
                }
            }
        }
    }

    private fun deliver(number: Int, action: () -> Unit) = runOnUiThread {
        if (!isDestroyed && number == requestNumber) {
            clipboardRequest = false
            request = null
            action()
        }
    }

    private fun showDefinitions(lookup: Dictionary.Result) {
        status.text = if (lookup.senses.isEmpty()) "No matching English definition." else ""
        if (lookup.senses.isEmpty() && lookup.suggestions.isEmpty()) return
        result.visibility = View.VISIBLE
        lookup.senses.forEach { sense ->
            result.addView(label(sense.word, 19, bold = true))
            if (sense.part.isNotBlank()) {
                result.addView(label(partOfSpeech(sense.part), 14).apply { setTextColor(muted) })
            }
            result.addView(label(sense.definition, 17))
            if (sense.example.isNotBlank()) {
                result.addView(label(sense.example, 15).apply { setTextColor(muted) })
            }
            result.addView(View(this), LinearLayout.LayoutParams(-1, dp(12)))
        }
        if (lookup.suggestions.isNotEmpty()) {
            result.addView(label("Suggestions", 17, bold = true))
            lookup.suggestions.forEach { suggestion ->
                button(result, suggestion) { importText(suggestion, fromClipboard = false) }
            }
        } else {
            lookup.senses.distinctBy { it.word to it.extended }.forEach { sense ->
                val host = if (sense.extended) "en.wiktionary.org" else "simple.wiktionary.org"
                val edition = if (sense.extended) "English Wiktionary" else "Simple English Wiktionary"
                button(result, edition) {
                    openLink("https://$host/wiki/${android.net.Uri.encode(sense.word)}")
                }
            }
        }
    }

    private fun partOfSpeech(part: String) = when (part) {
        "adj" -> "adjective"
        "adv" -> "adverb"
        "name" -> "proper noun"
        "prep" -> "preposition"
        "pron" -> "pronoun"
        "conj" -> "conjunction"
        "intj" -> "interjection"
        else -> part
    }

    private fun showTranslation(translation: TranslationEngine.Result, language: Language) {
        status.text = translation.status
        result.visibility = View.VISIBLE
        result.addView(label(language.label, 14, bold = true))
        result.addView(label(translation.text, 21).apply {
            textDirection = if (language == Language.ARABIC) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_FIRST_STRONG
            setTextIsSelectable(true)
        })
        if (translation.googleTranslation) {
            result.addView(ImageView(this).apply {
                setImageResource(R.drawable.google_translate_attribution)
                contentDescription = "Powered by Google Translate"
                setOnClickListener { openLink("https://translate.google.com/") }
            }, LinearLayout.LayoutParams(-2, -2))
        }
        translationToCopy = translation.text
        button(result, "Copy translation") {
            val text = translationToCopy ?: return@button
            clipboard.setPrimaryClip(ClipData.newPlainText("Translation", text))
            val timestamp = if (Build.VERSION.SDK_INT >= 26) clipboard.primaryClipDescription?.timestamp ?: 0L else 0L
            lastClip = timestamp to text
        }
    }

    private fun offerDownload() {
        AlertDialog.Builder(this)
            .setTitle("Download offline languages")
            .setMessage(
                "Download the missing French and Arabic packs once. " +
                    "When all three languages are ready, translation works without internet. " +
                    "Each pack is about 30 MB."
            )
            .setPositiveButton("Download on Wi-Fi") { _, _ -> download(wifiOnly = true) }
            .setNeutralButton("Allow mobile data") { _, _ -> download(wifiOnly = false) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun download(wifiOnly: Boolean) {
        if (downloading) return
        downloading = true
        packError = null
        updatePackPanel()
        modelWorker.submit {
            val error = try {
                engine.download(wifiOnly) { language, ready, transfer ->
                    runOnUiThread {
                        activePack = if (ready) null else language
                        packTransfer = transfer
                        if (ready) installedPacks = installedPacks.orEmpty() + language
                        updatePackPanel()
                    }
                }
                null
            } catch (exception: TranslationEngine.InputError) {
                exception.message
            } catch (_: Exception) {
                "Could not finish downloading. Try again."
            }
            val readiness = readInstalledPacks()
            runOnUiThread {
                if (!isDestroyed) {
                    downloading = false
                    activePack = null
                    packTransfer = null
                    installedPacks = readiness ?: installedPacks
                    packError = error
                    updatePackPanel()
                    if (error == null && mode == Mode.TRANSLATOR && editor.text.isNotBlank() && request == null &&
                        translationToCopy == null && hasWindowFocus()
                    ) runInput()
                }
            }
        }
    }

    private fun refreshPacks() {
        modelWorker.submit {
            val readiness = readInstalledPacks()
            runOnUiThread {
                checkingPacks = false
                if (!downloading) {
                    installedPacks = readiness
                    updatePackPanel()
                }
            }
        }
    }

    private fun readInstalledPacks(): Set<Language>? =
        runCatching { engine.installed() }.getOrNull()

    private fun updatePackPanel() {
        if (isDestroyed || mode != Mode.TRANSLATOR) return
        val allReady = installedPacks?.containsAll(Language.entries) == true
        val summary = when {
            allReady -> "All three languages are ready offline."
            checkingPacks -> "Checking installed packs…"
            packTransfer?.waitingForCooling == true -> "Android is holding this download while the phone is warm. WordBridge will offer a retry if the transfer stays paused."
            downloading -> "Download in progress. Stay connected to the internet."
            packError != null -> packError.orEmpty()
            installedPacks == null -> "Could not check every pack. Use Download offline languages to continue."
            else -> "One-time internet download for any missing packs."
        }
        packStatus.text = buildList {
            add(summary)
            if (!allReady) {
                Language.entries.forEach { language ->
                    val state = when {
                        language == Language.ENGLISH || language in installedPacks.orEmpty() -> "Ready"
                        language == activePack -> packTransfer?.let { transfer ->
                            listOf(transfer.detail(), transfer.progressText(this@MainActivity))
                                .filter(String::isNotEmpty).joinToString(" · ")
                        } ?: "Preparing transfer…"
                        installedPacks == null -> "Status unknown"
                        else -> "Download needed"
                    }
                    add("${language.label} · $state")
                }
            }
        }.joinToString("\n")
        val percent = packTransfer?.percent
        if (packProgress.isIndeterminate != (percent == null)) packProgress.isIndeterminate = percent == null
        packProgress.progress = percent ?: 0
        packProgress.visibility = if (downloading) View.VISIBLE else View.GONE
        downloadButton.visibility = if (allReady) View.GONE else View.VISIBLE
        downloadButton.isEnabled = !downloading && !checkingPacks
        downloadButton.text = if (packError != null) "Retry download" else "Download offline languages"
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(16).toFloat()
        }
        content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    private fun row(parent: LinearLayout) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun label(value: String, size: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(ink)
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun button(
        parent: LinearLayout, title: String, selected: Boolean = false,
        expand: Boolean = false, action: () -> Unit
    ) = Button(this).apply {
        text = title
        isAllCaps = false
        setTextColor(if (selected) Color.WHITE else blue)
        backgroundTintList = ColorStateList.valueOf(if (selected) blue else Color.rgb(226, 235, 250))
        setOnClickListener { action() }
        parent.addView(this, LinearLayout.LayoutParams(if (expand) 0 else -2, -2, if (expand) 1f else 0f)
            .apply { marginEnd = dp(4) })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
