package com.deltaforce.assistant

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deltaforce.assistant.ui.FloatingService
import com.deltaforce.assistant.utils.RootUtil

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DeltaForceTheme() }
        
        checkRootAccess()
        startFloatingService()
    }

    private fun checkRootAccess() {
        Thread {
            val hasRoot = RootUtil.checkRoot()
            runOnUiThread {
                if (hasRoot) {
                    Log.i("MainActivity", "Root access granted")
                } else {
                    Log.w("MainActivity", "Root access NOT granted - some features may not work")
                }
            }
        }.start()
    }

    private fun startFloatingService() {
        val intent = Intent(this, FloatingService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

@Composable
fun DeltaForceTheme() {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0E17))) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier.padding(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF111827),
                    contentColor = Color.White
                )
            ) {
                Column(modifier = Modifier.padding(32.dp)) {
                    Text(
                        text = "Delta Force Assistant",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00FF88)
                    )
                    
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 16.dp))
                    
                    Text(
                        text = "游戏进程: com.tencent.tmgp.dfm\nRoot 权限模式启动\n悬浮窗服务已在后台运行",
                        fontSize = 16.sp,
                        color = Color(0xFFD1D5DB)
                    )
                    
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 24.dp))
                    
                    Button(
                        onClick = { /* 打开设置 */ },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00FF88),
                            contentColor = Color.Black
                        )
                    ) {
                        Text("打开悬浮面板", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}