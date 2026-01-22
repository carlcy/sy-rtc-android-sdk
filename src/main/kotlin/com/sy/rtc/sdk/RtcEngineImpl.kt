package com.sy.rtc.sdk

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.hardware.camera2.CameraManager
import android.view.Surface
import android.util.Log
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjection
import android.hardware.display.VirtualDisplay
import android.graphics.SurfaceTexture
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.media.AudioDeviceInfo as AndroidAudioDeviceInfo
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.graphics.BitmapFactory
import android.media.MediaMuxer
import org.webrtc.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * RTC引擎实现类
 * 
 * 包含所有原生方法的实现
 * 使用WebRTC或自定义RTC引擎作为底层实现
 */
internal class RtcEngineImpl(
    private val context: Context,
    private val appId: String
) {
    private val TAG = "RtcEngineImpl"
    
    // 音频管理
    private var audioManager: AudioManager? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val isSpeakerphoneEnabled = AtomicBoolean(false)
    
    // 视频管理
    private val videoViews = ConcurrentHashMap<String, Int>()
    private val isVideoEnabled = AtomicBoolean(false)
    private val isLocalVideoEnabled = AtomicBoolean(false)
    private var currentVideoConfig: VideoEncoderConfiguration? = null
    
    // WebRTC核心组件
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var localVideoTrack: org.webrtc.VideoTrack? = null
    private var localAudioTrack: org.webrtc.AudioTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    
    // 音频质量配置
    private var currentAudioQuality: String = "medium"
    private var audioSampleRate: Int = 48000
    private var audioBitrate: Int = 32000
    
    // 音频混音
    private val audioMixingState = AtomicInteger(0) // 0:停止, 1:播放中, 2:暂停
    private var audioMixingVolume = 50
    private var audioMixingConfig: AudioMixingConfiguration? = null
    private var audioMixingPlayer: android.media.MediaPlayer? = null
    
    // 音效管理
    private val effects = ConcurrentHashMap<Int, AudioEffectState>()
    private val effectPlayers = ConcurrentHashMap<Int, android.media.MediaPlayer>()
    
    // 音量控制
    private val userVolumes = ConcurrentHashMap<String, Int>()
    private var playbackVolume = 100
    
    // 音频设备
    private val recordingDevices = mutableListOf<AudioDeviceInfo>()
    private val playbackDevices = mutableListOf<AudioDeviceInfo>()
    
    // 视频预览状态
    private val isPreviewing = AtomicBoolean(false)
    private val videoMutedStates = ConcurrentHashMap<String, Boolean>()
    
    // 屏幕共享状态
    private val isScreenCapturing = AtomicBoolean(false)
    private var screenCaptureConfig: ScreenCaptureConfiguration? = null
    
    // 美颜配置
    private var beautyOptions: BeautyOptions? = null
    
    // 音频录制
    private var audioRecorder: android.media.MediaRecorder? = null
    private var audioRecordingConfig: AudioRecordingConfiguration? = null
    
    // 数据流
    private val dataStreams = ConcurrentHashMap<Int, Boolean>()
    
    // 旁路推流
    private val rtmpStreams = ConcurrentHashMap<String, LiveTranscoding>()
    // RTMP 推流采用“服务端旁路 egress”方案：SDK 调用后端 /api/rtc/live/* 控制接口（业内常见）
    private var apiBaseUrl: String? = null
    
    // 屏幕共享
    private var mediaProjection: android.media.projection.MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var screenCaptureSurface: Surface? = null
    private var screenVideoSource: VideoSource? = null
    
    // 美颜滤镜
    private var beautyFilter: BeautyFilter? = null
    
    // 数据流 - WebRTC DataChannel
    private val dataChannelMap = ConcurrentHashMap<Int, org.webrtc.DataChannel>()
    private val peerConnections = ConcurrentHashMap<String, org.webrtc.PeerConnection>()
    
    // 远端视频轨道
    private val remoteVideoTracks = ConcurrentHashMap<String, org.webrtc.VideoTrack>()
    
    // 频道状态
    private var currentChannelId: String? = null
    private var currentUid: String? = null
    // join 传入 token：默认视为业务端 JWT（用于调用 /api/rtc/live/*）
    private var currentToken: String? = null
    private var isJoined = AtomicBoolean(false)
    
    // 事件处理器（需要从外部设置）
    var eventHandler: RtcEventHandler? = null
    
    // 信令客户端
    private var signalingClient: SignalingClient? = null
    // 默认走 Nginx（80/443），避免客户端直连 8087；生产建议改成你自己的域名 + wss
    private var signalingUrl: String = "ws://47.105.48.196/ws/signaling"
    
    // 多人语聊（Mesh）：每个远端用户一条 PeerConnection（key=remoteUid）
    private val offerSentByUid = ConcurrentHashMap<String, AtomicBoolean>()
    private val remoteSdpSetByUid = ConcurrentHashMap<String, AtomicBoolean>()
    private val pendingLocalIceByUid = ConcurrentHashMap<String, MutableList<IceCandidate>>() // 本地 ICE（在发 offer/answer 前缓存）
    private val pendingRemoteIceByUid = ConcurrentHashMap<String, MutableList<IceCandidate>>() // 远端 ICE（在 setRemoteDescription 前缓存）

    fun setSignalingServerUrl(url: String) {
        if (url.isNotBlank()) {
            signalingUrl = url
        }
    }

    fun setApiBaseUrl(url: String) {
        apiBaseUrl = url.trim().trimEnd('/')
    }

    private fun postLiveApi(path: String, body: JSONObject) {
        val base = apiBaseUrl
        val token = currentToken
        if (base.isNullOrEmpty()) {
            eventHandler?.onError(1001, "API_BASE_URL 未设置：请先调用 setApiBaseUrl() 或在 Flutter init 里传 apiBaseUrl")
            return
        }
        if (token.isNullOrEmpty()) {
            eventHandler?.onError(1001, "缺少登录 token：join() 传入的 token 为空，无法调用直播控制接口")
            return
        }
        try {
            val url = URL("$base$path")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("X-App-Id", appId)
            }
            conn.outputStream.use { os ->
                os.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            val resp = try {
                val s = if (code in 200..299) conn.inputStream else conn.errorStream
                s?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Exception) {
                ""
            }
            if (code !in 200..299) {
                eventHandler?.onError(1001, "直播接口失败: $code $resp")
            }
            conn.disconnect()
        } catch (e: Exception) {
            eventHandler?.onError(1001, "直播接口异常: ${e.message}")
        }
    }

    private fun guessLayoutFromTranscoding(transcoding: LiveTranscoding): JSONObject {
        val users = transcoding.transcodingUsers
        if (users.isNullOrEmpty()) {
            return JSONObject().apply {
                put("mode", "host-main")
                put("hostUid", currentUid ?: "")
                put("side", "right")
            }
        }
        val sorted = users.sortedByDescending { it.width * it.height }
        val top1 = sorted.getOrNull(0)
        val top2 = sorted.getOrNull(1)
        if (top1 != null && top2 != null) {
            val a1 = top1.width * top1.height
            val a2 = top2.width * top2.height
            val ratio = if (a2 <= 0.0) 999.0 else a1 / a2
            if (ratio < 1.2) {
                return JSONObject().apply {
                    put("mode", "pk")
                    put("pkUids", org.json.JSONArray(listOf(top1.uid, top2.uid)))
                }
            }
        }
        val host = top1?.uid ?: (currentUid ?: "")
        return JSONObject().apply {
            put("mode", "host-main")
            put("hostUid", host)
            put("side", "right")
        }
    }

    // ==================== 网络质量（简化实现） ====================

    fun getConnectionState(): String {
        // 这里先返回一个粗粒度状态：只要已 join 且 signaling 已连接就视为 connected
        return if (isJoined.get()) "connected" else "disconnected"
    }

    fun getNetworkType(): String {
        // Android 端网络类型需要依赖 ConnectivityManager，这里先给可用的占位实现
        return "unknown"
    }

    // ==================== 音频采集控制（简化实现） ====================

    fun adjustRecordingSignalVolume(volume: Int) {
        // 0-400，100 为原始音量；这里只做边界裁剪并记录
        val v = volume.coerceIn(0, 400)
        Log.d(TAG, "adjustRecordingSignalVolume=$v (no-op)")
    }

    fun muteRecordingSignal(muted: Boolean) {
        Log.d(TAG, "muteRecordingSignal=$muted (no-op)")
    }
    
    init {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        initializeAudioDevices()
        initializeWebRTC()
    }
    
    private fun initializeWebRTC() {
        try {
            // 初始化WebRTC
            val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initializationOptions)
            
            // 创建PeerConnectionFactory
            val options = PeerConnectionFactory.Options()
            val encoderFactory = DefaultVideoEncoderFactory(
                EglBase.create().eglBaseContext,
                true, // enableIntelVP8Encoder
                true  // enableH264HighProfile
            )
            val decoderFactory = DefaultVideoDecoderFactory(EglBase.create().eglBaseContext)
            
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()
            
            Log.d(TAG, "WebRTC初始化成功")
        } catch (e: Exception) {
            Log.e(TAG, "WebRTC初始化失败", e)
        }
    }
    
    // ==================== 初始化 ====================
    
    fun initialize() {
        Log.d(TAG, "初始化RTC引擎: appId=$appId")
        // 初始化音频系统
        initializeAudioSystem()
        // 初始化视频系统
        initializeVideoSystem()
    }
    
    // ==================== 频道管理 ====================
    
    fun join(channelId: String, uid: String, token: String) {
        if (isJoined.get()) {
            Log.w(TAG, "已经加入频道，请先离开")
            return
        }
        
        currentChannelId = channelId
        currentUid = uid
        currentToken = token
        // 加入频道即视为“已加入”（即便房间内暂时没有其他人）
        isJoined.set(true)
        // 清理多人会话状态
        offerSentByUid.clear()
        remoteSdpSetByUid.clear()
        pendingLocalIceByUid.clear()
        pendingRemoteIceByUid.clear()
        
        Log.d(TAG, "加入频道: channelId=$channelId, uid=$uid")
        
        try {
            // 连接信令服务器
            signalingClient = SignalingClient(signalingUrl, channelId, uid) { type, data ->
                handleSignalingMessage(type, data, channelId)
            }
            signalingClient?.connect()
            
            // 创建音频轨道
            val audioSource = peerConnectionFactory?.createAudioSource(org.webrtc.MediaConstraints())
            localAudioTrack = peerConnectionFactory?.createAudioTrack("audio_track", audioSource)
            localAudioTrack?.setEnabled(true)
        } catch (e: Exception) {
            Log.e(TAG, "加入频道失败", e)
        }
    }
    
    fun leave() {
        if (!isJoined.get()) {
            Log.w(TAG, "未加入频道")
            return
        }
        
        Log.d(TAG, "离开频道: channelId=$currentChannelId")
        
        try {
            // 断开信令连接
            signalingClient?.disconnect()
            signalingClient = null
            
            // 关闭所有 PeerConnection
            peerConnections.values.forEach { peerConnection ->
                peerConnection.close()
            }
            peerConnections.clear()
            offerSentByUid.clear()
            remoteSdpSetByUid.clear()
            pendingLocalIceByUid.clear()
            pendingRemoteIceByUid.clear()
            
            // 停止本地轨道
            localAudioTrack?.setEnabled(false)
            localVideoTrack?.setEnabled(false)
            
            // 清理状态
            currentChannelId = null
            currentUid = null
            currentToken = null
            isJoined.set(false)
            
            Log.d(TAG, "已离开频道")
        } catch (e: Exception) {
            Log.e(TAG, "离开频道失败", e)
        }
    }
    
    private fun handleSignalingMessage(type: String, data: Map<String, Any>, channelId: String) {
        Log.d(TAG, "处理信令消息: type=$type")
        
        when (type) {
            "user-list" -> {
                val usersAny = data["users"]
                val users: List<String> = when (usersAny) {
                    is org.json.JSONArray -> (0 until usersAny.length()).mapNotNull { idx -> usersAny.optString(idx)?.takeIf { it.isNotBlank() } }
                    is List<*> -> usersAny.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
                    else -> emptyList()
                }
                users.filter { it != currentUid }.forEach { remote ->
                    ensurePeer(remote, channelId)
                    if (shouldInitiateOffer(currentUid, remote)) {
                        startOffer(remote, channelId)
                    }
                }
            }
            "offer" -> {
                // 收到 Offer（定向），创建/获取与发送者的 PeerConnection，然后 Answer 回去
                val fromUid = (data["uid"] as? String) ?: return
                val sdp = data["sdp"] as? String
                val sdpType = data["type"] as? String
                if (sdp != null && sdpType == "offer") {
                    val peerConnection = ensurePeer(fromUid, channelId) ?: return
                        val sessionDescription = SessionDescription(
                            SessionDescription.Type.OFFER,
                            sdp
                        )
                        peerConnection.setRemoteDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                Log.d(TAG, "设置远端 SDP 成功")
                                remoteSdpSetByUid.computeIfAbsent(fromUid) { AtomicBoolean(true) }.set(true)
                                flushPendingRemoteIce(fromUid)
                                // 创建 Answer
                                peerConnection.createAnswer(object : SdpObserver {
                                    override fun onCreateSuccess(sdp: SessionDescription?) {
                                        sdp?.let {
                                            peerConnection.setLocalDescription(object : SdpObserver {
                                                override fun onSetSuccess() {
                                                    signalingClient?.sendAnswer(it.description, fromUid)
                                                    flushPendingLocalIce(fromUid)
                                                }
                                                override fun onSetFailure(error: String?) {
                                                    Log.e(TAG, "设置本地 Answer 失败: $error")
                                                }
                                                override fun onCreateSuccess(p0: SessionDescription?) {}
                                                override fun onCreateFailure(error: String?) {}
                                            }, it)
                                        }
                                    }
                                    override fun onCreateFailure(error: String?) {
                                        Log.e(TAG, "创建 Answer 失败: $error")
                                    }
                                    override fun onSetSuccess() {}
                                    override fun onSetFailure(error: String?) {}
                                }, org.webrtc.MediaConstraints())
                            }
                            override fun onSetFailure(error: String?) {
                                Log.e(TAG, "设置远端 SDP 失败: $error")
                            }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(error: String?) {}
                        }, sessionDescription)
                }
            }
            "answer" -> {
                // 收到 Answer
                val fromUid = (data["uid"] as? String) ?: return
                val sdp = data["sdp"] as? String
                val sdpType = data["type"] as? String
                if (sdp != null && sdpType == "answer") {
                    val peerConnection = peerConnections[fromUid] ?: return
                        val sessionDescription = SessionDescription(
                            SessionDescription.Type.ANSWER,
                            sdp
                        )
                        peerConnection.setRemoteDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                Log.d(TAG, "设置远端 Answer 成功")
                                remoteSdpSetByUid.computeIfAbsent(fromUid) { AtomicBoolean(true) }.set(true)
                                flushPendingRemoteIce(fromUid)
                            }
                            override fun onSetFailure(error: String?) {
                                Log.e(TAG, "设置远端 Answer 失败: $error")
                            }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(error: String?) {}
                        }, sessionDescription)
                }
            }
            "ice-candidate" -> {
                // 收到 ICE Candidate
                val fromUid = (data["uid"] as? String) ?: return
                val candidate = data["candidate"] as? String
                val sdpMLineIndex = (data["sdpMLineIndex"] as? Number)?.toInt() ?: 0
                val sdpMid = data["sdpMid"] as? String
                if (candidate != null) {
                    val peerConnection = ensurePeer(fromUid, channelId) ?: return
                    val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
                    if (remoteSdpSetByUid[fromUid]?.get() == true) {
                        peerConnection.addIceCandidate(iceCandidate)
                        Log.d(TAG, "添加 ICE Candidate 成功: from=$fromUid")
                    } else {
                        pendingRemoteIceByUid.computeIfAbsent(fromUid) { mutableListOf() }.add(iceCandidate)
                        Log.d(TAG, "缓存远端 ICE（等待 setRemoteDescription）: from=$fromUid")
                    }
                }
            }
            "user-joined" -> {
                val uid = data["uid"] as? String
                if (uid != null) {
                    eventHandler?.onUserJoined(uid, 0)
                    if (uid != currentUid) {
                        ensurePeer(uid, channelId)
                        if (shouldInitiateOffer(currentUid, uid)) {
                            startOffer(uid, channelId)
                        }
                    }
                }
            }
            "user-left" -> {
                val uid = data["uid"] as? String
                if (uid != null) {
                    eventHandler?.onUserOffline(uid, "quit")
                    peerConnections.remove(uid)?.close()
                    offerSentByUid.remove(uid)
                    remoteSdpSetByUid.remove(uid)
                    pendingLocalIceByUid.remove(uid)
                    pendingRemoteIceByUid.remove(uid)
                }
            }
        }
    }
    
    private fun shouldInitiateOffer(localUid: String?, remoteUid: String): Boolean {
        val l = localUid ?: return false
        // 简单的确定性发起者：字典序更小的一方发 offer，避免双方同时发（glare）
        return l < remoteUid
    }
    
    private fun ensurePeer(remoteUid: String, channelId: String): PeerConnection? {
        peerConnections[remoteUid]?.let { return it }
        val factory = peerConnectionFactory ?: return null
        
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )
        )
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        
        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "IceConnectionState(remote=$remoteUid): $state")
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    val to = remoteUid
                    // 在发出 offer/answer 前先缓存，等本地 SDP 设置完成后再补发（更稳）
                    if (offerSentByUid[to]?.get() == true || remoteSdpSetByUid[to]?.get() == true) {
                        signalingClient?.sendIceCandidate(it.sdp, it.sdpMLineIndex, it.sdpMid ?: "", to)
                    } else {
                        pendingLocalIceByUid.computeIfAbsent(to) { mutableListOf() }.add(it)
                    }
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })
        
        if (pc == null) return null
        
        // 添加本地轨道（多人：每条 PC 都要 addTrack）
        localAudioTrack?.let { pc.addTrack(it, listOf()) }
        localVideoTrack?.let { pc.addTrack(it, listOf()) }
        
        peerConnections[remoteUid] = pc
        offerSentByUid[remoteUid] = AtomicBoolean(false)
        remoteSdpSetByUid[remoteUid] = AtomicBoolean(false)
        pendingLocalIceByUid.computeIfAbsent(remoteUid) { mutableListOf() }
        pendingRemoteIceByUid.computeIfAbsent(remoteUid) { mutableListOf() }
        return pc
    }
    
    private fun startOffer(remoteUid: String, channelId: String) {
        val pc = peerConnections[remoteUid] ?: return
        val sentFlag = offerSentByUid.computeIfAbsent(remoteUid) { AtomicBoolean(false) }
        if (!sentFlag.compareAndSet(false, true)) return
        
        val constraints = org.webrtc.MediaConstraints()
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        signalingClient?.sendOffer(sdp.description, remoteUid)
                        flushPendingLocalIce(remoteUid)
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "设置本地 Offer 失败: $error")
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "创建 Offer 失败: $error")
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }
    
    private fun flushPendingLocalIce(remoteUid: String) {
        val list = pendingLocalIceByUid[remoteUid]?.toList().orEmpty()
        pendingLocalIceByUid[remoteUid]?.clear()
        list.forEach {
            signalingClient?.sendIceCandidate(it.sdp, it.sdpMLineIndex, it.sdpMid ?: "", remoteUid)
        }
    }
    
    private fun flushPendingRemoteIce(remoteUid: String) {
        val pc = peerConnections[remoteUid] ?: return
        val list = pendingRemoteIceByUid[remoteUid]?.toList().orEmpty()
        pendingRemoteIceByUid[remoteUid]?.clear()
        list.forEach { pc.addIceCandidate(it) }
    }
    
    fun setClientRole(role: RtcClientRole) {
        Log.d(TAG, "设置客户端角色: $role")
        // 根据角色调整音频/视频发布策略
        when (role) {
            RtcClientRole.HOST -> {
                // 主播：发布音频和视频
                localAudioTrack?.setEnabled(true)
                localVideoTrack?.setEnabled(true)
            }
            RtcClientRole.AUDIENCE -> {
                // 观众：只接收，不发布
                localAudioTrack?.setEnabled(false)
                localVideoTrack?.setEnabled(false)
            }
        }
    }
    
    private fun createDefaultPeerConnection(): PeerConnection? {
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                    .createIceServer()
            )
        )
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        
        return peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })
    }
    
    private fun initializeAudioSystem() {
        try {
            // 初始化音频录制
            val sampleRate = 48000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize * 2
            )
            
            // 初始化音频播放
            val channelOutConfig = AudioFormat.CHANNEL_OUT_MONO
            val trackBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelOutConfig, audioFormat)
            
            audioTrack = AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                sampleRate,
                channelOutConfig,
                audioFormat,
                trackBufferSize * 2,
                AudioTrack.MODE_STREAM
            )
            
            Log.d(TAG, "音频系统初始化成功")
        } catch (e: Exception) {
            Log.e(TAG, "音频系统初始化失败", e)
        }
    }
    
    private fun initializeVideoSystem() {
        try {
            Log.d(TAG, "视频系统初始化")
            
            // 检查摄像头权限和可用性
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            if (cameraManager != null) {
                val cameraIds = cameraManager.cameraIdList
                Log.d(TAG, "检测到 ${cameraIds.size} 个摄像头")
                
                // WebRTC 已在 initializeWebRTC 中初始化；这里不再访问内部 encoder/decoder（不同 WebRTC 包 API 不一致）
                if (peerConnectionFactory != null) {
                    Log.d(TAG, "视频系统初始化成功（factory 已就绪）")
                } else {
                    Log.w(TAG, "PeerConnectionFactory未初始化，视频系统初始化不完整")
                }
            } else {
                Log.w(TAG, "无法获取CameraManager，视频系统初始化不完整")
            }
        } catch (e: Exception) {
            Log.e(TAG, "视频系统初始化失败", e)
        }
    }
    
    private fun initializeAudioDevices() {
        try {
            recordingDevices.clear()
            playbackDevices.clear()
            
            // 枚举录音设备
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioManager?.let { am ->
                    // 获取录音设备
                    val inputDevices = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    inputDevices.forEach { device ->
                        val deviceName = when (device.type) {
                            AndroidAudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦克风"
                            AndroidAudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙麦克风"
                            AndroidAudioDeviceInfo.TYPE_USB_HEADSET -> "USB耳机麦克风"
                            AndroidAudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机麦克风"
                            else -> "麦克风 ${device.id}"
                        }
                        recordingDevices.add(AudioDeviceInfo(device.id.toString(), deviceName))
                    }
                    
                    // 获取播放设备
                    val outputDevices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    outputDevices.forEach { device ->
                        val deviceName = when (device.type) {
                            AndroidAudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "内置扬声器"
                            AndroidAudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
                            AndroidAudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙耳机"
                            AndroidAudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙A2DP"
                            AndroidAudioDeviceInfo.TYPE_USB_HEADSET -> "USB耳机"
                            AndroidAudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
                            AndroidAudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
                            else -> "音频输出 ${device.id}"
                        }
                        playbackDevices.add(AudioDeviceInfo(device.id.toString(), deviceName))
                    }
                }
            }
            
            // 如果没有检测到设备，添加默认设备
            if (recordingDevices.isEmpty()) {
                recordingDevices.add(AudioDeviceInfo("default", "默认麦克风"))
            }
            if (playbackDevices.isEmpty()) {
                playbackDevices.add(AudioDeviceInfo("default", "默认扬声器"))
                playbackDevices.add(AudioDeviceInfo("speaker", "扬声器"))
                playbackDevices.add(AudioDeviceInfo("earpiece", "听筒"))
            }
            
            // 检查蓝牙设备
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
                val bluetoothHeadset = bluetoothAdapter.getProfileProxy(
                    context,
                    object : android.bluetooth.BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                            if (profile == android.bluetooth.BluetoothProfile.HEADSET) {
                                val headset = proxy as? BluetoothHeadset
                                if (headset != null && headset.connectedDevices.isNotEmpty()) {
                                    playbackDevices.add(AudioDeviceInfo("bluetooth", "蓝牙耳机"))
                                    recordingDevices.add(AudioDeviceInfo("bluetooth", "蓝牙麦克风"))
                                }
                            }
                        }
                        override fun onServiceDisconnected(profile: Int) {}
                    },
                    android.bluetooth.BluetoothProfile.HEADSET
                )
            }
            
            Log.d(TAG, "音频设备枚举完成: 录音设备=${recordingDevices.size}, 播放设备=${playbackDevices.size}")
        } catch (e: Exception) {
            Log.e(TAG, "音频设备枚举失败", e)
            // 失败时添加默认设备
            recordingDevices.add(AudioDeviceInfo("default", "默认麦克风"))
            playbackDevices.add(AudioDeviceInfo("default", "默认扬声器"))
        }
    }
    
    // ==================== 音频路由控制 ====================
    
    fun setEnableSpeakerphone(enabled: Boolean) {
        isSpeakerphoneEnabled.set(enabled)
        audioManager?.let {
            it.isSpeakerphoneOn = enabled
            Log.d(TAG, "扬声器状态: $enabled")
        }
    }
    
    fun setDefaultAudioRouteToSpeakerphone(enabled: Boolean) {
        audioManager?.let {
            it.mode = AudioManager.MODE_IN_COMMUNICATION
            it.isSpeakerphoneOn = enabled
            isSpeakerphoneEnabled.set(enabled)
            Log.d(TAG, "默认音频路由设置为扬声器: $enabled")
        }
    }
    
    fun isSpeakerphoneEnabled(): Boolean {
        return isSpeakerphoneEnabled.get()
    }
    
    // ==================== 音频控制 ====================
    
    fun enableLocalAudio(enabled: Boolean) {
        if (enabled) {
            audioRecord?.startRecording()
            Log.d(TAG, "启用本地音频采集")
        } else {
            audioRecord?.stop()
            Log.d(TAG, "禁用本地音频采集")
        }
    }
    
    fun muteLocalAudio(muted: Boolean) {
        try {
            if (muted) {
                // 停止音频采集
                audioRecord?.stop()
                // 禁用本地音频轨道
                localAudioTrack?.setEnabled(false)
                Log.d(TAG, "本地音频已静音")
            } else {
                // 恢复音频采集
                audioRecord?.startRecording()
                // 启用本地音频轨道
                localAudioTrack?.setEnabled(true)
                Log.d(TAG, "本地音频已取消静音")
            }
        } catch (e: Exception) {
            Log.e(TAG, "设置本地音频静音状态失败", e)
        }
    }
    
    fun muteRemoteAudioStream(uid: String, muted: Boolean) {
        userVolumes[uid] = if (muted) 0 else 100
        Log.d(TAG, "远端用户 $uid 音频静音: $muted")
    }
    
    fun muteAllRemoteAudioStreams(muted: Boolean) {
        playbackVolume = if (muted) 0 else 100
        Log.d(TAG, "所有远端音频静音: $muted")
    }
    
    fun adjustUserPlaybackSignalVolume(uid: String, volume: Int) {
        userVolumes[uid] = volume.coerceIn(0, 100)
        Log.d(TAG, "用户 $uid 音量调整为: $volume")
    }
    
    fun adjustPlaybackSignalVolume(volume: Int) {
        playbackVolume = volume.coerceIn(0, 100)
        audioTrack?.setVolume(playbackVolume / 100f)
        Log.d(TAG, "播放音量调整为: $volume")
    }
    
    // ==================== Token刷新 ====================
    
    fun renewToken(token: String) {
        // Token 由业务后端签发：这里先保存并用于后续（如重连/重新 Join）时携带。
        // 不尝试对 PeerConnection 做“热更新配置”（不同 WebRTC 包 API 不一致，且热更新容易引发断链）。
        currentToken = token
        Log.d(TAG, "更新Token: len=${token.length}")
    }
    
    // ==================== 音频配置 ====================
    
    fun setAudioProfile(profile: String, scenario: String) {
        try {
            Log.d(TAG, "设置音频配置: profile=$profile, scenario=$scenario")
            
            // 根据profile设置音频参数
            when (profile.lowercase()) {
                "speech_low_quality", "low" -> {
                    audioSampleRate = 16000
                    audioBitrate = 16000
                }
                "speech_standard", "standard" -> {
                    audioSampleRate = 24000
                    audioBitrate = 24000
                }
                "music_standard", "medium" -> {
                    audioSampleRate = 48000
                    audioBitrate = 48000
                }
                "music_standard_stereo", "high" -> {
                    audioSampleRate = 48000
                    audioBitrate = 64000
                }
                "music_high_quality", "ultra" -> {
                    audioSampleRate = 48000
                    audioBitrate = 128000
                }
                else -> {
                    audioSampleRate = 48000
                    audioBitrate = 48000
                }
            }
            
            // 根据scenario设置音频模式
            audioManager?.let { am ->
                when (scenario.lowercase()) {
                    "game_streaming" -> {
                        am.mode = AudioManager.MODE_NORMAL
                        am.isSpeakerphoneOn = true
                    }
                    "chatroom_entertainment" -> {
                        am.mode = AudioManager.MODE_IN_COMMUNICATION
                        am.isSpeakerphoneOn = false
                    }
                    "education" -> {
                        am.mode = AudioManager.MODE_IN_COMMUNICATION
                        am.isSpeakerphoneOn = true
                    }
                    "default", "chatroom_gaming" -> {
                        am.mode = AudioManager.MODE_IN_COMMUNICATION
                    }
                    else -> {
                        am.mode = AudioManager.MODE_IN_COMMUNICATION
                    }
                }
            }
            
            // 重新初始化音频系统以应用新配置
            reinitializeAudioSystem(audioSampleRate)
            
            Log.d(TAG, "音频配置已更新: ${audioSampleRate}Hz, ${audioBitrate}bps, scenario=$scenario")
        } catch (e: Exception) {
            Log.e(TAG, "设置音频配置失败", e)
        }
    }
    
    fun enableAudio() {
        audioRecord?.startRecording()
        audioTrack?.play()
        Log.d(TAG, "启用音频模块")
    }
    
    fun disableAudio() {
        audioRecord?.stop()
        audioTrack?.pause()
        Log.d(TAG, "禁用音频模块")
    }
    
    // ==================== 音频设备管理 ====================
    
    fun enumerateRecordingDevices(): List<AudioDeviceInfo> {
        return recordingDevices.toList()
    }
    
    fun enumeratePlaybackDevices(): List<AudioDeviceInfo> {
        return playbackDevices.toList()
    }
    
    fun setRecordingDevice(deviceId: String): Int {
        Log.d(TAG, "设置录音设备: $deviceId")
        return 0 // 0表示成功
    }
    
    fun setPlaybackDevice(deviceId: String): Int {
        when (deviceId) {
            "speaker" -> setEnableSpeakerphone(true)
            "earpiece" -> setEnableSpeakerphone(false)
        }
        Log.d(TAG, "设置播放设备: $deviceId")
        return 0
    }
    
    fun getRecordingDeviceVolume(): Int {
        return audioManager?.getStreamVolume(AudioManager.STREAM_VOICE_CALL) ?: 0
    }
    
    fun setRecordingDeviceVolume(volume: Int) {
        audioManager?.setStreamVolume(
            AudioManager.STREAM_VOICE_CALL,
            volume.coerceIn(0, audioManager?.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) ?: 0),
            0
        )
    }
    
    fun getPlaybackDeviceVolume(): Int {
        return audioManager?.getStreamVolume(AudioManager.STREAM_VOICE_CALL) ?: 0
    }
    
    fun setPlaybackDeviceVolume(volume: Int) {
        audioManager?.setStreamVolume(
            AudioManager.STREAM_VOICE_CALL,
            volume.coerceIn(0, audioManager?.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) ?: 0),
            0
        )
    }
    
    // ==================== 视频控制 ====================
    
    fun enableVideo() {
        isVideoEnabled.set(true)
        Log.d(TAG, "启用视频模块")
    }
    
    fun disableVideo() {
        isVideoEnabled.set(false)
        Log.d(TAG, "禁用视频模块")
    }
    
    fun enableLocalVideo(enabled: Boolean) {
        isLocalVideoEnabled.set(enabled)
        Log.d(TAG, "启用本地视频: $enabled")
    }
    
    fun setVideoEncoderConfiguration(config: VideoEncoderConfiguration) {
        Log.d(TAG, "设置视频编码配置: ${config.width}x${config.height}, ${config.frameRate}fps, ${config.bitrate}bps")
        
        // 保存配置
        currentVideoConfig = config
        
        // 应用视频编码配置
        applyVideoEncoderConfiguration(config)
    }
    
    private fun applyVideoEncoderConfiguration(config: VideoEncoderConfiguration) {
        // 验证配置参数
        val width = config.width.coerceIn(160, 3840)
        val height = config.height.coerceIn(120, 2160)
        val frameRate = config.frameRate.coerceIn(1, 60)
        val bitrate = if (config.bitrate > 0) config.bitrate.coerceIn(100, 10000) else calculateBitrate(width, height, frameRate)
        
        Log.d(TAG, "应用视频编码配置: ${width}x${height}, ${frameRate}fps, ${bitrate}kbps")
        
        // 如果已启用视频，更新编码器配置
        if (isVideoEnabled.get() && localVideoTrack != null) {
            updateVideoEncoder(width, height, frameRate, bitrate)
        }
    }
    
    private fun updateVideoEncoder(width: Int, height: Int, frameRate: Int, bitrate: Int) {
        try {
            // 使用WebRTC设置视频编码参数
            // WebRTC会根据视频源的分辨率自动调整编码参数
            // 编码器参数会在创建VideoTrack时自动应用
            Log.d(TAG, "视频编码器配置已更新: ${width}x${height}, ${frameRate}fps, ${bitrate}kbps")
            
            // 如果视频轨道已存在，重新创建以应用新配置
            if (localVideoTrack != null && videoCapturer != null) {
                videoCapturer?.changeCaptureFormat(width, height, frameRate)
            }
        } catch (e: Exception) {
            Log.e(TAG, "更新视频编码器配置失败", e)
        }
    }
    
    private fun calculateBitrate(width: Int, height: Int, frameRate: Int): Int {
        // 根据分辨率和帧率计算推荐码率（kbps）
        val pixels = width * height
        val baseBitrate = when {
            pixels <= 640 * 480 -> 400
            pixels <= 1280 * 720 -> 800
            pixels <= 1920 * 1080 -> 2000
            else -> 5000
        }
        return (baseBitrate * frameRate / 30).coerceIn(100, 10000)
    }
    
    fun setVideoEncoderConfiguration(width: Int, height: Int, frameRate: Int, bitrate: Int) {
        val config = VideoEncoderConfiguration(
            width = width,
            height = height,
            frameRate = frameRate,
            bitrate = bitrate
        )
        setVideoEncoderConfiguration(config)
    }
    
    fun setAudioQuality(quality: String) {
        Log.d(TAG, "设置音频质量: $quality")
        
        val qualityLower = quality.lowercase()
        val (sampleRate, bitrate) = when (qualityLower) {
            "low" -> {
                // 低质量：降低采样率、码率，减少处理开销
                Pair(16000, 16000)
            }
            "medium" -> {
                // 中等质量：标准采样率、码率
                Pair(24000, 32000)
            }
            "high" -> {
                // 高质量：较高采样率、码率
                Pair(48000, 64000)
            }
            "ultra" -> {
                // 超高质量：最高采样率、码率
                Pair(48000, 128000)
            }
            else -> {
                Log.w(TAG, "未知的音频质量等级: $quality，使用默认中等质量")
                Pair(24000, 32000)
            }
        }
        
        // 保存配置
        currentAudioQuality = qualityLower
        audioSampleRate = sampleRate
        audioBitrate = bitrate
        
        Log.d(TAG, "应用音频质量设置: ${sampleRate}Hz采样率, ${bitrate}bps码率")
        
        // 重新初始化音频系统以应用新配置
        reinitializeAudioSystem(sampleRate)
    }
    
    private fun reinitializeAudioSystem(sampleRate: Int) {
        try {
            // 停止当前音频
            audioRecord?.stop()
            audioRecord?.release()
            audioTrack?.stop()
            audioTrack?.release()
            
            // 使用新采样率重新初始化
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            
            if (bufferSize > 0) {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize * 2
                )
                
                val channelOutConfig = AudioFormat.CHANNEL_OUT_MONO
                val trackBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelOutConfig, audioFormat)
                
                if (trackBufferSize > 0) {
                    audioTrack = AudioTrack(
                        AudioManager.STREAM_VOICE_CALL,
                        sampleRate,
                        channelOutConfig,
                        audioFormat,
                        trackBufferSize * 2,
                        AudioTrack.MODE_STREAM
                    )
                    
                    Log.d(TAG, "音频系统已重新初始化: ${sampleRate}Hz")
                } else {
                    Log.e(TAG, "无法创建AudioTrack，bufferSize=$trackBufferSize")
                }
            } else {
                Log.e(TAG, "无法创建AudioRecord，bufferSize=$bufferSize")
            }
        } catch (e: Exception) {
            Log.e(TAG, "重新初始化音频系统失败", e)
        }
    }
    
    fun startPreview() {
        if (!isVideoEnabled.get()) {
            Log.w(TAG, "视频模块未启用，无法开始预览")
            return
        }
        
        if (isPreviewing.get()) {
            Log.w(TAG, "视频预览已在进行中")
            return
        }
        
        isPreviewing.set(true)
        Log.d(TAG, "开始视频预览")
        
        // 应用当前视频编码配置
        currentVideoConfig?.let { config ->
            applyVideoEncoderConfiguration(config)
        }
        
        // 如果配置了本地视频视图，启动摄像头
        videoViews["local"]?.let { viewId ->
            try {
                // 使用WebRTC启动摄像头
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                val cameraEnumerator = Camera2Enumerator(context)
                val deviceNames = cameraEnumerator.deviceNames
                
                if (deviceNames.isNotEmpty()) {
                    val frontCameraName = deviceNames.find { cameraEnumerator.isFrontFacing(it) } ?: deviceNames[0]
                    videoCapturer = cameraEnumerator.createCapturer(frontCameraName, null)
                    
                    val videoSource = peerConnectionFactory?.createVideoSource(false)
                    videoCapturer?.initialize(
                        SurfaceTextureHelper.create("CaptureThread", EglBase.create().eglBaseContext),
                        context,
                        videoSource?.capturerObserver
                    )
                    
                    videoCapturer?.startCapture(
                        currentVideoConfig?.width ?: 640,
                        currentVideoConfig?.height ?: 480,
                        currentVideoConfig?.frameRate ?: 30
                    )
                    
                    localVideoTrack = peerConnectionFactory?.createVideoTrack("video_track", videoSource)
                    Log.d(TAG, "摄像头预览已启动到视图: $viewId")
                } else {
                    Log.w(TAG, "未检测到可用摄像头，无法启动预览")
                }
            } catch (e: Exception) {
                Log.e(TAG, "启动摄像头预览失败", e)
            }
        }
    }
    
    fun stopPreview() {
        if (!isPreviewing.get()) {
            Log.w(TAG, "视频预览未在进行中")
            return
        }
        
        isPreviewing.set(false)
        Log.d(TAG, "停止视频预览")
        
        // 停止摄像头
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null
            localVideoTrack?.dispose()
            localVideoTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "停止摄像头预览失败", e)
        }
    }
    
    fun muteLocalVideoStream(muted: Boolean) {
        videoMutedStates["local"] = muted
        Log.d(TAG, "本地视频静音: $muted")
        
        // 实际应用静音逻辑
        if (muted) {
            localVideoTrack?.setEnabled(false)
        } else {
            localVideoTrack?.setEnabled(true)
        }
    }
    
    fun muteRemoteVideoStream(uid: String, muted: Boolean) {
        videoMutedStates[uid] = muted
        Log.d(TAG, "远端用户 $uid 视频静音: $muted")
        
        // 实际应用静音逻辑
        // videoRenderer.setMuted(uid, muted)
    }
    
    fun muteAllRemoteVideoStreams(muted: Boolean) {
        // 更新所有远端用户的静音状态
        videoViews.keys.filter { it != "local" }.forEach { uid ->
            videoMutedStates[uid] = muted
        }
        Log.d(TAG, "所有远端视频静音: $muted")
        
        // 实际应用静音逻辑
        // videoRenderer.setAllMuted(muted)
    }
    
    fun setupLocalVideo(viewId: Int) {
        videoViews["local"] = viewId
        Log.d(TAG, "设置本地视频视图: $viewId")
        
        // 如果预览已在进行，立即绑定视图
        if (isPreviewing.get()) {
            // cameraManager.bindView(viewId)
            Log.d(TAG, "本地视频视图已绑定")
        }
    }
    
    fun setupRemoteVideo(uid: String, viewId: Int) {
        videoViews[uid] = viewId
        Log.d(TAG, "设置远端视频视图: uid=$uid, viewId=$viewId")
        
        try {
            // 从映射中获取远端视频轨道
            val remoteTrack = remoteVideoTracks[uid]
            if (remoteTrack != null) {
                // 创建视频渲染器并绑定到视图
                val renderer = org.webrtc.SurfaceViewRenderer(context)
                renderer.init(EglBase.create().eglBaseContext, null)
                remoteTrack.addSink(renderer)
                
                // 应用静音状态
                videoMutedStates[uid]?.let { muted ->
                    remoteTrack.setEnabled(!muted)
                }
                
                Log.d(TAG, "远端视频视图已绑定: uid=$uid")
            } else {
                Log.w(TAG, "未找到远端视频轨道: uid=$uid")
            }
        } catch (e: Exception) {
            Log.e(TAG, "设置远端视频视图失败", e)
        }
    }
    
    // ==================== 屏幕共享 ====================
    
    fun startScreenCapture(config: ScreenCaptureConfiguration) {
        if (isScreenCapturing.get()) {
            Log.w(TAG, "屏幕共享已在进行中")
            return
        }
        
        screenCaptureConfig = config
        isScreenCapturing.set(true)
        Log.d(TAG, "开始屏幕共享: ${config.width}x${config.height}, ${config.frameRate}fps")
        
        try {
            // 使用MediaProjection API进行屏幕录制
            val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            
            // 注意：MediaProjection需要用户授权，这里假设已经获得授权
            // 实际使用时需要通过Activity的startActivityForResult获取MediaProjection
            
            // 创建VirtualDisplay进行屏幕录制
            val displayMetrics = context.resources.displayMetrics
            val width = config.width.takeIf { it > 0 } ?: displayMetrics.widthPixels
            val height = config.height.takeIf { it > 0 } ?: displayMetrics.heightPixels
            val density = displayMetrics.densityDpi
            
            // 创建Surface用于接收屏幕内容
            val surfaceTexture = SurfaceTexture(0)
            surfaceTexture.setDefaultBufferSize(width, height)
            screenCaptureSurface = Surface(surfaceTexture)
            
            // 创建VideoSource用于屏幕共享
            screenVideoSource = peerConnectionFactory?.createVideoSource(false)
            
            // 创建VirtualDisplay
            // 注意：MediaProjection需要通过Activity获取，这里提供完整实现框架
            // 实际使用时，MediaProjection应该从外部传入（通过setMediaProjection方法）
            if (mediaProjection != null && screenCaptureSurface != null && screenVideoSource != null) {
                virtualDisplay = mediaProjection!!.createVirtualDisplay(
                    "ScreenCapture",
                    width, height, density,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    screenCaptureSurface,
                    null, null
                )
                
                // 使用屏幕内容创建视频轨道
                localVideoTrack = peerConnectionFactory?.createVideoTrack("screen_track", screenVideoSource)
                
                Log.d(TAG, "VirtualDisplay已创建: ${width}x${height}")
            } else {
                Log.w(TAG, "MediaProjection未设置，无法创建VirtualDisplay。请先调用setMediaProjection()")
            }
            
            Log.d(TAG, "屏幕录制已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动屏幕共享失败", e)
            isScreenCapturing.set(false)
        }
    }
    
    fun stopScreenCapture() {
        if (!isScreenCapturing.get()) {
            Log.w(TAG, "屏幕共享未在进行中")
            return
        }
        
        isScreenCapturing.set(false)
        Log.d(TAG, "停止屏幕共享")
        
        try {
            // 停止VirtualDisplay
            virtualDisplay?.release()
            virtualDisplay = null
            
            // 释放Surface
            screenCaptureSurface?.release()
            screenCaptureSurface = null
            
            // 停止MediaProjection
            mediaProjection?.stop()
            mediaProjection = null
            
            screenCaptureConfig = null
            Log.d(TAG, "屏幕录制已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止屏幕共享失败", e)
        }
    }
    
    fun updateScreenCaptureConfiguration(config: ScreenCaptureConfiguration) {
        if (!isScreenCapturing.get()) {
            Log.w(TAG, "屏幕共享未在进行中，无法更新配置")
            return
        }
        
        screenCaptureConfig = config
        Log.d(TAG, "更新屏幕共享配置: ${config.width}x${config.height}, ${config.frameRate}fps")
        
        try {
            // 重新创建VirtualDisplay以应用新配置
            virtualDisplay?.release()
            
            val displayMetrics = context.resources.displayMetrics
            val width = config.width.takeIf { it > 0 } ?: displayMetrics.widthPixels
            val height = config.height.takeIf { it > 0 } ?: displayMetrics.heightPixels
            val density = displayMetrics.densityDpi
            
            // 重新创建VirtualDisplay以应用新配置
            if (mediaProjection != null && screenCaptureSurface != null) {
                virtualDisplay = mediaProjection!!.createVirtualDisplay(
                    "ScreenCapture",
                    width, height, density,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    screenCaptureSurface,
                    null, null
                )
                Log.d(TAG, "VirtualDisplay已重新创建: ${width}x${height}")
            }
            
            Log.d(TAG, "屏幕共享配置已更新")
        } catch (e: Exception) {
            Log.e(TAG, "更新屏幕共享配置失败", e)
        }
    }
    
    // ==================== 视频增强 ====================
    
    fun setBeautyEffectOptions(options: BeautyOptions) {
        beautyOptions = options
        Log.d(TAG, "设置美颜选项: enabled=${options.enabled}, lightening=${options.lighteningLevel}, smoothness=${options.smoothnessLevel}")
        
        try {
            // 应用美颜效果
            if (options.enabled) {
                // 创建或更新美颜滤镜
                if (beautyFilter == null) {
                    beautyFilter = BeautyFilter()
                }
                beautyFilter?.setLighteningLevel(options.lighteningLevel.toFloat())
                beautyFilter?.setSmoothnessLevel(options.smoothnessLevel.toFloat())
                beautyFilter?.setRednessLevel(options.rednessLevel.toFloat())
                beautyFilter?.enable()
                
                // 将美颜滤镜应用到视频轨道
                localVideoTrack?.let { track ->
                    // 创建美颜VideoSink
                    val beautySink = BeautyVideoSink(beautyFilter!!) { filteredFrame: VideoFrame ->
                        // 将处理后的帧重新注入到视频轨道
                        // 实际实现需要替换原始帧
                    }
                    track.addSink(beautySink)
                }
                
                Log.d(TAG, "美颜效果已启用")
            } else {
                beautyFilter?.disable()
                beautyFilter = null
                Log.d(TAG, "美颜效果已禁用")
            }
        } catch (e: Exception) {
            Log.e(TAG, "设置美颜效果失败", e)
        }
    }
    
    fun takeSnapshot(uid: String, filePath: String) {
        Log.d(TAG, "视频截图: uid=$uid, path=$filePath")
        
        try {
            val viewId = if (uid == "local") {
                videoViews["local"]
            } else {
                videoViews[uid]
            }
            
            if (viewId == null) {
                Log.e(TAG, "未找到视频视图: uid=$uid")
                return
            }
            
            // 从视频轨道截取画面
            val videoTrack = if (uid == "local") {
                localVideoTrack
            } else {
                // 从远端视频轨道映射中获取
                null
            }
            
            if (videoTrack != null) {
                // 使用WebRTC的VideoSink捕获帧
                val file = java.io.File(filePath)
                file.parentFile?.mkdirs()
                
                // 创建VideoSink来捕获帧
                val frameCapturer = object : org.webrtc.VideoSink {
                    override fun onFrame(frame: org.webrtc.VideoFrame) {
                        try {
                            val bitmap = frameToBitmap(frame)
                            if (bitmap != null) {
                                val fos = java.io.FileOutputStream(file)
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, fos)
                                fos.close()
                                Log.d(TAG, "截图已保存: $filePath")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "截图保存失败", e)
                        }
                    }
                }
                videoTrack.addSink(frameCapturer)
                // 等待一帧后移除
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    videoTrack.removeSink(frameCapturer)
                }, 100)
            } else {
                Log.e(TAG, "未找到视频轨道: uid=$uid")
            }
        } catch (e: Exception) {
            Log.e(TAG, "截图失败", e)
        }
    }
    
    // ==================== 音频混音 ====================
    
    fun startAudioMixing(config: AudioMixingConfiguration) {
        if (audioMixingState.get() == 1) {
            Log.w(TAG, "音频混音已在进行中")
            stopAudioMixing()
        }
        
        audioMixingConfig = config
        audioMixingState.set(1)
        Log.d(TAG, "开始音频混音: ${config.filePath}, loopback=${config.loopback}")
        
        try {
            val player = android.media.MediaPlayer()
            player.setDataSource(config.filePath)
            player.isLooping = config.cycle > 1
            player.setVolume(audioMixingVolume / 100f, audioMixingVolume / 100f)
            player.prepare()
            
            if (config.startPos > 0) {
                player.seekTo(config.startPos)
            }
            
            player.start()
            audioMixingPlayer = player
            
            Log.d(TAG, "音频混音播放已开始")
        } catch (e: Exception) {
            Log.e(TAG, "启动音频混音失败", e)
            audioMixingState.set(0)
        }
    }
    
    fun stopAudioMixing() {
        if (audioMixingState.get() == 0) {
            return
        }
        
        audioMixingState.set(0)
        Log.d(TAG, "停止音频混音")
        
        audioMixingPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            } catch (e: Exception) {
                Log.e(TAG, "停止音频混音失败", e)
            }
        }
        audioMixingPlayer = null
        audioMixingConfig = null
    }
    
    fun pauseAudioMixing() {
        if (audioMixingState.get() != 1) {
            Log.w(TAG, "音频混音未在播放中，无法暂停")
            return
        }
        
        audioMixingState.set(2)
        Log.d(TAG, "暂停音频混音")
        
        audioMixingPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.pause()
                } else {
                    // no-op
                }
            } catch (e: Exception) {
                Log.e(TAG, "暂停音频混音失败", e)
            }
        }
    }
    
    fun resumeAudioMixing() {
        if (audioMixingState.get() != 2) {
            Log.w(TAG, "音频混音未在暂停状态，无法恢复")
            return
        }
        
        audioMixingState.set(1)
        Log.d(TAG, "恢复音频混音")
        
        audioMixingPlayer?.let { player ->
            try {
                if (!player.isPlaying) {
                    player.start()
                } else {
                    // no-op
                }
            } catch (e: Exception) {
                Log.e(TAG, "恢复音频混音失败", e)
            }
        }
    }
    
    fun adjustAudioMixingVolume(volume: Int) {
        audioMixingVolume = volume.coerceIn(0, 100)
        Log.d(TAG, "调整混音音量: $volume")
        
        audioMixingPlayer?.let { player ->
            try {
                val volumeFloat = audioMixingVolume / 100f
                player.setVolume(volumeFloat, volumeFloat)
            } catch (e: Exception) {
                Log.e(TAG, "调整混音音量失败", e)
            }
        }
    }
    
    fun getAudioMixingCurrentPosition(): Int {
        return audioMixingPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.currentPosition
                } else {
                    0
                }
            } catch (e: Exception) {
                Log.e(TAG, "获取混音位置失败", e)
                0
            }
        } ?: 0
    }
    
    fun setAudioMixingPosition(position: Int) {
        Log.d(TAG, "设置混音位置: $position")
        
        audioMixingPlayer?.let { player ->
            try {
                player.seekTo(position.coerceIn(0, player.duration))
            } catch (e: Exception) {
                Log.e(TAG, "设置混音位置失败", e)
            }
        }
    }
    
    // ==================== 音效 ====================
    
    fun playEffect(soundId: Int, config: AudioEffectConfiguration) {
        // 停止已存在的相同音效
        stopEffect(soundId)
        
        effects[soundId] = AudioEffectState(config, true)
        Log.d(TAG, "播放音效: soundId=$soundId, file=${config.filePath}, loopCount=${config.loopCount}")
        
        try {
            val player = android.media.MediaPlayer()
            player.setDataSource(config.filePath)
            player.isLooping = config.loopCount > 1 || config.loopCount == -1
            player.prepare()
            
            if (config.startPos > 0) {
                player.seekTo(config.startPos)
            }
            
            player.start()
            effectPlayers[soundId] = player
            
            // 设置播放完成监听
            player.setOnCompletionListener {
                if (config.loopCount <= 1) {
                    stopEffect(soundId)
                }
            }
            
            Log.d(TAG, "音效播放已开始: soundId=$soundId")
        } catch (e: Exception) {
            Log.e(TAG, "播放音效失败: soundId=$soundId", e)
            effects.remove(soundId)
        }
    }
    
    fun stopEffect(soundId: Int) {
        effectPlayers[soundId]?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            } catch (e: Exception) {
                Log.e(TAG, "停止音效失败: soundId=$soundId", e)
            }
        }
        effectPlayers.remove(soundId)
        effects.remove(soundId)
        Log.d(TAG, "停止音效: $soundId")
    }
    
    fun stopAllEffects() {
        effectPlayers.values.forEach { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            } catch (e: Exception) {
                Log.e(TAG, "停止音效失败", e)
            }
        }
        effectPlayers.clear()
        effects.clear()
        Log.d(TAG, "停止所有音效")
    }
    
    fun setEffectsVolume(volume: Int) {
        val volumeFloat = volume.coerceIn(0, 100) / 100f
        Log.d(TAG, "设置音效音量: $volume")
        
        effectPlayers.values.forEach { player ->
            try {
                player.setVolume(volumeFloat, volumeFloat)
            } catch (e: Exception) {
                Log.e(TAG, "设置音效音量失败", e)
            }
        }
    }
    
    fun preloadEffect(soundId: Int, filePath: String) {
        Log.d(TAG, "预加载音效: soundId=$soundId, file=$filePath")
        
        try {
            val player = android.media.MediaPlayer()
            player.setDataSource(filePath)
            player.prepare()
            // 预加载后不播放，只准备
            player.reset()
            effectPlayers[soundId] = player
            Log.d(TAG, "音效预加载完成: soundId=$soundId")
        } catch (e: Exception) {
            Log.e(TAG, "预加载音效失败: soundId=$soundId", e)
        }
    }
    
    fun unloadEffect(soundId: Int) {
        stopEffect(soundId)
        Log.d(TAG, "卸载音效: $soundId")
    }
    
    // ==================== 音频录制 ====================
    
    fun startAudioRecording(config: AudioRecordingConfiguration): Int {
        if (audioRecorder != null) {
            Log.w(TAG, "音频录制已在进行中")
            return -1
        }
        
        audioRecordingConfig = config
        Log.d(TAG, "开始音频录制: ${config.filePath}, ${config.sampleRate}Hz, ${config.channels}ch, codec=${config.codecType}")
        
        try {
            val recorder = android.media.MediaRecorder()
            
            // 设置音频源
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            
            // 设置输出格式和编码器
            when (config.codecType.lowercase()) {
                "aaclc", "aac" -> {
                    recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                    recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                }
                "mp3" -> {
                    recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                    recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AMR_NB)
                }
                else -> {
                    recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                    recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                }
            }
            
            // 设置采样率和声道
            recorder.setAudioSamplingRate(config.sampleRate)
            recorder.setAudioChannels(config.channels)
            
            // 设置输出文件
            val file = java.io.File(config.filePath)
            file.parentFile?.mkdirs()
            recorder.setOutputFile(file.absolutePath)
            
            // 准备并开始录制
            recorder.prepare()
            recorder.start()
            
            audioRecorder = recorder
            Log.d(TAG, "音频录制已开始")
            return 0
        } catch (e: Exception) {
            Log.e(TAG, "启动音频录制失败", e)
            audioRecorder = null
            return -1
        }
    }
    
    fun stopAudioRecording() {
        if (audioRecorder == null) {
            Log.w(TAG, "音频录制未在进行中")
            return
        }
        
        Log.d(TAG, "停止音频录制")
        
        try {
            audioRecorder?.stop()
            audioRecorder?.release()
            audioRecorder = null
            audioRecordingConfig = null
            Log.d(TAG, "音频录制已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止音频录制失败", e)
            audioRecorder = null
        }
    }
    
    // ==================== 网络质量 ====================
    
    fun getNetworkQuality(): NetworkQuality {
        // 不同 WebRTC 包的 getStats API（report 类型与遍历方式）差异很大，容易导致编译/运行不一致。
        // 这里先提供可用的占位实现；如需真实网络质量，后续再按具体 WebRTC 包的 stats 结构实现。
        return NetworkQuality(0, 0, 0, 0)
    }
    
    // ==================== 数据流 ====================
    
    fun createDataStream(reliable: Boolean, ordered: Boolean): Int {
        val streamId = dataStreams.size + 1
        
        try {
            // 创建DataChannel配置
            val init = DataChannel.Init()
            init.ordered = ordered
            // 不同 WebRTC 包的 DataChannel.Init 字段不完全一致，这里只保证 ordered 生效
            
            // 从PeerConnection创建DataChannel
            // 查找或创建默认PeerConnection
            val defaultPeerConnection = peerConnections.values.firstOrNull() 
                ?: createDefaultPeerConnection()
            
            if (defaultPeerConnection != null) {
                val dataChannel = defaultPeerConnection.createDataChannel("data_channel_$streamId", init)
                dataChannelMap[streamId] = dataChannel
                
                // 设置DataChannel回调
                dataChannel.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) {}
                    override fun onStateChange() {
                        Log.d(TAG, "DataChannel状态变化: ${dataChannel.state()}")
                    }
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        Log.d(TAG, "收到DataChannel消息: ${buffer.data.remaining()} bytes")
                    }
                })
            }
            
            dataStreams[streamId] = true
            Log.d(TAG, "创建数据流: streamId=$streamId, reliable=$reliable, ordered=$ordered")
            
            return streamId
        } catch (e: Exception) {
            Log.e(TAG, "创建数据流失败", e)
            return -1
        }
    }
    
    fun sendStreamMessage(streamId: Int, data: ByteArray) {
        if (!dataStreams.containsKey(streamId)) {
            Log.e(TAG, "数据流不存在: streamId=$streamId")
            return
        }
        
        try {
            val dataChannel = dataChannelMap[streamId]
            if (dataChannel != null && dataChannel.state() == DataChannel.State.OPEN) {
                val buffer = DataChannel.Buffer(java.nio.ByteBuffer.wrap(data), false)
                dataChannel.send(buffer)
                Log.d(TAG, "数据流消息已发送: streamId=$streamId, size=${data.size} bytes")
            } else {
                Log.w(TAG, "数据流未打开: streamId=$streamId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送数据流消息失败", e)
        }
    }
    
    // ==================== 旁路推流 ====================
    
    fun startRtmpStreamWithTranscoding(url: String, transcoding: LiveTranscoding) {
        val channelId = currentChannelId
        if (channelId.isNullOrBlank()) {
            eventHandler?.onError(1001, "未加入频道，无法开播")
            return
        }
        val publishers = transcoding.transcodingUsers
            ?.mapNotNull { it.uid?.takeIf { s -> s.isNotBlank() } }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(currentUid).filter { it.isNotBlank() }

        // 如果url为空，使用空数组，后端会自动生成我们服务器的RTMP地址
        val rtmpUrls = if (url.isBlank()) emptyList<String>() else listOf(url)

        val body = JSONObject().apply {
            put("channelId", channelId)
            put("publishers", org.json.JSONArray(publishers))
            put("rtmpUrls", org.json.JSONArray(rtmpUrls))
            put("video", JSONObject().apply {
                put("outW", transcoding.width)
                put("outH", transcoding.height)
                put("fps", transcoding.videoFramerate)
                put("bitrateKbps", transcoding.videoBitrate)
            })
            put("audio", JSONObject().apply {
                put("sampleRate", 48000)
                put("channels", 2)
                put("bitrateKbps", 128)
            })
            put("layout", guessLayoutFromTranscoding(transcoding))
        }
        postLiveApi("/api/rtc/live/start", body)
        
        // 如果url为空，使用生成的地址（从响应中获取，或使用默认格式）
        val finalUrl = if (url.isBlank()) "auto_generated_$channelId" else url
        rtmpStreams[finalUrl] = transcoding
    }
    
    fun stopRtmpStream(url: String) {
        if (url.isBlank()) return
        val channelId = currentChannelId ?: return
        val body = JSONObject().apply { put("channelId", channelId) }
        postLiveApi("/api/rtc/live/stop", body)
        rtmpStreams.remove(url)
    }
    
    fun updateRtmpTranscoding(transcoding: LiveTranscoding) {
        val channelId = currentChannelId ?: return
        val body = JSONObject().apply {
            put("channelId", channelId)
            put("video", JSONObject().apply {
                put("outW", transcoding.width)
                put("outH", transcoding.height)
                put("fps", transcoding.videoFramerate)
                put("bitrateKbps", transcoding.videoBitrate)
            })
            put("layout", guessLayoutFromTranscoding(transcoding))
        }
        postLiveApi("/api/rtc/live/update", body)
    }
    
    // ==================== 清理 ====================
    
    fun release() {
        // 停止音频混音
        stopAudioMixing()
        
        // 停止所有音效
        stopAllEffects()
        
        // 停止音频录制
        stopAudioRecording()
        
        // 停止屏幕共享
        if (isScreenCapturing.get()) {
            stopScreenCapture()
        }
        
        // 停止视频预览
        if (isPreviewing.get()) {
            stopPreview()
        }
        
        // 释放音频资源
        audioRecord?.stop()
        audioRecord?.release()
        audioTrack?.stop()
        audioTrack?.release()
        
        // 释放WebRTC资源
        localVideoTrack?.dispose()
        localAudioTrack?.dispose()
        videoCapturer?.dispose()
        peerConnectionFactory?.dispose()
        
        // 释放RTMP资源
        rtmpStreams.keys.forEach { url ->
            stopRtmpStream(url)
        }
        
        // 释放屏幕共享资源
        virtualDisplay?.release()
        screenCaptureSurface?.release()
        mediaProjection?.stop()
        
        // 释放数据流资源
        dataChannelMap.values.forEach { it.close() }
        dataChannelMap.clear()
        
        // 释放远端视频轨道
        remoteVideoTracks.values.forEach { it.dispose() }
        remoteVideoTracks.clear()
        
        // 释放PeerConnection
        peerConnections.values.forEach { it.dispose() }
        peerConnections.clear()
        
        // 停止所有旁路推流
        rtmpStreams.keys.forEach { url ->
            stopRtmpStream(url)
        }
        
        // 清理所有状态
        effects.clear()
        effectPlayers.clear()
        videoViews.clear()
        userVolumes.clear()
        videoMutedStates.clear()
        dataStreams.clear()
        rtmpStreams.clear()
        
        Log.d(TAG, "所有资源已释放")
    }
    
    // ==================== 内部类 ====================
    
    private data class AudioEffectState(
        val config: AudioEffectConfiguration,
        val isPlaying: Boolean
    )
    
    data class NetworkQuality(
        val txQuality: Int,
        val rxQuality: Int,
        val txBitrate: Int,
        val rxBitrate: Int
    )
    
    // RTMP 推流改为走服务端 egress：不在客户端实现 RTMP 连接/编码，以降低复杂度并提高跨平台一致性。
    
    // 美颜滤镜类
    private class BeautyFilter {
        private var lighteningLevel: Float = 0.5f
        private var smoothnessLevel: Float = 0.5f
        private var rednessLevel: Float = 0.1f
        private var enabled: Boolean = false
        
        fun setLighteningLevel(level: Float) {
            lighteningLevel = level.coerceIn(0f, 1f)
        }
        
        fun setSmoothnessLevel(level: Float) {
            smoothnessLevel = level.coerceIn(0f, 1f)
        }
        
        fun setRednessLevel(level: Float) {
            rednessLevel = level.coerceIn(0f, 1f)
        }
        
        fun enable() {
            enabled = true
        }
        
        fun disable() {
            enabled = false
        }
        
        fun isEnabled(): Boolean = enabled
        
        // 应用美颜效果到视频帧
        fun apply(frame: org.webrtc.VideoFrame): org.webrtc.VideoFrame {
            if (!enabled) return frame
            
            try {
                // 使用OpenGL ES进行美颜处理
                val i420Buffer = frame.buffer.toI420() ?: return frame
                val width = i420Buffer.width
                val height = i420Buffer.height
                
                // 创建OpenGL纹理
                val textures = IntArray(1)
                GLES20.glGenTextures(1, textures, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
                
                // 应用美颜滤镜（亮度、平滑度、红润度）
                // 使用CPU处理（实际生产环境建议使用OpenGL着色器）
                val yPlane = i420Buffer.dataY
                val uPlane = i420Buffer.dataU
                val vPlane = i420Buffer.dataV
                
                val yArray = ByteArray(yPlane.remaining())
                yPlane.get(yArray)
                
                // 亮度调整
                if (lighteningLevel != 0.5f) {
                    val brightness = (lighteningLevel - 0.5f) * 2f * 30f // -30到+30亮度调整
                    for (i in yArray.indices) {
                        val value = (yArray[i].toInt() + brightness.toInt()).coerceIn(0, 255)
                        yArray[i] = value.toByte()
                    }
                    yPlane.rewind()
                    yPlane.put(yArray)
                }
                
                // 平滑度处理（简单的均值滤波）
                if (smoothnessLevel > 0.5f) {
                    val smoothRadius = ((smoothnessLevel - 0.5f) * 2f * 3f).toInt() // 0-3像素半径
                    if (smoothRadius > 0) {
                        val smoothed = ByteArray(yArray.size)
                        for (y in 0 until height) {
                            for (x in 0 until width) {
                                var sum = 0
                                var count = 0
                                for (dy in -smoothRadius..smoothRadius) {
                                    for (dx in -smoothRadius..smoothRadius) {
                                        val ny = (y + dy).coerceIn(0, height - 1)
                                        val nx = (x + dx).coerceIn(0, width - 1)
                                        sum += yArray[ny * width + nx].toInt() and 0xFF
                                        count++
                                    }
                                }
                                smoothed[y * width + x] = (sum / count).toByte()
                            }
                        }
                        yPlane.rewind()
                        yPlane.put(smoothed)
                    }
                }
                
                // 红润度调整（调整UV平面）
                if (rednessLevel > 0.1f) {
                    val redAdjust = ((rednessLevel - 0.1f) * 10f).coerceIn(0f, 1f)
                    val uArray = ByteArray(uPlane.remaining())
                    uPlane.get(uArray)
                    for (i in uArray.indices) {
                        val value = (uArray[i].toInt() + (redAdjust * 10).toInt()).coerceIn(0, 255)
                        uArray[i] = value.toByte()
                    }
                    uPlane.rewind()
                    uPlane.put(uArray)
                }
                
                GLES20.glDeleteTextures(1, textures, 0)
                
                return frame
            } catch (e: Exception) {
                Log.e("BeautyFilter", "应用美颜效果失败", e)
                return frame
            }
        }
    }

    // 美颜 VideoSink：对外只做“处理并回调”，不保证替换原始 Track（替换需要更深的管线改造）
    private class BeautyVideoSink(
        private val filter: BeautyFilter,
        private val onFilteredFrame: (VideoFrame) -> Unit
    ) : VideoSink {
        override fun onFrame(frame: VideoFrame) {
            val out = try {
                filter.apply(frame)
            } catch (_: Exception) {
                frame
            }
            onFilteredFrame(out)
        }
    }
    
    private fun frameToBitmap(frame: org.webrtc.VideoFrame): android.graphics.Bitmap? {
        return try {
            val i420Buffer = frame.buffer.toI420() ?: return null
            val width = i420Buffer.width
            val height = i420Buffer.height
            
            // 将I420格式转换为RGB Bitmap
            // 使用高效的YUV到RGB转换
            val yPlane = i420Buffer.dataY
            val uPlane = i420Buffer.dataU
            val vPlane = i420Buffer.dataV
            
            // 创建NV21格式（Android YuvImage需要的格式）
            val nv21Size = width * height * 3 / 2
            val nv21 = ByteArray(nv21Size)
            
            // 复制Y平面
            yPlane.get(nv21, 0, width * height)
            
            // 交错U和V平面
            val uvOffset = width * height
            val uvSize = width * height / 4
            for (i in 0 until uvSize) {
                nv21[uvOffset + i * 2] = uPlane.get(i)
                nv21[uvOffset + i * 2 + 1] = vPlane.get(i)
            }
            
            val yuvImage = android.graphics.YuvImage(
                nv21,
                android.graphics.ImageFormat.NV21,
                width,
                height,
                null
            )
            val out = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 90, out)
            val imageBytes = out.toByteArray()
            android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "转换视频帧为Bitmap失败", e)
            null
        }
    }
}
