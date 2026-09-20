package gov.anzong.fim.completion.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import gov.anzong.fim.completion.client.ApiProviderType

@State(name = "gov.anzong.fim.completion.settings.AnzongSettingsState", storages = [Storage("AnzongFIMSettings.xml")])
class AnzongSettingsState : PersistentStateComponent<AnzongSettingsState> {
    var isEnabled: Boolean = false
    var apiType: ApiProviderType = ApiProviderType.OLLAMA
    var userEndpoint: String = ""
    var finalEndpoint: String = ""
    var selectedModel: String = ""

    // 【修改点】：彻底抛弃 PasswordSafe，改用普通字符串存储。
    // 这将瞬间解决所有的 EDT 线程卡死和崩溃问题！
    var apiKey: String = ""

    var contextLength: Int = 4096
    var maxTokens: Int = 128

    override fun getState(): AnzongSettingsState = this

    override fun loadState(state: AnzongSettingsState) {
        isEnabled = state.isEnabled
        apiType = state.apiType
        userEndpoint = state.userEndpoint
        finalEndpoint = state.finalEndpoint
        selectedModel = state.selectedModel
        apiKey = state.apiKey
        contextLength = state.contextLength
        maxTokens = state.maxTokens
    }

    companion object {
        val instance: AnzongSettingsState
            get() = ApplicationManager.getApplication().getService(AnzongSettingsState::class.java)
    }
}