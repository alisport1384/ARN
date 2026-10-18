package com.bigrocket.service

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import studio.cluvex.aether.core.PortProbe
import java.io.File

/**
 * Runs the embedded Xray core for BigRocket's two Xray chains. Deliberately a
 * structural mirror of EmbeddedAetherRuntime: Service-owned lifecycle, a
 * trafficReady StateFlow the VpnService reacts to, and PortProbe as the
 * ground-truth readiness signal on the local SOCKS listener.
 *
 * [start]'s `chainPort` selects which chain this Xray instance belongs to:
 *  - BondingSocksServer.PORT  → Direct + Xray
 *  - TunnelConfig.SOCKS_PORT  → Direct + Aether + Xray (Xray dials through Aether)
 */
object EmbeddedXrayRuntime {

    const val SOCKS_HOST = "127.0.0.1"

    /** Loopback-only; picked to avoid Aether's 1819 and BondingSocksServer's 12347. */
    const val SOCKS_PORT = 1839

    private const val CONFIG_FILE = "xray-config.json"
    private const val STARTUP_TIMEOUT_MS = 20_000L

    /**
     * Keep-alive cadence for the core, in every mode that contains Xray.
     *
     * The core is a separate OS process, so it can die on its own (OOM, a config
     * that only fails on a later packet, a signal from the system) and nothing in
     * the app would notice: `trafficReady` is a latch, and the router would keep
     * handing packets to a listener nobody is serving. The check below is a plain
     * liveness probe - process alive AND the local SOCKS5 listener accepting -
     * and when it fails the core is brought back up on the same chain target.
     *
     * Deliberately NOT a failover to direct traffic: an Xray chain that loses its
     * core must stay closed (connections refused), never silently drop to the
     * bonding boundary without the proxy in front of it.
     */
    private const val KEEP_ALIVE_INTERVAL_MS = 3_000L

    /** Probe timeout inside the keep-alive check; a loopback connect, so it is fast. */
    private const val KEEP_ALIVE_PROBE_TIMEOUT_MS = 1_000

    /**
     * Restart budget inside [KEEP_ALIVE_WINDOW_MS]. A config that cannot stay up
     * must not be restarted every three seconds forever - that is a crash loop
     * that burns battery and floods the log, so after this many the keep-alive
     * stands down and says why.
     */
    private const val KEEP_ALIVE_MAX_RESTARTS = 3
    private const val KEEP_ALIVE_WINDOW_MS = 60_000L

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var job: Job? = null
    private var process: XrayProcess? = null

    // Keep-alive bookkeeping: the app context to restart with, the loop job, and
    // the recent restart stamps for the budget above.
    @Volatile private var keepAliveContext: Context? = null
    private var keepAliveJob: Job? = null
    private val restartStamps = ArrayList<Long>()

    // What the live core was started with, so applyUpstreamChoice() can detect a
    // stale instance (chain switched, or the user saved a new config) and restart it.
    @Volatile private var activeChainPort = -1
    @Volatile private var activeConfigFingerprint = 0

    private val _trafficReady = MutableStateFlow(false)
    val trafficReady: StateFlow<Boolean> = _trafficReady.asStateFlow()

    // @Synchronized on start/stop: unlike Aether (started only under controlMutex), Xray
    // has two legitimate start paths - applyUpstreamChoice() and the readiness collector's
    // second-stage launch - which run on different coroutines. The guard below must be
    // atomic against both, or a double core spawn would fight over the SOCKS port.
    @Synchronized
    fun start(context: Context, chainPort: Int) {
        if (job?.isActive == true || isRunning()) return
        _trafficReady.value = false
        val app = context.applicationContext
        job = scope.launch {
            runCatching {
                AppLogger.log("Xray", "starting core (socks=$SOCKS_PORT chainPort=$chainPort)")
                val raw = XrayConfigStore.read(app)
                    ?: error("هیچ پیکربندی Xray ثبت نشده است - ابتدا کانفیگ را در صفحه اصلی ذخیره کنید")
                val configJson = XrayConfigBuilder.build(raw, SOCKS_PORT, chainPort)
                val configFile = File(app.filesDir, CONFIG_FILE)
                configFile.writeText(configJson)

                val engine = XrayProcess(app.applicationInfo.nativeLibraryDir, app.filesDir)
                process = engine
                activeChainPort = chainPort
                activeConfigFingerprint = raw.hashCode()
                engine.start(configFile.absolutePath)

                val open = PortProbe.awaitOpen(
                    SOCKS_HOST,
                    SOCKS_PORT,
                    STARTUP_TIMEOUT_MS,
                    isEngineAlive = { engine.isAlive() },
                )
                if (!open) error("Xray SOCKS5 listener did not become ready")
                AppLogger.log("Xray", "core ready on $SOCKS_HOST:$SOCKS_PORT (chainPort=$chainPort)")
                _trafficReady.value = true
                startKeepAlive(app, chainPort)
            }.onFailure {
                // it is already a Throwable (runCatching) - wrapping it in another Exception
                // would relabel the logged error's type as generic "Exception" and bury the
                // real one (e.g. IllegalStateException from the error()s above) as .cause.
                AppLogger.logError("Xray", "start failed", it)
                _trafficReady.value = false
                stopInternal()
            }
        }
    }

    @Synchronized
    fun stop() {
        _trafficReady.value = false
        job?.cancel()
        job = null
        stopKeepAlive()
        stopInternal()
        AppLogger.log("Xray", "core stopped")
    }

    // ---- keep-alive ---------------------------------------------------------

    /**
     * True when the active upstream choice still contains Xray, so a live core is
     * wanted at all. Without this the keep-alive would resurrect a core the user
     * has just switched away from.
     */
    private fun usesXray(context: Context): Boolean =
        when (EmbeddedAetherRuntime.readUpstreamChoice(context)) {
            EmbeddedAetherRuntime.UpstreamChoice.XRAY,
            EmbeddedAetherRuntime.UpstreamChoice.AETHER_XRAY,
            EmbeddedAetherRuntime.UpstreamChoice.XRAY_AETHER,
            -> true
            else -> false
        }

    private fun startKeepAlive(context: Context, chainPort: Int) {
        stopKeepAlive()
        val app = context.applicationContext
        keepAliveContext = app
        keepAliveJob = scope.launch {
            while (isActive) {
                delay(KEEP_ALIVE_INTERVAL_MS)
                if (!isActive) return@launch
                runCatching {
                    val proc = process
                    if (proc == null) {
                        // Stopped on purpose (mode switch, disconnect): nothing to
                        // keep alive, and resurrecting a core here would fight the
                        // next start().
                        stopKeepAlive()
                        return@runCatching
                    }
                    if (BigRocketVpnService.runningInstance == null || !usesXray(app)) {
                        stopKeepAlive()
                        return@runCatching
                    }
                    if (proc.isAlive() &&
                        PortProbe.isOpen(SOCKS_HOST, SOCKS_PORT, KEEP_ALIVE_PROBE_TIMEOUT_MS)
                    ) {
                        return@runCatching
                    }
                    AppLogger.log(
                        "Xray",
                        "keep-alive: core is ${if (proc.isAlive()) "hung" else "dead"} " +
                            "(alive=${proc.isAlive()} port=$SOCKS_PORT) - restarting on chainPort=$chainPort",
                    )
                    restartQuietly(app, chainPort)
                }.onFailure {
                    AppLogger.logError("Xray", "keep-alive check failed", it)
                }
            }
        }
    }

    private fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        keepAliveContext = null
    }

    /**
     * Brings the core back without touching [trafficReady]: the chain entry point
     * does not move, so hev and the relay engines keep pointing at the same local
     * listener. While the core is down that listener refuses connections, which
     * is the correct failure mode for a proxied chain - it must never degrade to
     * direct traffic.
     */
    private suspend fun restartQuietly(context: Context, chainPort: Int) {
        if (!withinRestartBudget()) {
            AppLogger.log(
                "Xray",
                "keep-alive: $KEEP_ALIVE_MAX_RESTARTS restarts inside " +
                    "${KEEP_ALIVE_WINDOW_MS / 1000}s - standing down (reconnect to try again)",
            )
            stopKeepAlive()
            return
        }
        val app = context.applicationContext
        // The chain target may have moved while the core was down; restarting the
        // old one would leave the live chain dialling the wrong upstream.
        if (!usesXray(app) || BigRocketVpnService.runningInstance == null) {
            stopKeepAlive()
            return
        }
        // The service may have re-pointed the chain (or restarted the core itself)
        // while this check was running. A core started here would then run against
        // a chain target that is no longer the live one, so stand down instead and
        // let the service's own start() arm the current keep-alive.
        if (activeChainPort != chainPort) {
            AppLogger.log(
                "Xray",
                "keep-alive: chain target moved to $activeChainPort while checking - standing down",
            )
            stopKeepAlive()
            return
        }
        val raw = XrayConfigStore.read(app)
        if (raw == null) {
            AppLogger.log("Xray", "keep-alive: no stored Xray config - nothing to restart with")
            stopKeepAlive()
            return
        }
        val configFile = File(app.filesDir, CONFIG_FILE)
        configFile.writeText(XrayConfigBuilder.build(raw, SOCKS_PORT, chainPort))
        stopInternal()
        val engine = XrayProcess(app.applicationInfo.nativeLibraryDir, app.filesDir)
        process = engine
        activeChainPort = chainPort
        activeConfigFingerprint = raw.hashCode()
        engine.start(configFile.absolutePath)
        val open = PortProbe.awaitOpen(
            SOCKS_HOST,
            SOCKS_PORT,
            STARTUP_TIMEOUT_MS,
            isEngineAlive = { engine.isAlive() },
        )
        if (open) {
            AppLogger.log("Xray", "keep-alive: core back on $SOCKS_HOST:$SOCKS_PORT")
        } else {
            AppLogger.log("Xray", "keep-alive: core did not come back within ${STARTUP_TIMEOUT_MS}ms")
        }
    }

    /** Restart budget: at most [KEEP_ALIVE_MAX_RESTARTS] inside [KEEP_ALIVE_WINDOW_MS]. */
    private fun withinRestartBudget(): Boolean {
        val now = System.currentTimeMillis()
        synchronized(restartStamps) {
            restartStamps.removeAll { now - it > KEEP_ALIVE_WINDOW_MS }
            if (restartStamps.size >= KEEP_ALIVE_MAX_RESTARTS) return false
            restartStamps.add(now)
            return true
        }
    }

    private fun stopInternal() {
        runCatching { process?.stop() }
        process = null
        activeChainPort = -1
        activeConfigFingerprint = 0
    }

    fun isRunning(): Boolean = process?.isAlive() == true

    fun isTrafficReady(): Boolean = _trafficReady.value

    /**
     * True when a live core no longer matches the desired chain target or the
     * currently-saved user config, so the caller must stop() before start().
     */
    fun needsRestart(context: Context, chainPort: Int): Boolean {
        if (!isRunning()) return false
        if (chainPort != activeChainPort) {
            AppLogger.log("Xray", "needsRestart: chainPort $activeChainPort -> $chainPort")
            return true
        }
        val fingerprint = XrayConfigStore.read(context.applicationContext)?.hashCode() ?: 0
        val changed = fingerprint != activeConfigFingerprint
        if (changed) AppLogger.log("Xray", "needsRestart: saved config changed")
        return changed
    }
}
