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
    private var token: String,
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
            val socket = client.newWebSocket(request, object : WebSocketListener() {
                private fun isActive(socket: WebSocket): Boolean = socket === webSocket

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!isActive(webSocket)) return
                    Log.d(TAG, "WebSocket 连接成功")
                    sendJoin()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!isActive(webSocket)) return
                    Log.d(TAG, "收到消息: $text")
                    handleMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (!isActive(webSocket)) return
                    Log.d(TAG, "收到二进制消息: ${bytes.size} bytes")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (!isActive(webSocket)) return
                    Log.d(TAG, "WebSocket 正在关闭: code=$code, reason=$reason")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!isActive(webSocket)) return
                    Log.d(TAG, "WebSocket 已关闭: code=$code, reason=$reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!isActive(webSocket)) {
                        Log.d(TAG, "忽略已替换连接的失败")
                        return
                    }
                    Log.e(TAG, "WebSocket 连接失败", t)
                    onConnectionFailure?.invoke()
                }
            })
            val previous = webSocket
            webSocket = socket
            if (previous != null && previous !== socket) {
                // 续期只换 ?token=，不发 leave，避免服务端把用户判成离房。
                previous.close(1000, "renew-token")
            }
        } catch (e: Exception) {
            Log.e(TAG, "连接信令服务器失败", e)
            onConnectionFailure?.invoke()
        }
    }

    /**
     * 用新的 RTC Token 重连信令。不发送 leave。
     */
    fun renewToken(newToken: String) {
        token = newToken
        Log.d(TAG, "renewToken len=${newToken.length}")
        connect()
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

    /** 本端静音状态，见 [WireProtocol.USER_MEDIA_TYPE]。只带传入的字段。 */
    fun sendUserMedia(audioMuted: Boolean?, videoMuted: Boolean?) {
        val message = JSONObject().apply {
            put("type", WireProtocol.USER_MEDIA_TYPE)
            put("channelId", channelId)
            put("uid", uid)
            put("data", JSONObject().apply {
                put("uid", uid)
                audioMuted?.let { put("audioMuted", it) }
                videoMuted?.let { put("videoMuted", it) }
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
