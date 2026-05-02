package com.nshell.nsplayer.ui.settings.advanced

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import android.os.Build
import android.view.translation.TranslationManager
import android.view.translation.TranslationSpec
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import com.nshell.nsplayer.BuildConfig
import com.nshell.nsplayer.R
import com.nshell.nsplayer.NsPlayerApp
import com.nshell.nsplayer.data.settings.ThemeMode
import com.nshell.nsplayer.data.settings.SettingsState
import com.nshell.nsplayer.data.settings.TranslationEngine
import com.nshell.nsplayer.ui.base.BaseActivity
import com.nshell.nsplayer.data.settings.VisibleItem
import com.nshell.nsplayer.ui.settings.SettingsViewModel
import com.nshell.nsplayer.ui.settings.searchfolders.SearchFoldersActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AdvancedSettingsActivity : BaseActivity() {
    private companion object {
        private const val LOG_TAG = "AdvancedSettings"
    }

    private data class TranslationEngineAvailability(
        val mlKitSupported: Boolean,
        val systemSupported: Boolean
    ) {
        val availableEngines: List<TranslationEngine>
            get() = buildList {
                if (mlKitSupported) {
                    add(TranslationEngine.ML_KIT)
                }
                if (systemSupported) {
                    add(TranslationEngine.SYSTEM)
                }
            }
    }

    private lateinit var settingsViewModel: SettingsViewModel
    private lateinit var translationEngineSpinner: Spinner
    private lateinit var translationEngineHint: TextView
    private lateinit var translationEngineAdapter: ArrayAdapter<String>
    private var updating = false
    private val languageTags = listOf<String?>(null, "ko", "en")
    private var currentSettings: SettingsState? = null
    private var pendingLanguageRecreate = false
    private var translationAvailability: TranslationEngineAvailability? = null
    private var translationSpinnerEngines: List<TranslationEngine?> = emptyList()
    private var resolvingTranslationAvailability = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced_settings)
        supportActionBar?.hide()

        val backButton = findViewById<View>(R.id.commonBackButton)
        backButton.setOnClickListener { finish() }

        val titleText = findViewById<TextView>(R.id.commonTitleText)
        titleText.text = getString(R.string.advanced_settings_title)

        val searchFoldersRow = findViewById<View>(R.id.advancedSearchFoldersRow)
        val searchTitle = searchFoldersRow.findViewById<TextView>(R.id.settingsRowTitle)
        searchTitle.text = getString(R.string.advanced_settings_search_folders)
        searchFoldersRow.setOnClickListener {
            startActivity(Intent(this, SearchFoldersActivity::class.java))
        }

        settingsViewModel = ViewModelProvider(this)[SettingsViewModel::class.java]

        val languageSpinner = findViewById<Spinner>(R.id.advancedLanguageSpinner)
        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (updating) {
                    return
                }
                if (currentSettings == null) {
                    return
                }
                val tag = languageTags.getOrNull(position) ?: languageTags.first()
                if (currentSettings?.languageTag == tag) {
                    return
                }
                settingsViewModel.updateLanguageTag(tag)
                NsPlayerApp.applyLanguage(tag, recreate = false)
                requestLanguageRecreate()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val themeSpinner = findViewById<Spinner>(R.id.advancedThemeSpinner)
        themeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (updating) {
                    return
                }
                if (currentSettings == null) {
                    return
                }
                val mode = when (position) {
                    1 -> ThemeMode.LIGHT
                    2 -> ThemeMode.DARK
                    else -> ThemeMode.SYSTEM
                }
                if (currentSettings?.themeMode == mode) {
                    return
                }
                settingsViewModel.updateThemeMode(mode)
                NsPlayerApp.applyTheme(mode)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val noMediaRow = findViewById<View>(R.id.advancedNoMediaRow)
        val noMediaTitle = noMediaRow.findViewById<TextView>(R.id.settingsRowTitle)
        noMediaTitle.text = getString(R.string.advanced_settings_nomedia)
        val noMediaCheckBox = noMediaRow.findViewById<CheckBox>(R.id.settingsRowCheckBox)
        noMediaRow.setOnClickListener {
            if (!updating) {
                noMediaCheckBox.isChecked = !noMediaCheckBox.isChecked
            }
        }
        noMediaCheckBox.setOnCheckedChangeListener { _, isChecked ->
            if (updating) {
                return@setOnCheckedChangeListener
            }
            if (currentSettings?.nomediaEnabled == isChecked) {
                return@setOnCheckedChangeListener
            }
            settingsViewModel.updateNomediaEnabled(isChecked)
        }

        val autoPipRow = findViewById<View>(R.id.advancedAutoPipRow)
        val autoPipTitle = autoPipRow.findViewById<TextView>(R.id.settingsRowTitle)
        autoPipTitle.text = getString(R.string.advanced_settings_auto_pip)
        val autoPipCheckBox = autoPipRow.findViewById<CheckBox>(R.id.settingsRowCheckBox)
        autoPipRow.setOnClickListener {
            if (!updating) {
                autoPipCheckBox.isChecked = !autoPipCheckBox.isChecked
            }
        }
        autoPipCheckBox.setOnCheckedChangeListener { _, isChecked ->
            if (updating) {
                return@setOnCheckedChangeListener
            }
            if (currentSettings?.autoPipEnabled == isChecked) {
                return@setOnCheckedChangeListener
            }
            settingsViewModel.updateAutoPipEnabled(isChecked)
        }

        translationEngineSpinner = findViewById(R.id.advancedTranslationEngineSpinner)
        translationEngineHint = findViewById(R.id.advancedTranslationEngineHint)
        translationEngineAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            mutableListOf("")
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        translationEngineSpinner.adapter = translationEngineAdapter
        translationEngineSpinner.isEnabled = false
        translationEngineSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (updating) {
                    return
                }
                val selectedEngine = translationSpinnerEngines.getOrNull(position) ?: return
                if (currentSettings?.translationEngine == selectedEngine) {
                    return
                }
                settingsViewModel.updateTranslationEngine(selectedEngine)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val inquiryRow = findViewById<View>(R.id.advancedInquiryRow)
        val inquiryTitle = inquiryRow.findViewById<TextView>(R.id.settingsRowTitle)
        inquiryTitle.text = getString(R.string.advanced_settings_inquiry)
        inquiryRow.setOnClickListener { showInquiryDialog() }

        val visibleItemIds = listOf(
            R.id.visibleItemThumbnail,
            R.id.visibleItemDuration,
            R.id.visibleItemExtension,
            R.id.visibleItemResolution,
            R.id.visibleItemFrameRate,
            R.id.visibleItemSize,
            R.id.visibleItemModified
        )
        val visibleItemMap = mapOf(
            R.id.visibleItemThumbnail to VisibleItem.THUMBNAIL,
            R.id.visibleItemDuration to VisibleItem.DURATION,
            R.id.visibleItemExtension to VisibleItem.EXTENSION,
            R.id.visibleItemResolution to VisibleItem.RESOLUTION,
            R.id.visibleItemFrameRate to VisibleItem.FRAME_RATE,
            R.id.visibleItemSize to VisibleItem.SIZE,
            R.id.visibleItemModified to VisibleItem.MODIFIED
        )
        visibleItemIds.forEach { id ->
            findViewById<CheckBox>(id).setOnCheckedChangeListener { _, isChecked ->
                if (updating) {
                    return@setOnCheckedChangeListener
                }
                val item = visibleItemMap[id] ?: return@setOnCheckedChangeListener
                if (currentSettings?.visibleItems?.contains(item) == isChecked) {
                    return@setOnCheckedChangeListener
                }
                settingsViewModel.updateVisibleItem(item, isChecked)
            }
        }

        settingsViewModel.getSettings().observe(this) { settings ->
            updating = true
            currentSettings = settings
            val languagePosition = languageTags.indexOf(settings.languageTag).let { idx ->
                if (idx >= 0) idx else 0
            }
            if (languageSpinner.selectedItemPosition != languagePosition) {
                languageSpinner.setSelection(languagePosition, false)
            }
            val targetPosition = when (settings.themeMode) {
                ThemeMode.LIGHT -> 1
                ThemeMode.DARK -> 2
                ThemeMode.SYSTEM -> 0
            }
            if (themeSpinner.selectedItemPosition != targetPosition) {
                themeSpinner.setSelection(targetPosition, false)
            }
            if (noMediaCheckBox.isChecked != settings.nomediaEnabled) {
                noMediaCheckBox.isChecked = settings.nomediaEnabled
            }
            if (autoPipCheckBox.isChecked != settings.autoPipEnabled) {
                autoPipCheckBox.isChecked = settings.autoPipEnabled
            }
            visibleItemIds.forEach { id ->
                val item = visibleItemMap[id] ?: return@forEach
                val checkBox = findViewById<CheckBox>(id)
                val shouldChecked = settings.visibleItems.contains(item)
                if (checkBox.isChecked != shouldChecked) {
                    checkBox.isChecked = shouldChecked
                }
            }
            val forcedEngine = renderTranslationEngineSection(settings)
            updating = false
            if (forcedEngine != null && settings.translationEngine != forcedEngine) {
                settingsViewModel.updateTranslationEngine(forcedEngine)
            }
        }

        val topBar = findViewById<View>(R.id.commonTopBar)
            ?: findViewById(R.id.advancedTopBarInclude)
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { view, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            view.setPadding(
                view.paddingLeft,
                topInset + dpToPx(12),
                view.paddingRight,
                view.paddingBottom
            )
            insets
        }
        resolveTranslationEngineAvailability()
    }

    override fun onResume() {
        super.onResume()
        resolveTranslationEngineAvailability()
    }

    private fun resolveTranslationEngineAvailability() {
        if (resolvingTranslationAvailability) {
            return
        }
        resolvingTranslationAvailability = true
        Thread {
            val availability = TranslationEngineAvailability(
                mlKitSupported = isMlKitTranslationSupported(),
                systemSupported = isSystemTranslationSupported()
            )
            if (isFinishing || isDestroyed) {
                resolvingTranslationAvailability = false
                return@Thread
            }
            runOnUiThread {
                resolvingTranslationAvailability = false
                translationAvailability = availability
                val settings = currentSettings ?: return@runOnUiThread
                updating = true
                val forcedEngine = renderTranslationEngineSection(settings)
                updating = false
                if (forcedEngine != null && settings.translationEngine != forcedEngine) {
                    settingsViewModel.updateTranslationEngine(forcedEngine)
                }
            }
        }.start()
    }

    private fun isMlKitTranslationSupported(): Boolean {
        return runCatching {
            Class.forName("com.google.mlkit.nl.translate.Translation")
        }.isSuccess
    }

    private fun isSystemTranslationSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false
        }
        return isSystemTranslationSupportedApi31()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun isSystemTranslationSupportedApi31(): Boolean {
        val manager = getSystemService(TranslationManager::class.java) ?: return false
        return runCatching {
            val capabilities = manager.getOnDeviceTranslationCapabilities(
                TranslationSpec.DATA_FORMAT_TEXT,
                TranslationSpec.DATA_FORMAT_TEXT
            )
            capabilities.isNotEmpty()
        }.getOrDefault(false)
    }

    private fun renderTranslationEngineSection(settings: SettingsState): TranslationEngine? {
        val availability = translationAvailability
        if (availability == null) {
            bindTranslationSpinner(
                engines = listOf(null),
                labels = listOf(""),
                selection = 0,
                enabled = false
            )
            translationEngineHint.visibility = View.GONE
            return null
        }
        val availableEngines = availability.availableEngines
        return when {
            availableEngines.size >= 2 -> {
                val selectedEngine = if (availableEngines.contains(settings.translationEngine)) {
                    settings.translationEngine
                } else {
                    availableEngines.first()
                }
                val labels = availableEngines.map { translationEngineLabel(it) }
                val selectedIndex = availableEngines.indexOf(selectedEngine).coerceAtLeast(0)
                bindTranslationSpinner(
                    engines = availableEngines,
                    labels = labels,
                    selection = selectedIndex,
                    enabled = true
                )
                translationEngineHint.visibility = View.GONE
                if (selectedEngine != settings.translationEngine) {
                    selectedEngine
                } else {
                    null
                }
            }
            availableEngines.size == 1 -> {
                val fixedEngine = availableEngines.first()
                bindTranslationSpinner(
                    engines = listOf(fixedEngine),
                    labels = listOf(translationEngineLabel(fixedEngine)),
                    selection = 0,
                    enabled = false
                )
                translationEngineHint.text = getString(R.string.translation_engine_locked_message)
                translationEngineHint.visibility = View.VISIBLE
                if (settings.translationEngine != fixedEngine) {
                    fixedEngine
                } else {
                    null
                }
            }
            else -> {
                bindTranslationSpinner(
                    engines = listOf(null),
                    labels = listOf(""),
                    selection = 0,
                    enabled = false
                )
                translationEngineHint.text = getString(R.string.translation_engine_unsupported_message)
                translationEngineHint.visibility = View.VISIBLE
                null
            }
        }
    }

    private fun bindTranslationSpinner(
        engines: List<TranslationEngine?>,
        labels: List<String>,
        selection: Int,
        enabled: Boolean
    ) {
        translationSpinnerEngines = engines
        translationEngineAdapter.clear()
        translationEngineAdapter.addAll(labels)
        translationEngineAdapter.notifyDataSetChanged()
        if (translationEngineSpinner.selectedItemPosition != selection) {
            translationEngineSpinner.setSelection(selection, false)
        }
        translationEngineSpinner.isEnabled = enabled
        translationEngineSpinner.alpha = if (enabled) 1f else 0.7f
    }

    private fun translationEngineLabel(engine: TranslationEngine): String {
        return when (engine) {
            TranslationEngine.ML_KIT -> getString(R.string.translation_engine_ml_kit)
            TranslationEngine.SYSTEM -> getString(R.string.translation_engine_system)
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return Math.round(dp * density)
    }

    private fun requestLanguageRecreate() {
        if (pendingLanguageRecreate || isFinishing || isDestroyed) {
            return
        }
        pendingLanguageRecreate = true
        window.decorView.post {
            pendingLanguageRecreate = false
            if (!isFinishing && !isDestroyed) {
                recreate()
            }
        }
    }

    private fun showInquiryDialog() {
        val content = layoutInflater.inflate(R.layout.dialog_inquiry, null)
        val subjectInput = content.findViewById<EditText>(R.id.inquirySubjectInput)
        val messageInput = content.findViewById<EditText>(R.id.inquiryMessageInput)
        val dialog = AlertDialog.Builder(this, R.style.ThemeOverlay_NsPlayer_Dialog)
            .setTitle(R.string.advanced_settings_inquiry_title)
            .setView(content)
            .setPositiveButton(R.string.inquiry_send, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            val sendButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            sendButton.setOnClickListener {
                val title = subjectInput.text?.toString()?.trim().orEmpty()
                if (title.isEmpty()) {
                    Toast.makeText(
                        this,
                        getString(R.string.inquiry_error_empty_title),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                val message = messageInput.text?.toString()?.trim().orEmpty()
                if (message.isEmpty()) {
                    Toast.makeText(
                        this,
                        getString(R.string.inquiry_error_empty_content),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                submitInquiry(title, message, buildModelCode())
            }
        }
        dialog.show()
    }

    private fun submitInquiry(title: String, message: String, modelCode: String) {
        val token = BuildConfig.NOTION_TOKEN
        if (token.isBlank()) {
            Toast.makeText(this, getString(R.string.inquiry_send_failed), Toast.LENGTH_SHORT)
                .show()
            return
        }
        val dataSourceId = BuildConfig.NOTION_DATA_SOURCE_ID
        val notionVersion = BuildConfig.NOTION_VERSION
        if (dataSourceId.isBlank() || notionVersion.isBlank()) {
            Toast.makeText(
                this,
                getString(R.string.inquiry_send_failed),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val payload = JSONObject()
            .put(
                "parent",
                JSONObject()
                    .put("type", "data_source_id")
                    .put("data_source_id", dataSourceId)
            )
            .put(
                "properties",
                JSONObject()
                    .put(
                        "title",
                        JSONObject()
                            .put("type", "title")
                            .put(
                                "title",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("type", "text")
                                            .put("text", JSONObject().put("content", title))
                                    )
                            )
                    )
                    .put(
                        "content",
                        JSONObject()
                            .put("type", "rich_text")
                            .put(
                                "rich_text",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("type", "text")
                                            .put("text", JSONObject().put("content", message))
                                    )
                            )
                    )
                    .put(
                        "model_code",
                        JSONObject()
                            .put("type", "rich_text")
                            .put(
                                "rich_text",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("type", "text")
                                            .put("text", JSONObject().put("content", modelCode))
                                    )
                            )
                    )
            )
            .toString()
        submitJson(
            url = "https://api.notion.com/v1/pages",
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Notion-Version" to notionVersion,
                "Content-Type" to "application/json; charset=utf-8",
                "Accept" to "application/json"
            ),
            body = payload
        )
    }

    private fun submitJson(url: String, headers: Map<String, String>, body: String) {
        Thread {
            val success = try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 15000
                    doOutput = true
                    headers.forEach { (key, value) -> setRequestProperty(key, value) }
                }
                connection.outputStream.use { stream ->
                    stream.write(body.toByteArray(Charsets.UTF_8))
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val errorBody = readStream(connection.errorStream ?: connection.inputStream)
                    Log.e(
                        LOG_TAG,
                        "Inquiry request failed. code=$code body=${errorBody.take(2000)}"
                    )
                }
                connection.disconnect()
                code in 200..299
            } catch (_: Exception) {
                false
            }
            if (!isFinishing && !isDestroyed) {
                runOnUiThread {
                    val messageRes = if (success) {
                        R.string.inquiry_send_success
                    } else {
                        R.string.inquiry_send_failed
                    }
                    Toast.makeText(this, getString(messageRes), Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun buildModelCode(): String {
        val modelCode = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
        return if (modelCode.isBlank()) {
            "Unknown"
        } else {
            modelCode
        }
    }

    private fun readStream(stream: java.io.InputStream?): String {
        if (stream == null) {
            return ""
        }
        return try {
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            ""
        }
    }
}
