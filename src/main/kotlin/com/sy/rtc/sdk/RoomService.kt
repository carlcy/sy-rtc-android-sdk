package com.sy.rtc.sdk

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 房间信息
 *
 * 包含房间的基本信息，用于房间列表、详情等场景。
 *
 * @property channelId 房间/频道 ID
 * @property hostUid 房主用户 ID
 * @property status 房间状态（如 active、closed）
 * @property onlineCount 当前在线人数
 * @property maxSeats 麦位上限
 * @property createTime 创建时间（ISO8601 字符串）
 */
data class RoomInfo(
    val channelId: String,
    val hostUid: String? = null,
    val status: String = "active",
    val onlineCount: Int = 0,
    val maxSeats: Int = 8,
    val createTime: String? = null,
    val currentSeats: Int = 0,
    val attrs: Map<String, String> = emptyMap()
) {
    companion object {
        /**
         * 从 JSON 对象解析 [RoomInfo]
         *
         * 兼容 heat 与 onlineCount 字段（后端可能返回 heat 表示在线人数）
         */
        internal fun fromJson(json: JSONObject): RoomInfo {
            val heat = json.optInt("heat", -1)
            val onlineCount = if (heat >= 0) heat else json.optInt("onlineCount", 0)
            return RoomInfo(
                channelId = json.optString("channelId", ""),
                hostUid = json.optString("hostUid").takeIf { it.isNotEmpty() },
                status = json.optString("status", "active"),
                onlineCount = onlineCount,
                maxSeats = json.optInt("maxSeats", 8),
                createTime = json.optString("createTime").takeIf { it.isNotEmpty() },
                currentSeats = json.optInt("currentSeats", 0),
                attrs = readAttrs(json)
            )
        }
    }
}

/**
 * SY RTC 房间服务
 *
 * 提供房间管理和 Token 获取的便捷封装，是 RTC 引擎的可选配套组件。
 *
 * 典型使用流程：
 * ```
 * val roomService = RoomService(
 *     apiBaseUrl = "http://your-server.com/demo-api",
 *     appId = "YOUR_APP_ID"
 * )
 * roomService.setAuthToken(jwt)  // 用户登录后获取的 JWT
 *
 * // 1. 浏览房间列表（不需要 RTC Token）
 * roomService.getRoomList { rooms, error -> ... }
 *
 * // 2. 创建房间
 * roomService.createRoom("my_room") { room, error -> ... }
 *
 * // 3. 获取 RTC Token 并加入房间
 * roomService.fetchToken("my_room", "user_1") { token, error ->
 *     engine.join("my_room", "user_1", token)
 * }
 * ```
 *
 * @property apiBaseUrl 后端 API 基础 URL（如 http://your-server.com）
 * @property appId 应用 ID
 */
class RoomService(
    private val apiBaseUrl: String,
    private val appId: String
) {
    @Volatile
    private var authToken: String? = null

    @Volatile
    private var appSecret: String? = null

    @Volatile
    private var userId: String? = null

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 设置 API 认证 Token（用户登录后获取的 JWT）
     *
     * 用于调用需要登录认证的接口（如创建房间、获取 Token 等）。
     */
    fun setAuthToken(token: String) {
        authToken = token
    }

    /**
     * 设置 AppSecret（仅 Demo/测试用，生产环境应使用 JWT）
     *
     * 使用 AppSecret 时无需 JWT，适用于快速联调。
     */
    fun setAppSecret(secret: String) {
        appSecret = secret
    }

    /**
     * 设置用户 ID（用于房间创建、上下麦等需要身份的操作）
     */
    fun setUserId(uid: String) {
        userId = uid
    }

    private fun buildHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>(
            "X-App-Id" to appId,
            "Content-Type" to "application/json"
        )
        authToken?.takeIf { it.isNotEmpty() }?.let {
            headers["Authorization"] = "Bearer $it"
        }
        appSecret?.takeIf { it.isNotEmpty() }?.let {
            headers["X-App-Secret"] = it
        }
        userId?.takeIf { it.isNotEmpty() }?.let {
            headers["X-Uid"] = it
        }
        return headers
    }

    private fun buildUrl(path: String, queryParams: Map<String, String>? = null): String {
        val base = apiBaseUrl.trimEnd('/')
        val fullPath = if (path.startsWith("/")) path else "/$path"
        var url = "$base$fullPath"
        if (!queryParams.isNullOrEmpty()) {
            val query = queryParams.entries.joinToString("&") { (k, v) ->
                "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
            }
            url = "$url?$query"
        }
        return url
    }

    private fun executeRequest(
        method: String,
        path: String,
        queryParams: Map<String, String>? = null,
        body: String? = null
    ): Pair<Int, String> {
        val url = URL(buildUrl(path, queryParams))
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.doInput = true

        buildHeaders().forEach { (k, v) -> conn.setRequestProperty(k, v) }

        if (body != null && (method == "POST" || method == "PUT")) {
            conn.doOutput = true
            conn.outputStream.use { os: OutputStream ->
                os.write(body.toByteArray(Charsets.UTF_8))
            }
        }

        val code = conn.responseCode
        val inputStream = if (code in 200..299) conn.inputStream else conn.errorStream
        val responseBody = inputStream?.use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
        } ?: ""
        conn.disconnect()
        return code to responseBody
    }

    private fun <T> runOnBackground(block: () -> T, callback: (T?, Exception?) -> Unit) {
        executor.execute {
            try {
                val result = block()
                mainHandler.post { callback(result, null) }
            } catch (e: Exception) {
                mainHandler.post { callback(null, e) }
            }
        }
    }

    /**
     * 获取活跃房间列表
     *
     * 返回当前应用下的所有活跃房间，每个房间含 channelId、在线人数等基本信息。
     * 不需要 RTC Token，只需要 API 认证（JWT 或 AppSecret）。
     *
     * @param callback 回调 (房间列表, 异常)，成功时 rooms 非 null，失败时 exception 非 null
     */
    fun getRoomList(callback: (List<RoomInfo>?, Exception?) -> Unit) {
        runOnBackground({
            val (code, body) = executeRequest("GET", "/api/room/active")
            val json = JSONObject(body)
            val respCode = json.optInt("code", -1)
            if (respCode != 0) {
                throw Exception(json.optString("msg", "获取房间列表失败"))
            }
            val data = json.optJSONArray("data")
            if (data == null) return@runOnBackground emptyList<RoomInfo>()
            (0 until data.length()).map { i ->
                RoomInfo.fromJson(data.getJSONObject(i))
            }
        }, callback)
    }

    /**
     * 创建房间
     *
     * @param channelId 房间 ID（唯一标识）
     * @param callback 回调 (房间信息, 异常)，成功时 room 非 null
     */
    fun createRoom(channelId: String, callback: (RoomInfo?, Exception?) -> Unit) {
        runOnBackground({
            val body = JSONObject().apply { put("channelId", channelId) }.toString()
            val (_, respBody) = executeRequest("POST", "/api/room/create", body = body)
            val json = JSONObject(respBody)
            val respCode = json.optInt("code", -1)
            if (respCode != 0) {
                throw Exception(json.optString("msg", "创建房间失败"))
            }
            val data = json.optJSONObject("data")
            if (data != null) RoomInfo.fromJson(data)
            else RoomInfo(channelId = channelId)
        }, callback)
    }

    /**
     * 关闭房间
     *
     * @param channelId 房间 ID
     * @param callback 回调 (是否成功, 异常)
     */
    fun closeRoom(channelId: String, callback: (Boolean, Exception?) -> Unit) {
        runOnBackground({
            val (_, respBody) = executeRequest("POST", "/api/room/$channelId/close")
            val json = JSONObject(respBody)
            val respCode = json.optInt("code", -1)
            if (respCode != 0) {
                throw Exception(json.optString("msg", "关闭房间失败"))
            }
            true
        }) { success, error ->
            callback(success ?: false, error)
        }
    }

    /**
     * 获取房间详情
     *
     * @param channelId 房间 ID
     * @param callback 回调 (房间信息, 异常)，成功时 room 非 null
     */
    fun getRoomDetail(channelId: String, callback: (RoomInfo?, Exception?) -> Unit) {
        runOnBackground({
            val (_, respBody) = executeRequest("GET", "/api/room/$channelId")
            val json = JSONObject(respBody)
            val respCode = json.optInt("code", -1)
            if (respCode != 0) {
                throw Exception(json.optString("msg", "获取房间详情失败"))
            }
            val data = json.optJSONObject("data")
            if (data != null) RoomInfo.fromJson(data)
            else RoomInfo(channelId = channelId)
        }, callback)
    }

    /**
     * 查询频道在线人数
     *
     * @param channelId 房间/频道 ID
     * @param callback 回调 (在线人数, 异常)，失败时返回 0
     */
    fun getOnlineCount(channelId: String, callback: (Int, Exception?) -> Unit) {
        runOnBackground({
            val (_, respBody) = executeRequest("GET", "/api/room/$channelId/online-count")
            val json = JSONObject(respBody)
            val respCode = json.optInt("code", -1)
            if (respCode != 0) return@runOnBackground 0
            val data = json.optJSONObject("data")
            data?.optInt("count", 0) ?: json.optInt("count", 0)
        }) { count, error ->
            callback(count ?: 0, error)
        }
    }

    /**
     * 获取 RTC Token（别名 [getToken]）。
     *
     * `POST /api/rtc/token`。鉴权与其它房间接口相同：`X-App-Id`，再加上用户 JWT 或 AppSecret。
     * 用于 [RtcEngine.join]；信令 WS 须带 ?token=。
     *
     * 业务码 4031 / 4032 / 4033 通过 callback 的异常返回，类型为 [RtcCredentialException]。
     *
     * @param role host|audience|publisher|subscriber（写入 Token privilege）
     * @param qualityTier audio|sd|hd|fhd
     * @param meta true 时返回 JSON 字符串含 token+canPublish（一般用 false 拿纯 token）
     */
    fun fetchToken(
        channelId: String,
        uid: String,
        expireHours: Int = 24,
        role: String? = null,
        qualityTier: String? = null,
        meta: Boolean = false,
        callback: (String?, Exception?) -> Unit
    ) = requestRtcToken(
        "/api/rtc/token", channelId, uid, expireHours, role, qualityTier, meta, "获取 Token 失败", callback
    )

    /**
     * 续期 RTC Token：`POST /api/rtc/token/renew`。
     *
     * 查询参数和鉴权与 [fetchToken] 相同。成功后把返回的字符串交给 [RtcEngine.renewToken]，不必先 leave。
     * 4031 凭证停用、4032 吊销、4033 过期同样走 callback 的 [RtcCredentialException]。
     */
    fun renewToken(
        channelId: String,
        uid: String,
        expireHours: Int = 24,
        role: String? = null,
        qualityTier: String? = null,
        meta: Boolean = false,
        callback: (String?, Exception?) -> Unit
    ) = requestRtcToken(
        "/api/rtc/token/renew", channelId, uid, expireHours, role, qualityTier, meta, "续期 Token 失败", callback
    )

    /** [fetchToken] 别名，便于与文档 getToken 对齐。 */
    fun getToken(
        channelId: String,
        uid: String,
        expireHours: Int = 24,
        role: String? = null,
        qualityTier: String? = null,
        meta: Boolean = false,
        callback: (String?, Exception?) -> Unit
    ) = fetchToken(channelId, uid, expireHours, role, qualityTier, meta, callback)

    private fun requestRtcToken(
        path: String,
        channelId: String,
        uid: String,
        expireHours: Int,
        role: String?,
        qualityTier: String?,
        meta: Boolean,
        fallback: String,
        callback: (String?, Exception?) -> Unit
    ) {
        runOnBackground({
            val queryParams = mutableMapOf(
                "channelId" to channelId,
                "uid" to uid,
                "expireHours" to expireHours.toString()
            )
            role?.takeIf { it.isNotBlank() }?.let { queryParams["role"] = it }
            qualityTier?.takeIf { it.isNotBlank() }?.let { queryParams["qualityTier"] = it }
            if (meta) queryParams["meta"] = "true"
            val (http, respBody) = executeRequest("POST", path, queryParams = queryParams)
            val json = try {
                JSONObject(respBody)
            } catch (e: Exception) {
                throw Exception("$fallback: HTTP $http ${respBody.take(180)}", e)
            }
            val respCode = if (json.has("code")) json.optInt("code") else if (http !in 200..299) http else -1
            if (respCode != 0) {
                throw businessException(respCode, serverMessage(json), fallback)
            }
            val data = json.opt("data")
            when (data) {
                is String -> data
                is JSONObject -> if (meta) data.toString() else data.optString("token").ifBlank { data.toString() }
                null, JSONObject.NULL -> throw Exception("Token 响应格式错误")
                else -> data.toString()
            }
        }, callback)
    }

    /**
     * 切换控制面画质档位：`POST /api/rtc/quality/switch`。
     *
     * 只接受用户 JWT（先 [setAuthToken]）。`qualityTier` 为 audio|sd|hd|fhd。
     * 成功且响应里带有新 RTC Token 时，callback 的第一个参数是该 Token，应再调用 [RtcEngine.renewToken]；
     * 成功但没有新 Token 时两个参数都是 null。本地编码用 [RtcEngine.setVideoQuality]。
     */
    fun switchQualityTier(
        channelId: String,
        qualityTier: String,
        uid: String? = null,
        callback: (String?, Exception?) -> Unit
    ) {
        runOnBackground({
            val queryParams = mutableMapOf(
                "channelId" to channelId,
                "qualityTier" to qualityTier
            )
            val bodyJson = JSONObject().apply {
                put("channelId", channelId)
                put("qualityTier", qualityTier)
            }
            if (!uid.isNullOrBlank()) {
                queryParams["uid"] = uid
                bodyJson.put("uid", uid)
            }
            val payload = postEnvelope(
                "/api/rtc/quality/switch",
                queryParams,
                bodyJson.toString(),
                "切换画质失败"
            )
            when (payload) {
                is String -> payload.takeIf { it.isNotEmpty() }
                is JSONObject -> payload.optString("token").takeIf { it.isNotEmpty() }
                else -> null
            }
        }, callback)
    }

    /**
     * 轮询单成员踢人/静音状态：GET /api/room/{channelId}/members/{uid}/state
     * 信令未在线时服务端 note 要求 SDK poll。
     */
    fun getMemberState(
        channelId: String,
        uid: String,
        callback: (MemberModerationState?, Exception?) -> Unit
    ) {
        runOnBackground({
            val (_, respBody) = executeRequest("GET", "/api/room/$channelId/members/$uid/state")
            val json = JSONObject(respBody)
            if (json.optInt("code", -1) != 0) {
                throw Exception(json.optString("msg", "获取成员状态失败"))
            }
            val data = json.optJSONObject("data") ?: JSONObject()
            MemberModerationState.fromJson(data)
        }, callback)
    }

    /** 房间内全部成员 kick/mute 标志：GET /api/room/{channelId}/members/state */
    fun listMemberStates(
        channelId: String,
        callback: (List<MemberModerationState>?, Exception?) -> Unit
    ) {
        runOnBackground({
            val (_, respBody) = executeRequest("GET", "/api/room/$channelId/members/state")
            val json = JSONObject(respBody)
            if (json.optInt("code", -1) != 0) {
                throw Exception(json.optString("msg", "获取成员状态列表失败"))
            }
            val data = json.optJSONObject("data")
            val arr = data?.optJSONArray("list")
            val out = mutableListOf<MemberModerationState>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { out.add(MemberModerationState.fromJson(it)) }
                }
            }
            out
        }, callback)
    }

    /**
     * 写入一个房间属性：`POST /api/rtc/channel/meta/set`。
     *
     * 需要用户 JWT（[setAuthToken]）。对应一个 key 和一条字符串 value。
     */
    fun setRoomAttribute(
        channelId: String,
        key: String,
        value: String,
        callback: (Boolean, Exception?) -> Unit
    ) {
        runOnBackground({
            val query = mapOf("channelId" to channelId, "key" to key, "value" to value)
            val body = JSONObject().apply {
                put("channelId", channelId)
                put("key", key)
                put("value", value)
            }.toString()
            postEnvelope("/api/rtc/channel/meta/set", query, body, "设置房间属性失败")
            true
        }) { success, error ->
            callback(success ?: false, error)
        }
    }

    /**
     * 读取房间属性：`POST /api/rtc/channel/meta/get`。
     *
     * 不传 [key] 时取整张表。需要用户 JWT（[setAuthToken]）。
     */
    fun getRoomAttributes(
        channelId: String,
        key: String? = null,
        callback: (Map<String, String>?, Exception?) -> Unit
    ) {
        runOnBackground({
            val query = mutableMapOf("channelId" to channelId)
            val bodyJson = JSONObject().apply { put("channelId", channelId) }
            if (!key.isNullOrBlank()) {
                query["key"] = key
                bodyJson.put("key", key)
            }
            val payload = postEnvelope(
                "/api/rtc/channel/meta/get",
                query,
                bodyJson.toString(),
                "获取房间属性失败"
            )
            attributesFrom(payload, key)
        }, callback)
    }

    /**
     * 删除一个房间属性：`POST /api/rtc/channel/meta/delete`。
     *
     * 需要用户 JWT（[setAuthToken]）。
     */
    fun deleteRoomAttribute(
        channelId: String,
        key: String,
        callback: (Boolean, Exception?) -> Unit
    ) {
        runOnBackground({
            val query = mapOf("channelId" to channelId, "key" to key)
            val body = JSONObject().apply {
                put("channelId", channelId)
                put("key", key)
            }.toString()
            postEnvelope("/api/rtc/channel/meta/delete", query, body, "删除房间属性失败")
            true
        }) { success, error ->
            callback(success ?: false, error)
        }
    }

    private fun postEnvelope(
        path: String,
        queryParams: Map<String, String>,
        body: String,
        fallback: String
    ): Any? {
        val (http, respBody) = executeRequest("POST", path, queryParams = queryParams, body = body)
        val json = try {
            JSONObject(respBody)
        } catch (e: Exception) {
            throw Exception("$fallback: HTTP $http ${respBody.take(180)}", e)
        }
        val respCode = if (json.has("code")) json.optInt("code") else if (http !in 200..299) http else -1
        if (respCode != 0) {
            throw businessException(respCode, serverMessage(json), fallback)
        }
        return if (json.has("data")) json.opt("data") else null
    }

    private fun serverMessage(json: JSONObject): String {
        return json.optString("msg").ifBlank { json.optString("message") }
    }

    private fun businessException(code: Int, serverMessage: String, fallback: String): Exception {
        return RtcCredentialException.fromCode(code, serverMessage)
            ?: Exception(serverMessage.ifBlank { fallback })
    }
}

private fun attributesFrom(payload: Any?, requestedKey: String?): Map<String, String> {
    when (payload) {
        null, JSONObject.NULL -> return emptyMap()
        is String -> {
            if (!requestedKey.isNullOrBlank()) return mapOf(requestedKey to payload)
            return emptyMap()
        }
        is JSONObject -> {
            val key = payload.optString("key")
            val value = scalarString(payload.opt("value"))
            if (key.isNotEmpty() && value != null && payload.has("value")) {
                return mapOf(key to value)
            }
            val nested = payload.optJSONObject("attrs")
                ?: payload.optJSONObject("meta")
                ?: payload.optJSONObject("attributes")
            if (nested != null) return stringMap(nested)
            val list = payload.optJSONArray("list")
            if (list != null) {
                val out = linkedMapOf<String, String>()
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val itemKey = item.optString("key")
                    val itemValue = scalarString(item.opt("value"))
                    if (itemKey.isNotEmpty() && itemValue != null) out[itemKey] = itemValue
                }
                return out
            }
            return stringMap(payload)
        }
        else -> return emptyMap()
    }
}

private fun stringMap(obj: JSONObject): Map<String, String> {
    val out = linkedMapOf<String, String>()
    val keys = obj.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val text = scalarString(obj.opt(key)) ?: continue
        out[key] = text
    }
    return out
}

private fun scalarString(value: Any?): String? {
    return when (value) {
        null, JSONObject.NULL -> null
        is String -> value
        is JSONObject, is org.json.JSONArray -> null
        is Number, is Boolean -> value.toString()
        else -> null
    }
}

private fun readAttrs(json: JSONObject): Map<String, String> {
    val obj = json.optJSONObject("attrs")
        ?: json.optJSONObject("attributes")
        ?: json.optJSONObject("extra")
        ?: return emptyMap()
    val map = linkedMapOf<String, String>()
    val keys = obj.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = obj.opt(key)
        if (value == null || value == JSONObject.NULL || value is JSONObject || value is org.json.JSONArray) {
            continue
        }
        map[key] = value.toString()
    }
    return map
}

/**
 * 控制面凭证被拒绝。出现在拉 Token / 续期 Token（以及其它走同一信封的接口）。
 *
 * - 4031 凭证已停用（credential suspended）
 * - 4032 凭证已吊销（credential revoked）
 * - 4033 凭证已过期（credential expired）
 */
class RtcCredentialException(
    val businessCode: Int,
    message: String
) : Exception(message) {
    companion object {
        fun fromCode(code: Int, serverMessage: String): RtcCredentialException? {
            val reason = when (code) {
                4031 -> "凭证已停用 (credential suspended)"
                4032 -> "凭证已吊销 (credential revoked)"
                4033 -> "凭证已过期 (credential expired)"
                else -> return null
            }
            val detail = serverMessage.trim()
            val text = if (detail.isEmpty()) "$reason [$code]" else "$reason [$code]: $detail"
            return RtcCredentialException(code, text)
        }
    }
}

/**
 * 房间成员 moderation 状态（控制面持久化；非 SFU 强制切断）。
 */
data class MemberModerationState(
    val appId: String,
    val channelId: String,
    val uid: String,
    val mutedAudio: Boolean,
    val kicked: Boolean,
    val kickReason: String,
    val signalingNotified: Boolean,
) {
    companion object {
        fun fromJson(json: JSONObject): MemberModerationState = MemberModerationState(
            appId = json.optString("appId"),
            channelId = json.optString("channelId"),
            uid = json.optString("uid"),
            mutedAudio = json.optInt("mutedAudio", 0) == 1 || json.optBoolean("mutedAudio", false),
            kicked = json.optInt("kicked", 0) == 1 || json.optBoolean("kicked", false),
            kickReason = json.optString("kickReason"),
            signalingNotified = json.optInt("signalingNotified", 0) == 1,
        )
    }
}
