package com.sy.rtc.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.sy.rtc.sdk.RtcEngine
import com.sy.rtc.sdk.RtcEventHandler
import com.sy.rtc.sdk.RtcClientRole
import com.sy.rtc.sdk.VolumeInfo
import com.sy.rtc.sdk.VideoEncoderConfiguration

class MainActivity : AppCompatActivity() {
    private lateinit var engine: RtcEngine
    private lateinit var statusText: TextView
    private var isJoined = false
    
    private val PERMISSIONS = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.INTERNET
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        statusText = findViewById(R.id.statusText)
        val initButton: Button = findViewById(R.id.initButton)
        val joinButton: Button = findViewById(R.id.joinButton)
        val leaveButton: Button = findViewById(R.id.leaveButton)
        val enableAudioButton: Button = findViewById(R.id.enableAudioButton)
        val muteButton: Button = findViewById(R.id.muteButton)
        val enableVideoButton: Button = findViewById(R.id.enableVideoButton)
        val startPreviewButton: Button = findViewById(R.id.startPreviewButton)
        val localVideoView: SurfaceView = findViewById(R.id.localVideoView)
        val remoteVideoView: SurfaceView = findViewById(R.id.remoteVideoView)
        
        // 检查权限
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, PERMISSIONS, 100)
        }
        
        // 创建引擎
        engine = RtcEngine.create()
        
        // 设置事件处理器
        engine.setEventHandler(object : RtcEventHandler {
            override fun onUserJoined(uid: String, elapsed: Int) {
                runOnUiThread {
                    statusText.text = "用户加入: $uid (耗时: ${elapsed}ms)"
                    Toast.makeText(this@MainActivity, "用户加入: $uid", Toast.LENGTH_SHORT).show()
                    
                    // 设置远端视频视图
                    try {
                        val remoteVideoView: SurfaceView = findViewById(R.id.remoteVideoView)
                        engine.setupRemoteVideo(uid, remoteVideoView.hashCode())
                    } catch (e: Exception) {
                        // 忽略视图设置错误
                    }
                }
            }
            
            override fun onUserOffline(uid: String, reason: Int) {
                runOnUiThread {
                    statusText.text = "用户离开: $uid"
                    Toast.makeText(this@MainActivity, "用户离开: $uid", Toast.LENGTH_SHORT).show()
                }
            }
            
            override fun onVolumeIndication(speakers: List<VolumeInfo>) {
                runOnUiThread {
                    if (speakers.isNotEmpty()) {
                        val info = speakers[0]
                        statusText.text = "音量指示: ${info.uid} = ${info.volume}"
                    }
                }
            }
        })
        
        // 初始化
        initButton.setOnClickListener {
            try {
                engine.init("your_app_id")
                statusText.text = "初始化成功"
                Toast.makeText(this, "初始化成功", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                statusText.text = "初始化失败: ${e.message}"
                Toast.makeText(this, "初始化失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        
        // 加入房间
        joinButton.setOnClickListener {
            if (!isJoined) {
                joinChannel()
            }
        }
        
        // 离开房间
        leaveButton.setOnClickListener {
            if (isJoined) {
                leaveChannel()
            }
        }
        
        // 启用音频
        enableAudioButton.setOnClickListener {
            engine.enableLocalAudio(true)
            statusText.text = "音频已启用"
        }
        
        // 静音/取消静音
        muteButton.setOnClickListener {
            val muted = !engine.isLocalAudioMuted()
            engine.muteLocalAudio(muted)
            statusText.text = if (muted) "已静音" else "已取消静音"
        }
        
        // 启用视频（需要直播权限）
        enableVideoButton.setOnClickListener {
            try {
                // 检查是否有直播权限（实际应该从后端查询）
                // 这里简化处理，假设有权限
                engine.enableVideo()
                
                // 设置视频编码配置
                val config = VideoEncoderConfiguration(
                    width = 640,
                    height = 480,
                    frameRate = 15,
                    bitrate = 400
                )
                engine.setVideoEncoderConfiguration(config)
                
                statusText.text = "视频已启用"
                Toast.makeText(this, "视频已启用", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                statusText.text = "启用视频失败: ${e.message}"
                Toast.makeText(this, "启用视频失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        
        // 开始预览
        startPreviewButton.setOnClickListener {
            try {
                // 设置本地视频视图
                engine.setupLocalVideo(localVideoView.hashCode())
                
                // 开始预览
                engine.startPreview()
                
                statusText.text = "预览已开始"
                Toast.makeText(this, "预览已开始", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                statusText.text = "开始预览失败: ${e.message}"
                Toast.makeText(this, "开始预览失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun hasPermissions(): Boolean {
        return PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun joinChannel() {
        try {
            // 从服务器获取Token（实际使用时需要实现HTTP请求）
            val token = getTokenFromServer()
            
            engine.join("channel_001", "user_001", token)
            engine.enableLocalAudio(true)
            
            isJoined = true
            statusText.text = "加入房间成功"
            Toast.makeText(this, "加入房间成功", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            statusText.text = "加入房间失败: ${e.message}"
            Toast.makeText(this, "加入房间失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun leaveChannel() {
        try {
            engine.leave()
            isJoined = false
            statusText.text = "离开房间成功"
            Toast.makeText(this, "离开房间成功", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            statusText.text = "离开房间失败: ${e.message}"
            Toast.makeText(this, "离开房间失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun getTokenFromServer(): String {
        // 实际实现应该调用您的后端API获取Token
        // 示例：
        // val response = httpClient.get("https://your-api.com/api/rtc/token?channelId=channel_001&uid=user_001")
        // return response.body()?.string()?.let { JSONObject(it).getString("token") } ?: ""
        
        // 这里返回示例Token，实际使用时请替换为真实的Token获取逻辑
        return "your_token_here"
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (isJoined) {
            engine.leave()
        }
        engine.release()
    }
}
