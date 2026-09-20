package gov.anzong.fim.completion.client

import com.google.gson.annotations.SerializedName

enum class ApiProviderType { OLLAMA, OPENAI_COMPATIBLE }

data class OllamaRequest(
    val model: String,
    val prompt: String,
    val suffix: String,
    val stream: Boolean = false,
    val raw: Boolean = false, // 【新增这个字段】
    val options: Map<String, Any>
)

data class OllamaResponse(val response: String?)

data class OpenAiRequest(
    val model: String,
    val prompt: String,
    val suffix: String,
    @SerializedName("max_tokens") val maxTokens: Int,
    val stream: Boolean = false
)

data class OpenAiResponse(val choices: List<Choice>?) {
    data class Choice(val text: String?)
}