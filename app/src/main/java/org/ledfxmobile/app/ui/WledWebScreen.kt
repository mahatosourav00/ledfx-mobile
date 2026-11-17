package org.ledfxmobile.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ledfxmobile.app.engine.LightEngine

/**
 * WLED's own web interface, inside the app. While it's open, streaming to this device is paused
 * so WLED shows its normal state instead of "Live" (can be toggled).
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WledWebScreen(deviceId: String, onBack: () -> Unit) {
    val devices by LightEngine.devices.collectAsStateWithLifecycle()
    val paused by LightEngine.paused.collectAsStateWithLifecycle()
    val device = devices.firstOrNull { it.id == deviceId }
    if (device == null) { onBack(); return }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(deviceId) {
        LightEngine.setPaused(deviceId, true)
        onDispose { LightEngine.setPaused(deviceId, false) }
    }
    BackHandler {
        val w = webView
        if (w != null && w.canGoBack()) w.goBack() else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(device.name)
                        Text("WLED · ${device.host}", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { error = null; webView?.reload() }) { Icon(Icons.Rounded.Refresh, "Reload") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PauseCircle, null, tint = MaterialTheme.colorScheme.secondary)
                    Text(
                        if (deviceId in paused) "  Music effects paused while you're here"
                        else "  Music effects are streaming (WLED shows Live)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = deviceId in paused,
                        onCheckedChange = { LightEngine.setPaused(deviceId, it) },
                    )
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            setBackgroundColor(0xFF111111.toInt())
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { loading = true }
                                override fun onPageFinished(view: WebView?, url: String?) { loading = false }
                                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, err: WebResourceError?) {
                                    if (request?.isForMainFrame == true) {
                                        loading = false
                                        error = "Couldn't reach ${device.host}. Is the controller on and on this Wi-Fi?"
                                    }
                                }
                            }
                            loadUrl("http://${device.host}/")
                            webView = this
                        }
                    },
                    onRelease = { it.destroy() },
                )
                error?.let {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(24.dp)) {
                            Text(it, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}
