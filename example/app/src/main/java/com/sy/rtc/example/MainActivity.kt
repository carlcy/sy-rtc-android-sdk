package com.sy.rtc.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.sy.rtc.sdk.RtcEngine
import com.sy.rtc.sdk.RtcEventHandler
import com.sy.rtc.sdk.VideoEncoderConfiguration
import com.sy.rtc.sdk.VolumeInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * RTC Example：可配置 appId / apiBaseUrl / signalingUrl / token，
 * 支持模拟器（至少音频）与真机（音视频）。
 *
 * setupLocalVideo / setupRemoteVideo 传入的是 **容器 View 的资源 id**
 *（FrameLayout），SDK 内部会挂载 WebRTC SurfaceViewRenderer。
 */
class MainActivity : AppCompatActivity() {
    private val tag = "RtcExample"

    // 演示默认值（可改；模拟器访问宿主机用 10.0.2.2）
    private val defaultAppId = "APP1769003318261114285E3"
    private val defaultAppSecret =
        "524d401de4c34ad1b554f2b35fe74d6f4f8f7e55614146069b527c1f8799b488"
    /**
     * Single switch for production samples:
     *   "ip-https" → https://47.105.48.196 + wss (self-signed; see CLIENT_TRUST.md)
     *   "ip-http"  → http://47.105.48.196 + ws
     *   "domain"   → https://syrtcapi.shengyuchenyao.cn (only when public CA / LE works)
     */
    private val endpointProfile = "ip-https"

    private val defaultApiBase: String = when (endpointProfile) {
        "ip-http" -> "http://47.105.48.196"
        "domain" -> "https://syrtcapi.shengyuchenyao.cn"
        else -> "https://47.105.48.196"
    }
    private val defaultSignaling: String = when {
        defaultApiBase.startsWith("https://") ->
            "wss://" + defaultApiBase.removePrefix("https://") + "/ws/signaling"
        defaultApiBase.startsWith("http://") ->
            "ws://" + defaultApiBase.removePrefix("http://") + "/ws/signaling"
        else -> "wss://47.105.48.196/ws/signaling"
    }
    private val defaultChannel = "channel_001"
    private val defaultUid = "user_001"

    private lateinit var engine: RtcEngine
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    private lateinit var inputAppId: EditText
    private lateinit var inputApiBase: EditText
    private lateinit var inputSignaling: EditText
    private lateinit var inputAppSecret: EditText
    private lateinit var inputChannel: EditText
    private lateinit var inputUid: EditText
    private lateinit var inputToken: EditText

    private var isJoined = false
    private var isLocalAudioMuted = false
    private var isLocalVideoMuted = false
    private var engineReady = false

    private val permissions: Array<String>
        get() {
            val list = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                list.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            return list.toTypedArray()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        fillDefaults()
        requestRuntimePermissions()

        engine = RtcEngine.create()
        engine.setEventHandler(buildHandler())

        findViewById<Button>(R.id.btnFetchToken).setOnClickListener { fetchTokenAsync() }
        findViewById<Button>(R.id.btnInit).setOnClickListener { initEngine() }
        findViewById<Button>(R.id.btnJoin).setOnClickListener { joinChannel() }
        findViewById<Button>(R.id.btnLeave).setOnClickListener { leaveChannel() }
        findViewById<Button>(R.id.btnMuteAudio).setOnClickListener { toggleMuteAudio() }
        findViewById<Button>(R.id.btnMuteVideo).setOnClickListener { toggleMuteVideo() }
        findViewById<Button>(R.id.btnEnableVideo).setOnClickListener { enableVideo() }
        findViewById<Button>(R.id.btnPreview).setOnClickListener { startPreview() }

        appendLog("示例启动。默认对接 Go 后端 rtc-backend-go :8080（模拟器 10.0.2.2）")
        appendLog("视频容器 id: local=${R.id.localVideoContainer}, remote=${R.id.remoteVideoContainer}")
    }

    private fun bindViews() {
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        inputAppId = findViewById(R.id.inputAppId)
        inputApiBase = findViewById(R.id.inputApiBase)
        inputSignaling = findViewById(R.id.inputSignaling)
        inputAppSecret = findViewById(R.id.inputAppSecret)
        inputChannel = findViewById(R.id.inputChannel)
        inputUid = findViewById(R.id.inputUid)
        inputToken = findViewById(R.id.inputToken)
    }

    private fun fillDefaults() {
        inputAppId.setText(defaultAppId)
        inputApiBase.setText(defaultApiBase)
        inputSignaling.setText(defaultSignaling)
        inputAppSecret.setText(defaultAppSecret)
        inputChannel.setText(defaultChannel)
        inputUid.setText(defaultUid)
    }

    private fun requestRuntimePermissions() {
        val need = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), 100)
        }
    }

    private fun buildHandler(): RtcEventHandler = object : RtcEventHandler() {
        override fun onJoinChannelSuccess(channelId: String, uid: String, elapsed: Int) {
            runOnUiThread {
                setStatus("加入成功: $channelId / $uid (${elapsed}ms)")
                appendLog("onJoinChannelSuccess channel=$channelId uid=$uid")
            }
        }

        override fun onLeaveChannel(stats: Map<String, Any?>) {
            runOnUiThread {
                setStatus("已离开频道")
                appendLog("onLeaveChannel $stats")
            }
        }

        override fun onConnectionStateChanged(state: String, reason: String) {
            runOnUiThread {
                setStatus("连接: $state ($reason)")
                appendLog("connection state=$state reason=$reason")
            }
        }

        override fun onUserJoined(uid: String, elapsed: Int) {
            runOnUiThread {
                setStatus("远端加入: $uid")
                appendLog("onUserJoined uid=$uid")
                try {
                    // 传入容器资源 id（非 View.hashCode）
                    engine.setupRemoteVideo(uid, R.id.remoteVideoContainer)
                } catch (e: Exception) {
                    appendLog("setupRemoteVideo failed: ${e.message}")
                }
            }
        }

        override fun onUserOffline(uid: String, reason: String) {
            runOnUiThread {
                setStatus("远端离开: $uid ($reason)")
                appendLog("onUserOffline uid=$uid reason=$reason")
            }
        }

        override fun onError(code: Int, message: String) {
            runOnUiThread {
                setStatus("错误: $code $message")
                appendLog("ERROR $code $message")
            }
        }

        override fun onTokenPrivilegeWillExpire() {
            runOnUiThread { appendLog("Token 即将过期，请重新拉取并 renew") }
        }

        override fun onVolumeIndication(speakers: List<VolumeInfo>) {
            // 避免刷屏，仅 debug
            if (speakers.isNotEmpty()) {
                Log.d(tag, "volume ${speakers[0].uid}=${speakers[0].volume}")
            }
        }

        override fun onUserMuteAudio(uid: String, muted: Boolean) {
            runOnUiThread { appendLog("远端静音 uid=$uid muted=$muted") }
        }
    }

    private fun initEngine() {
        try {
            val appId = inputAppId.text.toString().trim()
            val api = inputApiBase.text.toString().trim()
            val signaling = inputSignaling.text.toString().trim()
            require(appId.isNotEmpty()) { "appId 不能为空" }
            engine.init(appId, this)
            if (api.isNotEmpty()) engine.setApiBaseUrl(api)
            if (signaling.isNotEmpty()) engine.setSignalingServerUrl(signaling)
            engineReady = true
            setStatus("初始化成功")
            appendLog("init ok appId=$appId api=$api signaling=$signaling")
            toast("初始化成功")
        } catch (e: Exception) {
            engineReady = false
            setStatus("初始化失败: ${e.message}")
            appendLog("init failed: ${e.message}")
            toast("初始化失败: ${e.message}")
        }
    }

    private fun fetchTokenAsync() {
        setStatus("正在拉取 Token…")
        appendLog("fetch token…")
        Thread {
            try {
                val token = fetchTokenFromServer()
                runOnUiThread {
                    inputToken.setText(token)
                    setStatus("Token 已填入")
                    appendLog("token ok len=${token.length}")
                    toast("Token 已获取")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setStatus("拉 Token 失败: ${e.message}")
                    appendLog("fetch token failed: ${e.message}")
                    toast("拉 Token 失败")
                }
            }
        }.start()
    }

    private fun joinChannel() {
        if (!engineReady) {
            toast("请先初始化")
            return
        }
        if (isJoined) {
            toast("已在频道中")
            return
        }
        val channel = inputChannel.text.toString().trim()
        val uid = inputUid.text.toString().trim()
        var token = inputToken.text.toString().trim()
        if (channel.isEmpty() || uid.isEmpty()) {
            toast("channel / uid 不能为空")
            return
        }
        setStatus("正在加入…")
        Thread {
            try {
                if (token.isEmpty()) {
                    appendLogUi("token 为空，自动拉取…")
                    token = fetchTokenFromServer()
                    runOnUiThread { inputToken.setText(token) }
                }
                runOnUiThread {
                    try {
                        engine.join(channel, uid, token)
                        engine.enableLocalAudio(true)
                        isJoined = true
                        setStatus("已请求加入 $channel")
                        appendLog("join requested channel=$channel uid=$uid")
                    } catch (e: Exception) {
                        setStatus("加入失败: ${e.message}")
                        appendLog("join failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setStatus("加入失败: ${e.message}")
                    appendLog("join failed: ${e.message}")
                }
            }
        }.start()
    }

    private fun leaveChannel() {
        try {
            engine.leave()
            isJoined = false
            setStatus("已离开")
            appendLog("leave()")
        } catch (e: Exception) {
            setStatus("离开失败: ${e.message}")
            appendLog("leave failed: ${e.message}")
        }
    }

    private fun toggleMuteAudio() {
        if (!engineReady) return
        isLocalAudioMuted = !isLocalAudioMuted
        engine.muteLocalAudio(isLocalAudioMuted)
        findViewById<Button>(R.id.btnMuteAudio).text =
            if (isLocalAudioMuted) "取消静音" else "静音音频"
        appendLog("muteLocalAudio=$isLocalAudioMuted")
        setStatus(if (isLocalAudioMuted) "本地音频已静音" else "本地音频已开启")
    }

    private fun toggleMuteVideo() {
        if (!engineReady) return
        isLocalVideoMuted = !isLocalVideoMuted
        engine.muteLocalVideoStream(isLocalVideoMuted)
        findViewById<Button>(R.id.btnMuteVideo).text =
            if (isLocalVideoMuted) "打开视频" else "关闭视频"
        appendLog("muteLocalVideoStream=$isLocalVideoMuted")
        setStatus(if (isLocalVideoMuted) "本地视频已关闭" else "本地视频已开启")
    }

    private fun enableVideo() {
        if (!engineReady) {
            toast("请先初始化")
            return
        }
        try {
            engine.enableVideo()
            engine.enableLocalVideo(true)
            engine.setVideoEncoderConfiguration(
                VideoEncoderConfiguration(
                    width = 640,
                    height = 480,
                    frameRate = 15,
                    bitrate = 400,
                )
            )
            engine.setupLocalVideo(R.id.localVideoContainer)
            setStatus("视频已启用")
            appendLog("enableVideo + setupLocalVideo(container)")
            toast("视频已启用（模拟器可能无摄像头）")
        } catch (e: Exception) {
            setStatus("启用视频失败: ${e.message}")
            appendLog("enableVideo failed: ${e.message}")
        }
    }

    private fun startPreview() {
        if (!engineReady) {
            toast("请先初始化并启用视频")
            return
        }
        try {
            engine.setupLocalVideo(R.id.localVideoContainer)
            engine.startPreview()
            setStatus("本地预览已开始")
            appendLog("startPreview")
        } catch (e: Exception) {
            setStatus("预览失败: ${e.message}")
            appendLog("startPreview failed: ${e.message}")
        }
    }

    private fun fetchTokenFromServer(): String {
        val apiBase = inputApiBase.text.toString().trim().trimEnd('/')
        val appId = inputAppId.text.toString().trim()
        val secret = inputAppSecret.text.toString().trim()
        val channelId = inputChannel.text.toString().trim()
        val uid = inputUid.text.toString().trim()
        val url =
            "$apiBase/api/rtc/token?channelId=${java.net.URLEncoder.encode(channelId, "UTF-8")}" +
                "&uid=${java.net.URLEncoder.encode(uid, "UTF-8")}&expireHours=24"
        val client = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(url)
            .post(ByteArray(0).toRequestBody(null))
            .addHeader("X-App-Id", appId)
            .addHeader("X-App-Secret", secret)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body?.string() ?: throw Exception("empty body")
            val json = JSONObject(body)
            if (json.optInt("code", -1) != 0) {
                throw Exception(json.optString("msg", "unknown"))
            }
            val data = json.opt("data") ?: throw Exception("no data")
            return data.toString().trim('"')
        }
    }

    private fun setStatus(msg: String) {
        statusText.text = msg
    }

    private fun appendLog(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$ts] $msg\n"
        Log.i(tag, msg)
        logText.append(line)
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun appendLogUi(msg: String) {
        runOnUiThread { appendLog(msg) }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (isJoined) engine.leave()
            engine.release()
        } catch (_: Exception) {
        }
    }
}
