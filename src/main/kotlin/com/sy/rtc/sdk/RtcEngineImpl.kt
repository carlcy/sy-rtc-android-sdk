package com.sy.rtc.sdk

import android.content.Context
import android.content.Intent
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
    /** 已请求 mediaProjection 前台服务、尚未开始采集。 */
    private val screenCaptureStarting = AtomicBoolean(false)
    private var screenCaptureConfig: ScreenCaptureConfiguration? = null
    
    // 美颜配置
    private var beautyOptions: BeautyOptions? = null
    
    // 音频录制
    private var audioRecorder: android.media.MediaRecorder? = null
    private var audioRecordingConfig: AudioRecordingConfiguration? = null
    
    // 数据流
    private val dataStreams = ConcurrentHashMap<Int, Boolean>()
    
    private var apiBaseUrl: String? = null
    
    // 屏幕共享
    private var mediaProjection: android.media.projection.MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var screenCaptureSurface: Surface? = null
    private var screenVideoSource: VideoSource? = null
    
    // 美颜滤镜
    private var beautyFilter: BeautyFilter? = null
    
    // 数据流 - WebRTC DataChannel（按 streamId、对端 uid 保存，不建空的 default PC）
    private val dataChannelMap = ConcurrentHashMap<Int, org.webrtc.DataChannel>()
    private val streamSpecs = ConcurrentHashMap<Int, StreamSpec>()
    private val dataChannels = ConcurrentHashMap<Int, ConcurrentHashMap<String, org.webrtc.DataChannel>>()
    private val nextStreamId = AtomicInteger(0)
    private val peerConnections = ConcurrentHashMap<String, org.webrtc.PeerConnection>()
    
    // 远端视频轨道
    private val remoteVideoTracks = ConcurrentHashMap<String, org.webrtc.VideoTrack>()
    private val remoteAudioTracks = ConcurrentHashMap<String, org.webrtc.AudioTrack>()
    // 远端视频渲染器（便于 release 时移除）
    private val remoteRenderers = ConcurrentHashMap<String, org.webrtc.SurfaceViewRenderer>()
    private val pendingRemoteContainers = ConcurrentHashMap<String, java.lang.ref.WeakReference<android.view.ViewGroup>>()
    private var localRenderer: org.webrtc.SurfaceViewRenderer? = null
    private var eglBase: EglBase? = null
    private var localVideoSource: VideoSource? = null
    private var screenCapturer: VideoCapturer? = null
    private var screenCaptureIntent: Intent? = null
    private var customVideoCapture = false
    private var usingFrontCamera = true
    private var videoFrameProcessor: VideoFrameProcessor? = null
    private var frameProcessor: LocalVideoProcessor? = null
    private val reconnectTracker = ReconnectTracker()
    private val iceRecoveryPending = AtomicBoolean(false)
    private val signalingRetryPosted = AtomicBoolean(false)
    private var signalingRetry: Runnable? = null
    private var iceRetry: Runnable? = null
    private val iceLostPeers: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private var tokenWarnTask: Runnable? = null
    private val remoteFrameSinks = ConcurrentHashMap<String, Pair<org.webrtc.VideoTrack, org.webrtc.VideoSink>>()
    private var localFrameSink: org.webrtc.VideoSink? = null
    private var tokenExpireTask: Runnable? = null
    private var localAudioMuted = false
    private var localVideoMuted = false
    private var allRemoteAudioMuted = false
    private var allRemoteVideoMuted = false
    private val remoteAudioMuted = ConcurrentHashMap<String, Boolean>()
    /** 对端通过 user-media 通知的自身静音状态。 */
    private val remoteSelfAudioMuted = ConcurrentHashMap<String, Boolean>()
    private val remoteSelfVideoMuted = ConcurrentHashMap<String, Boolean>()
    /** useFrontCamera 在摄像头启动前设置的偏好。 */
    private var preferFrontCamera = true
    private var localPcmVolume = 0
    private var localPcmSeen = false
    private val remotePcmVolume = ConcurrentHashMap<String, Int>()
    private val remotePcmSeen = ConcurrentHashMap.newKeySet<String>()
    private var smoothedLocalVolume = 0
    private val smoothedRemoteVolume = ConcurrentHashMap<String, Int>()
    private var volumeSmooth = 3
    private var streamExtraInfo: String = ""
    private var statsRunnable: Runnable? = null
    private val lastBytesSent = ConcurrentHashMap<String, Long>()
    private val lastBytesRecv = ConcurrentHashMap<String, Long>()
    private val lastStatsMs = ConcurrentHashMap<String, Long>()
    private var lastNetwork = NetworkQuality(0, 0, 0, 0)
    private var publishedAudioRoute = -1
    
    // 频道状态
    private var currentChannelId: String? = null
    private var currentUid: String? = null
    private var joinStartTime: Long = 0
    // join 传入 token：RTC Token（用于加入频道）
    private var currentToken: String? = null
    // 后端 API 认证用的 JWT
    private var apiAuthToken: String? = null
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

    private fun guessRemoteUid(): String {
        return peerConnections.keys.firstOrNull { it != "default" } ?: ""
    }

    fun setSignalingServerUrl(url: String) {
        if (url.isNotBlank()) {
            signalingUrl = url
        }
    }

    fun setApiBaseUrl(url: String) {
        apiBaseUrl = url.trim().trimEnd('/')
    }

    fun setApiAuthToken(token: String) {
        apiAuthToken = token
    }


    // ==================== 网络质量（简化实现） ====================

    fun getConnectionState(): String {
        // 这里先返回一个粗粒度状态：只要已 join 且 signaling 已连接就视为 connected
        return if (isJoined.get()) "connected" else "disconnected"
    }

    /**
     * 当前默认网络：wifi / cellular / ethernet / none / unknown（与 iOS 同名）。
     * 需要 ACCESS_NETWORK_STATE（SDK manifest 已声明）。
     */
    fun getNetworkType(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                ?: return NetworkTypes.UNKNOWN
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return NetworkTypes.NONE
                val caps = cm.getNetworkCapabilities(network) ?: return NetworkTypes.NONE
                NetworkTypes.classify(
                    connected = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    wifi = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI),
                    cellular = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR),
                    ethernet = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
                )
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo ?: return NetworkTypes.NONE
                @Suppress("DEPRECATION")
                NetworkTypes.classify(
                    connected = info.isConnected,
                    wifi = info.type == android.net.ConnectivityManager.TYPE_WIFI,
                    cellular = info.type == android.net.ConnectivityManager.TYPE_MOBILE,
                    ethernet = info.type == android.net.ConnectivityManager.TYPE_ETHERNET
                )
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "缺少 ACCESS_NETWORK_STATE，无法读取网络类型", e)
            NetworkTypes.UNKNOWN
        } catch (e: Exception) {
            Log.w(TAG, "读取网络类型失败", e)
            NetworkTypes.UNKNOWN
        }
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
        try { initializeAudioDevices() } catch (t: Throwable) { Log.e(TAG, "initializeAudioDevices failed", t) }
        try { initializeWebRTC() } catch (t: Throwable) { Log.e(TAG, "initializeWebRTC failed", t) }
    }
    
    private fun initializeWebRTC() {
        try {
            val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initializationOptions)
            
            val options = PeerConnectionFactory.Options()
            eglBase = try {
                EglBase.create()
            } catch (t: Throwable) {
                Log.w(TAG, "EglBase.create() 失败，尝试 createEgl14", t)
                EglBase.createEgl14(EglBase.CONFIG_PLAIN)
            }
            val encoderFactory = DefaultVideoEncoderFactory(
                eglBase!!.eglBaseContext,
                true,
                true
            )
            val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)
            
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()
            
            Log.d(TAG, "WebRTC初始化成功")
        } catch (t: Throwable) {
            Log.e(TAG, "WebRTC初始化失败", t)
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
        if (channelId.isBlank() || uid.isBlank() || token.isBlank()) {
            Log.e(TAG, "join 参数不能为空: channelId/uid/token")
            eventHandler?.onError(RtcErrorCode.INVALID_ARGUMENT, "channelId/uid/token 不能为空")
            return
        }
        if (isJoined.get()) {
            Log.w(TAG, "已经加入频道，请先离开")
            eventHandler?.onError(RtcErrorCode.INVALID_ARGUMENT, "已经加入频道，请先 leave()")
            return
        }
        
        currentChannelId = channelId
        currentUid = uid
        currentToken = token
        reconnectTracker.reset()
        iceRecoveryPending.set(false)
        iceLostPeers.clear()
        signalingRetryPosted.set(false)
        // 加入频道即视为“已加入”（即便房间内暂时没有其他人）
        joinStartTime = System.currentTimeMillis()
        isJoined.set(true)
        eventHandler?.onConnectionStateChanged("connecting", "joining")
        // 清理多人会话状态
        offerSentByUid.clear()
        remoteSdpSetByUid.clear()
        pendingLocalIceByUid.clear()
        pendingRemoteIceByUid.clear()
        
        Log.d(TAG, "加入频道: channelId=$channelId, uid=$uid")
        
        try {
            // 连接信令服务器
            signalingClient = SignalingClient(
                signalingUrl, channelId, uid, token,
                onMessage = { type, data -> handleSignalingMessage(type, data, channelId) },
                onConnectionFailure = { handleSignalingFailure() }
            )
            signalingClient?.connect()
            startMemberStatePoll(channelId, uid)
            startStatsPoll()
            scheduleTokenPrivilegeWatch(token)
            
            // 创建音频轨道
            val audioSource = peerConnectionFactory?.createAudioSource(org.webrtc.MediaConstraints())
            localAudioTrack = peerConnectionFactory?.createAudioTrack("audio_track", audioSource)
            localAudioTrack?.setEnabled(!localAudioMuted && clientRole.canPublish())
            attachLocalVolumeSink()
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
        eventHandler?.onConnectionStateChanged("disconnecting", "leaving")
        
        try {
            if (callRecorder != null) stopAudioRecording()
            stopMemberStatePoll()
            stopStatsPoll()
            cancelTokenPrivilegeWatch()
            signalingRetry?.let { mainHandler.removeCallbacks(it) }
            signalingRetry = null
            signalingRetryPosted.set(false)
            reconnectTracker.reset()
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
            
            // 清理远端渲染器（主线程）
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                remoteRenderers.values.forEach { it.release() }
                remoteRenderers.clear()
            }
            remoteFrameSinks.keys.toList().forEach { detachRemoteFrameSink(it) }
            remoteVideoTracks.clear()
            remoteAudioTracks.clear()
            remotePcmVolume.clear()
            remotePcmSeen.clear()
            smoothedRemoteVolume.clear()
            localPcmVolume = 0
            localPcmSeen = false
            dataChannels.values.forEach { map -> map.values.forEach { channel -> channel.close() } }
            dataChannels.clear()
            dataChannelMap.clear()
            videoViews.keys.filter { it != "local" }.forEach { videoViews.remove(it) }
            val stats = mapOf<String, Any?>(
                "duration" to 0,
                "txBytes" to 0,
                "rxBytes" to 0,
                "txPacketLossRate" to 0.0,
                "rxPacketLossRate" to 0.0
            )
            eventHandler?.onLeaveChannel(stats)
            eventHandler?.onConnectionStateChanged("disconnected", "leave")
            iceRetry?.let { mainHandler.removeCallbacks(it) }
            iceRetry = null
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
            "kicked" -> {
                val reason = (data["reason"] as? String) ?: "kicked"
                Log.w(TAG, "被踢出频道: $reason")
                eventHandler?.onKicked(channelId, reason)
                eventHandler?.onError(RtcErrorCode.forKicked(data), "kicked: $reason")
                leave()
            }
            "user-kicked" -> {
                val kickedUid = (data["uid"] as? String) ?: return
                if (kickedUid == currentUid) {
                    val reason = (data["reason"] as? String) ?: "user-kicked"
                    eventHandler?.onKicked(channelId, reason)
                    leave()
                } else {
                    eventHandler?.onUserOffline(kickedUid, "kicked")
                    peerConnections.remove(kickedUid)?.close()
                }
            }
            "mute-audio", "unmute-audio" -> {
                val target = (data["uid"] as? String) ?: return
                val muted = type == "mute-audio" || (data["mutedAudio"] as? Boolean) == true
                eventHandler?.onServerMuteAudio(target, muted)
                if (target == currentUid) {
                    localAudioTrack?.setEnabled(!muted)
                } else {
                    muteRemoteAudioStream(target, muted)
                }
            }
            "user-list" -> {
                val elapsed = (System.currentTimeMillis() - joinStartTime).toInt().coerceAtLeast(0)
                when (reconnectTracker.onConnected()) {
                    RejoinSignal.REJOINED -> {
                        eventHandler?.onRejoinChannelSuccess(channelId, currentUid ?: "", elapsed)
                        eventHandler?.onConnectionStateChanged("connected", "rejoin_success")
                        eventHandler?.onReconnected("signaling")
                    }
                    RejoinSignal.JOINED -> {
                        eventHandler?.onJoinChannelSuccess(channelId, currentUid ?: "", elapsed)
                        eventHandler?.onConnectionStateChanged("connected", "join_success")
                    }
                    // renewToken 等主动重连信令：不重复回调 onJoinChannelSuccess。
                    RejoinSignal.NONE -> {}
                }
                val usersAny = data["users"]
                val users: List<String> = when (usersAny) {
                    is org.json.JSONArray -> (0 until usersAny.length()).mapNotNull { idx -> usersAny.optString(idx)?.takeIf { it.isNotBlank() } }
                    is List<*> -> usersAny.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
                    else -> emptyList()
                }
                users.filter { it != currentUid }.forEach { remote ->
                    eventHandler?.onUserJoined(remote, 0)
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
                                                    if (ensureOutgoingDataChannels(fromUid)) {
                                                        renegotiate(fromUid)
                                                    }
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
                        republishSideInfo()
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
                    if (iceLostPeers.remove(uid) && iceLostPeers.isEmpty() && iceRecoveryPending.getAndSet(false)) {
                        // 断开的对端已离开，不再为它重连。
                        iceRetry?.let { mainHandler.removeCallbacks(it) }
                        iceRetry = null
                        reconnectTracker.onConnected()
                    }
                    peerConnections.remove(uid)?.close()
                    offerSentByUid.remove(uid)
                    remoteSdpSetByUid.remove(uid)
                    pendingLocalIceByUid.remove(uid)
                    pendingRemoteIceByUid.remove(uid)
                    detachRemoteFrameSink(uid)
                    callRecorder?.removeSource(uid)
                    remoteVideoTracks.remove(uid)
                    remoteAudioTracks.remove(uid)
                    remotePcmVolume.remove(uid)
                    remotePcmSeen.remove(uid)
                    remoteSelfAudioMuted.remove(uid)
                    remoteSelfVideoMuted.remove(uid)
                    dataChannels.values.forEach { it.remove(uid)?.close() }
                    val viewId = videoViews[uid]
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        remoteRenderers.remove(uid)?.let { r ->
                            r.release()
                            (context as? android.app.Activity)?.findViewById<android.view.ViewGroup>(viewId ?: 0)?.removeView(r)
                        }
                        videoViews.remove(uid)
                    }
                }
            }
            "channel-message" -> {
                val fromUid = (data["uid"] as? String) ?: ""
                val msg = (data["message"] as? String) ?: ""
                if (WireProtocol.isReservedChannelMessage(msg)) {
                    if (fromUid != currentUid) dispatchClientEnvelope(fromUid, msg)
                } else {
                    eventHandler?.onChannelMessage(fromUid, msg)
                }
            }
            WireProtocol.USER_MEDIA_TYPE -> {
                val fromUid = (data["uid"] as? String) ?: ""
                if (fromUid.isNotEmpty() && fromUid != currentUid) {
                    val (audioMuted, videoMuted) = WireProtocol.decodeUserMedia(data)
                    audioMuted?.let { applyRemoteSelfMute(fromUid, "audio", it) }
                    videoMuted?.let { applyRemoteSelfMute(fromUid, "video", it) }
                }
            }
            "token-will-expire", "token-privilege-will-expire" -> eventHandler?.onTokenPrivilegeWillExpire()
            "token-expired", "request-token" -> eventHandler?.onRequestToken()
            "error" -> {
                eventHandler?.onError(
                    RtcErrorCode.forSignalingError(data),
                    RtcErrorCode.signalingErrorMessage(data),
                )
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
                when (state) {
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED -> mainHandler.post { handleIceLost(remoteUid) }
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> mainHandler.post { handleIceRecovered(remoteUid) }
                    else -> {}
                }
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
            override fun onDataChannel(channel: DataChannel?) {
                acceptIncomingDataChannel(remoteUid, channel)
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                receiver?.track()?.let { track ->
                    if (track.kind() == "audio") {
                        val audioTrack = track as org.webrtc.AudioTrack
                        remoteAudioTracks[remoteUid] = audioTrack
                        applyRemoteAudio(remoteUid)
                        attachRemoteVolumeSink(remoteUid, audioTrack)
                    } else if (track.kind() == "video") {
                        val videoTrack = track as org.webrtc.VideoTrack
                        remoteVideoTracks[remoteUid] = videoTrack
                        applyRemoteVideo(remoteUid)
                        attachRemoteFrameSink(remoteUid, videoTrack)
                        val container = pendingRemoteContainers[remoteUid]?.get()
                        val viewId = videoViews[remoteUid]
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (container != null) {
                                bindRemoteVideoToContainer(remoteUid, videoTrack, container)
                            } else if (viewId != null) {
                                bindRemoteVideoToView(remoteUid, videoTrack, viewId)
                            }
                        }
                    }
                }
            }
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
        if (shouldInitiateOffer(currentUid, remoteUid)) {
            ensureOutgoingDataChannels(remoteUid)
        }
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
    
    private var clientRole: RtcClientRole = RtcClientRole.HOST

    fun setClientRole(role: RtcClientRole) {
        Log.d(TAG, "设置客户端角色: $role canPublish=${role.canPublish()}")
        clientRole = role
        val publish = role.canPublish()
        localAudioTrack?.setEnabled(publish && !localAudioMuted)
        localVideoTrack?.setEnabled(publish && !localVideoMuted)
    }

    private var channelProfile: String = "communication"

    fun setChannelProfile(profile: String) {
        Log.d(TAG, "设置频道场景: $profile")
        channelProfile = profile
    }

    @Volatile
    private var volumeIndicationInterval: Int = 0
    private var volumeIndicationHandler: android.os.Handler? = null
    private var volumeIndicationRunnable: Runnable? = null

    fun enableAudioVolumeIndication(interval: Int, smooth: Int, reportVad: Boolean) {
        Log.d(TAG, "音量提示: interval=$interval, smooth=$smooth, reportVad=$reportVad")
        volumeIndicationRunnable?.let { volumeIndicationHandler?.removeCallbacks(it) }
        volumeIndicationInterval = interval
        volumeSmooth = smooth.coerceAtLeast(1)
        if (interval <= 0) return
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        volumeIndicationHandler = handler
        val runnable = object : Runnable {
            override fun run() {
                if (volumeIndicationInterval <= 0) return
                smoothedLocalVolume = VolumeMeter.smooth(smoothedLocalVolume, localPcmVolume, volumeSmooth)
                val speakers = mutableListOf(VolumeInfo(uid = "local", volume = smoothedLocalVolume))
                remotePcmVolume.forEach { (uid, volume) ->
                    val next = VolumeMeter.smooth(smoothedRemoteVolume[uid] ?: volume, volume, volumeSmooth)
                    smoothedRemoteVolume[uid] = next
                    speakers.add(VolumeInfo(uid, next))
                }
                eventHandler?.onVolumeIndication(speakers)
                handler.postDelayed(this, volumeIndicationInterval.toLong())
            }
        }
        volumeIndicationRunnable = runnable
        handler.postDelayed(runnable, interval.toLong())
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
            
            // 检查蓝牙设备（Android 12+ 需要 BLUETOOTH_CONNECT 权限）
            try {
                val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
                if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            Log.d(TAG, "缺少 BLUETOOTH_CONNECT 权限，跳过蓝牙设备枚举")
                        } else {
                            enumerateBluetoothDevices(bluetoothAdapter)
                        }
                    } else {
                        enumerateBluetoothDevices(bluetoothAdapter)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "蓝牙设备枚举失败（忽略）", t)
            }
            
            Log.d(TAG, "音频设备枚举完成: 录音设备=${recordingDevices.size}, 播放设备=${playbackDevices.size}")
        } catch (t: Throwable) {
            Log.e(TAG, "音频设备枚举失败", t)
            recordingDevices.add(AudioDeviceInfo("default", "默认麦克风"))
            playbackDevices.add(AudioDeviceInfo("default", "默认扬声器"))
        }
    }

    private fun enumerateBluetoothDevices(bluetoothAdapter: BluetoothAdapter) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "缺少 BLUETOOTH_CONNECT 权限，跳过蓝牙 ProfileProxy")
                    return
                }
            }
            bluetoothAdapter.getProfileProxy(
                context,
                object : android.bluetooth.BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                    Log.d(TAG, "回调时缺少 BLUETOOTH_CONNECT 权限，跳过")
                                    return
                                }
                            }
                            if (profile == android.bluetooth.BluetoothProfile.HEADSET) {
                                val headset = proxy as? BluetoothHeadset
                                if (headset != null && headset.connectedDevices.isNotEmpty()) {
                                    playbackDevices.add(AudioDeviceInfo("bluetooth", "蓝牙耳机"))
                                    recordingDevices.add(AudioDeviceInfo("bluetooth", "蓝牙麦克风"))
                                }
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "蓝牙设备枚举回调异常（忽略）", t)
                        }
                    }
                    override fun onServiceDisconnected(profile: Int) {}
                },
                android.bluetooth.BluetoothProfile.HEADSET
            )
        } catch (t: Throwable) {
            Log.w(TAG, "蓝牙 ProfileProxy 请求失败", t)
        }
    }
    
    // ==================== 音频路由控制 ====================
    
    fun setEnableSpeakerphone(enabled: Boolean) {
        isSpeakerphoneEnabled.set(enabled)
        audioManager?.let {
            it.mode = AudioManager.MODE_IN_COMMUNICATION
            it.isSpeakerphoneOn = enabled
            Log.d(TAG, "扬声器状态: $enabled")
        }
        publishAudioRoute()
    }
    
    fun setDefaultAudioRouteToSpeakerphone(enabled: Boolean) {
        audioManager?.let {
            it.mode = AudioManager.MODE_IN_COMMUNICATION
            it.isSpeakerphoneOn = enabled
            isSpeakerphoneEnabled.set(enabled)
            Log.d(TAG, "默认音频路由设置为扬声器: $enabled")
        }
        publishAudioRoute()
    }
    
    fun isSpeakerphoneEnabled(): Boolean {
        return isSpeakerphoneEnabled.get()
    }

    fun getAudioRoute(): Int = publishedAudioRoute
    
    // ==================== 音频控制 ====================
    
    fun enableLocalAudio(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled && !localAudioMuted && clientRole.canPublish())
        Log.d(TAG, "启用/禁用本地音频: $enabled")
    }
    
    fun sendChannelMessage(message: String) {
        if (!isJoined.get()) {
            Log.w(TAG, "未加入频道，无法发送频道消息")
            return
        }
        signalingClient?.sendChannelMessage(message)
    }

    fun muteLocalAudio(muted: Boolean) {
        try {
            localAudioMuted = muted
            localAudioTrack?.setEnabled(!muted && clientRole.canPublish())
            eventHandler?.onLocalAudioStateChanged(if (muted) "muted" else "recording", "")
            publishClientMute("audio", muted)
            Log.d(TAG, "本地音频静音: $muted")
        } catch (e: Exception) {
            Log.e(TAG, "设置本地音频静音状态失败", e)
        }
    }

    fun isLocalAudioMuted(): Boolean = localAudioMuted

    fun isLocalVideoMuted(): Boolean = localVideoMuted

    /** 本端听不到该用户：本端静音了他，或对端自己静音（user-media 通知）。与 iOS 相同。 */
    fun isRemoteAudioMuted(uid: String): Boolean {
        return allRemoteAudioMuted || remoteAudioMuted[uid] == true || remoteSelfAudioMuted[uid] == true
    }

    /** 本端看不到该用户视频：本端静音了他，或对端自己关了视频。与 iOS 相同。 */
    fun isRemoteVideoMuted(uid: String): Boolean {
        return allRemoteVideoMuted || videoMutedStates[uid] == true || remoteSelfVideoMuted[uid] == true
    }
    
    fun muteRemoteAudioStream(uid: String, muted: Boolean) {
        remoteAudioMuted[uid] = muted
        userVolumes[uid] = if (muted) 0 else 100
        applyRemoteAudio(uid)
        Log.d(TAG, "远端用户 $uid 音频静音: $muted")
    }
    
    fun muteAllRemoteAudioStreams(muted: Boolean) {
        allRemoteAudioMuted = muted
        playbackVolume = if (muted) 0 else 100
        remoteAudioTracks.keys.forEach { applyRemoteAudio(it) }
        peerConnections.values.forEach { pc ->
            try {
                pc.setAudioPlayout(!muted)
            } catch (e: Exception) {
                Log.w(TAG, "setAudioPlayout 失败", e)
            }
        }
        Log.d(TAG, "所有远端音频静音: $muted")
    }
    
    fun adjustUserPlaybackSignalVolume(uid: String, volume: Int) {
        userVolumes[uid] = volume.coerceIn(0, 100)
        applyRemoteAudio(uid)
        Log.d(TAG, "用户 $uid 音量调整为: $volume")
    }
    
    fun adjustPlaybackSignalVolume(volume: Int) {
        playbackVolume = volume.coerceIn(0, 100)
        audioTrack?.setVolume(playbackVolume / 100f)
        remoteAudioTracks.keys.forEach { applyRemoteAudio(it) }
        Log.d(TAG, "播放音量调整为: $volume")
    }
    
    // ==================== Token刷新 ====================
    
    fun renewToken(token: String) {
        if (token.isBlank()) {
            eventHandler?.onError(RtcErrorCode.INVALID_ARGUMENT, "token 不能为空")
            return
        }
        // 媒体 PeerConnection 保持不动。信令 URL 的 ?token= 必须换成新 Token，
        // 否则 onTokenPrivilegeWillExpire 之后服务端会拒绝这条 WebSocket。
        currentToken = token
        val client = signalingClient
        if (client != null && isJoined.get()) {
            client.renewToken(token)
            scheduleTokenPrivilegeWatch(token)
        }
        Log.d(TAG, "更新Token: len=${token.length}")
    }

    private fun cancelTokenPrivilegeWatch() {
        tokenWarnTask?.let { mainHandler.removeCallbacks(it) }
        tokenExpireTask?.let { mainHandler.removeCallbacks(it) }
        tokenWarnTask = null
        tokenExpireTask = null
    }

    /**
     * Token 里带过期时间（服务端 `expireAt` 或 JWT `exp`）时，过期前 30 秒回调
     * `onTokenPrivilegeWillExpire`，到期回调 `onRequestToken`。与 iOS 相同。
     */
    private fun scheduleTokenPrivilegeWatch(token: String) {
        cancelTokenPrivilegeWatch()
        val exp = TokenExpiry.expireAtSeconds(token) ?: return
        val (warnDelay, expireDelay) = TokenExpiry.delaysMs(exp, System.currentTimeMillis())
        if (expireDelay <= 0) {
            mainHandler.post { if (isJoined.get()) eventHandler?.onRequestToken() }
            return
        }
        val warn = Runnable { if (isJoined.get()) eventHandler?.onTokenPrivilegeWillExpire() }
        val expired = Runnable { if (isJoined.get()) eventHandler?.onRequestToken() }
        tokenWarnTask = warn
        tokenExpireTask = expired
        mainHandler.postDelayed(warn, warnDelay)
        mainHandler.postDelayed(expired, expireDelay)
    }

    /**
     * 切换画质档位，对齐控制面 qualityTier：audio|sd|hd|fhd。
     * 只改本地编码；计费档位以 Token 里的 qualityTier 为准，换档后请用新 Token 调 [renewToken]。
     * @return 0 成功，-1 未知档位
     */
    fun setVideoQuality(tier: VideoQualityTier): Int {
        if (tier == VideoQualityTier.AUDIO) {
            if (isVideoEnabled.get()) {
                muteLocalVideoStream(true)
            }
            Log.d(TAG, "画质切换: audio")
            return 0
        }
        setVideoEncoderConfiguration(tier.width, tier.height, tier.frameRate, tier.bitrateKbps)
        Log.d(TAG, "画质切换: ${tier.apiValue} ${tier.width}x${tier.height}")
        return 0
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
        localAudioTrack?.setEnabled(true)
        Log.d(TAG, "启用音频模块")
    }
    
    fun disableAudio() {
        localAudioTrack?.setEnabled(false)
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
                    val frontCameraName = deviceNames.find { cameraEnumerator.isFrontFacing(it) == preferFrontCamera } ?: deviceNames[0]
                    usingFrontCamera = cameraEnumerator.isFrontFacing(frontCameraName)
                    videoCapturer = cameraEnumerator.createCapturer(frontCameraName, null) as? CameraVideoCapturer
                    val egl = eglBase?.eglBaseContext
                    val videoSource = peerConnectionFactory?.createVideoSource(false)
                    if (videoSource != null) {
                        localVideoSource = videoSource
                        installFrameProcessor(videoSource)
                    }
                    if (egl != null && videoSource != null) {
                        videoCapturer?.initialize(
                            SurfaceTextureHelper.create("CaptureThread", egl),
                            context,
                            videoSource.capturerObserver
                        )
                        videoCapturer?.startCapture(
                            currentVideoConfig?.width ?: 640,
                            currentVideoConfig?.height ?: 480,
                            currentVideoConfig?.frameRate ?: 30
                        )
                        val track = peerConnectionFactory?.createVideoTrack("video_track", videoSource)
                        replaceLocalVideoTrack(track)
                        if (localVideoMuted) track?.setEnabled(false)
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        bindLocalVideoToView(viewId)
                    }
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
            if (!isScreenCapturing.get()) {
                localVideoSource?.dispose()
                localVideoSource = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "停止摄像头预览失败", e)
        }
    }
    
    fun muteLocalVideoStream(muted: Boolean) {
        localVideoMuted = muted
        videoMutedStates["local"] = muted
        localVideoTrack?.setEnabled(!muted && clientRole.canPublish())
        eventHandler?.onLocalVideoStateChanged(if (muted) "muted" else "capturing", "")
        publishClientMute("video", muted)
        Log.d(TAG, "本地视频静音: $muted")
    }
    
    fun muteRemoteVideoStream(uid: String, muted: Boolean) {
        videoMutedStates[uid] = muted
        applyRemoteVideo(uid)
        eventHandler?.onRemoteVideoStateChanged(uid, if (muted) "muted" else "decoding", "local-mute", 0)
        Log.d(TAG, "远端用户 $uid 视频静音: $muted")
    }
    
    fun muteAllRemoteVideoStreams(muted: Boolean) {
        allRemoteVideoMuted = muted
        val uids = (videoViews.keys + remoteVideoTracks.keys).filter { it != "local" }
        uids.forEach { uid ->
            applyRemoteVideo(uid)
        }
        Log.d(TAG, "所有远端视频静音: $muted")
    }
    
    fun setupLocalVideo(viewId: Int) {
        videoViews["local"] = viewId
        Log.d(TAG, "设置本地视频视图: $viewId")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            bindLocalVideoToView(viewId)
        }
    }

    /** 将本地预览绑定到 Activity 中 id=viewId 的 ViewGroup（如 FrameLayout）。 */
    private fun bindLocalVideoToView(viewId: Int) {
        try {
            val activity = context as? android.app.Activity ?: return
            val container = activity.findViewById<android.view.ViewGroup>(viewId) ?: return
            bindLocalVideoToContainer(container)
            Log.d(TAG, "本地视频已绑定到视图: $viewId")
        } catch (e: Exception) {
            Log.e(TAG, "绑定本地视频视图失败", e)
        }
    }

    fun setupLocalVideo(container: android.view.ViewGroup) {
        videoViews["local"] = container.id
        Log.d(TAG, "设置本地视频 ViewGroup")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            bindLocalVideoToContainer(container)
        }
    }

    private fun bindLocalVideoToContainer(container: android.view.ViewGroup) {
        try {
            localRenderer?.let { old ->
                try { localVideoTrack?.removeSink(old) } catch (_: Exception) {}
                try { old.release() } catch (_: Exception) {}
                try { (old.parent as? android.view.ViewGroup)?.removeView(old) } catch (_: Exception) {}
            }
            val renderer = org.webrtc.SurfaceViewRenderer(container.context)
            renderer.init(eglBase?.eglBaseContext, null)
            renderer.setMirror(true)
            renderer.setEnableHardwareScaler(true)
            container.removeAllViews()
            container.addView(
                renderer,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            localRenderer = renderer
            localVideoTrack?.addSink(renderer)
            Log.d(TAG, "本地视频已绑定到 ViewGroup")
        } catch (e: Exception) {
            Log.e(TAG, "绑定本地视频 ViewGroup 失败", e)
        }
    }
    
    private fun bindRemoteVideoToView(uid: String, remoteTrack: org.webrtc.VideoTrack, viewId: Int) {
        try {
            val activity = context as? android.app.Activity ?: return
            val container = activity.findViewById<android.view.ViewGroup>(viewId) ?: return
            bindRemoteVideoToContainer(uid, remoteTrack, container)
        } catch (e: Exception) {
            Log.e(TAG, "绑定远端视频视图失败", e)
        }
    }

    private fun bindRemoteVideoToContainer(uid: String, remoteTrack: org.webrtc.VideoTrack, container: android.view.ViewGroup) {
        try {
            remoteRenderers[uid]?.let { old ->
                try { remoteTrack.removeSink(old) } catch (_: Exception) {}
                try { old.release() } catch (_: Exception) {}
                try { (old.parent as? android.view.ViewGroup)?.removeView(old) } catch (_: Exception) {}
            }
            val renderer = org.webrtc.SurfaceViewRenderer(container.context)
            renderer.init(eglBase?.eglBaseContext, null)
            renderer.setZOrderMediaOverlay(true)
            remoteTrack.addSink(renderer)
            videoMutedStates[uid]?.let { muted -> remoteTrack.setEnabled(!muted) }
            container.removeAllViews()
            container.addView(renderer, android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            remoteRenderers[uid] = renderer
            Log.d(TAG, "远端视频已绑定到 ViewGroup: uid=$uid")
        } catch (e: Exception) {
            Log.e(TAG, "绑定远端视频 ViewGroup 失败", e)
        }
    }
    
    fun setupRemoteVideo(uid: String, viewId: Int) {
        videoViews[uid] = viewId
        Log.d(TAG, "设置远端视频视图: uid=$uid, viewId=$viewId")
        val remoteTrack = remoteVideoTracks[uid]
        if (remoteTrack != null) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                bindRemoteVideoToView(uid, remoteTrack, viewId)
            }
        } else {
            Log.d(TAG, "远端轨道尚未到达，将在 onAddTrack 时自动绑定: uid=$uid")
        }
    }

    fun setupRemoteVideo(uid: String, container: android.view.ViewGroup) {
        videoViews[uid] = container.id
        Log.d(TAG, "设置远端视频 ViewGroup: uid=$uid")
        val remoteTrack = remoteVideoTracks[uid]
        // remember container for late bind
        pendingRemoteContainers[uid] = java.lang.ref.WeakReference(container)
        if (remoteTrack != null) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                bindRemoteVideoToContainer(uid, remoteTrack, container)
            }
        } else {
            Log.d(TAG, "远端轨道尚未到达，将在 onAddTrack 时自动绑定 ViewGroup: uid=$uid")
        }
    }
    
    // ==================== 屏幕共享 ====================
    
    fun setScreenCaptureIntent(permissionResult: Intent) {
        screenCaptureIntent = permissionResult
    }

    fun startScreenCapture(permissionResult: Intent, config: ScreenCaptureConfiguration): Int {
        screenCaptureIntent = permissionResult
        return startScreenCapture(config)
    }

    fun startScreenCapture(config: ScreenCaptureConfiguration): Int {
        if (isScreenCapturing.get() || screenCaptureStarting.get()) {
            Log.w(TAG, "屏幕共享已在进行中")
            return -1
        }
        val intent = screenCaptureIntent
        if (intent == null) {
            Log.w(TAG, "屏幕共享缺少 MediaProjection 授权")
            eventHandler?.onError(RtcErrorCode.SCREEN_SHARE, "屏幕共享需要授权 Intent，请调用 startScreenCapture(intent, config)")
            return -1
        }
        val factory = peerConnectionFactory
        val egl = eglBase?.eglBaseContext
        if (factory == null || egl == null) {
            eventHandler?.onError(RtcErrorCode.SCREEN_SHARE, "WebRTC 未就绪，无法共享屏幕")
            return -1
        }
        if (ScreenCaptureService.enabled && ScreenCaptureService.isRequired()) {
            // Android 10+：先让 mediaProjection 前台服务进入前台，再创建 MediaProjection。
            // 返回 0 表示已提交；真正开始采集时回调 onLocalVideoStateChanged("screen_capturing")，失败回调 onError(1006)。
            val appContext = context.applicationContext
            screenCaptureStarting.set(true)
            ScreenCaptureService.start(appContext) { error ->
                mainHandler.post {
                    if (!screenCaptureStarting.getAndSet(false)) {
                        // 期间已调用 stopScreenCapture。
                        ScreenCaptureService.stop(appContext)
                        return@post
                    }
                    if (error != null) {
                        ScreenCaptureService.stop(appContext)
                        eventHandler?.onError(RtcErrorCode.SCREEN_SHARE, "屏幕共享前台服务启动失败: ${error.message}")
                    } else if (startScreenCaptureNow(intent, config) != 0) {
                        ScreenCaptureService.stop(appContext)
                    }
                }
            }
            return 0
        }
        return startScreenCaptureNow(intent, config)
    }

    private fun startScreenCaptureNow(intent: Intent, config: ScreenCaptureConfiguration): Int {
        val factory = peerConnectionFactory
        val egl = eglBase?.eglBaseContext
        if (factory == null || egl == null) {
            eventHandler?.onError(RtcErrorCode.SCREEN_SHARE, "WebRTC 未就绪，无法共享屏幕")
            return -1
        }
        return try {
            stopCameraOnly()
            val displayMetrics = context.resources.displayMetrics
            val width = config.width.takeIf { it > 0 } ?: displayMetrics.widthPixels
            val height = config.height.takeIf { it > 0 } ?: displayMetrics.heightPixels
            val fps = config.frameRate.takeIf { it > 0 } ?: 15
            val capturer = ScreenCapturerAndroid(intent, object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post { stopScreenCapture() }
                }
            })
            val source = factory.createVideoSource(true)
            source.setIsScreencast(true)
            localVideoSource = source
            screenVideoSource = source
            installFrameProcessor(source)
            capturer.initialize(
                SurfaceTextureHelper.create("ScreenCapture", egl),
                context,
                source.capturerObserver
            )
            capturer.startCapture(width, height, fps)
            screenCapturer = capturer
            val track = factory.createVideoTrack("screen_track", source)
            replaceLocalVideoTrack(track)
            screenCaptureConfig = config
            isScreenCapturing.set(true)
            eventHandler?.onLocalVideoStateChanged("screen_capturing", "")
            Log.d(TAG, "屏幕共享已启动: ${width}x${height} @${fps}fps")
            0
        } catch (e: Exception) {
            Log.e(TAG, "启动屏幕共享失败", e)
            isScreenCapturing.set(false)
            eventHandler?.onError(RtcErrorCode.SCREEN_SHARE, e.message ?: "startScreenCapture failed")
            -1
        }
    }
    
    fun stopScreenCapture() {
        if (screenCaptureStarting.getAndSet(false)) {
            ScreenCaptureService.stop(context.applicationContext)
            Log.d(TAG, "屏幕共享在前台服务就绪前被取消")
            return
        }
        if (!isScreenCapturing.get()) {
            Log.w(TAG, "屏幕共享未在进行中")
            return
        }
        
        isScreenCapturing.set(false)
        Log.d(TAG, "停止屏幕共享")
        
        try {
            screenCapturer?.stopCapture()
            screenCapturer?.dispose()
            screenCapturer = null
            virtualDisplay?.release()
            virtualDisplay = null
            screenCaptureSurface?.release()
            screenCaptureSurface = null
            mediaProjection?.stop()
            mediaProjection = null
            screenVideoSource?.dispose()
            screenVideoSource = null
            screenCaptureConfig = null
            eventHandler?.onLocalVideoStateChanged("stopped", "")
            Log.d(TAG, "屏幕录制已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止屏幕共享失败", e)
        } finally {
            if (ScreenCaptureService.enabled) {
                ScreenCaptureService.stop(context.applicationContext)
            }
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
            val displayMetrics = context.resources.displayMetrics
            val width = config.width.takeIf { it > 0 } ?: displayMetrics.widthPixels
            val height = config.height.takeIf { it > 0 } ?: displayMetrics.heightPixels
            val fps = config.frameRate.takeIf { it > 0 } ?: 15
            screenCapturer?.changeCaptureFormat(width, height, fps)
            Log.d(TAG, "屏幕共享配置已更新")
        } catch (e: Exception) {
            Log.e(TAG, "更新屏幕共享配置失败", e)
        }
    }
    
    // ==================== 视频增强 ====================
    
    fun setBeautyEffectOptions(options: BeautyOptions) {
        beautyOptions = options
        Log.d(TAG, "设置美颜选项: enabled=${options.enabled}, lightening=${options.lighteningLevel}")
        localVideoSource?.let { installFrameProcessor(it) }
    }

    fun setVideoFrameProcessor(processor: VideoFrameProcessor?) {
        videoFrameProcessor = processor
        localVideoSource?.let { installFrameProcessor(it) }
    }

    fun enableCustomVideoCapture(enabled: Boolean): Int {
        val factory = peerConnectionFactory ?: return -1
        if (!enabled) {
            customVideoCapture = false
            return 0
        }
        return try {
            stopCameraOnly()
            val source = factory.createVideoSource(false)
            localVideoSource = source
            installFrameProcessor(source)
            val track = factory.createVideoTrack("custom_video", source)
            replaceLocalVideoTrack(track)
            customVideoCapture = true
            eventHandler?.onLocalVideoStateChanged("custom_capture", "")
            0
        } catch (e: Exception) {
            Log.e(TAG, "开启自定义采集失败", e)
            -1
        }
    }

    fun pushExternalVideoFrame(frame: VideoFrame): Int {
        if (!customVideoCapture) return -1
        val source = localVideoSource ?: return -1
        return try {
            source.capturerObserver.onFrameCaptured(frame)
            0
        } catch (e: Exception) {
            Log.e(TAG, "推送外部视频帧失败", e)
            -1
        }
    }

    /**
     * 选择前置或后置摄像头。摄像头未启动时只记录偏好，启动时生效。
     * 自定义采集或屏幕共享中返回 -1。
     */
    fun useFrontCamera(front: Boolean): Int {
        preferFrontCamera = front
        if (customVideoCapture || isScreenCapturing.get()) return -1
        if (videoCapturer == null || usingFrontCamera == front) return 0
        return switchCamera()
    }

    fun switchCamera(): Int {
        if (customVideoCapture || isScreenCapturing.get()) return -1
        val capturer = videoCapturer ?: return -1
        return try {
            capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                    usingFrontCamera = isFrontCamera
                    preferFrontCamera = isFrontCamera
                    eventHandler?.onLocalVideoStateChanged(if (isFrontCamera) "front_camera" else "back_camera", "")
                }
                override fun onCameraSwitchError(errorDescription: String?) {
                    eventHandler?.onError(RtcErrorCode.CAMERA, errorDescription ?: "switchCamera failed")
                }
            })
            0
        } catch (e: Exception) {
            Log.e(TAG, "切换摄像头失败", e)
            eventHandler?.onError(RtcErrorCode.CAMERA, e.message ?: "switchCamera failed")
            -1
        }
    }

    /**
     * 广播流附加信息（`sy-extra:` 前缀的频道消息，与 iOS 同格式）。
     * 超过 1024 字节返回 -2；未进房返回 -1（已保存，进房后对新成员补发）。
     */
    fun setStreamExtraInfo(extra: String): Int {
        if (extra.toByteArray(Charsets.UTF_8).size > WireProtocol.MAX_STREAM_EXTRA_BYTES) return -2
        streamExtraInfo = extra
        if (!isJoined.get()) return -1
        signalingClient?.sendChannelMessage(WireProtocol.encodeStreamExtra(extra))
        return 0
    }

    fun getStreamExtraInfo(): String = streamExtraInfo
    
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
    
    @Volatile private var callRecorder: CallAudioRecorder? = null

    fun startAudioRecording(config: AudioRecordingConfiguration): Int {
        if (audioRecorder != null || callRecorder != null) {
            Log.w(TAG, "音频录制已在进行中")
            return -1
        }
        val format = RecordingFormat.fromCodec(config.codecType)
        if (format == null) {
            eventHandler?.onError(RtcErrorCode.INVALID_ARGUMENT, "不支持的录音格式 ${config.codecType}，可选 aac（.m4a）或 wav")
            return -1
        }
        if (config.filePath.isBlank() || config.sampleRate !in 8000..48000) {
            eventHandler?.onError(RtcErrorCode.INVALID_ARGUMENT, "filePath 不能为空，sampleRate 需在 8000–48000")
            return -1
        }
        audioRecordingConfig = config
        val file = java.io.File(config.filePath)
        if (isJoined.get() && localAudioTrack != null) {
            // 频道内：用 WebRTC 已有 PCM，不另开麦克风（另开会与 WebRTC 抢采集，部分机型录到静音）。
            return try {
                val rec = CallAudioRecorder(file, format, config.sampleRate, config.aacBitrate)
                rec.start()
                callRecorder = rec
                Log.d(TAG, "通话录音开始: ${file.absolutePath} $format ${config.sampleRate}Hz local=${config.includeLocal} remote=${config.includeRemote}")
                0
            } catch (e: Exception) {
                Log.e(TAG, "启动通话录音失败", e)
                callRecorder = null
                -1
            }
        }
        if (format != RecordingFormat.AAC_M4A) {
            eventHandler?.onError(RtcErrorCode.INVALID_ARGUMENT, "未加入频道时只支持 aac")
            return -1
        }
        return try {
            val recorder = android.media.MediaRecorder()
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(config.sampleRate)
            recorder.setAudioChannels(1)
            recorder.setAudioEncodingBitRate(config.aacBitrate)
            file.parentFile?.mkdirs()
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            audioRecorder = recorder
            Log.d(TAG, "麦克风录音开始（未在频道内）: ${file.absolutePath}")
            0
        } catch (e: Exception) {
            Log.e(TAG, "启动音频录制失败", e)
            audioRecorder = null
            -1
        }
    }

    /** AudioTrackSink 回调里调用：通话录音进行中时把 PCM 送进混音器。 */
    private fun feedCallRecorder(sourceId: String, bytes: ByteArray, sampleRate: Int, channels: Int) {
        val rec = callRecorder ?: return
        val cfg = audioRecordingConfig ?: return
        val isLocal = sourceId == CallAudioRecorder.LOCAL_SOURCE
        if ((isLocal && !cfg.includeLocal) || (!isLocal && !cfg.includeRemote)) return
        if (!isLocal && (allRemoteAudioMuted || remoteAudioMuted[sourceId] == true)) return
        rec.push(sourceId, bytes, sampleRate, channels)
    }

    fun stopAudioRecording() {
        val rec = callRecorder
        if (rec != null) {
            callRecorder = null
            audioRecordingConfig = null
            try { rec.stop() } catch (e: Exception) { Log.e(TAG, "停止通话录音失败", e) }
            Log.d(TAG, "通话录音已停止")
            return
        }
        if (audioRecorder == null) {
            Log.w(TAG, "音频录制未在进行中")
            return
        }
        try {
            audioRecorder?.stop()
            audioRecorder?.release()
            Log.d(TAG, "音频录制已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止音频录制失败", e)
        }
        audioRecorder = null
        audioRecordingConfig = null
    }
    
    // ==================== 网络质量 ====================
    
    fun getNetworkQuality(): NetworkQuality = lastNetwork
    
    // ==================== 数据流 ====================
    
    fun createDataStream(reliable: Boolean, ordered: Boolean): Int {
        val streamId = nextStreamId.incrementAndGet()
        streamSpecs[streamId] = StreamSpec(reliable, ordered)
        dataStreams[streamId] = true
        peerConnections.keys.filter { it != "default" }.forEach { uid ->
            val created = ensureOutgoingDataChannels(uid)
            val negotiated = offerSentByUid[uid]?.get() == true || remoteSdpSetByUid[uid]?.get() == true
            if (created && negotiated) renegotiate(uid)
        }
        Log.d(TAG, "创建数据流: streamId=$streamId, reliable=$reliable, ordered=$ordered")
        return streamId
    }
    
    fun sendStreamMessage(streamId: Int, data: ByteArray) {
        sendOnDataChannels(streamId, data, binary = false)
    }

    fun sendSei(streamId: Int, data: ByteArray): Int {
        return sendOnDataChannels(streamId, DataFrame.wrapSei(data), binary = true)
    }
    
    
    // ==================== 清理 ====================
    
    fun release() {
        stopStatsPoll()
        volumeIndicationRunnable?.let { volumeIndicationHandler?.removeCallbacks(it) }
        signalingRetry?.let { mainHandler.removeCallbacks(it) }
        // 停止音频混音
        stopAudioMixing()
        
        // 停止所有音效
        stopAllEffects()
        
        // 停止音频录制
        stopAudioRecording()
        
        // 停止屏幕共享（含尚在等待前台服务的请求）
        if (isScreenCapturing.get() || screenCaptureStarting.get()) {
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
        eglBase?.release()
        eglBase = null
        
        // 释放屏幕共享资源
        virtualDisplay?.release()
        screenCaptureSurface?.release()
        mediaProjection?.stop()
        
        // 释放数据流资源
        dataChannelMap.values.forEach { it.close() }
        dataChannelMap.clear()
        dataChannels.values.forEach { map -> map.values.forEach { it.close() } }
        dataChannels.clear()
        
        // 释放远端视频轨道与渲染器
        remoteFrameSinks.keys.toList().forEach { detachRemoteFrameSink(it) }
        remoteVideoTracks.values.forEach { it.dispose() }
        remoteVideoTracks.clear()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                localRenderer?.let { r ->
                    try { localVideoTrack?.removeSink(r) } catch (_: Exception) {}
                    r.release()
                }
            } catch (_: Exception) {}
            localRenderer = null
            remoteRenderers.values.forEach { it.release() }
            remoteRenderers.clear()
        }
        
        // 释放PeerConnection
        peerConnections.values.forEach { it.dispose() }
        peerConnections.clear()
        
        // 清理所有状态
        effects.clear()
        effectPlayers.clear()
        videoViews.clear()
        userVolumes.clear()
        videoMutedStates.clear()
        dataStreams.clear()
        
        Log.d(TAG, "所有资源已释放")
    }
    
    // ==================== 端上能力 ====================

    private fun handleSignalingFailure() {
        if (!isJoined.get()) return
        if (!signalingRetryPosted.compareAndSet(false, true)) return
        val decision = reconnectTracker.onTransportLost()
        eventHandler?.onConnectionStateChanged(decision.state, "signaling")
        if (!decision.shouldRetry) {
            signalingRetryPosted.set(false)
            eventHandler?.onReconnectFailed("signaling")
            eventHandler?.onError(RtcErrorCode.RECONNECT_FAILED, "信令重连失败")
            return
        }
        val delayMs = decision.delayMs
        eventHandler?.onReconnecting("signaling", decision.attempt, ReconnectPolicy.MAX_ATTEMPTS, delayMs)
        val task = Runnable {
            signalingRetryPosted.set(false)
            if (isJoined.get()) signalingClient?.connect()
        }
        signalingRetry = task
        mainHandler.postDelayed(task, delayMs)
    }

    /**
     * 某个对端 ICE 断开：计一次重连（与信令共用次数），按 [ReconnectPolicy] 等待后若仍未恢复再计下一次。
     * 字典序较小的一方 restartIce 并重发 offer（与首次 offer 的发起方相同，避免 glare）。
     */
    private fun handleIceLost(remoteUid: String) {
        if (!isJoined.get()) return
        if (peerConnections[remoteUid] == null) return
        iceLostPeers.add(remoteUid)
        if (!iceRecoveryPending.compareAndSet(false, true)) {
            // 已在恢复中：这个对端也重启 ICE，但不额外计次。
            restartIceFor(remoteUid)
            return
        }
        val decision = reconnectTracker.onTransportLost()
        eventHandler?.onConnectionStateChanged(decision.state, "ice")
        if (!decision.shouldRetry) {
            iceRecoveryPending.set(false)
            iceLostPeers.clear()
            eventHandler?.onReconnectFailed("ice")
            eventHandler?.onError(RtcErrorCode.RECONNECT_FAILED, "媒体连接重连失败")
            return
        }
        eventHandler?.onReconnecting("ice", decision.attempt, ReconnectPolicy.MAX_ATTEMPTS, decision.delayMs)
        iceLostPeers.toList().forEach(::restartIceFor)
        iceRetry?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            iceRetry = null
            if (!isJoined.get() || !iceRecoveryPending.get()) return@Runnable
            iceLostPeers.retainAll(peerConnections.keys)
            iceRecoveryPending.set(false)
            val next = iceLostPeers.firstOrNull() ?: return@Runnable
            handleIceLost(next)
        }
        iceRetry = task
        mainHandler.postDelayed(task, decision.delayMs)
    }

    private fun restartIceFor(remoteUid: String) {
        val pc = peerConnections[remoteUid] ?: return
        try {
            pc.restartIce()
        } catch (e: Exception) {
            Log.w(TAG, "restartIce 失败", e)
        }
        if (shouldInitiateOffer(currentUid, remoteUid)) {
            mainHandler.post { if (isJoined.get()) renegotiate(remoteUid) }
        }
    }

    private fun handleIceRecovered(remoteUid: String) {
        iceLostPeers.remove(remoteUid)
        if (iceLostPeers.isNotEmpty()) return
        val wasRecovering = iceRecoveryPending.getAndSet(false)
        iceRetry?.let { mainHandler.removeCallbacks(it) }
        iceRetry = null
        if (!reconnectTracker.hasJoined()) return
        val elapsed = (System.currentTimeMillis() - joinStartTime).toInt().coerceAtLeast(0)
        if (reconnectTracker.onConnected() == RejoinSignal.REJOINED) {
            eventHandler?.onRejoinChannelSuccess(currentChannelId ?: "", currentUid ?: "", elapsed)
            eventHandler?.onConnectionStateChanged("connected", "rejoin_success")
            eventHandler?.onReconnected(if (wasRecovering) "ice" else "signaling")
        }
    }

    private fun publishAudioRoute() {
        val (wired, bluetooth) = playbackRouteFlags()
        val route = AudioRoute.resolve(isSpeakerphoneEnabled.get(), wired, bluetooth)
        if (route == publishedAudioRoute) return
        publishedAudioRoute = route
        eventHandler?.onAudioRoutingChanged(route)
    }

    private fun playbackRouteFlags(): Pair<Boolean, Boolean> {
        val am = audioManager ?: return false to false
        var wired = am.isWiredHeadsetOn
        var bluetooth = am.isBluetoothScoOn || am.isBluetoothA2dpOn
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { device ->
                when (device.type) {
                    AndroidAudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AndroidAudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AndroidAudioDeviceInfo.TYPE_USB_HEADSET -> wired = true
                    AndroidAudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AndroidAudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> bluetooth = true
                }
            }
        }
        return wired to bluetooth
    }

    private fun publishClientMute(media: String, muted: Boolean) {
        if (!isJoined.get()) return
        if (media == "audio") {
            signalingClient?.sendUserMedia(audioMuted = muted, videoMuted = null)
        } else {
            signalingClient?.sendUserMedia(audioMuted = null, videoMuted = muted)
        }
    }

    /** 新成员进房时补发本端附加信息和静音状态，与 iOS 行为一致。 */
    private fun republishSideInfo() {
        if (!isJoined.get()) return
        if (streamExtraInfo.isNotEmpty()) {
            signalingClient?.sendChannelMessage(WireProtocol.encodeStreamExtra(streamExtraInfo))
        }
        val audioMuted = localAudioMuted || !clientRole.canPublish()
        val videoMuted = localVideoMuted || !clientRole.canPublish()
        if (audioMuted || videoMuted) {
            signalingClient?.sendUserMedia(audioMuted = audioMuted, videoMuted = videoMuted)
        }
    }

    private fun applyRemoteSelfMute(uid: String, media: String, muted: Boolean) {
        if (media == "audio") {
            remoteSelfAudioMuted[uid] = muted
            eventHandler?.onUserMuteAudio(uid, muted)
            eventHandler?.onRemoteAudioStateChanged(uid, if (muted) "muted" else "decoding", "remote-mute", 0)
        } else if (media == "video") {
            remoteSelfVideoMuted[uid] = muted
            eventHandler?.onUserMuteVideo(uid, muted)
            eventHandler?.onRemoteVideoStateChanged(uid, if (muted) "muted" else "decoding", "remote-mute", 0)
        }
    }

    private fun dispatchClientEnvelope(fromUid: String, message: String) {
        WireProtocol.decodeStreamExtra(message)?.let { extra ->
            eventHandler?.onStreamExtraInfoUpdated(fromUid, extra)
            return
        }
        // 旧 Android JSON 信封，只收不发。
        StreamExtra.decode(message)?.let { payload ->
            eventHandler?.onStreamExtraInfoUpdated(payload.uid.ifBlank { fromUid }, payload.extra)
        }
        ClientMuteNotice.decode(message)?.let { payload ->
            applyRemoteSelfMute(payload.uid.ifBlank { fromUid }, payload.media, payload.muted)
        }
    }

    private fun applyRemoteAudio(uid: String) {
        val track = remoteAudioTracks[uid] ?: return
        val muted = allRemoteAudioMuted || remoteAudioMuted[uid] == true
        track.setEnabled(!muted)
        val volume = if (muted) 0 else (userVolumes[uid] ?: playbackVolume).coerceIn(0, 100)
        try {
            track.setVolume(volume / 100.0)
        } catch (e: Exception) {
            Log.w(TAG, "设置远端音量失败", e)
        }
    }

    private fun applyRemoteVideo(uid: String) {
        val track = remoteVideoTracks[uid] ?: return
        val muted = allRemoteVideoMuted || videoMutedStates[uid] == true
        track.setEnabled(!muted)
    }

    private fun attachLocalVolumeSink() {
        val track = localAudioTrack ?: return
        val sink = AudioTrackSink { buffer, bits, rate, channels, frames, _ ->
            val bytes = copyPcm16(buffer, bits, channels, frames) ?: return@AudioTrackSink
            localPcmVolume = VolumeMeter.pcm16LeRms(bytes)
            localPcmSeen = true
            feedCallRecorder(CallAudioRecorder.LOCAL_SOURCE, bytes, rate, channels)
        }
        try {
            track.addSink(sink)
        } catch (e: Exception) {
            Log.w(TAG, "本地音量采集失败", e)
        }
    }

    private fun attachRemoteVolumeSink(uid: String, track: org.webrtc.AudioTrack) {
        val sink = AudioTrackSink { buffer, bits, rate, channels, frames, _ ->
            val bytes = copyPcm16(buffer, bits, channels, frames) ?: return@AudioTrackSink
            remotePcmVolume[uid] = VolumeMeter.pcm16LeRms(bytes)
            remotePcmSeen.add(uid)
            feedCallRecorder(uid, bytes, rate, channels)
        }
        try {
            track.addSink(sink)
        } catch (e: Exception) {
            Log.w(TAG, "远端音量采集失败", e)
        }
    }

    private fun copyPcm16(buffer: java.nio.ByteBuffer, bits: Int, channels: Int, frames: Int): ByteArray? {
        if (bits != 16 || frames <= 0 || channels <= 0) return null
        val size = bits / 8 * channels * frames
        val duplicate = buffer.duplicate()
        if (duplicate.remaining() < size) return null
        val out = ByteArray(size)
        duplicate.get(out)
        return out
    }

    /**
     * 远端视频轨挂一个常驻 sink：首帧回调 onFirstRemoteVideoDecoded / onFirstRemoteVideoFrame，
     * 首帧及之后宽高或旋转变化时回调 onVideoSizeChanged。与 iOS 相同。
     */
    private fun attachRemoteFrameSink(uid: String, track: org.webrtc.VideoTrack) {
        detachRemoteFrameSink(uid)
        val tracker = VideoFrameTracker()
        val sink = org.webrtc.VideoSink { frame ->
            val w = frame.buffer.width
            val h = frame.buffer.height
            val rot = frame.rotation
            val change = tracker.onFrame(w, h, rot)
            if (!change.first && !change.sizeChanged) return@VideoSink
            val elapsed = (System.currentTimeMillis() - joinStartTime).toInt().coerceAtLeast(0)
            mainHandler.post {
                if (!isJoined.get()) return@post
                if (change.first) {
                    eventHandler?.onFirstRemoteVideoDecoded(uid, w, h, elapsed)
                    eventHandler?.onFirstRemoteVideoFrame(uid, w, h, elapsed)
                }
                if (change.sizeChanged) eventHandler?.onVideoSizeChanged(uid, w, h, rot)
            }
        }
        try {
            track.addSink(sink)
            remoteFrameSinks[uid] = track to sink
        } catch (e: Exception) {
            Log.w(TAG, "远端首帧 sink 挂载失败: uid=$uid", e)
        }
    }

    private fun detachRemoteFrameSink(uid: String) {
        val (track, sink) = remoteFrameSinks.remove(uid) ?: return
        try { track.removeSink(sink) } catch (_: Exception) {}
    }

    /** 本地视频轨（摄像头 / 屏幕 / 自定义采集）换轨后，新轨的第一帧回调 onFirstLocalVideoFrame。 */
    private fun attachLocalFrameSink(previous: org.webrtc.VideoTrack?, track: org.webrtc.VideoTrack) {
        localFrameSink?.let { old -> try { previous?.removeSink(old) } catch (_: Exception) {} }
        val tracker = VideoFrameTracker()
        val sink = org.webrtc.VideoSink { frame ->
            val change = tracker.onFrame(frame.buffer.width, frame.buffer.height, frame.rotation)
            if (!change.first) return@VideoSink
            val w = frame.buffer.width
            val h = frame.buffer.height
            val elapsed = if (joinStartTime > 0) (System.currentTimeMillis() - joinStartTime).toInt().coerceAtLeast(0) else 0
            mainHandler.post { eventHandler?.onFirstLocalVideoFrame(w, h, elapsed) }
        }
        try {
            track.addSink(sink)
            localFrameSink = sink
        } catch (e: Exception) {
            Log.w(TAG, "本地首帧 sink 挂载失败", e)
        }
    }

    private fun replaceLocalVideoTrack(track: org.webrtc.VideoTrack?) {
        val previous = localVideoTrack
        localVideoTrack = track
        if (track == null) return
        attachLocalFrameSink(previous, track)
        peerConnections.values.forEach { pc ->
            val sender = pc.senders.firstOrNull { sender ->
                sender.track()?.kind() == "video" || (previous != null && sender.track() == previous)
            }
            if (sender != null) sender.setTrack(track, false) else pc.addTrack(track, listOf())
        }
        if (previous != null && previous !== track) {
            try {
                previous.dispose()
            } catch (_: Exception) {
            }
        }
    }

    private fun stopCameraOnly() {
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "停止摄像头失败", e)
        }
        videoCapturer = null
        isPreviewing.set(false)
    }

    private fun installFrameProcessor(source: VideoSource) {
        val processor = frameProcessor ?: LocalVideoProcessor().also { frameProcessor = it }
        source.setVideoProcessor(processor)
    }

    private fun lightenFrame(frame: VideoFrame, level: Float): VideoFrame {
        if (level <= 0f) return frame
        val i420 = try {
            frame.buffer.toI420()
        } catch (e: Exception) {
            Log.w(TAG, "读取 I420 失败", e)
            null
        } ?: return frame
        return try {
            val width = i420.width
            val height = i420.height
            val yBytes = readPlane(i420.dataY, i420.strideY, width, height)
            val lit = BeautyMath.applyLightening(yBytes, level)
            val out = JavaI420Buffer.allocate(width, height)
            writePlane(lit, width, out.dataY, out.strideY, width, height)
            val chromaWidth = (width + 1) / 2
            val chromaHeight = (height + 1) / 2
            copyPlane(i420.dataU, i420.strideU, out.dataU, out.strideU, chromaWidth, chromaHeight)
            copyPlane(i420.dataV, i420.strideV, out.dataV, out.strideV, chromaWidth, chromaHeight)
            VideoFrame(out, frame.rotation, frame.timestampNs)
        } catch (e: Exception) {
            Log.w(TAG, "提亮帧失败，保持原帧", e)
            frame
        } finally {
            i420.release()
        }
    }

    private fun readPlane(buffer: java.nio.ByteBuffer, stride: Int, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height)
        val duplicate = buffer.duplicate()
        for (row in 0 until height) {
            duplicate.position(row * stride)
            duplicate.get(out, row * width, width)
        }
        return out
    }

    private fun writePlane(
        bytes: ByteArray,
        rowWidth: Int,
        buffer: java.nio.ByteBuffer,
        stride: Int,
        width: Int,
        height: Int
    ) {
        for (row in 0 until height) {
            buffer.position(row * stride)
            buffer.put(bytes, row * rowWidth, width)
        }
    }

    private fun copyPlane(
        src: java.nio.ByteBuffer,
        srcStride: Int,
        dst: java.nio.ByteBuffer,
        dstStride: Int,
        width: Int,
        height: Int
    ) {
        val row = ByteArray(width)
        val duplicate = src.duplicate()
        for (y in 0 until height) {
            duplicate.position(y * srcStride)
            duplicate.get(row, 0, width)
            dst.position(y * dstStride)
            dst.put(row, 0, width)
        }
    }

    private fun startStatsPoll() {
        stopStatsPoll()
        val task = object : Runnable {
            override fun run() {
                if (!isJoined.get()) return
                val peers = peerConnections.filterKeys { it != "default" }
                if (peers.isEmpty()) {
                    eventHandler?.onNetworkQuality(
                        currentUid ?: "",
                        NetworkQualityEstimator.UNKNOWN,
                        NetworkQualityEstimator.UNKNOWN
                    )
                } else {
                    // 一轮收齐所有对端后再回调：本端 uid = 最差一档，然后逐个对端。与 iOS 相同。
                    val pending = java.util.concurrent.atomic.AtomicInteger(peers.size)
                    val round = ConcurrentHashMap<String, String>()
                    val finishOne = {
                        if (pending.decrementAndGet() == 0) {
                            val snapshot = HashMap(round)
                            mainHandler.post { emitNetworkQualityRound(snapshot) }
                        }
                    }
                    peers.forEach { (uid, pc) ->
                        try {
                            pc.getStats { report ->
                                try {
                                    round[uid] = handleStatsReport(uid, report)
                                } finally {
                                    finishOne()
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "getStats 失败", e)
                            round[uid] = NetworkQualityEstimator.UNKNOWN
                            finishOne()
                        }
                    }
                }
                mainHandler.postDelayed(this, 2000)
            }
        }
        statsRunnable = task
        mainHandler.postDelayed(task, 2000)
    }

    private fun stopStatsPoll() {
        statsRunnable?.let { mainHandler.removeCallbacks(it) }
        statsRunnable = null
        lastBytesSent.clear()
        lastBytesRecv.clear()
        lastStatsMs.clear()
    }

    private fun emitNetworkQualityRound(round: Map<String, String>) {
        if (!isJoined.get()) return
        val local = NetworkQualityEstimator.worst(round.values)
        eventHandler?.onNetworkQuality(currentUid ?: "", local, local)
        round.toSortedMap().forEach { (uid, q) -> eventHandler?.onNetworkQuality(uid, q, q) }
    }

    /** 解析一次对端统计，回调 onRtcStats，返回该链路质量档位。 */
    private fun handleStatsReport(uid: String, report: RTCStatsReport): String {
        val records = report.statsMap.values.map { stat -> StatRecord(stat.type, stat.members) }
        val sample = StatsParser.parse(records)
        val now = System.currentTimeMillis()
        val tx = Bitrate.bps(lastBytesSent[uid], lastStatsMs[uid], sample.bytesSent, now)
        val rx = Bitrate.bps(lastBytesRecv[uid], lastStatsMs[uid], sample.bytesReceived, now)
        sample.bytesSent?.let { lastBytesSent[uid] = it }
        sample.bytesReceived?.let { lastBytesRecv[uid] = it }
        lastStatsMs[uid] = now
        val quality = NetworkQualityEstimator.fromRttAndLoss(sample.rttMs, sample.lossPercent)
        val rank = NetworkQualityEstimator.toRank(quality)
        lastNetwork = NetworkQuality(rank, rank, (tx ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), (rx ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (!remotePcmSeen.contains(uid)) {
            sample.audioLevel?.let { remotePcmVolume[uid] = VolumeMeter.fromUnitInterval(it) }
        }
        mainHandler.post {
            if (!isJoined.get()) return@post
            val stats = linkedMapOf<String, Any?>("uid" to uid, "quality" to quality)
            sample.rttMs?.let { stats["rttMs"] = it }
            sample.lossPercent?.let {
                stats["lossPercent"] = it
                stats["packetLossRate"] = it / 100.0
            }
            tx?.let { stats["txBitrate"] = it }
            rx?.let { stats["rxBitrate"] = it }
            if (stats.size > 2) eventHandler?.onRtcStats(stats)
        }
        return quality
    }

    private fun renegotiate(remoteUid: String) {
        val channelId = currentChannelId ?: return
        offerSentByUid.computeIfAbsent(remoteUid) { AtomicBoolean(false) }.set(false)
        startOffer(remoteUid, channelId)
    }

    private fun ensureOutgoingDataChannels(remoteUid: String): Boolean {
        val pc = peerConnections[remoteUid] ?: return false
        var created = false
        streamSpecs.forEach { (streamId, spec) ->
            val perUid = dataChannels.computeIfAbsent(streamId) { ConcurrentHashMap() }
            if (perUid.containsKey(remoteUid)) return@forEach
            val init = DataChannel.Init().apply {
                ordered = spec.ordered
                if (!spec.reliable) maxRetransmits = 0
            }
            val channel = try {
                pc.createDataChannel("sy-$streamId", init)
            } catch (e: Exception) {
                Log.w(TAG, "createDataChannel 失败", e)
                null
            } ?: return@forEach
            perUid[remoteUid] = channel
            dataChannelMap[streamId] = channel
            watchDataChannel(channel, remoteUid, streamId)
            created = true
        }
        return created
    }

    private fun acceptIncomingDataChannel(remoteUid: String, channel: DataChannel?) {
        val incoming = channel ?: return
        val streamId = DataFrame.streamIdFromLabel(incoming.label()) ?: return
        watchDataChannel(incoming, remoteUid, streamId)
        dataChannels.computeIfAbsent(streamId) { ConcurrentHashMap() }.putIfAbsent(remoteUid, incoming)
        dataStreams.putIfAbsent(streamId, true)
    }

    private fun watchDataChannel(channel: DataChannel, remoteUid: String, streamId: Int) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                Log.d(TAG, "DataChannel ${channel.label()} ${channel.state()} uid=$remoteUid")
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val sei = DataFrame.unwrapSei(bytes)
                mainHandler.post {
                    eventHandler?.onStreamMessage(remoteUid, streamId, bytes)
                    if (sei != null) eventHandler?.onSeiMessage(remoteUid, streamId, sei)
                }
            }
        })
    }

    private fun sendOnDataChannels(streamId: Int, data: ByteArray, binary: Boolean): Int {
        if (!dataStreams.containsKey(streamId) && dataChannels[streamId].isNullOrEmpty()) {
            eventHandler?.onStreamMessageError("", streamId, 1, 0, 0)
            return -1
        }
        val open = dataChannels[streamId]?.values?.filter { it.state() == DataChannel.State.OPEN }.orEmpty()
        if (open.isEmpty()) {
            eventHandler?.onStreamMessageError("", streamId, 2, 0, 0)
            return -1
        }
        return try {
            open.forEach { channel ->
                channel.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(data), binary))
            }
            0
        } catch (e: Exception) {
            Log.e(TAG, "发送数据流消息失败", e)
            eventHandler?.onStreamMessageError("", streamId, 3, 0, 0)
            -1
        }
    }

    private inner class LocalVideoProcessor : VideoProcessor {
        @Volatile
        private var downstream: VideoSink? = null

        override fun onCapturerStarted(success: Boolean) {}

        override fun onCapturerStopped() {}

        override fun setSink(sink: VideoSink?) {
            downstream = sink
        }

        override fun onFrameCaptured(frame: VideoFrame) {
            val custom = videoFrameProcessor
            val options = beautyOptions
            val processed = when {
                custom != null -> custom.onFrameCaptured(frame)
                options?.enabled == true -> lightenFrame(frame, options.lighteningLevel.toFloat())
                else -> frame
            }
            downstream?.onFrame(processed)
            if (processed !== frame) processed.release()
        }
    }

    // ==================== 内部类 ====================

    private data class StreamSpec(val reliable: Boolean, val ordered: Boolean)

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

    // ---- mute/kick state poll (fallback when signalingNotified=0) ----
    private var memberPollHandler: android.os.Handler? = null
    private var memberPollRunnable: Runnable? = null
    private var roomServiceForPoll: RoomService? = null
    private var pollChannelId: String? = null
    private var pollUid: String? = null

    private fun startMemberStatePoll(channelId: String, uid: String) {
        stopMemberStatePoll()
        val base = apiBaseUrl?.takeIf { it.isNotBlank() }
        if (base == null || appId.isBlank()) {
            Log.d(TAG, "skip member state poll: apiBaseUrl/appId unset")
            return
        }
        pollChannelId = channelId
        pollUid = uid
        val svc = RoomService(base, appId).also {
            apiAuthToken?.let { t -> it.setAuthToken(t) }
            it.setUserId(uid)
        }
        roomServiceForPoll = svc
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        memberPollHandler = handler
        val runnable = object : Runnable {
            override fun run() {
                val ch = pollChannelId ?: return
                val u = pollUid ?: return
                svc.getMemberState(ch, u) { state, err ->
                    if (err != null || state == null) {
                        memberPollHandler?.postDelayed(this, 5000)
                        return@getMemberState
                    }
                    if (state.kicked) {
                        eventHandler?.onKicked(ch, state.kickReason.ifBlank { "polled-kicked" })
                        leave()
                        return@getMemberState
                    }
                    if (state.mutedAudio) {
                        localAudioTrack?.setEnabled(false)
                        eventHandler?.onServerMuteAudio(u, true)
                    }
                    if (isJoined.get()) {
                        memberPollHandler?.postDelayed(this, 5000)
                    }
                }
            }
        }
        memberPollRunnable = runnable
        handler.postDelayed(runnable, 3000)
    }

    private fun stopMemberStatePoll() {
        memberPollRunnable?.let { memberPollHandler?.removeCallbacks(it) }
        memberPollRunnable = null
        memberPollHandler = null
        roomServiceForPoll = null
        pollChannelId = null
        pollUid = null
    }


}
