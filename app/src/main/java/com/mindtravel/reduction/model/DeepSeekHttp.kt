package com.mindtravel.reduction.model

import com.mindtravel.reduction.core.ApiException
import com.mindtravel.reduction.core.CancelSignal
import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.core.NetworkException
import com.mindtravel.reduction.core.objectOrNull
import com.mindtravel.reduction.core.stringOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

class DeepSeekHttp(
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        private val RETRYABLE = setOf(502, 503, 504)

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // Short: a connection that has not opened in this time is not going to, and the user is waiting.
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    fun postJson(path: String, apiKey: String, body: JSONObject): JSONObject {
        val request = request(path, apiKey)
            .header("Content-Type", JSON_CONTENT_TYPE)
            .post(body.toString().toRequestBody(JSON_CONTENT_TYPE.toMediaType()))
            .build()
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw httpError(response.code, text)
            }
            parseObject(text)
        }
    }

    fun postBytes(path: String, apiKey: String, body: JSONObject): ByteArray {
        val request = request(path, apiKey)
            .header("Content-Type", JSON_CONTENT_TYPE)
            .post(body.toString().toRequestBody(JSON_CONTENT_TYPE.toMediaType()))
            .build()
        return client.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                throw httpError(response.code, String(bytes, Charsets.UTF_8))
            }
            if (bytes.isEmpty()) throw ApiException("接口返回为空")
            bytes
        }
    }

    fun postMultipart(
        path: String,
        apiKey: String,
        fieldName: String,
        fileName: String,
        contentType: String,
        bytes: ByteArray
    ): JSONObject {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                fieldName,
                fileName,
                bytes.toRequestBody(contentType.toMediaType())
            )
            .build()
        val request = request(path, apiKey).post(body).build()
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw httpError(response.code, text)
            }
            parseObject(text)
        }
    }

    fun stream(
        path: String,
        apiKey: String,
        body: JSONObject,
        cancellation: CancelSignal? = null,
        onLine: (String) -> Unit
    ) {
        val request = request(path, apiKey)
            .header("Content-Type", JSON_CONTENT_TYPE)
            .post(body.toString().toRequestBody(JSON_CONTENT_TYPE.toMediaType()))
            .build()
        val call = client.newCall(request)
        // The coroutine job cannot interrupt a blocking read; this is what does.
        cancellation?.onCancel { call.cancel() }
        try {
            cancellation?.ensureActive()
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw httpError(response.code, response.body?.string().orEmpty())
                }
                val source = response.body?.source() ?: throw ApiException("DeepSeek 返回了空响应")
                while (true) {
                    cancellation?.ensureActive()
                    val line = source.readUtf8Line() ?: break
                    onLine(line)
                }
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: ApiException) {
            throw t
        } catch (t: Throwable) {
            if (cancellation?.isCancelled == true) throw CancellationException("回复已取消")
            Logs.add("Network error: ${t.javaClass.simpleName}: ${t.message}")
            throw NetworkException("网络错误：${t.message ?: t.javaClass.simpleName}", t)
        }
    }

    private fun request(path: String, apiKey: String): Request.Builder {
        if (apiKey.isBlank()) throw ApiException("API Key 为空")
        return Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer $apiKey")
    }

    private fun parseObject(text: String): JSONObject =
        try {
            JSONObject(text)
        } catch (t: Throwable) {
            throw ApiException("接口返回格式异常", t)
        }

    private fun httpError(code: Int, body: String): ApiException {
        val detail = runCatching {
            JSONObject(body).objectOrNull("error")?.stringOrNull("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }

        val base = when (code) {
            400 -> "请求参数有误"
            401 -> "API Key 无效或未授权"
            402 -> "账户余额不足"
            413 -> "上传内容过大"
            429 -> "请求过于频繁"
            503 -> "DeepSeek 服务繁忙"
            504 -> "请求超时"
            else -> "请求失败（HTTP $code）"
        }
        val message = if (detail == null) base else "$base：${detail.take(180)}"
        // A gateway or server that is momentarily overwhelmed is the same to the user as no network.
        return if (code in RETRYABLE) NetworkException(message) else ApiException(message)
    }
}
