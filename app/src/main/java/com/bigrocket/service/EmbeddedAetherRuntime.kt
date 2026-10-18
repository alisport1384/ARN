package com.bigrocket.service

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import studio.cluvex.aether.core.AetherController
import studio.cluvex.aether.core.AetherProcess
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.ExitRegions
import studio.cluvex.aether.core.EngineMeta
import studio.cluvex.aether.core.PortProbe
import studio.cluvex.aether.core.NetProbe
import studio.cluvex.aether.core.PsiphonBootstrap
import studio.cluvex.aether.core.PsiphonEngine
import studio.cluvex.aether.core.TunnelConfig
import studio.cluvex.aether.data.ProfileStore
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.Protocol

/** Runs Aether in embedded proxy mode so BigRocket remains the only Android VPN. */
object EmbeddedAetherRuntime {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var job: Job? = null
    private var process: AetherProcess? = null

    private const val UPSTREAM_PREFS = "bigrocket_upstream"
    private const val KEY_UPSTREAM_CHOICE = "upstream_choice"

    /**
     * How long the engine's Psiphon stage may take to establish its own tunnel,
     * on top of the engine's own startup budget. The engine's own default for
     * the same wait is 180 s; this adds the same again because psiphon has to
     * connect through a tunnel that was itself just established.
     */
    private const val PSIPHON_READY_BUDGET_MS = 240_000L

    /** Poll cadence and probe timeout while waiting for that stage. */
    private const val PSIPHON_POLL_MS = 1_000L
    private const val PSIPHON_PROBE_TIMEOUT_MS = 4_000

    /**
     * What the readiness probe dials through psiphon's SOCKS5. An IP literal on
     * port 80, so the probe says nothing about DNS and everything about the
     * tunnel actually carrying TCP.
     */
    private const val PSIPHON_PROBE_IP = "1.1.1.1"
    private const val PSIPHON_PROBE_PORT = 80

    /**
     * Owned here, not in MainActivity: the Service must be able to read (and start/stop
     * Aether from) this choice on its own, independent of whether any Activity currently
     * exists - that is the whole point of this step (see learnings-and-working-style: engine
     * lifecycle must be Service-owned, not Activity-owned, or a recreated/reopened Activity
     * can race a healthy running engine - which is exactly what previously caused the
     * reconnect flap on reopening the app).
     *
     * [XRAY] = Direct + Xray (TUN traffic → Xray → bonding boundary → physical paths).
     * [AETHER_XRAY] = Direct + Aether + Xray (TUN traffic → Xray → Aether → bonding
     * boundary → physical paths). [XRAY_AETHER] = Direct + Xray + Aether (TUN traffic
     * → Aether → Xray → bonding boundary → physical paths: Aether is the chain entry
     * point and its own outbound dials through Xray's local SOCKS instead of the
     * bonding boundary directly). [AETHER_PSIPHON] = Direct + Aether + Psiphon
     * (TUN traffic → the psiphon-tunnel-core client the engine spawns → Aether's
     * tunnel → bonding boundary → physical paths: Psiphon is the chain entry point
     * and its own outbound dials through Aether's local SOCKS). All are orchestrated
     * by BigRocketVpnService's applyUpstreamChoice(), exactly like NONE/AETHER.
     */
    enum class UpstreamChoice { NONE, AETHER, XRAY, AETHER_XRAY, XRAY_AETHER, AETHER_PSIPHON }

    /** Synchronous on purpose - see loadUpstreamChoice()'s callers for why. */
    fun readUpstreamChoice(context: Context): UpstreamChoice {
        val name = context.getSharedPreferences(UPSTREAM_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_UPSTREAM_CHOICE, null)
        return runCatching { UpstreamChoice.valueOf(name ?: "") }.getOrDefault(UpstreamChoice.NONE)
    }

    fun saveUpstreamChoice(context: Context, choice: UpstreamChoice) {
        context.getSharedPreferences(UPSTREAM_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_UPSTREAM_CHOICE, choice.name)
            .apply()
    }

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _trafficReady = MutableStateFlow(false)
    val trafficReady: StateFlow<Boolean> = _trafficReady.asStateFlow()

    // Which local SOCKS port the LIVE engine chains its own outbound through
    // (BondingSocksServer.PORT, or Xray's listener in the Direct+Xray+Aether chain).
    // Lets applyUpstreamChoice() detect a stale instance after a chain switch.
    @Volatile private var activeUpstreamPort = -1

    // Whether the LIVE engine was started with its Psiphon stage on. The engine
    // reads AETHER_PSIPHON from its environment at startup only, so switching
    // between Direct+Aether and Direct+Aether+Psiphon is a restart, exactly like
    // changing the upstream port above.
    @Volatile private var activeEnginePsiphon = false

    fun start(context: Context, profile: ConnectionProfile, upstreamPort: Int = BondingSocksServer.PORT) {
        if (job?.isActive == true) return
        _enabled.value = true
        _trafficReady.value = false
        val app = context.applicationContext
        activeUpstreamPort = upstreamPort
        activeEnginePsiphon = profile.enginePsiphon
        job = scope.launch {
            runCatching {
                AetherController.setState(ConnectionState.Launching)
                EngineMeta.reset()
                // Aether must consume BigRocket's already-bonded transport. Its own
                // physical dials are therefore chained through a local SOCKS5 boundary
                // instead of going directly to Wi-Fi/Cellular: the bonding boundary
                // itself by default, or Xray's listener when Aether rides on top of
                // Xray in the Direct+Xray+Aether chain.
                //
                // Direct+Xray+Aether ONLY: every default Aether transport (WireGuard,
                // gool, MASQUE over QUIC) is UDP, and here that UDP has to ride
                // UDP-over-VLESS through the user's Xray server. Real-world VLESS
                // provider configs do not carry UDP (they ship `"network":"udp" ->
                // block` rules), so every UDP transport starves silently and "Aether
                // cannot connect after Xray". Probing UDP transit at runtime is NOT
                // reliable either: these same configs hijack port 53 (`"port":53 ->
                // dns-out` with a local DNS answer), so a DNS-shaped probe gets a
                // locally fabricated reply and falsely reports "UDP works" while
                // every WireGuard/QUIC datagram still dies - which is exactly how the
                // first field fix failed. The only deterministic carrier over this
                // upstream is TCP, so this chain ALWAYS runs MASQUE over HTTP/2 -
                // the engine's own documented remedy for TCP-only upstreams (it
                // applies the same h2 fallback for its tor/psiphon reverse chains and
                // for http:// upstreams). TCP transit is guaranteed by construction:
                // Xray only reports trafficReady after carrying real TCP traffic.
                // Chains that dial the bonding boundary directly are untouched.
                val xrayChained = upstreamPort == EmbeddedXrayRuntime.SOCKS_PORT
                val embeddedProfile = if (xrayChained) {
                    DiagnosticsLog.i(
                        "bigrocket",
                        "Aether rides on Xray: forcing MASQUE over HTTP/2 (TCP carrier) - " +
                            "VLESS upstreams cannot be trusted to carry UDP",
                    )
                    profile.copy(
                        proxyMode = true,
                        upstreamProxy = "socks5://127.0.0.1:$upstreamPort",
                        protocol = Protocol.MASQUE,
                        masqueHttp2 = true,
                        // A pinned single endpoint keeps its port on the h2 carrier
                        // (h2_peer() only rewrites it when AETHER_MASQUE_H2_PEER is
                        // set), so a WireGuard-style pin like ip:2408 would dial a
                        // dead TCP port forever. Let the engine scan for an h2
                        // gateway itself; a pinned RANGE stays honoured because the
                        // h2 prober works within it.
                        endpointMode = if (profile.endpointMode == studio.cluvex.aether.model.EndpointMode.MANUAL_PEER) {
                            studio.cluvex.aether.model.EndpointMode.AUTO
                        } else {
                            profile.endpointMode
                        },
                    )
                } else {
                    profile.copy(
                        proxyMode = true,
                        upstreamProxy = "socks5://127.0.0.1:$upstreamPort"
                    )
                }
                // Persist only what every working build persisted (proxy mode + the
                // upstream boundary). The forced MASQUE/h2 transport above is a
                // property of THIS chain's launch, not a user setting: writing it to
                // the store would silently flip the user's chosen protocol for the
                // plain Direct+Aether mode afterwards.
                ProfileStore(app).save(
                    profile.copy(
                        proxyMode = true,
                        upstreamProxy = "socks5://127.0.0.1:$upstreamPort"
                    )
                )
                AppLogger.log(
                    "Aether",
                    "starting engine: upstream=socks5://127.0.0.1:$upstreamPort " +
                        "protocol=${embeddedProfile.protocol} h2=${embeddedProfile.masqueHttp2} " +
                        "scan=${embeddedProfile.scanMode} endpoint=${embeddedProfile.endpointMode}" +
                        (if (embeddedProfile.enginePsiphon) {
                            " psiphon=chain exit=${ExitRegions.name(embeddedProfile.enginePsiphonRegion)}"
                        } else {
                            ""
                        }),
                )
                var attemptProfile = embeddedProfile
                var engine: AetherProcess
                while (true) {
                    if (attemptProfile.enginePsiphon) {
                        // Re-staged on every attempt: a retry without a region is
                        // a new config, and the trust store must already be on
                        // disk before the engine spawns its Psiphon stage.
                        PsiphonBootstrap.prepare(app)
                    }
                    engine = AetherProcess(app.applicationInfo.nativeLibraryDir, app.filesDir)
                    process = engine
                    engine.start(attemptProfile)
                    AetherController.setState(ConnectionState.Connecting)
                    // GOOL establishes two sequential WireGuard layers before the
                    // native SOCKS5 listener is created. Use the protocol's full
                    // startup budget here instead of failing while the inner tunnel
                    // is still legitimately being established.
                    val startupTimeoutMs = attemptProfile.connectTimeoutMs().coerceAtLeast(10_000L)
                    // NOTE: embeddedProfile.upstreamProxy ("socks5://127.0.0.1:${BondingSocksServer.PORT}")
                    // is where Aether sends ITS OWN outbound traffic (the --upstream target).
                    // It is unrelated to the port Aether exposes ITS OWN SOCKS5 relay on for us
                    // to consume — that is always the fixed TunnelConfig.SOCKS_PORT (1819).
                    // Probing BondingSocksServer's port here always returns "open" immediately
                    // (it's up from VPN start), which made every protocol falsely report
                    // Connected before Aether's tunnel was actually ready.
                    val open = PortProbe.awaitOpen(
                        TunnelConfig.SOCKS_HOST,
                        TunnelConfig.SOCKS_PORT,
                        startupTimeoutMs,
                        isEngineAlive = { engine.isAlive() },
                    )
                    if (!open) {
                        AppLogger.log(
                            "Aether",
                            "SOCKS listener NOT ready after ${startupTimeoutMs}ms " +
                                "(engineAlive=${engine.isAlive()}) - giving up this attempt",
                        )
                        error("Aether SOCKS5 listener did not become ready")
                    }
                    AppLogger.log("Aether", "SOCKS listener ready on ${TunnelConfig.SOCKS_HOST}:${TunnelConfig.SOCKS_PORT}")
                    if (!attemptProfile.enginePsiphon) break
                    if (awaitPsiphonReady(engine, attemptProfile, startupTimeoutMs)) break
                    // A hand-picked exit country is a hard filter on Psiphon's
                    // side: no server offering it right now means no tunnel at
                    // all. One retry with an automatic exit beats no connection -
                    // the user still gets out, just not from the country they
                    // picked, and the log says so plainly.
                    val wanted = ExitRegions.normalize(attemptProfile.enginePsiphonRegion)
                    if (wanted.isNotEmpty()) {
                        AppLogger.log(
                            "Aether",
                            "psiphon has no exit in ${ExitRegions.name(wanted)} right now - " +
                                "retrying once with an automatic exit",
                        )
                        stopInternal()
                        attemptProfile = attemptProfile.copy(enginePsiphonRegion = "")
                        continue
                    }
                    error(
                        "Psiphon did not become ready on " +
                            "${TunnelConfig.SOCKS_HOST}:${TunnelConfig.PSIPHON_SOCKS_PORT}",
                    )
                }

                AetherController.setState(ConnectionState.Verifying)
                AetherController.setIpLoading(true)
                val chainHost = TunnelConfig.SOCKS_HOST
                val chainPort = if (attemptProfile.enginePsiphon) {
                    TunnelConfig.PSIPHON_SOCKS_PORT
                } else {
                    TunnelConfig.SOCKS_PORT
                }
                val ip = NetProbe.fetchIpInfoViaSocks(chainHost, chainPort)
                AppLogger.log(
                    "Aether",
                    if (ip != null) "tunnel verified, exit ip=${ip.ip} country=${ip.countryCode}"
                    else "tunnel up but exit-IP verification returned nothing (continuing)",
                )
                AetherController.setIpInfo(ip?.let { studio.cluvex.aether.core.IpEndpoint(it.ip, it.countryCode, true) })
                AetherController.setIpLoading(false)
                AetherController.setState(ConnectionState.Connected("$chainHost:$chainPort"))
                _trafficReady.value = true
                AppLogger.log("Aether", "traffic ready - chain entry live")
            }.onFailure {
                AppLogger.logError("Aether", "start failed", it)
                AetherController.setState(ConnectionState.Error(it.message ?: "Aether connection failed"))
                _enabled.value = false
                _trafficReady.value = false
                stopInternal()
            }
        }
    }

    fun stop(context: Context) {
        if (process != null || job?.isActive == true) {
            AppLogger.log("Aether", "stopping engine (upstreamPort=$activeUpstreamPort)")
        }
        _enabled.value = false
        _trafficReady.value = false
        job?.cancel()
        job = null
        stopInternal()
        activeUpstreamPort = -1
        activeEnginePsiphon = false
        AetherController.setState(ConnectionState.Idle)
        EngineMeta.reset()
        PsiphonEngine.reset()
    }

    /**
     * True when the live engine's own outbound no longer chains through the SOCKS
     * port the desired chain requires, so the caller must stop() before start().
     */
    /**
     * Waits until the engine's Psiphon stage is usable.
     *
     * Readiness is established functionally, not from a log line: the client's
     * SOCKS5 listener must accept AND actually proxy a TCP connection through the
     * tunnel underneath it. A bare port probe goes green while psiphon is still
     * hunting for a server, which is exactly the "connected but nothing loads"
     * state this chain must not publish as ready. The engine's own ready line is
     * honoured too, for the log levels that expose it.
     *
     * Returns false when the engine dies, the client reports a failure, or the
     * budget runs out.
     */
    private suspend fun awaitPsiphonReady(
        engine: AetherProcess,
        profile: ConnectionProfile,
        startupTimeoutMs: Long,
    ): Boolean {
        val wanted = ExitRegions.normalize(profile.enginePsiphonRegion)
        if (wanted.isNotEmpty()) {
            DiagnosticsLog.i(
                "Aether",
                "psiphon is asked to leave from ${ExitRegions.name(wanted)}",
            )
        }
        val deadline = System.currentTimeMillis() + startupTimeoutMs + PSIPHON_READY_BUDGET_MS
        while (System.currentTimeMillis() < deadline) {
            if (!engine.isAlive()) return false
            val snapshot = PsiphonEngine.snapshot()
            if (snapshot.failure != null) return false
            if (snapshot.ready) return true
            val listening = PortProbe.isOpen(
                TunnelConfig.SOCKS_HOST,
                TunnelConfig.PSIPHON_SOCKS_PORT,
                PSIPHON_PROBE_TIMEOUT_MS,
            )
            if (listening &&
                NetProbe.checkTcpViaProxy(
                    TunnelConfig.SOCKS_HOST,
                    TunnelConfig.PSIPHON_SOCKS_PORT,
                    PSIPHON_PROBE_IP,
                    PSIPHON_PROBE_PORT,
                    PSIPHON_PROBE_TIMEOUT_MS,
                )
            ) {
                AppLogger.log(
                    "Aether",
                    "psiphon SOCKS5 ready on ${TunnelConfig.SOCKS_HOST}:${TunnelConfig.PSIPHON_SOCKS_PORT}" +
                        (if (wanted.isNotEmpty()) " (exit=${ExitRegions.name(wanted)})" else ""),
                )
                return true
            }
            delay(PSIPHON_POLL_MS)
        }
        val snapshot = PsiphonEngine.snapshot()
        AppLogger.log(
            "Aether",
            "psiphon stage not ready within ${startupTimeoutMs + PSIPHON_READY_BUDGET_MS}ms " +
                "(engineAlive=${engine.isAlive()} failure=${snapshot.failure})",
        )
        return false
    }

    fun needsRestart(upstreamPort: Int): Boolean = isRunning() && upstreamPort != activeUpstreamPort

    /**
     * Same question for the Psiphon stage: the engine reads AETHER_PSIPHON from
     * its environment at startup only, so a live engine that is running without
     * it cannot grow one later - it has to be restarted.
     */
    fun needsRestart(upstreamPort: Int, enginePsiphon: Boolean): Boolean =
        needsRestart(upstreamPort) || (isRunning() && enginePsiphon != activeEnginePsiphon)

    private fun stopInternal() {
        runCatching { process?.stop() }
        process = null
    }

    fun isRunning(): Boolean = process?.isAlive() == true

    fun isTrafficReady(): Boolean = _trafficReady.value
}
