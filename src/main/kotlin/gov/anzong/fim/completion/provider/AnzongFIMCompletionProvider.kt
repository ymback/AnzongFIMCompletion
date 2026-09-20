package gov.anzong.fim.completion.provider

import com.google.gson.Gson
import com.intellij.codeInsight.inline.completion.*
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionVariant
import gov.anzong.fim.completion.client.*
import gov.anzong.fim.completion.settings.AnzongSettingsState
import gov.anzong.fim.completion.util.CompletionPostProcessor
import gov.anzong.fim.completion.util.ContextManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

class AnzongFIMCompletionProvider : InlineCompletionProvider {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()

    override val id: InlineCompletionProviderID = InlineCompletionProviderID("AnzongFIM")

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        return AnzongSettingsState.instance.isEnabled
    }

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val settings = AnzongSettingsState.instance

        if (!settings.isEnabled || settings.finalEndpoint.isBlank() || settings.selectedModel.isBlank()) {
            return InlineCompletionSuggestion.Empty
        }

        return object : InlineCompletionSuggestion {
            override suspend fun getVariants(): List<InlineCompletionVariant> {
                val variant = InlineCompletionVariant.build(elements = flow {
                    // 智能防抖：等待用户键入稳定
                    if (request.event is InlineCompletionEvent.DocumentChange) {
                        delay(250) // 缩短至 250ms，使响应更跟手
                    }

                    if (!coroutineContext.isActive) return@flow

                    val editor = request.editor
                    val project = editor.project ?: return@flow
                    val offset = request.endOffset

                    val context = ContextManager.buildFIMContext(project, editor, offset)
                    if (context.prefix.isBlank()) return@flow

                    val rawGeneratedCode = fetchCodeFromAPI(context.prefix, context.suffix, context.isSingleLine, settings)

                    if (!coroutineContext.isActive) return@flow

                    // 后处理清洗（包含之前做的大括号防幻觉平衡）
                    val cleanCode = CompletionPostProcessor.cleanCompletion(rawGeneratedCode, context.suffix)

                    if (cleanCode.isNotBlank()) {
                        emit(InlineCompletionGrayTextElement(cleanCode))
                    }
                })

                return listOf(variant)
            }
        }
    }

    private suspend fun fetchCodeFromAPI(prefix: String, suffix: String, isSingleLine: Boolean, settings: AnzongSettingsState): String {
        return suspendCancellableCoroutine { continuation ->
            val requestBuilder = Request.Builder().url(settings.finalEndpoint)

            settings.apiKey?.takeIf { it.isNotBlank() }?.let {
                requestBuilder.addHeader("Authorization", "Bearer $it")
            }

            val jsonBody = FimRequestBuilder.buildRequestBody(
                type = settings.apiType,
                model = settings.selectedModel,
                prefix = prefix,
                suffix = suffix,
                maxTokens = settings.maxTokens,
                isSingleLine = isSingleLine
            )

            val request = requestBuilder.post(jsonBody.toRequestBody(jsonMediaType)).apply {
                addHeader("Accept", "application/json")
            }.build()

            val call = client.newCall(request)

            continuation.invokeOnCancellation {
                try {
                    call.cancel()
                } catch (e: Exception) {
                    // 忽略取消时的异常
                }
            }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resume("")
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    try {
                        val responseBody = response.body?.string() ?: ""
                        if (!response.isSuccessful) {
                            continuation.resume("")
                            return
                        }

                        val code = when (settings.apiType) {
                            ApiProviderType.OLLAMA -> gson.fromJson(responseBody, OllamaResponse::class.java).response ?: ""
                            ApiProviderType.OPENAI_COMPATIBLE -> gson.fromJson(responseBody, OpenAiResponse::class.java).choices?.firstOrNull()?.text ?: ""
                        }
                        continuation.resume(code)
                    } catch (e: Exception) {
                        if (continuation.isActive) {
                            continuation.resume("")
                        }
                    } finally {
                        response.close()
                    }
                }
            })
        }
    }
}