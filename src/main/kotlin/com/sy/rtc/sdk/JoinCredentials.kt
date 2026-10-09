package com.sy.rtc.sdk

import org.json.JSONObject

/**
 * LiveKit join credentials from a `meta=true` token response
 * (`sfuUrl` / `sfuToken` / `sfuRoom` / `sfuIdentity` / `sfuExpireAt`).
 * `sfuExpireAt` equals the SY token's `expireAt`, so both are renewed together.
 */
internal data class SfuJoinInfo(
    val url: String,
    val token: String,
    val room: String,
    val identity: String,
    val expireAt: Long,
)

/**
 * What [RtcEngine.join] / [RtcEngine.renewToken] received: the SY token plus,
 * when the server wired a media node, the LiveKit credentials.
 *
 * Accepts a plain token, the `data` object of `POST /api/rtc/token?meta=true`,
 * or the whole `{code, data}` envelope. LiveKit is used only when
 * `mediaWired == true` and both `sfuUrl` and `sfuToken` are present;
 * otherwise [sfu] is null and the existing P2P mesh is used.
 */
internal data class JoinCredentials(
    val token: String,
    val sfu: SfuJoinInfo?,
    val canPublish: Boolean?,
) {
    companion object {
        fun parse(input: String): JoinCredentials {
            val text = input.trim()
            if (!text.startsWith("{")) return JoinCredentials(text, null, null)
            val root = try {
                JSONObject(text)
            } catch (_: Exception) {
                return JoinCredentials(text, null, null)
            }
            val data = root.optJSONObject("data") ?: root
            val token = data.optString("token", "").trim()
            if (token.isEmpty()) return JoinCredentials(text, null, null)
            val url = data.optString("sfuUrl", "").trim()
            val sfuToken = data.optString("sfuToken", "").trim()
            val kind = data.optString("sfuKind", "livekit").trim().ifEmpty { "livekit" }
            val sfu = if (data.optBoolean("mediaWired", false) && url.isNotEmpty() &&
                sfuToken.isNotEmpty() && kind == "livekit"
            ) {
                SfuJoinInfo(
                    url = url,
                    token = sfuToken,
                    room = data.optString("sfuRoom", ""),
                    identity = data.optString("sfuIdentity", ""),
                    expireAt = data.optLong("sfuExpireAt", 0L),
                )
            } else {
                null
            }
            val canPublish = if (data.has("canPublish")) data.optBoolean("canPublish") else null
            return JoinCredentials(token, sfu, canPublish)
        }
    }
}
