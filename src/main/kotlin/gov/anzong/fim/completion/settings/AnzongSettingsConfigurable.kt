package gov.anzong.fim.completion.settings

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.*
import com.intellij.util.ui.FormBuilder
import gov.anzong.fim.completion.client.ApiProviderType
import gov.anzong.fim.completion.client.FimRequestBuilder
import gov.anzong.fim.completion.client.OllamaResponse
import gov.anzong.fim.completion.client.OpenAiResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.awt.FlowLayout
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

class AnzongSettingsConfigurable : Configurable {
    private val enableCheckbox = JBCheckBox("Enable Anzong FIM Completion")
    private val apiTypeComboBox = ComboBox(ApiProviderType.values())
    private val endpointField = JBTextField()
    private val finalEndpointLabel = JBLabel().apply { foreground = com.intellij.ui.JBColor.GRAY }
    private val apiKeyField = JBPasswordField()
    private val modelComboBox = ComboBox<String>().apply { isEditable = true }
    private val contextLengthField = JBTextField()
    private val maxTokensField = JBTextField()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private var isFetchingModels = false

    override fun getDisplayName(): String = "Anzong FIM Completion"

    private fun getCleanBaseUrl(): String {
        return endpointField.text.trimEnd('/')
            .removeSuffix("/api/generate")
            .removeSuffix("/api/tags")
            .removeSuffix("/api")
            .removeSuffix("/v1/chat/completions")
            .removeSuffix("/v1/completions")
            .removeSuffix("/v1/models")
            .removeSuffix("/v1")
    }

    override fun createComponent(): JComponent {
        val settings = AnzongSettingsState.instance
        enableCheckbox.isSelected = settings.isEnabled
        apiTypeComboBox.selectedItem = settings.apiType
        endpointField.text = settings.userEndpoint
        apiKeyField.text = settings.apiKey
        modelComboBox.selectedItem = settings.selectedModel
        contextLengthField.text = settings.contextLength.toString()
        maxTokensField.text = settings.maxTokens.toString()

        val updateFinalUrl = {
            val rootBase = getCleanBaseUrl()
            val type = apiTypeComboBox.selectedItem as ApiProviderType

            val finalUrl = when (type) {
                ApiProviderType.OLLAMA -> "$rootBase/api/generate"
                ApiProviderType.OPENAI_COMPATIBLE -> "$rootBase/v1/completions"
            }

            finalEndpointLabel.text = "Actual URL: $finalUrl"
        }

        endpointField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = updateFinalUrl()
        })
        apiTypeComboBox.addActionListener { updateFinalUrl() }
        updateFinalUrl()

        modelComboBox.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {
                fetchModelsFromAPI(silent = true)
            }
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {}
            override fun popupMenuCanceled(e: PopupMenuEvent?) {}
        })

        val testFimButton = JButton("Test FIM Support").apply {
            addActionListener { testFIMEndpoint() }
        }

        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(testFimButton)
        }

        return FormBuilder.createFormBuilder()
            .addComponent(enableCheckbox)
            .addSeparator()
            .addLabeledComponent(JBLabel("API Type:"), apiTypeComboBox, 1, false)
            .addLabeledComponent(JBLabel("Base API URL:"), endpointField, 1, false)
            .addComponentToRightColumn(finalEndpointLabel)
            .addLabeledComponent(JBLabel("API Key:"), apiKeyField, 1, false)
            .addLabeledComponent(JBLabel("Model Name:"), modelComboBox, 1, false)
            .addLabeledComponent(JBLabel("Max Context:"), contextLengthField, 1, false)
            .addLabeledComponent(JBLabel("Max Tokens:"), maxTokensField, 1, false)
            .addComponentToRightColumn(buttonPanel)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    private fun fetchModelsFromAPI(silent: Boolean = false) {
        if (isFetchingModels || endpointField.text.isBlank()) return
        isFetchingModels = true

        val rootBase = getCleanBaseUrl()
        val type = apiTypeComboBox.selectedItem as ApiProviderType
        val url = when (type) {
            ApiProviderType.OLLAMA -> "$rootBase/api/tags"
            ApiProviderType.OPENAI_COMPATIBLE -> "$rootBase/v1/models"
        }
        val key = String(apiKeyField.password)

        val modalityState = ModalityState.current()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val request = Request.Builder().url(url).apply {
                    if (key.isNotBlank()) addHeader("Authorization", "Bearer $key")
                }.build()

                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: return@use

                    if (!response.isSuccessful) {
                        throw RuntimeException("API returned ${response.code}")
                    }

                    val models = mutableListOf<String>()
                    val json = JsonParser.parseString(body).asJsonObject

                    if (type == ApiProviderType.OLLAMA) {
                        json.getAsJsonArray("models")?.forEach { models.add(it.asJsonObject.get("name").asString) }
                    } else {
                        json.getAsJsonArray("data")?.forEach { models.add(it.asJsonObject.get("id").asString) }
                    }


                    ApplicationManager.getApplication().invokeLater({
                        val currentSelection = modelComboBox.selectedItem
                        modelComboBox.removeAllItems()
                        models.forEach { modelComboBox.addItem(it) }

                        if (models.contains(currentSelection)) {
                            modelComboBox.selectedItem = currentSelection
                        } else if (models.isNotEmpty()) {
                            modelComboBox.selectedIndex = 0
                        }

                        if (!silent) Messages.showInfoMessage("Loaded ${models.size} models.", "Success")
                    }, modalityState)
                }
            } catch (e: Exception) {
                if (!silent) {
                    ApplicationManager.getApplication().invokeLater({
                        Messages.showErrorDialog("Failed to fetch models: ${e.message}", "Error")
                    }, modalityState)
                }
            } finally {
                isFetchingModels = false
            }
        }
    }

    private fun testFIMEndpoint() {
        val modelName = modelComboBox.selectedItem?.toString() ?: ""
        if (modelName.isBlank()) {
            Messages.showWarningDialog("Please fetch and select a model first.", "Warning")
            return
        }

        val finalUrl = finalEndpointLabel.text.removePrefix("Actual URL: ").trim()
        val type = apiTypeComboBox.selectedItem as ApiProviderType
        val key = String(apiKeyField.password)

        val prefix = "def calculate_sum(a, b):\n    "
        val suffix = "\n\nprint(calculate_sum(5, 10))"

        val modalityState = ModalityState.current()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val jsonBody = FimRequestBuilder.buildRequestBody(
                    type = type,
                    model = modelName,
                    prefix = prefix,
                    suffix = suffix,
                    maxTokens = 30
                )

                val request = Request.Builder().url(finalUrl).post(jsonBody.toRequestBody(jsonMediaType)).apply {
                    if (key.isNotBlank()) addHeader("Authorization", "Bearer $key")
                    addHeader("Accept", "application/json")
                }.build()

                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""

                    if (response.isSuccessful && body.isNotBlank()) {
                        val generatedText = when (type) {
                            ApiProviderType.OLLAMA -> gson.fromJson(body, OllamaResponse::class.java).response ?: ""
                            ApiProviderType.OPENAI_COMPATIBLE -> gson.fromJson(body, OpenAiResponse::class.java).choices?.firstOrNull()?.text ?: ""
                        }

                        ApplicationManager.getApplication().invokeLater({
                            if (generatedText.trim().contains("return")) {
                                Messages.showInfoMessage("FIM test PASSED!\nModel filled in:\n$generatedText", "Success")
                            } else {
                                Messages.showWarningDialog("FIM test got response, but it might not be FIM compliant.\nRaw output:\n$generatedText", "Partial Success")
                            }
                        }, modalityState)
                    } else {
                        ApplicationManager.getApplication().invokeLater({
                            Messages.showErrorDialog("API Error: ${response.code}\n$body", "Test Failed")
                        }, modalityState)
                    }
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater({
                    Messages.showErrorDialog("Network Error: ${e.message}", "Test Failed")
                }, modalityState)
            }
        }
    }

    override fun isModified(): Boolean {
        val settings = AnzongSettingsState.instance
        return enableCheckbox.isSelected != settings.isEnabled ||
                apiTypeComboBox.selectedItem != settings.apiType ||
                endpointField.text != settings.userEndpoint ||
                String(apiKeyField.password) != settings.apiKey ||
                (modelComboBox.selectedItem?.toString() ?: "") != settings.selectedModel ||
                contextLengthField.text != settings.contextLength.toString() ||
                maxTokensField.text != settings.maxTokens.toString()
    }

    override fun apply() {
        val settings = AnzongSettingsState.instance
        settings.isEnabled = enableCheckbox.isSelected
        settings.apiType = apiTypeComboBox.selectedItem as ApiProviderType
        settings.userEndpoint = endpointField.text
        settings.selectedModel = modelComboBox.selectedItem?.toString() ?: ""
        settings.apiKey = String(apiKeyField.password)
        settings.contextLength = contextLengthField.text.toIntOrNull() ?: 4096
        settings.maxTokens = maxTokensField.text.toIntOrNull() ?: 128
        settings.finalEndpoint = finalEndpointLabel.text.removePrefix("Actual URL: ").trim()
    }
}