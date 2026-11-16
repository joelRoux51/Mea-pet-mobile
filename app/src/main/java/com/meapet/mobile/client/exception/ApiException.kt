package com.meapet.mobile.client.exception

import android.content.Context
import com.meapet.mobile.R
import com.meapet.mobile.core.getStringOrFallback

/**
 * OpenAI 兼容 API 的统一异常封装。
 *
 * @property statusCode HTTP 状态码
 * @property responseBody 服务端返回的原始响应体（JSON 字符串）
 * @property detail 解析出的服务端错误详情（可选）
 */
class ApiException(
    val statusCode: Int,
    val responseBody: String,
    message: String,
    val detail: String? = null
) : RuntimeException(message) {

    fun localizedMessage(context: Context): String {
        val base = when (statusCode) {
            401 -> context.getStringOrFallback(
                R.string.api_err_401,
                "Invalid or missing API Key. Please configure a valid API Key in Settings."
            )
            402 -> context.getStringOrFallback(
                R.string.api_err_402,
                "Insufficient API balance. Please top up and retry."
            )
            403 -> context.getStringOrFallback(
                R.string.api_err_403,
                "API Key lacks permission for this endpoint."
            )
            404 -> context.getStringOrFallback(
                R.string.api_err_404,
                "Endpoint not found. Check your API URL."
            )
            429 -> context.getStringOrFallback(
                R.string.api_err_429,
                "Too many requests. Please try again later."
            )
            else -> context.getStringOrFallback(
                R.string.api_err_http,
                "API request failed (HTTP %1\$d)",
                statusCode
            )
        }
        val trimmed = detail?.trim()?.takeIf { it.isNotEmpty() } ?: return base
        val shown = if (trimmed.length > MAX_DETAIL_LENGTH) {
            trimmed.take(MAX_DETAIL_LENGTH) + "…"
        } else {
            trimmed
        }
        return context.getStringOrFallback(
            R.string.api_err_server_detail,
            "%1\$s\n(Server: %2\$s)",
            base,
            shown
        )
    }

    companion object {
        private const val MAX_DETAIL_LENGTH = 200

        /** Default English user-facing hint for HTTP status codes. */
        fun friendlyMessage(statusCode: Int): String = when (statusCode) {
            401 -> "Invalid or missing API Key. Please configure a valid API Key in Settings."
            402 -> "Insufficient API balance. Please top up and retry."
            403 -> "API Key lacks permission for this endpoint."
            404 -> "Endpoint not found. Check your API URL."
            429 -> "Too many requests. Please try again later."
            else -> "API request failed (HTTP $statusCode)"
        }

        fun friendlyMessage(statusCode: Int, detail: String?): String {
            val base = friendlyMessage(statusCode)
            val trimmed = detail?.trim()?.takeIf { it.isNotEmpty() } ?: return base
            val shown = if (trimmed.length > MAX_DETAIL_LENGTH) {
                trimmed.take(MAX_DETAIL_LENGTH) + "…"
            } else {
                trimmed
            }
            return "$base\n(Server: $shown)"
        }
    }
}

