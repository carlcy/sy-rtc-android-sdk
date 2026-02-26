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
    val createTime: String? = null
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
                createTime = json.optString("createTime").takeIf { it.isNotEmpty() }
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
     * 获取 RTC Token
     *
     * 用于 [RtcEngine.join] 加入房间时所需的 RTC Token。
     *
     * @param channelId 要加入的房间 ID
     * @param uid 用户 ID
     * @param expireHours 过期时间（小时），默认 24
     * @param callback 回调 (Token 字符串, 异常)，成功时 token 非 null
     */
    fun fetchToken(
        channelId: String,
        uid: String,
        expireHours: Int = 24,
        callback: (String?, Exception?) -> Unit
    ) {
        runOnBackground({
            val queryParams = mapOf(
                "channelId" to channelId,
                "uid" to uid,
                "expireHours" to expireHours.toString()
            )
            val (_, respBody) = executeRequest("POST", "/api/rtc/token", queryParams = queryParams)
            val json = JSONObject(respBody)
            val respCode = json.optInt("code", -1)
            if (respCode != 0) {
                throw Exception(json.optString("msg", "获取 Token 失败"))
            }
            val data = json.opt("data")
            when (data) {
                is String -> data
                null -> throw Exception("Token 响应格式错误")
                else -> data.toString()
            }
        }, callback)
    }
}
