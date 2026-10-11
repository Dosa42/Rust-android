package com.example.openclaw

import android.content.Context
import android.util.Log
import com.example.proxy.CodexFixedAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Manages the lifecycle of OpenClaw Gateway, Control UI, CONNECT Proxy,
 * and the Termux-based Linux environment on Android.
 * Ported from Dosa42/openclaw-android-assistant and wired with CodexFixedAccount SSoT.
 */
class OpenClawServerManager(private val context: Context) {

    companion object {
        private const val TAG = "OpenClawServerManager"
        const val OPENCLAW_GATEWAY_PORT = 18789
        const val OPENCLAW_CONTROL_UI_PORT = 19001
        const val CODEX_SERVER_PORT = 18923
        const val CONNECT_PROXY_PORT = 18924
        private const val CODEX_VERSION = "0.104.0"
    }

    private var serverProcess: Process? = null
    private var proxyProcess: Process? = null
    private var openClawGatewayProcess: Process? = null
    private var openClawControlUiProcess: Process? = null

    private val _isGatewayRunning = MutableStateFlow(false)
    val isGatewayRunning: StateFlow<Boolean> = _isGatewayRunning.asStateFlow()

    private val _isControlUiRunning = MutableStateFlow(false)
    val isControlUiRunning: StateFlow<Boolean> = _isControlUiRunning.asStateFlow()

    private val _isConnectProxyRunning = MutableStateFlow(false)
    val isConnectProxyRunning: StateFlow<Boolean> = _isConnectProxyRunning.asStateFlow()

    private val _isCodexServerRunning = MutableStateFlow(false)
    val isCodexServerRunning: StateFlow<Boolean> = _isCodexServerRunning.asStateFlow()

    private val _currentStatus = MutableStateFlow("Ready")
    val currentStatus: StateFlow<String> = _currentStatus.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private fun appendLog(line: String) {
        val entry = "[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}] $line"
        _logs.value = (_logs.value + entry).takeLast(250)
    }

    // ── Shell Execution ──────────────────────────────────────────────────

    fun runInPrefix(
        command: String,
        onOutput: ((String) -> Unit)? = null,
    ): Int {
        val paths = BootstrapInstaller.getPaths(context)
        val env = buildEnvironment(paths)
        val shell = "${paths.prefixDir}/bin/sh"

        if (!File(shell).exists()) {
            appendLog("Warning: Prefix shell not found at $shell")
            return -1
        }

        return try {
            val pb = ProcessBuilder(shell, "-c", command)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.directory(File(paths.homeDir))
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line = reader.readLine()
            while (line != null) {
                Log.d(TAG, line)
                appendLog(line)
                onOutput?.invoke(line)
                line = reader.readLine()
            }
            proc.waitFor()
        } catch (e: Exception) {
            appendLog("Shell execution failed: ${e.message}")
            -1
        }
    }

    private fun runCapture(command: String): String {
        val sb = StringBuilder()
        runInPrefix(command) { sb.appendLine(it) }
        return sb.toString().trim()
    }

    // ── Inspection Helpers ───────────────────────────────────────────────

    fun isProotInstalled(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        return File(paths.prefixDir, "bin/proot").exists()
    }

    fun isNodeInstalled(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        return File(paths.prefixDir, "bin/node").exists()
    }

    fun isPythonInstalled(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        return File(paths.prefixDir, "bin/python3").exists() || File(paths.prefixDir, "bin/python").exists()
    }

    fun isCodexInstalled(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        return File(paths.prefixDir, "lib/node_modules/@openai/codex/bin/codex.js").exists()
    }

    fun isOpenClawInstalled(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        return File(paths.prefixDir, "lib/node_modules/openclaw").exists()
    }

    fun isPlatformBinaryInstalled(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        return File(
            paths.prefixDir,
            "lib/node_modules/@openai/codex-linux-arm64/vendor/aarch64-unknown-linux-musl/codex/codex",
        ).exists()
    }

    // ── Environment Setup from Dosa42/openclaw-android-assistant ─────────

    fun ensureBionicCompat() {
        val paths = BootstrapInstaller.getPaths(context)
        val patchDir = File(paths.homeDir, ".openclaw-android/patches")
        patchDir.mkdirs()

        val target = File(patchDir, "bionic-compat.js")
        try {
            context.assets.open("bionic-compat.js").use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            appendLog("bionic-compat.js installed in ${patchDir.absolutePath}")
        } catch (e: Exception) {
            appendLog("Failed to extract bionic-compat.js: ${e.message}")
        }
    }

    fun ensureDefaultWorkspace() {
        val paths = BootstrapInstaller.getPaths(context)
        val workspaceDir = File(paths.homeDir, "codex")
        if (workspaceDir.exists()) return
        workspaceDir.mkdirs()
        runInPrefix("cd ${workspaceDir.absolutePath} && git init 2>&1")
        appendLog("Default workspace initialized at $workspaceDir")
    }

    fun ensureFullAccessConfig() {
        val paths = BootstrapInstaller.getPaths(context)
        val configDir = File(paths.homeDir, ".codex")
        configDir.mkdirs()
        val configFile = File(configDir, "config.toml")
        val desired = """
            approval_policy = "never"
            sandbox_mode = "danger-full-access"
        """.trimIndent() + "\n"
        configFile.writeText(desired)
        appendLog("Wrote full-access config to $configFile")
    }

    // ── Auth Synchronization ─────────────────────────────────────────────

    /**
     * Automatically writes the persistent Codex token (dosa042@gmail.com)
     * to both ~/.codex/auth.json and OpenClaw's auth-profiles.json.
     */
    fun syncCodexAuthToOpenClaw(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val codexDir = File(paths.homeDir, ".codex")
        codexDir.mkdirs()

        // 1. Write ~/.codex/auth.json
        val codexAuthJson = JSONObject().apply {
            val tokens = JSONObject().apply {
                put("access_token", CodexFixedAccount.ACCESS_TOKEN)
                put("refresh_token", CodexFixedAccount.REFRESH_TOKEN)
                put("account_id", CodexFixedAccount.ACCOUNT_ID)
            }
            put("tokens", tokens)
            put("email", CodexFixedAccount.EMAIL)
        }
        File(codexDir, "auth.json").writeText(codexAuthJson.toString(2))
        appendLog("Synced ~/.codex/auth.json with ${CodexFixedAccount.EMAIL}")

        // 2. Write ~/.openclaw/auth-profiles.json and agent specific profile
        val openClawDir = File(paths.homeDir, ".openclaw")
        openClawDir.mkdirs()
        val agentDir = File(openClawDir, "agents/main/agent")
        agentDir.mkdirs()

        val profilesJson = JSONObject().apply {
            put("version", 1)
            val profMap = JSONObject().apply {
                put("openai-codex:codex-cli", JSONObject().apply {
                    put("type", "token")
                    put("provider", "openai-codex")
                    put("token", CodexFixedAccount.ACCESS_TOKEN)
                    put("source", "codex-auth")
                    put("createdAt", Instant.now().toString())
                })
                put("openai:codex", JSONObject().apply {
                    put("type", "token")
                    put("provider", "openai")
                    put("token", CodexFixedAccount.ACCESS_TOKEN)
                    put("source", "codex-auth")
                    put("createdAt", Instant.now().toString())
                })
            }
            put("profiles", profMap)
            put("order", JSONArray().apply {
                put("openai-codex:codex-cli")
                put("openai:codex")
            })
        }
        val profContent = profilesJson.toString(2)
        File(openClawDir, "auth-profiles.json").writeText(profContent)
        File(agentDir, "auth-profiles.json").writeText(profContent)
        appendLog("Synced OpenClaw auth-profiles.json (global + agent)")

        // 3. Write openclaw.json with zero security ceremony
        val configJson = JSONObject().apply {
            put("meta", JSONObject().apply {
                put("lastTouchedVersion", "2026.2.21-2")
                put("lastTouchedAt", Instant.now().toString())
            })
            put("commands", JSONObject().apply {
                put("native", "auto")
                put("nativeSkills", "auto")
                put("restart", true)
                put("ownerDisplay", "raw")
            })
            put("gateway", JSONObject().apply {
                put("mode", "local")
                put("port", OPENCLAW_GATEWAY_PORT)
                put("controlUi", JSONObject().apply {
                    put("enabled", true)
                    put("allowedOrigins", JSONArray().apply {
                        put("http://127.0.0.1:$OPENCLAW_CONTROL_UI_PORT")
                        put("http://localhost:$OPENCLAW_CONTROL_UI_PORT")
                    })
                    put("allowInsecureAuth", true)
                    put("dangerouslyDisableDeviceAuth", true)
                })
                put("auth", JSONObject().apply {
                    put("mode", "none")
                })
            })
        }
        File(openClawDir, "openclaw.json").writeText(configJson.toString(2))
        appendLog("Wrote openclaw.json (auth=none, dangerouslyDisableDeviceAuth=true)")
        return true
    }

    // ── CONNECT Proxy ────────────────────────────────────────────────────

    fun startProxy(): Boolean {
        if (_isConnectProxyRunning.value && proxyProcess != null) return true
        val paths = BootstrapInstaller.getPaths(context)
        val proxyScript = File(paths.homeDir, "proxy.js")

        try {
            context.assets.open("proxy.js").use { input ->
                proxyScript.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            appendLog("Error copying proxy.js asset: ${e.message}")
            return false
        }

        val env = buildEnvironment(paths)
        val shell = "${paths.prefixDir}/bin/sh"
        if (!File(shell).exists()) {
            appendLog("Termux environment not yet installed; cannot start Node.js proxy")
            return false
        }

        return try {
            val cmd = "exec node ${proxyScript.absolutePath}"
            val pb = ProcessBuilder(shell, "-c", cmd)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.directory(File(paths.homeDir))
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proxyProcess = proc
            _isConnectProxyRunning.value = true

            Thread {
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var line = reader.readLine()
                while (line != null) {
                    appendLog("[proxy] $line")
                    line = reader.readLine()
                }
                _isConnectProxyRunning.value = false
            }.start()
            appendLog("CONNECT proxy started on 127.0.0.1:$CONNECT_PROXY_PORT")
            true
        } catch (e: Exception) {
            appendLog("Failed to start proxy: ${e.message}")
            _isConnectProxyRunning.value = false
            false
        }
    }

    fun stopProxy() {
        proxyProcess?.destroy()
        proxyProcess = null
        _isConnectProxyRunning.value = false
        appendLog("CONNECT proxy stopped")
    }

    // ── OpenClaw Gateway ─────────────────────────────────────────────────

    fun startOpenClawGateway(): Boolean {
        if (_isGatewayRunning.value && openClawGatewayProcess != null) return true
        syncCodexAuthToOpenClaw()

        val paths = BootstrapInstaller.getPaths(context)
        val shell = "${paths.prefixDir}/bin/sh"
        if (!File(shell).exists()) {
            appendLog("Termux environment not found. Please run Bootstrap setup first.")
            return false
        }

        val env = buildEnvironment(paths)
        val cmd = "exec openclaw gateway run --force --port $OPENCLAW_GATEWAY_PORT 2>&1"
        return try {
            val pb = ProcessBuilder(shell, "-c", cmd)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.directory(File(paths.homeDir))
            pb.redirectErrorStream(true)
            val proc = pb.start()
            openClawGatewayProcess = proc
            _isGatewayRunning.value = true

            Thread {
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var line = reader.readLine()
                while (line != null) {
                    appendLog("[openclaw-gw] $line")
                    line = reader.readLine()
                }
                _isGatewayRunning.value = false
            }.start()
            appendLog("OpenClaw gateway process launched on port $OPENCLAW_GATEWAY_PORT")
            true
        } catch (e: Exception) {
            appendLog("Failed to start OpenClaw gateway: ${e.message}")
            _isGatewayRunning.value = false
            false
        }
    }

    fun stopOpenClawGateway() {
        openClawGatewayProcess?.destroy()
        openClawGatewayProcess = null
        _isGatewayRunning.value = false
        appendLog("OpenClaw gateway stopped")
    }

    // ── OpenClaw Control UI Server ───────────────────────────────────────

    fun startOpenClawControlUiServer(): Boolean {
        if (_isControlUiRunning.value && openClawControlUiProcess != null) return true
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val controlUiRoot = "$prefix/lib/node_modules/openclaw/dist/control-ui"

        val env = buildEnvironment(paths)
        val shell = "${paths.prefixDir}/bin/sh"
        if (!File(shell).exists()) {
            appendLog("Prefix environment not available for Control UI")
            return false
        }

        val serverScript = """
            node -e "
              const http = require('http');
              const fs = require('fs');
              const path = require('path');
              const root = '$controlUiRoot';
              const mimeTypes = {
                '.html':'text/html','.js':'application/javascript',
                '.css':'text/css','.json':'application/json',
                '.svg':'image/svg+xml','.png':'image/png',
                '.woff2':'font/woff2','.woff':'font/woff',
              };
              http.createServer((req, res) => {
                let url = req.url.split('?')[0];
                if (url === '/') url = '/index.html';
                const fp = path.join(root, url);
                fs.readFile(fp, (err, data) => {
                  if (err) {
                    fs.readFile(path.join(root,'index.html'), (e2, d2) => {
                      if (e2) { res.writeHead(404); return res.end('Not Found'); }
                      res.writeHead(200, {'Content-Type':'text/html'});
                      res.end(d2);
                    });
                    return;
                  }
                  const ext = path.extname(fp);
                  res.writeHead(200, {'Content-Type': mimeTypes[ext]||'application/octet-stream'});
                  res.end(data);
                });
              }).listen($OPENCLAW_CONTROL_UI_PORT, '127.0.0.1', () => console.log('Control UI on port $OPENCLAW_CONTROL_UI_PORT'));
            " 2>&1
        """.trimIndent()

        return try {
            val pb = ProcessBuilder(shell, "-c", "exec $serverScript")
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.directory(File(paths.homeDir))
            pb.redirectErrorStream(true)
            val proc = pb.start()
            openClawControlUiProcess = proc
            _isControlUiRunning.value = true

            Thread {
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var line = reader.readLine()
                while (line != null) {
                    appendLog("[openclaw-ui] $line")
                    line = reader.readLine()
                }
                _isControlUiRunning.value = false
            }.start()
            appendLog("Control UI server launched on http://127.0.0.1:$OPENCLAW_CONTROL_UI_PORT")
            true
        } catch (e: Exception) {
            appendLog("Failed to start Control UI: ${e.message}")
            _isControlUiRunning.value = false
            false
        }
    }

    fun stopOpenClawControlUiServer() {
        openClawControlUiProcess?.destroy()
        openClawControlUiProcess = null
        _isControlUiRunning.value = false
        appendLog("Control UI server stopped")
    }

    fun stopAll() {
        stopOpenClawGateway()
        stopOpenClawControlUiServer()
        stopProxy()
        serverProcess?.destroy()
        serverProcess = null
        _isCodexServerRunning.value = false
        appendLog("All OpenClaw processes stopped")
    }

    // ── Environment Builder ──────────────────────────────────────────────

    private fun buildEnvironment(paths: BootstrapInstaller.Paths): Map<String, String> {
        val bionicCompat = "${paths.homeDir}/.openclaw-android/patches/bionic-compat.js"
        val bionicCompatOpt = if (File(bionicCompat).exists()) " -r $bionicCompat" else ""
        return mapOf(
            "PREFIX" to paths.prefixDir,
            "HOME" to paths.homeDir,
            "PATH" to "${paths.prefixDir}/bin:${paths.prefixDir}/bin/applets:/system/bin",
            "LD_LIBRARY_PATH" to "${paths.prefixDir}/lib",
            "LD_PRELOAD" to "${paths.prefixDir}/lib/libtermux-exec.so",
            "TERMUX_PREFIX" to paths.prefixDir,
            "TERMUX__PREFIX" to paths.prefixDir,
            "LANG" to "en_US.UTF-8",
            "TMPDIR" to paths.tmpDir,
            "TMP" to paths.tmpDir,
            "TEMP" to paths.tmpDir,
            "PROOT_TMP_DIR" to paths.tmpDir,
            "TERM" to "xterm-256color",
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            "APT_CONFIG" to "${paths.prefixDir}/etc/apt/apt.conf",
            "DPKG_ADMINDIR" to "${paths.prefixDir}/var/lib/dpkg",
            "SSL_CERT_FILE" to "${paths.prefixDir}/etc/tls/cert.pem",
            "SSL_CERT_DIR" to "/system/etc/security/cacerts",
            "CURL_CA_BUNDLE" to "${paths.prefixDir}/etc/tls/cert.pem",
            "GIT_SSL_CAINFO" to "${paths.prefixDir}/etc/tls/cert.pem",
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_EXEC_PATH" to "${paths.prefixDir}/libexec/git-core",
            "GIT_TEMPLATE_DIR" to "${paths.prefixDir}/share/git-core/templates",
            "OPENSSL_CONF" to "${paths.prefixDir}/etc/tls/openssl.cnf",
            "NODE_OPTIONS" to "--openssl-config=${paths.prefixDir}/etc/tls/openssl.cnf --unhandled-rejections=warn$bionicCompatOpt",
            "CONTAINER" to "1",
        )
    }
}
