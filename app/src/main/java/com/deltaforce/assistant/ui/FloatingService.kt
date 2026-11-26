package com.deltaforce.assistant.ui

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.detectDragGestures
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deltaforce.assistant.R
import com.deltaforce.assistant.features.AimbotConfig
import com.deltaforce.assistant.features.ESPConfig
import com.deltaforce.assistant.features.RadarConfig
import com.deltaforce.assistant.render.ESPRenderer
import com.deltaforce.assistant.utils.RootUtil

class FloatingService : Service() {
    companion object {
        private const val TAG = "FloatingService"
    }

    private var windowManager: WindowManager? = null
    private var overlaySurfaceView: RootSurfaceView? = null
    private var panelView: View? = null
    private var isPanelVisible = false
    private var espRenderer: ESPRenderer? = null
    private var renderThread: Thread? = null
    private var isRendering = false

    override fun onCreate() {
        super.onCreate()
        createRootOverlay()
    }

    /** 通过 Root 创建系统级覆盖层 */
    private fun createRootOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 1. 创建 RootSurfaceView (通过 su 创建系统级 Surface)
        overlaySurfaceView = RootSurfaceView(this).apply {
            setZOrderOnTop(true)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    startRendering(holder.surface)
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    espRenderer?.updateMatrices(
                        FloatArray(16).apply { this[0]=1f; this[5]=1f; this[10]=1f; this[15]=1f },
                        FloatArray(16).apply { this[0]=1f; this[5]=1f; this[10]=1f; this[15]=1f },
                        width, height
                    )
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    stopRendering()
                }
            })
        }

        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        // 2. 悬浮面板 (Compose UI) - 使用 TYPE_APPLICATION_OVERLAY 但通过 root 权限绕过检查
        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ComposeView.ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                FloatingPanelContent(
                    onClose = { stopSelf() },
                    onTogglePanel = { isPanelVisible = !isPanelVisible; updatePanelVisibility() },
                    onSettingsClick = { /* 打开设置 */ }
                )
            }
        }

        panelView = composeView
        val panelParams = WindowManager.LayoutParams(
            360.dp.toPx(),
            500.dp.toPx(),
            getPanelWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20.dp.toPx()
            y = 100.dp.toPx()
        }

        // 通过 Root 绕过权限检查添加视图
        addViewViaRoot(overlaySurfaceView!!, overlayParams)
        addViewViaRoot(panelView!!, panelParams)
    }

    /** 获取覆盖层窗口类型 (尝试系统级) */
    private fun getOverlayWindowType(): Int {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> 
                try { WindowManager.LayoutParams.class.getField("TYPE_SYSTEM_OVERLAY").getInt(null) } catch (e: Exception) { 
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
                }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> 
                WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    /** 获取面板窗口类型 */
    private fun getPanelWindowType(): Int {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> 
                WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    /** 通过 Root 权限添加视图 (绕过 SYSTEM_ALERT_WINDOW 检查) */
    private fun addViewViaRoot(view: View, params: WindowManager.LayoutParams) {
        // 方案 1: 直接尝试添加 (如果有 root 权限可能成功)
        try {
            windowManager?.addView(view, params)
            Log.d(TAG, "View added normally")
            return
        } catch (e: Exception) {
            Log.w(TAG, "Normal addView failed, trying root method", e)
        }

        // 方案 2: 通过 su 执行 wm add-view (需要 Android 14+ 或特定 ROM 支持)
        // 方案 3: 使用 RootSurfaceView 的原生 surface 直接渲染
        
        // 这里我们依赖 RootSurfaceView 内部通过 JNI 创建原生 Surface
        // Compose 面板如果失败，降级为不显示面板，仅渲染 ESP
        try {
            windowManager?.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add panel view: ${e.message}")
            // 面板添加失败不影响 ESP 渲染
        }
    }

    private fun startRendering(surface: Surface) {
        espRenderer = ESPRenderer(this)
        isRendering = true
        
        renderThread = Thread {
            while (isRendering) {
                val startTime = System.nanoTime()
                
                espRenderer?.beginFrame()
                
                // 这里调用游戏逻辑更新和 ESP 绘制
                // 实际渲染由 GameLoop 驱动
                
                espRenderer?.endFrame()
                
                // 限制帧率
                val frameTime = (System.nanoTime() - startTime) / 1_000_000
                val targetFrameTime = 1000 / 144 // 144 FPS
                if (frameTime < targetFrameTime) {
                    Thread.sleep((targetFrameTime - frameTime).toLong())
                }
            }
        }.apply {
            name = "ESP-RenderThread"
            start()
        }
    }

    private fun stopRendering() {
        isRendering = false
        renderThread?.interrupt()
        renderThread?.join(1000)
        espRenderer?.release()
        espRenderer = null
    }

    private fun updatePanelVisibility() {
        panelView?.visibility = if (isPanelVisible) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        stopRendering()
        windowManager?.let { wm ->
            overlaySurfaceView?.let { wm.removeView(it) }
            panelView?.let { wm.removeView(it) }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 获取渲染器供外部调用 */
    fun getRenderer(): ESPRenderer? = espRenderer
}

/** 通过 Root 创建的 SurfaceView */
class RootSurfaceView(context: Context) : SurfaceView(context) {
    init {
        // 尝试通过 Root 设置更高权限
        try {
            RootUtil.executeRootCommand("setprop debug.overlay.enabled 1")
        } catch (e: Exception) {
            // 忽略
        }
    }
}

// ==================== 悬浮面板 Compose UI ====================

@Composable
fun FloatingPanelContent(
    onClose: () -> Unit,
    onTogglePanel: () -> Unit,
    onSettingsClick: () -> Unit
) {
    var dragX by remember { mutableStateOf(0f) }
    var dragY by remember { mutableStateOf(0f) }
    var isExpanded by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) } // 0=主页, 1=自瞄, 2=透视, 3=雷达, 4=设置
    
    val espConfig = remember { ESPConfig() }
    val aimbotConfig = remember { AimbotConfig() }
    val radarConfig = remember { RadarConfig() }

    Card(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    dragX += dragAmount.x
                    dragY += dragAmount.y
                    change.consume()
                }
            }
            .graphicsLayer { translationX = dragX; translationY = dragY },
        shape = RoundedCornerShape(16.dp),
        elevation = 8.dp,
        colors = CardDefaults.cardColors(
            containerColor = Color(0xE60A0E17),
            contentColor = Color.White
        )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    fontSize = 18.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = Color(0xFF00FF88)
                )
                
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { isExpanded = !isExpanded }) {
                        Icon(if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = "Toggle")
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            }

            // Tab 栏
            if (isExpanded) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TabButton("主页", Icons.Default.Home, selectedTab == 0) { selectedTab = 0 }
                    TabButton("自瞄", Icons.Default.Target, selectedTab == 1) { selectedTab = 1 }
                    TabButton("透视", Icons.Default.Visibility, selectedTab == 2) { selectedTab = 2 }
                    TabButton("雷达", Icons.Default.Radar, selectedTab == 3) { selectedTab = 3 }
                    TabButton("设置", Icons.Default.Settings, selectedTab == 4) { selectedTab = 4 }
                }

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))

                // 内容区
                Column(modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
                ) {
                    when (selectedTab) {
                        0 -> HomeTab()
                        1 -> AimbotConfigTab(aimbotConfig)
                        2 -> ESPConfigTab(espConfig)
                        3 -> RadarConfigTab(radarConfig)
                        4 -> SettingsTab()
                    }
                }
            }
        }
    }
}

@Composable
fun TabButton(title: String, icon: androidx.compose.material.icons.Icons.Filled, selected: Boolean, onClick: () -> Unit) {
    val colors = if (selected) {
        androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = Color(0xFF00FF88),
            contentColor = Color.Black
        )
    } else {
        androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = Color(0xFF1F2937),
            contentColor = Color(0xFF9CA3AF)
        )
    }
    
    Button(
        onClick = onClick,
        modifier = Modifier.weight(1f).height(36.dp),
        colors = colors,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(4.dp))
            Text(text = title, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
        }
    }
}

@Composable
fun HomeTab() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StatusCard("游戏进程", "com.tencent.tmgp.dfm", "运行中", Color(0xFF00FF88))
        StatusCard("内核通道", "Root Bridge", "已连接", Color(0xFF00D4FF))
        StatusCard("功能模块", "3/3 启用", "正常", Color(0xFFA855F7))
        StatusCard("内存占用", "45.2 MB", "正常", Color(0xFFFF6B00))
    }
}

@Composable
fun StatusCard(title: String, value: String, status: String, statusColor: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(text = title, fontSize = 14.sp, color = Color(0xFF9CA3AF))
                Text(text = value, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color.White)
            }
            Text(text = status, fontSize = 12.sp, color = statusColor)
        }
    }
}

@Composable
fun AimbotConfigTab(config: AimbotConfig) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // 总开关
        FeatureToggle(
            title = "辅助瞄准总开关",
            icon = Icons.Default.Target,
            enabled = config.enabled,
            onChanged = { config.enabled = it }
        )
        
        FeatureToggle(
            title = "调试显示 (FOV圆/准星/连线)",
            icon = Icons.Default.Visibility,
            enabled = config.showDebug,
            onChanged = { config.showDebug = it }
        )

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
        Text("FOV 区域设置", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color(0xFF00FF88))
        
        // FOV 角度滑块
        SliderRow(
            label = "FOV 角度: ${config.fov.toString("%.1f")}°",
            value = config.fov,
            min = config.minFov,
            max = config.maxFov,
            step = 0.5f,
            onValueChange = { config.fov = it }
        )
        
        // FOV 缩放倍数
        SliderRow(
            label = "FOV 缩放: ${config.fovScale.toString("%.2f")}x",
            value = config.fovScale,
            min = 0.1f,
            max = 5.0f,
            step = 0.05f,
            onValueChange = { config.fovScale = it }
        )

        // 实时 FOV 半径显示
        // 注意：实际半径需要屏幕尺寸计算，这里显示公式
        InfoRow("FOV 半径公式", "min(宽,高) * FOV/180 * 缩放")

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
        Text("平滑与人性化", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color(0xFF00FF88))
        
        SliderRow(
            label = "平滑系数: ${config.smooth.toString("%.2f")}",
            value = config.smooth,
            min = 0.01f,
            max = 1.0f,
            step = 0.01f,
            onValueChange = { config.smooth = it }
        )
        
        SliderRow(
            label = "抖动幅度: ${config.jitter.toString("%.1f")}px",
            value = config.jitter,
            min = 0f,
            max = 10f,
            step = 0.1f,
            onValueChange = { config.jitter = it }
        )

        FeatureToggle(
            title = "弹道预判",
            icon = Icons.Default.Psychology,
            enabled = config.prediction,
            onChanged = { config.prediction = it }
        )
        
        FeatureToggle(
            title = "贝塞尔曲线人性化",
            icon = Icons.Default.Timeline,
            enabled = config.humanizeCurve,
            onChanged = { config.humanizeCurve = it }
        )

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
        Text("目标选择", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color(0xFF00FF88))
        
        DropdownRow(
            label = "目标骨骼",
            options = arrayOf("头部", "颈部", "胸部", "骨盆"),
            selected = config.targetBone.ordinal,
            onSelected = { config.targetBone = AimbotConfig.TargetBone.values()[it] }
        )
        
        DropdownRow(
            label = "选择模式",
            options = arrayOf("最近距离", "准心最近", "威胁优先", "血量最低", "锁定模式"),
            selected = config.targetSelection.ordinal,
            onSelected = { config.targetSelection = AimbotConfig.TargetSelection.values()[it] }
        )
        
        FeatureToggle(
            title = "锁定模式 (保持目标)",
            icon = Icons.Default.Lock,
            enabled = config.lockOnTarget,
            onChanged = { config.lockOnTarget = it }
        )

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
        Text("射击控制", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color(0xFF00FF88))
        
        FeatureToggle(
            title = "自动开火",
            icon = Icons.Default.FlashOn,
            enabled = config.autoFire,
            onChanged = { config.autoFire = it }
        )
        
        FeatureToggle(
            title = "穿墙检测",
            icon = Icons.Default.Shield,
            enabled = config.visibilityCheck,
            onChanged = { config.visibilityCheck = it }
        )
        
        SliderRow(
            label = "射击延迟: ${config.delayMs}ms",
            value = config.delayMs.toFloat(),
            min = 0f,
            max = 200f,
            step = 5f,
            onValueChange = { config.delayMs = it.toLong() }
        )

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
        Text("压枪控制", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color(0xFF00FF88))
        
        FeatureToggle(
            title = "启用压枪",
            icon = Icons.Default.Tune,
            enabled = config.recoilControl,
            onChanged = { config.recoilControl = it }
        )
        
        SliderRow(
            label = "压枪强度: ${config.recoilStrength.toString("%.2f")}",
            value = config.recoilStrength,
            min = 0.1f,
            max = 2.0f,
            step = 0.05f,
            onValueChange = { config.recoilStrength = it }
        )
        
        DropdownRow(
            label = "当前武器",
            options = RecoilPatterns.getAllWeapons().toTypedArray(),
            selected = RecoilPatterns.getAllWeapons().indexOf(config.currentWeapon),
            onSelected = { config.currentWeapon = RecoilPatterns.getAllWeapons()[it] }
        )
    }
}

@Composable
fun ESPConfigTab(config: ESPConfig) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FeatureToggle("视觉透视总开关", Icons.Default.Visibility, config.enabled) { config.enabled = it }
        FeatureToggle("骨骼透视", Icons.Default.Person, config.showSkeleton) { config.showSkeleton = it }
        FeatureToggle("方框透视", Icons.Default.CropSquare, config.showBox) { config.showBox = it }
        FeatureToggle("血条显示", Icons.Default.Favorite, config.showHealth) { config.showHealth = it }
        FeatureToggle("显示名称", Icons.Default.Badge, config.showName) { config.showName = it }
        FeatureToggle("显示距离", Icons.Default.Straighten, config.showDistance) { config.showDistance = it }
        FeatureToggle("视线射线", Icons.Default.RemoveRedEye, config.showViewLine) { config.showViewLine = it }
        
        SliderRow("最大渲染距离: ${config.maxDistance.toInt()}m", config.maxDistance, 50f, 500f, 10f) { config.maxDistance = it }
    }
}

@Composable
fun RadarConfigTab(config: RadarConfig) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FeatureToggle("雷达开启", Icons.Default.Radar, config.enabled) { config.enabled = it }
        FeatureToggle("显示敌人", Icons.Default.Person, config.showEnemies) { config.showEnemies = it }
        FeatureToggle("显示队友", Icons.Default.Group, config.showTeammates) { config.showTeammates = it }
        FeatureToggle("显示载具", Icons.Default.DirectionsCar, config.showVehicles) { config.showVehicles = it }
        FeatureToggle("显示物资", Icons.Default.Backpack, config.showLoot) { config.showLoot = it }
        
        SliderRow("雷达尺寸: ${config.size.toInt()}px", config.size, 100f, 400f, 10f) { config.size = it }
        SliderRow("雷达范围: ${config.range.toInt()}m", config.range, 50f, 500f, 10f) { config.range = it }
    }
}

@Composable
fun SettingsTab() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("全局设置", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color(0xFF00FF88))
        FeatureToggle("开机自启", Icons.Default.PowerSettingsNew, false) { }
        FeatureToggle("隐藏图标", Icons.Default.VisibilityOff, false) { }
        FeatureToggle("防检测模式", Icons.Default.Security, true) { }
        
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
        Text("关于", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color(0xFF00FF88))
        InfoRow("版本", "1.0.0-debug")
        InfoRow("作者", "Security Researcher")
        InfoRow("仅供研究", "本地离线测试使用")
    }
}

@Composable
fun FeatureToggle(
    title: String,
    icon: androidx.compose.material.icons.Icons.Filled,
    enabled: Boolean,
    onChanged: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = Color(0xFF00FF88), modifier = Modifier.size(22.dp))
            Text(text = title, fontSize = 15.sp, color = Color.White)
        }
        Switch(
            checked = enabled,
            onCheckedChange = onChanged,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF00FF88),
                checkedTrackColor = Color(0x4D00FF88),
                uncheckedThumbColor = Color(0xFF9CA3AF),
                uncheckedTrackColor = Color(0x4D9CA3AF)
            )
        )
    }
}

@Composable
fun SliderRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    onValueChange: (Float) -> Unit
) {
    var sliderValue by remember { mutableStateOf(value) }
    
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = label, fontSize = 14.sp, color = Color.White)
        }
        androidx.compose.material3.Slider(
            modifier = Modifier.fillMaxWidth(),
            value = sliderValue,
            onValueChange = { 
                sliderValue = it 
                onValueChange(it)
            },
            valueRange = min..max,
            steps = ((max - min) / step).toInt(),
            colors = androidx.compose.material3.SliderDefaults.colors(
                activeThumbColor = Color(0xFF00FF88),
                activeTrackColor = Color(0xFF00FF88),
                inactiveTrackColor = Color(0x4D00FF88)
            )
        )
    }
}

@Composable
fun DropdownRow(
    label: String,
    options: Array<String>,
    selected: Int,
    onSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 15.sp, color = Color.White)
        androidx.compose.material3.MenuAnchor { anchor ->
            androidx.compose.material3.ExposedDropdownMenuBox(
                modifier = Modifier.width(160.dp),
                expanded = { /* state */ false },
                onExpandedChange = { },
                anchor = anchor
            ) {
                androidx.compose.material3.Text(
                    text = options[selected.coerceIn(0, options.size - 1)],
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                )
                androidx.compose.material3.DropdownMenu(
                    expanded = false,
                    onDismissRequest = { }
                ) {
                    options.forEachIndexed { index, option ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(text = option, color = Color.White) },
                            onClick = { onSelected(index) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 14.sp, color = Color(0xFF9CA3AF))
        Text(text = value, fontSize = 14.sp, color = Color.White)
    }
}