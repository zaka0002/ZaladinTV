package com.zaladintv.app

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import java.util.Locale

sealed interface VpnState {
    data object Off : VpnState
    data object Connecting : VpnState
    data class On(val server: String) : VpnState
    /** Android has not (or no longer) allowed ZaladinTV to run a VPN: the user must accept the system prompt. */
    data object NeedsPermission : VpnState
    data class Failed(val message: String) : VpnState
}

/**
 * Optional WireGuard VPN for ZaladinTV's own traffic (every other app on the device keeps its normal
 * connection). Built in: Cloudflare WARP, free and without an account (see [Warp]). The user may
 * also send their own WireGuard files from the phone, one per country. Keys stay in app-private
 * storage, excluded from backups (see data_extraction_rules).
 */
class Vpn(private val context: Context, private val http: OkHttpClient) {
    private val sp = context.getSharedPreferences("vpn", Context.MODE_PRIVATE)
    private val backend by lazy { GoBackend(context.applicationContext) }

    private val _state = MutableStateFlow<VpnState>(VpnState.Off)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _countries = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Countries that have a configuration (country code → date it was sent to ZaladinTV). */
    val countries: StateFlow<Map<String, Long>> = _countries.asStateFlow()

    /** Always true: WARP needs nothing from the user. */
    private val _configured = MutableStateFlow(true)
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

    private val _choices = MutableStateFlow<List<String>>(emptyList())
    /** Servers the user can pick: WARP first, then the files sent from the phone. */
    val choices: StateFlow<List<String>> = _choices.asStateFlow()

    private val _selected = MutableStateFlow<String?>(null)
    /** Server the tunnel uses. */
    val selected: StateFlow<String?> = _selected.asStateFlow()

    private val _enabled = MutableStateFlow(sp.getBoolean(KEY_ENABLED, false))
    /** The user's switch; the tunnel follows it whenever the app runs. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val tunnel = object : Tunnel {
        override fun getName() = "zaladintv"
        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN && _state.value is VpnState.On) _state.value = VpnState.Off
        }
    }

    init {
        migrateSingleConfig()
        migrateToWarp()
        refresh()
    }

    /**
     * Imports every WireGuard configuration found in [raw] (several files may be pasted one after
     * the other). A country that already had one is replaced. Returns how many were valid.
     */
    fun importConfigs(raw: String): Int {
        // Files saved on Windows start with an invisible byte-order mark and end lines with CR LF;
        // copied text can carry zero-width or non-breaking spaces. None of them may hide "[Interface]".
        val text = raw.replace(Regex("[﻿​‌‍⁠]"), "").replace(' ', ' ')
            .replace("\r\n", "\n").replace('\r', '\n')
        val blocks = text.split(Regex("""(?m)(?=^\s*\[Interface])""")).map { it.trim() }.filter { it.isNotEmpty() }
        var count = 0
        var first: String? = null
        val edit = sp.edit()
        for (block in blocks) {
            if (parse(block) == null) continue
            val code = countryOf(block)
            edit.putString(KEY_CFG + code, block).putLong(KEY_IMP + code, System.currentTimeMillis())
            if (first == null) first = code
            count++
        }
        if (count == 0) return 0
        if (_selected.value == null || sp.getString(KEY_CFG + _selected.value, null) == null) edit.putString(KEY_SELECTED, first)
        edit.apply()
        refresh()
        return count
    }

    /** Switches server; reconnects at once when the VPN is on. */
    suspend fun select(code: String) {
        if (code !in _choices.value) return
        sp.edit().putString(KEY_SELECTED, code).apply()
        _selected.value = code
        if (_enabled.value) connect()
    }

    /** Forgets the files sent from the phone and the WARP key, and turns the VPN off. */
    suspend fun removeAll() {
        disconnect()
        val edit = sp.edit()
        sp.all.keys.filter { it.startsWith(KEY_CFG) || it.startsWith(KEY_IMP) }.forEach { edit.remove(it) }
        edit.remove(KEY_SELECTED).remove(KEY_WARP).putBoolean(KEY_ENABLED, false).apply()
        _enabled.value = false
        refresh()
    }

    /** System consent screen to show first, or null when ZaladinTV may already run its VPN. */
    fun permissionIntent(): Intent? = VpnService.prepare(context)

    fun setEnabled(on: Boolean) {
        sp.edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
    }

    /**
     * Brings the tunnel up on the selected server (WARP, or a file sent from the phone), keeping the
     * first candidate that really answers.
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        val code = _selected.value ?: return@withContext false
        if (VpnService.prepare(context) != null) {
            _state.value = VpnState.NeedsPermission
            return@withContext false
        }
        _state.value = VpnState.Connecting
        var error: String? = null
        val candidates = try {
            candidates(code).mapNotNull(::parse)
        } catch (e: Exception) {
            // WARP registration failed (no network, Cloudflare refused…).
            _state.value = VpnState.Failed(e.message ?: e.javaClass.simpleName)
            return@withContext false
        }
        if (candidates.isEmpty()) {
            _state.value = VpnState.Off
            return@withContext false
        }
        for (config in candidates) {
            try {
                backend.setState(tunnel, Tunnel.State.UP, config)
                if (handshake(config) && trafficFlows()) {
                    _state.value = VpnState.On(countryName(code))
                    return@withContext true
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
        }
        runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
        _state.value = VpnState.Failed(error ?: NO_ANSWER)
        false
    }

    /** True once the server answered (WireGuard handshake), within a few seconds. */
    private suspend fun handshake(config: Config): Boolean {
        val key = config.peers.firstOrNull()?.publicKey ?: return false
        repeat(16) {
            kotlinx.coroutines.delay(500)
            val at = runCatching { backend.getStatistics(tunnel).peer(key)?.latestHandshakeEpochMillis() ?: 0L }.getOrDefault(0L)
            if (at > 0) return true
        }
        return false
    }

    /**
     * A real request through the tunnel: some servers answer the WireGuard handshake even for a key
     * they will not carry traffic for, so the handshake alone proves nothing.
     */
    private suspend fun trafficFlows(): Boolean {
        // Android needs a moment to route the app through the new tunnel: a few tries over ~8 s.
        repeat(4) { attempt ->
            val ok = runCatching {
                probe.newCall(Request.Builder().url("https://connectivitycheck.gstatic.com/generate_204").build()).execute().use { it.code in 200..299 }
            }.onFailure { android.util.Log.w("ZaladinTV/VPN", "probe ${attempt + 1}: $it") }.getOrDefault(false)
            if (ok) return true
            kotlinx.coroutines.delay(1_500)
        }
        return false
    }

    /** Short timeouts; the name is resolved by the tunnel's own DNS (WARP: 1.1.1.1). */
    private val probe by lazy {
        OkHttpClient.Builder().connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    /** Configuration texts to try, best first: for WARP, Cloudflare's endpoints (registering once). */
    private suspend fun candidates(code: String): List<String> {
        if (code != WARP) return listOfNotNull(sp.getString(KEY_CFG + code, null))
        val id = Warp.decode(sp.getString(KEY_WARP, null))
            ?: Warp.register(http).also { sp.edit().putString(KEY_WARP, Warp.encode(it)).apply() }
        return Warp.configs(id)
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
        _state.value = VpnState.Off
    }

    /** App start: bring the tunnel up before anything goes to the network (bounded wait). */
    suspend fun startIfEnabled() {
        refresh()
        if (_enabled.value && _configured.value && _state.value !is VpnState.On) {
            withTimeoutOrNull(20_000) { connect() }
        }
    }

    /** Address the outside world sees for ZaladinTV's traffic (through the tunnel when it is up). */
    suspend fun publicIp(http: OkHttpClient): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("https://api.ipify.org").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string().orEmpty().trim()
        }
    }

    private fun refresh() {
        val map = sp.all.keys.filter { it.startsWith(KEY_CFG) }.map { it.removePrefix(KEY_CFG) }
            .associateWith { code ->
                sp.getLong(KEY_IMP + code, 0).takeIf { it > 0 } ?: System.currentTimeMillis().also {
                    sp.edit().putLong(KEY_IMP + code, it).apply()
                }
            }
        _countries.value = map
        val all = listOf(WARP) + map.keys.sorted()
        _choices.value = all
        _selected.value = sp.getString(KEY_SELECTED, null)?.takeIf { it in all } ?: WARP
    }

    /** WARP replaces Proton VPN: Proton files are dropped and WARP becomes the server. */
    private fun migrateToWarp() {
        if (sp.getBoolean(KEY_WARP_MIGRATED, false)) return
        val edit = sp.edit()
        sp.all.filter { it.key.startsWith(KEY_CFG) && isProton(it.value as? String ?: "") }.keys.forEach {
            edit.remove(it).remove(KEY_IMP + it.removePrefix(KEY_CFG))
        }
        edit.putString(KEY_SELECTED, WARP).remove("reminded_day").remove("servers_refreshed_at")
            .putBoolean(KEY_WARP_MIGRATED, true).apply()
        runCatching { java.io.File(context.filesDir, "proton_free.json").delete() }
    }

    /** Older versions kept a single configuration: file it under its country. */
    private fun migrateSingleConfig() {
        val old = sp.getString(LEGACY_CONFIG, null) ?: return
        val code = countryOf(old)
        sp.edit()
            .putString(KEY_CFG + code, old)
            .putLong(KEY_IMP + code, sp.getLong(LEGACY_IMPORTED, 0).takeIf { it > 0 } ?: System.currentTimeMillis())
            .putString(KEY_SELECTED, code)
            .remove(LEGACY_CONFIG).remove(LEGACY_IMPORTED)
            .apply()
    }

    /** Parses a wg-quick file and limits it to this app, whatever the file said about apps. */
    private fun parse(text: String): Config? = runCatching {
        val lines = text.lines().filterNot {
            val l = it.trim().lowercase()
            l.startsWith("includedapplications") || l.startsWith("excludedapplications")
        }
        // DNS servers of the file stay inside the tunnel even when private.
        val dns = lines.firstOrNull { it.trim().lowercase().startsWith("dns") }
            ?.substringAfter('=')?.split(',')?.map { it.trim() }
            ?.filter { it.matches(Regex("""\d+\.\d+\.\d+\.\d+""")) }.orEmpty()
        val out = StringBuilder()
        var added = false
        for (raw in lines) {
            // Full tunnel → everything except the local network, so a home IPTV server or the phone portal still work.
            val line = if (raw.trim().lowercase().startsWith("allowedips") && raw.contains("0.0.0.0/0")) {
                raw.replace("0.0.0.0/0", (PUBLIC_IPV4 + dns.map { "$it/32" }).joinToString(", "))
            } else raw
            out.append(line).append('\n')
            if (!added && line.trim().equals("[Interface]", ignoreCase = true)) {
                out.append("IncludedApplications = ").append(context.packageName).append('\n')
                added = true
            }
        }
        if (!added) return null
        if (lines.none { it.trim().lowercase().startsWith("persistentkeepalive") }) out.append("PersistentKeepalive = 25\n")
        Config.parse(BufferedReader(StringReader(out.toString())))
    }.getOrNull()

    companion object {
        private const val LEGACY_CONFIG = "config"
        private const val LEGACY_IMPORTED = "imported_at"
        private const val KEY_CFG = "cfg_"
        private const val KEY_IMP = "imp_"
        private const val KEY_SELECTED = "selected"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_WARP = "warp"
        private const val KEY_WARP_MIGRATED = "warp_migrated"

        /** Choice code of the built-in Cloudflare WARP server. */
        const val WARP = "WARP"

        private const val NO_ANSWER = "no server answered"

        /** A Proton VPN file (dropped when moving to WARP): its server names ("CA-FREE#24") or Proton's in-tunnel DNS. */
        fun isProton(text: String): Boolean =
            Regex("""(?m)^\s*#\s*[A-Z]{2}(-[A-Z]{2})?(-FREE)?#\d+""").containsMatchIn(text) || text.contains("10.2.0.1")

        /** Code for configurations whose server name gives no country. */
        const val OTHER = "ZZ"

        /** Country of a configuration, from the server name a provider writes in it ("# CA-FREE#24", "# NL#12"). */
        fun countryOf(text: String): String =
            Regex("""(?m)^\s*#\s*([A-Z]{2})(?:-[A-Z]{2})?(?:-FREE)?#\d+""").find(text)?.groupValues?.get(1) ?: OTHER

        /** Server name in the app's language. */
        fun countryName(code: String, language: String = Lang.code): String =
            if (code == WARP) "Cloudflare WARP" else if (code == OTHER) "WireGuard" else Locale("", code).getDisplayCountry(Locale(language)).ifBlank { code }

        /** IPv4 space minus private ranges (same list as the WireGuard app's "Exclude private IPs"). */
        private val PUBLIC_IPV4 = listOf(
            "0.0.0.0/5", "8.0.0.0/7", "11.0.0.0/8", "12.0.0.0/6", "16.0.0.0/4", "32.0.0.0/3", "64.0.0.0/2",
            "128.0.0.0/3", "160.0.0.0/5", "168.0.0.0/6", "172.0.0.0/12", "172.32.0.0/11", "172.64.0.0/10",
            "172.128.0.0/9", "173.0.0.0/8", "174.0.0.0/7", "176.0.0.0/4", "192.0.0.0/9", "192.128.0.0/11",
            "192.160.0.0/13", "192.169.0.0/16", "192.170.0.0/15", "192.172.0.0/14", "192.176.0.0/12",
            "192.192.0.0/10", "193.0.0.0/8", "194.0.0.0/7", "196.0.0.0/6", "200.0.0.0/5", "208.0.0.0/4",
        )
    }
}
