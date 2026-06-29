package com.example.emergencywatch.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.example.emergencywatch.ble.AlertData
import com.example.emergencywatch.ble.Direction
import com.example.emergencywatch.ble.GattServer
import com.example.emergencywatch.ble.Motion
import com.example.emergencywatch.presentation.theme.EmergencyWatchTheme
import com.example.emergencywatch.vibration.VibrationEngine

class MainActivity : ComponentActivity() {

    private lateinit var vibration: VibrationEngine
    private var gattServer: GattServer? = null

    // 화면에 보여줄 상태(상태 문자열 / 마지막 알림)
    private var status by mutableStateOf("초기화 중…")
    private var lastAlert by mutableStateOf("(아직 수신 없음)")

    // 런타임 권한 요청 런처
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            startBle()
        } else {
            status = "BLE 권한이 거부됨 — 설정에서 허용 필요"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vibration = VibrationEngine(this)

        setContent {
            EmergencyWatchTheme {
                StatusScreen(
                    status = status,
                    lastAlert = lastAlert,
                    onTest = { dir, motion -> vibration.play(dir, motion) },
                )
            }
        }

        ensurePermissionsAndStart()
    }

    /** 필요한 BLE 권한이 있으면 바로 시작, 없으면 요청. */
    private fun ensurePermissionsAndStart() {
        val needed = requiredBlePermissions()
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startBle()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requiredBlePermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ : 런타임 권한 필요
            listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Android 11 이하 : 설치 시 부여되므로 런타임 요청 불필요
            emptyList()
        }

    private fun startBle() {
        if (gattServer != null) return
        gattServer = GattServer(
            context = this,
            onAlert = { alert -> onAlertReceived(alert) },
            onStatus = { s -> runOnUiThread { status = s } },
        ).also { it.start() }
    }

    /** Jetson에서 알림 수신 시: 진동 + 화면 갱신. */
    private fun onAlertReceived(alert: AlertData) {
        runOnUiThread { lastAlert = alert.toKorean() }
        vibration.play(alert)
    }

    override fun onDestroy() {
        super.onDestroy()
        gattServer?.stop()
        vibration.stop()
    }
}

@Composable
fun StatusScreen(
    status: String,
    lastAlert: String,
    onTest: (Direction, Motion) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "긴급차량 알림",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(text = "상태: $status", textAlign = TextAlign.Center)
        Text(text = "최근: $lastAlert", textAlign = TextAlign.Center)

        // ── 진동 테스트 버튼들 (Jetson 없이 워치만으로 패턴 확인용) ──
        Text(text = "─ 진동 테스트 ─", textAlign = TextAlign.Center)
        Button(
            onClick = { onTest(Direction.LEFT, Motion.APPROACHING) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("좌측 · 접근") }
        Button(
            onClick = { onTest(Direction.RIGHT, Motion.APPROACHING) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("우측 · 접근") }
        Button(
            onClick = { onTest(Direction.REAR, Motion.RECEDING) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("후방 · 멀어짐") }
    }
}
