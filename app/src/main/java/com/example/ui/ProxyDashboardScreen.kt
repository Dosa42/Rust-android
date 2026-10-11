package com.example.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.proxy.ProxyAccount
import com.example.proxy.ProxyLog
import com.example.proxy.ProxyManager
import com.example.ui.theme.CodeBackground
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberGreen
import com.example.ui.theme.CyberOrange
import com.example.ui.theme.CyberRed
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyDashboardScreen(manager: ProxyManager) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val isRunning by manager.isProxyRunning.collectAsState()
    val isNativeActive by manager.isNativeActive.collectAsState()
    val port by manager.proxyPort.collectAsState()
    val accounts by manager.accounts.collectAsState()
    val logs by manager.logs.collectAsState()
    val metrics by manager.metrics.collectAsState()
    val testResponse by manager.testResponse.collectAsState()
    val isTesting by manager.isTesting.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddAccountDialog by remember { mutableStateOf(false) }
    var editingPort by remember { mutableStateOf(port.toString()) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isRunning) CyberGreen else CyberRed)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "CODEX PROXY",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // Engine pill
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isNativeActive) CyberCyan.copy(alpha = 0.2f) else CyberGreen.copy(alpha = 0.15f))
                                .border(1.dp, if (isNativeActive) CyberCyan else CyberGreen, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (isNativeActive) "JNI NATIVE" else "EMBEDDED",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = if (isNativeActive) CyberCyan else CyberGreen
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { manager.refreshData() },
                        modifier = Modifier.testTag("refresh_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Main Controls & Endpoint Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .testTag("proxy_control_card"),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (isRunning) "DAEMON RUNNING" else "DAEMON STOPPED",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = if (isRunning) CyberGreen else CyberRed
                            )
                            Text(
                                text = "http://127.0.0.1:$port/v1",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily.Monospace,
                                color = CyberCyan
                            )
                            Row(
                                modifier = Modifier.padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(CyberGreen.copy(alpha = 0.15f))
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Text("/v1/responses", fontSize = 9.sp, color = CyberGreen, fontFamily = FontFamily.Monospace)
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(CyberCyan.copy(alpha = 0.15f))
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Text("/v1/messages", fontSize = 9.sp, color = CyberCyan, fontFamily = FontFamily.Monospace)
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(CyberOrange.copy(alpha = 0.15f))
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Text("/v1/chat/completions", fontSize = 9.sp, color = CyberOrange, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isRunning) {
                                Button(
                                    onClick = { manager.stopProxy() },
                                    colors = ButtonDefaults.buttonColors(containerColor = CyberRed),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.testTag("stop_proxy_button")
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Stop", fontSize = 13.sp)
                                }
                            } else {
                                Button(
                                    onClick = {
                                        val p = editingPort.toIntOrNull() ?: 8080
                                        manager.startProxy(p)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CyberGreen),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.testTag("start_proxy_button")
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Start", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Start", fontSize = 13.sp, color = Color.Black)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Copy quick actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString("http://127.0.0.1:$port/v1"))
                                Toast.makeText(context, "Base URL copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f).testTag("copy_url_button")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(14.dp), tint = CyberCyan)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy Base URL", fontSize = 11.sp, color = CyberCyan)
                        }

                        OutlinedButton(
                            onClick = {
                                val curl = "curl -X POST http://127.0.0.1:$port/v1/chat/completions -H 'Content-Type: application/json' -d '{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}'"
                                clipboard.setText(AnnotatedString(curl))
                                Toast.makeText(context, "cURL command copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f).testTag("copy_curl_button")
                        ) {
                            Icon(Icons.Default.Terminal, contentDescription = "cURL", modifier = Modifier.size(14.dp), tint = TextSecondary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy cURL", fontSize = 11.sp, color = TextSecondary)
                        }
                    }
                }
            }

            // Real-time Metrics Grid
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricCard("Requests", metrics.totalRequests.toString(), CyberCyan, Modifier.weight(1f))
                MetricCard("Latency", "${metrics.averageLatencyMs}ms", CyberGreen, Modifier.weight(1f))
                MetricCard("Accounts", metrics.activeAccounts.toString(), CyberOrange, Modifier.weight(1f))
                MetricCard("Errors", metrics.errorRequests.toString(), if (metrics.errorRequests > 0) CyberRed else TextMuted, Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tab Navigation
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = DarkSurface,
                contentColor = CyberCyan,
                edgePadding = 8.dp,
                divider = { Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(DarkBorder)) }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Console", fontSize = 13.sp, fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Accounts (${accounts.size})", fontSize = 13.sp, fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Logs (${logs.size})", fontSize = 13.sp, fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal) }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("JNI Native", fontSize = 13.sp, fontWeight = if (selectedTab == 3) FontWeight.Bold else FontWeight.Normal) }
                )
                Tab(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    text = { Text("OpenClaw", fontSize = 13.sp, fontWeight = if (selectedTab == 4) FontWeight.Bold else FontWeight.Normal) }
                )
            }

            // Tab Content
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                when (selectedTab) {
                    0 -> TestConsoleTab(
                        manager = manager,
                        isTesting = isTesting,
                        testResponse = testResponse,
                        port = port
                    )
                    1 -> AccountsTab(
                        accounts = accounts,
                        onAddAccount = { showAddAccountDialog = true },
                        onToggle = { id, active -> manager.toggleAccount(id, active) },
                        onDelete = { id -> manager.deleteAccount(id) }
                    )
                    2 -> LogsTab(
                        logs = logs,
                        onClearLogs = { manager.clearLogs() }
                    )
                    3 -> JniArchitectureTab(
                        isNativeActive = isNativeActive,
                        port = port
                    )
                    4 -> OpenClawTab(
                        manager = manager
                    )
                }
            }
        }
    }

    if (showAddAccountDialog) {
        AddAccountDialog(
            onDismiss = { showAddAccountDialog = false },
            onConfirm = { name, provider, apiKey, baseUrl ->
                manager.addAccount(name, provider, apiKey, baseUrl)
                showAddAccountDialog = false
            }
        )
    }
}

@Composable
fun MetricCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, fontSize = 10.sp, color = TextMuted)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = color
            )
        }
    }
}

@Composable
fun TestConsoleTab(
    manager: ProxyManager,
    isTesting: Boolean,
    testResponse: String?,
    port: Int
) {
    var prompt by remember { mutableStateOf("Write a 1-line Python code to print Hello World") }
    var selectedEndpoint by remember { mutableStateOf("/v1/responses") }
    val endpoints = listOf(
        "/v1/responses" to "Codex Responses",
        "/v1/messages" to "Claude Messages",
        "/v1/chat/completions" to "OpenAI Chat"
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Text("Interactive CCX Relay Playground", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Spacer(modifier = Modifier.height(4.dp))
        Text("Target Endpoint: http://127.0.0.1:$port$selectedEndpoint", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = CyberCyan)

        Spacer(modifier = Modifier.height(8.dp))

        // Protocol selector chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            endpoints.forEach { (ep, label) ->
                Button(
                    onClick = { selectedEndpoint = ep },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedEndpoint == ep) CyberCyan else DarkSurfaceElevated,
                        contentColor = if (selectedEndpoint == ep) Color.Black else TextSecondary
                    ),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("User Prompt", color = TextSecondary) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("test_prompt_input"),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = DarkSurfaceElevated,
                unfocusedContainerColor = DarkSurface,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = CyberCyan,
                unfocusedBorderColor = DarkBorder
            ),
            shape = RoundedCornerShape(8.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { manager.sendTestPrompt(prompt, selectedEndpoint) },
                enabled = !isTesting,
                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("send_test_request_button")
            ) {
                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.Black)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Proxying via $selectedEndpoint...", color = Color.Black, fontSize = 12.sp)
                } else {
                    Icon(Icons.Default.Send, contentDescription = "Send", modifier = Modifier.size(14.dp), tint = Color.Black)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Execute Relay to $selectedEndpoint", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text("Proxy Response / Output:", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextSecondary)
        Spacer(modifier = Modifier.height(4.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = CodeBackground),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            SelectionContainer {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                    item {
                        Text(
                            text = testResponse ?: "Ready. Enter a prompt above and tap Execute to route through local proxy daemon.",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = if (testResponse != null) CyberCyan else TextMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AccountsTab(
    accounts: List<ProxyAccount>,
    onAddAccount: () -> Unit,
    onToggle: (Long, Boolean) -> Unit,
    onDelete: (Long) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Configured Upstream Accounts", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Button(
                onClick = onAddAccount,
                colors = ButtonDefaults.buttonColors(containerColor = CyberGreen),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.testTag("add_account_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(14.dp), tint = Color.Black)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Account", fontSize = 12.sp, color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (accounts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No upstream accounts. Tap Add Account above.", color = TextMuted, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(accounts, key = { it.id }) { acc ->
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("account_card_${acc.id}"),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(acc.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(CyberCyan.copy(alpha = 0.15f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(acc.provider, fontSize = 10.sp, color = CyberCyan, fontFamily = FontFamily.Monospace)
                                    }
                                    if (acc.id == 1L) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(CyberGreen.copy(alpha = 0.15f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text("FIXED SSoT", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = CyberGreen, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("Endpoint: ${acc.baseUrl}", fontSize = 11.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
                                if (acc.accountId.isNotBlank()) {
                                    Text("Account ID: ${acc.accountId}", fontSize = 10.sp, color = CyberCyan, fontFamily = FontFamily.Monospace)
                                }
                                Text("Token: ${acc.apiKey.take(16)}... | Requests: ${acc.requestCount}", fontSize = 10.sp, color = TextMuted)
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = acc.isActive,
                                    onCheckedChange = { onToggle(acc.id, it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = CyberGreen,
                                        checkedTrackColor = CyberGreen.copy(alpha = 0.3f)
                                    ),
                                    modifier = Modifier.testTag("toggle_account_${acc.id}")
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(
                                    onClick = { onDelete(acc.id) },
                                    modifier = Modifier.testTag("delete_account_${acc.id}")
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = CyberRed, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogsTab(logs: List<ProxyLog>, onClearLogs: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Real-Time Traffic Stream", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            OutlinedButton(
                onClick = onClearLogs,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.testTag("clear_logs_button")
            ) {
                Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(14.dp), tint = TextSecondary)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Clear", fontSize = 11.sp, color = TextSecondary)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No requests captured yet. Use Console tab to execute calls.", color = TextMuted, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(logs, key = { it.id }) { log ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(6.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (log.statusCode < 400) CyberGreen.copy(alpha = 0.15f) else CyberRed.copy(alpha = 0.15f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = log.statusCode.toString(),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (log.statusCode < 400) CyberGreen else CyberRed
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Column {
                                    Text(
                                        text = "${log.method} ${log.path}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        fontFamily = FontFamily.Monospace,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "Route: ${log.provider} • ${dateFormat.format(Date(log.timestamp))}",
                                        fontSize = 10.sp,
                                        color = TextMuted
                                    )
                                }
                            }

                            Text(
                                text = "${log.latencyMs}ms",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = CyberCyan
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun JniArchitectureTab(isNativeActive: Boolean, port: Int) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text("Native JNI Architecture & Compilation", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Text("codex-proxy-rs direct compilation to native .so shared library", fontSize = 11.sp, color = TextMuted)

            Spacer(modifier = Modifier.height(10.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Target Architecture:", fontSize = 12.sp, color = TextSecondary)
                        Text(abi, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = CyberCyan)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Library Name:", fontSize = 12.sp, color = TextSecondary)
                        Text("libcodex_proxy.so", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = TextPrimary)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("JNI Link Status:", fontSize = 12.sp, color = TextSecondary)
                        Text(
                            text = if (isNativeActive) "Active (libcodex_proxy.so loaded)" else "Fallback (Embedded daemon running)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isNativeActive) CyberGreen else CyberOrange
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("Rust NDK Cross-Compilation Command:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Spacer(modifier = Modifier.height(4.dp))

            val buildCommand = "cd rust_bridge && cargo ndk -t arm64-v8a -t x86_64 -o ../app/src/main/jniLibs build --release"

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = CodeBackground),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = buildCommand,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = CyberGreen,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(buildCommand))
                            Toast.makeText(context, "Command copied", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = CyberCyan, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("JNI Export Mapping:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Spacer(modifier = Modifier.height(4.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = CodeBackground),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "Java_com_example_proxy_NativeProxy_startProxyNative\n" +
                               "  -> Spawns Tokio Multi-Thread Runtime\n" +
                               "  -> Binds Axum TCP Listener to 127.0.0.1:$port\n" +
                               "  -> Attaches SQLite storage at app filesDir\n" +
                               "\n" +
                               "Java_com_example_proxy_NativeProxy_stopProxyNative\n" +
                               "  -> Signals watch channel to graceful shutdown\n" +
                               "\n" +
                               "Java_com_example_proxy_NativeProxy_getProxyStatusNative\n" +
                               "  -> Returns live JSON telemetry from Rust runtime",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, provider: String, apiKey: String, baseUrl: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("OpenAI") }
    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("https://api.openai.com/v1") }

    val providers = listOf("OpenAI", "Codex", "xAI", "Custom")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Upstream Account", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Account Name") },
                    placeholder = { Text("Production Key") },
                    modifier = Modifier.fillMaxWidth().testTag("new_account_name_input"),
                    shape = RoundedCornerShape(8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    providers.forEach { p ->
                        Button(
                            onClick = {
                                provider = p
                                baseUrl = when (p) {
                                    "OpenAI", "Codex" -> "https://api.openai.com/v1"
                                    "xAI" -> "https://api.x.ai/v1"
                                    else -> "https://your-custom-endpoint/v1"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (provider == p) CyberCyan else DarkSurfaceElevated,
                                contentColor = if (provider == p) Color.Black else TextSecondary
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(p, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key (sk-...)") },
                    placeholder = { Text("sk-proj-...") },
                    modifier = Modifier.fillMaxWidth().testTag("new_account_key_input"),
                    shape = RoundedCornerShape(8.dp)
                )

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    modifier = Modifier.fillMaxWidth().testTag("new_account_url_input"),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, provider, apiKey, baseUrl) },
                colors = ButtonDefaults.buttonColors(containerColor = CyberGreen),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.testTag("confirm_add_account_button")
            ) {
                Text("Add Account", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text("Cancel", color = TextSecondary)
            }
        },
        containerColor = DarkSurface
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OpenClawTab(manager: ProxyManager) {
    val context = LocalContext.current
    val isGatewayRunning by manager.openClaw.isGatewayRunning.collectAsState()
    val isControlUiRunning by manager.openClaw.isControlUiRunning.collectAsState()
    val isConnectProxyRunning by manager.openClaw.isConnectProxyRunning.collectAsState()
    val openClawLogs by manager.openClaw.logs.collectAsState()
    val bootstrapInstalling by manager.bootstrapInstalling.collectAsState()
    val bootstrapProgress by manager.bootstrapProgress.collectAsState()

    var showWebView by remember { mutableStateOf(false) }
    var currentWebUrl by remember { mutableStateOf("http://127.0.0.1:19001/") }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    if (showWebView) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground)
        ) {
            // WebView Navigation Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurface)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = currentWebUrl,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = CyberCyan,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { webViewInstance?.reload() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reload", tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = { showWebView = false },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Clear, contentDescription = "Close", tint = CyberRed, modifier = Modifier.size(18.dp))
                }
            }

            // Interactive Android WebView
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.allowFileAccess = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                                view.loadUrl(url)
                                currentWebUrl = url
                                return true
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                                android.util.Log.d("OpenClawWebView", "[Console] ${msg.message()}")
                                return true
                            }
                        }
                        loadUrl(currentWebUrl)
                        webViewInstance = this
                    }
                },
                update = { view ->
                    if (view.url != currentWebUrl) {
                        view.loadUrl(currentWebUrl)
                    }
                }
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Hero Header Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(8.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CyberCyan.copy(alpha = 0.4f)))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isGatewayRunning) CyberGreen else CyberOrange)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "OPENCLAW ASSISTANT & CONTROL HUB",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Termux Linux runtime embedding OpenClaw Gateway (18789), Control UI (19001), CONNECT Proxy (18924), and Codex App-Server, integrated with CCX local multiplexer.",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        lineHeight = 15.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Persistent SSoT Token Status
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(DarkBackground)
                            .border(1.dp, CyberGreen.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                            .padding(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "SSoT PERSISTENT ACCOUNT",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberGreen,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "${com.example.proxy.CodexFixedAccount.EMAIL} (af8d241c...)",
                                    fontSize = 11.sp,
                                    color = TextPrimary,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Button(
                                onClick = {
                                    val ok = manager.syncOpenClawToken()
                                    Toast.makeText(context, if (ok) "Token Synced to OpenClaw" else "Sync Failed", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CyberGreen.copy(alpha = 0.2f)),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.testTag("sync_token_button")
                            ) {
                                Text("Sync Token", fontSize = 10.sp, color = CyberGreen, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Daemons Status Matrix Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(8.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "ACTIVE DAEMONS & SERVICE MATRIX",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = CyberCyan
                    )

                    // Daemon 1: OpenClaw Gateway (18789)
                    DaemonRow(
                        title = "OpenClaw Gateway",
                        port = "18789 (WS)",
                        isRunning = isGatewayRunning,
                        onToggle = {
                            if (isGatewayRunning) manager.stopOpenClawGateway()
                            else manager.startOpenClawGateway()
                        },
                        tag = "gateway_toggle_button"
                    )

                    // Daemon 2: Control UI Server (19001)
                    DaemonRow(
                        title = "Control UI Server",
                        port = "19001 (HTTP)",
                        isRunning = isControlUiRunning,
                        onToggle = {
                            if (isControlUiRunning) manager.stopOpenClawControlUi()
                            else manager.startOpenClawControlUi()
                        },
                        tag = "control_ui_toggle_button"
                    )

                    // Daemon 3: CONNECT Proxy (18924)
                    DaemonRow(
                        title = "CONNECT Proxy",
                        port = "18924 (TCP)",
                        isRunning = isConnectProxyRunning,
                        onToggle = {
                            if (isConnectProxyRunning) manager.stopConnectProxy()
                            else manager.startConnectProxy()
                        },
                        tag = "connect_proxy_toggle_button"
                    )

                    // Daemon 4: CCX Relay Gateway (8080)
                    DaemonRow(
                        title = "CCX Relay Multiplexer",
                        port = "${manager.proxyPort.value} (HTTP)",
                        isRunning = manager.isProxyRunning.value,
                        onToggle = {
                            if (manager.isProxyRunning.value) manager.stopProxy()
                            else manager.startProxy()
                        },
                        tag = "ccx_proxy_toggle_button"
                    )
                }
            }
        }

        // Web Viewer & Control UI Launchers
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(8.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "WEB VIEWER & EXTERNAL LAUNCHERS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = CyberCyan
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                currentWebUrl = "http://127.0.0.1:19001/"
                                showWebView = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f).testTag("open_control_ui_button")
                        ) {
                            Text("Open Control UI (19001)", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                currentWebUrl = "file:///android_asset/web/index.html"
                                showWebView = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f).testTag("open_bundled_web_button")
                        ) {
                            Text("Open Bundled Web (Assets)", fontSize = 11.sp, color = TextPrimary)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:19001/"))
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Cannot open browser: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Open in Browser", fontSize = 11.sp, color = TextSecondary)
                        }

                        Button(
                            onClick = {
                                manager.installBootstrap { success ->
                                    Toast.makeText(context, if (success) "Bootstrap Ready" else "Install Failed", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = !bootstrapInstalling,
                            colors = ButtonDefaults.buttonColors(containerColor = CyberOrange.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f).testTag("bootstrap_install_button")
                        ) {
                            if (bootstrapInstalling) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = CyberOrange, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(if (bootstrapInstalling) "Installing…" else "Setup Bootstrap", fontSize = 11.sp, color = CyberOrange, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (bootstrapProgress.isNotEmpty()) {
                        Text(
                            text = bootstrapProgress,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = CyberOrange
                        )
                    }
                }
            }
        }

        // OpenClaw Real-Time Terminal Logs
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(8.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Terminal, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "PROCESS TERMINAL & OUTPUT LOGS",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = CyberCyan
                            )
                        }
                        Text(
                            text = "${openClawLogs.size} lines",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextMuted
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(CodeBackground)
                            .border(1.dp, DarkBorder, RoundedCornerShape(6.dp))
                            .padding(8.dp)
                    ) {
                        if (openClawLogs.isEmpty()) {
                            Text(
                                text = "No process logs yet. Start the Gateway, Control UI, or Bootstrap setup to see live daemon output.",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextMuted
                            )
                        } else {
                            SelectionContainer {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    reverseLayout = true
                                ) {
                                    items(openClawLogs.reversed()) { line ->
                                        Text(
                                            text = line,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = when {
                                                line.contains("error", ignoreCase = true) || line.contains("failed", ignoreCase = true) -> CyberRed
                                                line.contains("started", ignoreCase = true) || line.contains("success", ignoreCase = true) -> CyberGreen
                                                line.contains("download", ignoreCase = true) || line.contains("extract", ignoreCase = true) -> CyberOrange
                                                else -> TextSecondary
                                            },
                                            lineHeight = 14.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DaemonRow(
    title: String,
    port: String,
    isRunning: Boolean,
    onToggle: () -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(DarkBackground)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isRunning) CyberGreen else CyberRed)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = port,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary
                )
            }
        }

        Button(
            onClick = onToggle,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) CyberRed.copy(alpha = 0.2f) else CyberGreen.copy(alpha = 0.2f)
            ),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.testTag(tag)
        ) {
            Text(
                text = if (isRunning) "Stop" else "Start",
                fontSize = 10.sp,
                color = if (isRunning) CyberRed else CyberGreen,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

