package gov.anzong.fim.completion.client

import com.google.gson.Gson

object FimRequestBuilder {
    private val gson = Gson()

    // 严禁模型越界生成的标志词
    private val DEFAULT_STOPS = listOf(
        "\n\n\n",          // 连续空行通常意味着一段逻辑的终结
        "\nclass ",        // 严禁在方法内凭空定义新类
        "\npublic class ",
        "<|endoftext|>",
        "<|file_separator|>"
    )

    fun buildRequestBody(
        type: ApiProviderType,
        model: String,
        prefix: String,
        suffix: String,
        maxTokens: Int,
        isSingleLine: Boolean = false
    ): String {
        val stops = if (isSingleLine) {
            DEFAULT_STOPS + listOf("\n", ";\n")
        } else {
            DEFAULT_STOPS
        }

        return when (type) {
            ApiProviderType.OLLAMA -> {
                // 使用 Ollama 原生的 prompt + suffix 字段，让引擎自动处理 FIM 标记
                val payload = mapOf(
                    "model" to model,
                    "prompt" to prefix,
                    "suffix" to suffix,
                    "stream" to false,
                    "raw" to true, // 保持 raw=true，防止 Ollama 强行插入 Chat 聊天模板
                    "options" to mapOf(
                        "num_predict" to maxTokens,
                        "temperature" to 0.1,
                        "stop" to stops
                    )
                )
                gson.toJson(payload)
            }
            ApiProviderType.OPENAI_COMPATIBLE -> {
                // 标准 OpenAI /v1/completions 接口同样原生支持 suffix 字段
                val payload = mapOf(
                    "model" to model,
                    "prompt" to prefix,
                    "suffix" to suffix,
                    "max_tokens" to maxTokens,
                    "temperature" to 0.1,
                    "stream" to false,
                    "stop" to stops
                )
                gson.toJson(payload)
            }
        }
    }
}