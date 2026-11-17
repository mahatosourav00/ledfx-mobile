package org.ledfxmobile.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ledfxmobile.app.engine.LightEngine
import org.ledfxmobile.app.service.CaptureConsentActivity
import org.ledfxmobile.app.service.EngineService

class MainActivity : ComponentActivity() {

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            EngineService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LedTheme {
                AppRoot(
                    onStart = { EngineService.start(this) },
                    onStop = { EngineService.stop(this) },
                    onRequestPhoneAudio = {
                        startActivity(Intent(this, CaptureConsentActivity::class.java))
                    },
                )
            }
        }
        if (savedInstanceState == null) startWithPermissions()
    }

    private fun startWithPermissions() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) EngineService.start(this) else permissions.launch(needed.toTypedArray())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(onStart: () -> Unit, onStop: () -> Unit, onRequestPhoneAudio: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    var openWledId by rememberSaveable { mutableStateOf<String?>(null) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    val running by LightEngine.running.collectAsStateWithLifecycle()

    val wledId = openWledId
    if (wledId != null) {
        WledWebScreen(deviceId = wledId, onBack = { openWledId = null })
        return
    }
    val deviceId = openDeviceId
    if (deviceId != null) {
        BackHandler { openDeviceId = null }
        DeviceScreen(deviceId = deviceId, onBack = { openDeviceId = null }, onOpenWled = { openWledId = it })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LedFx Mobile", fontWeight = FontWeight.Bold) },
                actions = {
                    FilledTonalButton(
                        onClick = if (running) onStop else onStart,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Icon(
                            if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                        )
                        Text(if (running) " Stop" else " Start")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Rounded.Lightbulb, null) }, label = { Text("Lights") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Rounded.GraphicEq, null) }, label = { Text("Audio") },
                )
            }
        },
        floatingActionButton = {
            if (tab == 0) {
                ExtendedFloatingActionButton(
                    onClick = { showAdd = true },
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text("Add device") },
                )
            }
        },
    ) { padding ->
        when (tab) {
            0 -> DevicesScreen(
                padding = padding,
                running = running,
                onStart = onStart,
                onOpen = { openDeviceId = it },
                onOpenWled = { openWledId = it },
            )
            else -> AudioScreen(padding = padding, onRequestPhoneAudio = onRequestPhoneAudio)
        }
    }

    if (showAdd) AddDeviceSheet(onDismiss = { showAdd = false })
}
