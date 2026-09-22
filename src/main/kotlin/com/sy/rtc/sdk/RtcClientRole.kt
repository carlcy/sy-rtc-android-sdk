package com.sy.rtc.sdk

/**
 * 客户端角色（对齐控制面 Token role：host|audience|publisher|subscriber）。
 * audience/subscriber → 本地不推流；host/publisher → 可推流。
 * 控制面 canPublish 以 Token privilege 为准；此处仅本地媒体开关。
 */
enum class RtcClientRole {
    /** 主播/房主（可推流） */
    HOST,

    /** 观众（默认不推流） */
    AUDIENCE,

    /** 推流者（可推流） */
    PUBLISHER,

    /** 订阅者（默认不推流） */
    SUBSCRIBER;

    fun canPublish(): Boolean = this == HOST || this == PUBLISHER

    fun toApiRole(): String = when (this) {
        HOST -> "host"
        AUDIENCE -> "audience"
        PUBLISHER -> "publisher"
        SUBSCRIBER -> "subscriber"
    }

    companion object {
        fun fromApi(role: String?): RtcClientRole = when (role?.trim()?.lowercase()) {
            "host" -> HOST
            "publisher" -> PUBLISHER
            "subscriber" -> SUBSCRIBER
            "audience" -> AUDIENCE
            else -> AUDIENCE
        }
    }
}
