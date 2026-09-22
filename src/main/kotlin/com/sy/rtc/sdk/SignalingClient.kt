package com.sy.rtc.sdk

import android.util.Log
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * WebSocket 信令客户端。
 * 连接 URL 必须带 ?token=（RTC Token）；服务端 [Hub.HandleWS] 校验后约束 join 的 channel/uid。
 */
internal class SignalingClient(
    private val signalingUrl: String,
    private val channelId: String,
    private val uid: String,
    private val token: String,
    private val onMessage: (type: String, data: Map<String, Any>) -> Unit,
    private val onConnectionFailure: (() -> Unit)? = null
) {
    private val TAG = "SignalingClient"
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    /** Build ws URL with ?token= (or &token= if query already present). */
    private fun urlWithToken(): String {
        val base = signalingUrl.trim()
        if (token.isBlank()) return base
        val encoded = URLEncoder.encode(token, "UTF-8")
        val sep = if (base.contains("?")) "&" else "?"
        // replace existing token= if any
        val cleaned = base.replace(Regex("""([?&])token=[^&]*"""), "$1").trimEnd('?', '&')
        val sep2 = if (cleaned.contains("?")) "&" else "?"
        return "$cleaned${sep2}token=$encoded"
    }

    fun connect() {
        try {
            val url = urlWithToken()
            Log.d(TAG, "Connecting signaling (token len=${token.length})")
            val request = Request.Builder().url(url).build()
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket 连接成功")
                    sendJoin()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "收到消息: $text")
                    handleMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    Log.d(TAG, "收到二进制消息: ${bytes.size} bytes")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket 正在关闭: code=$code, reason=$reason")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket 已关闭: code=$code, reason=$reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket 连接失败", t)
                    onConnectionFailure?.invoke()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "连接信令服务器失败", e)
            onConnectionFailure?.invoke()
        }
    }

    private fun sendJoin() {
        val message = JSONObject().apply {
            put("type", "join")
            put("channelId", channelId)
            put("uid", uid)
            // token 已在 URL；body 再带一份便于排障（服务端以 URL 为准）
            if (token.isNotBlank()) put("token", token)
        }
        send(message.toString())
    }

    fun sendOffer(sdp: String, toUid: String? = null) {
        val message = JSONObject().apply {
            put("type", "offer")
            put("channelId", channelId)
            put("uid", uid)
            if (!toUid.isNullOrEmpty()) put("toUid", toUid)
            put("data", JSONObject().apply {
                put("sdp", sdp)
                put("type", "offer")
            })
        }
        send(message.toString())
    }

    fun sendAnswer(sdp: String, toUid: String? = null) {
        val message = JSONObject().apply {
            put("type", "answer")
            put("channelId", channelId)
            put("uid", uid)
            if (!toUid.isNullOrEmpty()) put("toUid", toUid)
            put("data", JSONObject().apply {
                put("sdp", sdp)
                put("type", "answer")
            })
        }
        send(message.toString())
    }

    fun sendIceCandidate(candidate: String, sdpMLineIndex: Int, sdpMid: String, toUid: String? = null) {
        val message = JSONObject().apply {
            put("type", "ice-candidate")
            put("channelId", channelId)
            put("uid", uid)
            if (!toUid.isNullOrEmpty()) put("toUid", toUid)
            put("data", JSONObject().apply {
                put("candidate", candidate)
                put("sdpMLineIndex", sdpMLineIndex)
                put("sdpMid", sdpMid)
            })
        }
        send(message.toString())
    }

    fun sendChannelMessage(msg: String) {
        val message = JSONObject().apply {
            put("type", "channel-message")
            put("channelId", channelId)
            put("uid", uid)
            put("data", JSONObject().apply {
                put("uid", uid)
                put("message", msg)
            })
        }
        send(message.toString())
    }

    fun sendLeave() {
        val message = JSONObject().apply {
            put("type", "leave")
            put("channelId", channelId)
            put("uid", uid)
        }
        send(message.toString())
    }

    private fun send(text: String) {
        webSocket?.send(text) ?: Log.w(TAG, "WebSocket 未连接，无法发送消息")
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.getString("type")
            val data = json.optJSONObject("data")?.let { obj ->
                obj.keys().asSequence().associateWith { obj.get(it) }
            } ?: emptyMap()

            val extra = mutableMapOf<String, Any>()
            json.optString("uid")?.takeIf { it.isNotEmpty() }?.let { extra["uid"] = it }
            json.optString("channelId")?.takeIf { it.isNotEmpty() }?.let { extra["channelId"] = it }
            json.optString("toUid")?.takeIf { it.isNotEmpty() }?.let { extra["toUid"] = it }

            onMessage(type, if (extra.isEmpty()) data else data + extra)
        } catch (e: Exception) {
            Log.e(TAG, "解析消息失败", e)
        }
    }

    fun disconnect() {
        sendLeave()
        webSocket?.close(1000, "正常关闭")
        webSocket = null
    }
}
