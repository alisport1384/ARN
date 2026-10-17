package studio.cluvex.aether.core

import org.json.JSONObject

/**
 * psiphon-tunnel-core notice stream, read off the ENGINE's own stdout.
 *
 * Two transport modes exist, and the notices travel differently in each:
 *
 *  * **psiphon alone** (`--psiphon` = only): the engine starts the client and
 *    re-emits every notice it receives, as JSON, on its own stdout. Those lines
 *    carry the noticeType the readiness logic needs.
 *  * **psiphon through the tunnel** (`--psiphon` = chain, the BigRocket
 *    "Direct + Aether + Psiphon" mode): the engine consumes the client's notice
 *    stream itself to decide when psiphon is usable, and afterwards renders each
 *    notice as a human-readable line (`[+] psiphon reached a server at ...`,
 *    `[-] psiphon: ...`). Raw notice JSON therefore does NOT appear on the
 *    engine's stdout in this mode.
 *
 * The one signal that always does is the engine's own ready line:
 *
 *     [+] psiphon is ready; 127.0.0.1:1827 leaves through psiphon, carried by the tunnel
 *
 * which `run_chain` logs only after the client has BOTH established a tunnel
 * (notice "Tunnels", count > 0) AND opened its SOCKS listener
 * (notice "ListeningSocksProxyPort"). Because the client dials through the
 * tunnel underneath it, that line also proves the lower chain is carrying
 * traffic. [ingest] keys readiness on it.
 */
object PsiphonEngine {
    data class Snapshot(
        val ready: Boolean = false,
        val port: Int = 0,
        val connectedServers: Int = 0,
        val failure: String? = null,
    )

    private const val TAG = "PsiphonEngine"

    @Volatile
    private var snap = Snapshot()

    private val lock = Any()

    fun snapshot(): Snapshot = snap

    fun reset() {
        synchronized(lock) { snap = Snapshot() }
    }

    /**
     * One line of the ENGINE's stdout. Cheap and non-blocking: called from the
     * engine's log-drain thread, never from a coroutine.
     */
    fun ingest(line: String) {
        if (line.isBlank()) return
        if (line.contains("noticeType")) {
            ingestNotice(line)
            return
        }
        if ("[+] psiphon is ready" in line) {
            synchronized(lock) {
                if (!snap.ready) {
                    DiagnosticsLog.i(TAG, line)
                    snap = snap.copy(ready = true)
                }
            }
            return
        }
        if ("psiphon stopped" in line) {
            synchronized(lock) {
                if (!snap.ready) {
                    DiagnosticsLog.w(TAG, line)
                    snap = snap.copy(failure = line)
                }
            }
            return
        }
        if (line.startsWith("[-] psiphon:") || "psiphon: " in line) {
            DiagnosticsLog.w(TAG, line)
        }
    }

    /** Raw psiphon notice JSON, re-emitted by the engine in `--psiphon` = only mode. */
    private fun ingestNotice(line: String) {
        runCatching {
            val obj = JSONObject(line)
            when (obj.optString("noticeType")) {
                "ListeningSocksProxyPort" -> {
                    val port = obj.optJSONObject("data")?.optInt("port", 0) ?: 0
                    synchronized(lock) {
                        snap = snap.copy(port = port)
                        if (!snap.ready) DiagnosticsLog.i(TAG, "psiphon SOCKS listening on $port")
                    }
                }
                "Tunnels" -> {
                    val count = obj.optJSONObject("data")?.optInt("count", 0) ?: 0
                    synchronized(lock) {
                        snap = snap.copy(
                            connectedServers = count,
                            ready = snap.ready || count > 0,
                        )
                    }
                }
                "Error", "InternalError" -> {
                    val message = obj.optJSONObject("data")?.optString("message")
                    if (!message.isNullOrBlank()) {
                        synchronized(lock) {
                            if (!snap.ready) {
                                snap = snap.copy(failure = message)
                                DiagnosticsLog.e(TAG, message)
                            }
                        }
                    }
                }
            }
        }
    }
}
