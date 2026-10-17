package studio.cluvex.aether.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * The app-side half of the engine's Psiphon stage.
 *
 * Core 2.3.0 spawns psiphon-tunnel-core itself (`AETHER_PSIPHON=chain`) with
 * AETHER_PSIPHON_BIN naming the executable. What the engine does NOT do is
 * everything the Android platform owes a static GOOS=linux Go binary:
 *
 *  1. **A trust store.** Go looks for CA roots in /etc/ssl/certs/...; Android
 *     has no such directory. Without one, the very first remote server-list
 *     download fails with `x509: certificate signed by unknown authority`,
 *     there are no servers, and nothing ever connects. [prepare] exports the
 *     system CAs (system aliases only - a user-installed CA is exactly what a
 *     TLS interception proxy installs, and Psiphon must not trust it) and points
 *     the client at them through both mechanisms it honours.
 *  2. **A server list.** The console client is started with no entries at all,
 *     so its only source of servers is that download. [prepare] stages the
 *     embedded list next to the trust store so the first dial has servers before
 *     any network access succeeds.
 *  3. **DNS.** On Android the static binary cannot read /etc/resolv.conf, so
 *     every resolver lookup is refused on the loopback address. The config
 *     overlay written by [prepare] names working resolvers for the client's own
 *     `DNSResolverAlternateServers` parameter.
 *
 * Everything is derived from the app's own files dir and is idempotent: the
 * trust store and the list are only rewritten when they change.
 */
object PsiphonBootstrap {
    private const val TAG = "PsiphonBootstrap"

    private const val STATE_DIR = "psiphon"
    private const val CA_BUNDLE = "ca.pem"
    private const val SERVER_LIST = "server_entries.txt"
    private const val BASE_CONFIG = "aether-overrides.json"

    /** Asset shipped in the APK: the server list the engine hands to the client. */
    private const val ASSET_SERVER_LIST = "server_entries.txt"

    /**
     * Fallback resolvers for the psiphon client's `DNSResolverAlternateServers`,
     * best first: the network's own resolvers, then these public ones on 53.
     * They are only reached from inside psiphon's own lookups, and psiphon's
     * traffic itself leaves through the engine's tunnel, so they see the
     * tunnel's exit, not the user's ISP.
     */
    private val PUBLIC_RESOLVERS = listOf(
        "1.1.1.1:53",
        "1.0.0.1:53",
        "8.8.8.8:53",
        "8.8.4.4:53",
        "9.9.9.9:53",
    )

    private const val MAX_RESOLVERS = 8

    private val SYSTEM_CA_DIRS = listOf(
        "/system/etc/security/cacerts",
        "/apex/com.android.conscrypt/cacerts",
    )

    /** Everything psiphon's own state lives in, beside the engine's working dir. */
    fun dir(filesDir: File): File = File(filesDir, STATE_DIR)

    /**
     * Writes the CA bundle, the embedded server list and the config overlay into
     * the engine's Psiphon directory. Called before a Psiphon session's engine is
     * launched; cheap enough (one PEM of ~150 certificates, one asset copy that
     * is skipped when unchanged) to run on every connect, which also means a
     * system CA update is picked up on the next session.
     */
    fun prepare(context: Context) {
        val dir = dir(context.filesDir).apply { mkdirs() }
        runCatching { writeCaBundle(File(dir, CA_BUNDLE)) }
            .onFailure { DiagnosticsLog.w(TAG, "Could not export the system CA store for Psiphon: ${it.message}") }
        runCatching { copyServerList(context, File(dir, SERVER_LIST)) }
            .onFailure { DiagnosticsLog.w(TAG, "Could not stage Psiphon's embedded server list: ${it.message}") }
        val resolvers = resolvers(context)
        runCatching { writeOverlay(dir, resolvers) }
            .onFailure { DiagnosticsLog.w(TAG, "Could not write Psiphon's config overlay: ${it.message}") }
        DiagnosticsLog.i(TAG, "Psiphon resolvers for untunneled lookups: ${resolvers.joinToString(" ")}")
    }

    /**
     * The DNS servers Psiphon may use for its UNTUNNELED lookups, best first.
     *
     * The network's own resolvers come first: on a network that only answers its
     * own resolver (and blocks UDP/53 to anywhere else) they are the only ones
     * that work at all. The public ones are the fallback for the opposite
     * network, and for the case where Android reports no resolver for the
     * active link.
     */
    internal fun resolvers(context: Context): List<String> =
        (deviceResolvers(context) + PUBLIC_RESOLVERS).distinct().take(MAX_RESOLVERS)

    /** IPv4 resolvers of the active, non-VPN network, as Android reports them. */
    private fun deviceResolvers(context: Context): List<String> = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return@runCatching emptyList<String>()
        val network = cm.activeNetwork ?: return@runCatching emptyList<String>()
        // A VPN link's resolvers are OUR tunnel's; Psiphon dials outside it.
        if (cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
            return@runCatching emptyList<String>()
        }
        cm.getLinkProperties(network)?.dnsServers
            ?.filterIsInstance<Inet4Address>()
            ?.mapNotNull { it.hostAddress }
            ?.filter { it.isNotBlank() && !it.startsWith("127.") }
            .orEmpty()
    }.getOrDefault(emptyList())

    /**
     * Environment for the engine process (and so for the Psiphon child it spawns,
     * which inherits it). Only names files that actually exist, so a failed
     * [prepare] degrades to upstream's behaviour instead of breaking the launch.
     */
    fun env(filesDir: File): Map<String, String> = buildMap {
        val dir = dir(filesDir)
        val bundle = File(dir, CA_BUNDLE)
        if (bundle.isFile && bundle.length() > 0) {
            // Go reads SSL_CERT_FILE / SSL_CERT_DIR on linux; without them a static
            // linux binary on Android has an empty root pool.
            put("SSL_CERT_FILE", bundle.absolutePath)
        }
        SYSTEM_CA_DIRS.filter { File(it).isDirectory }
            .takeIf { it.isNotEmpty() }
            ?.let { put("SSL_CERT_DIR", it.joinToString(":")) }
        val overlay = File(dir, BASE_CONFIG)
        if (overlay.isFile && overlay.length() > 0) put("AETHER_PSIPHON_CONFIG", overlay.absolutePath)
        val list = File(dir, SERVER_LIST)
        if (list.isFile && list.length() > 0) put("AETHER_PSIPHON_EMBEDDED_SERVERS", list.absolutePath)
    }

    private fun writeCaBundle(target: File) {
        val pem = StringBuilder()
        var count = 0
        // The system CAs only: a user-installed CA is exactly what a TLS
        // interception proxy installs, and Psiphon must not trust it.
        runCatching {
            val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            for (alias in store.aliases()) {
                if (!alias.startsWith("system:")) continue
                val cert = store.getCertificate(alias) as? X509Certificate ?: continue
                pem.append("-----BEGIN CERTIFICATE-----\n")
                Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
                    .chunked(64).forEach { pem.append(it).append('\n') }
                pem.append("-----END CERTIFICATE-----\n")
                count++
            }
        }
        if (count == 0) {
            // Fallback: the store files are PEM already (plus a text dump that Go's
            // PEM reader skips).
            for (path in SYSTEM_CA_DIRS) {
                File(path).listFiles()?.forEach { file ->
                    runCatching { file.readText() }.getOrNull()
                        ?.takeIf { it.contains("BEGIN CERTIFICATE") }
                        ?.let { pem.append(it).append('\n'); count++ }
                }
                if (count > 0) break
            }
        }
        check(count > 0) { "no system CA certificate could be read" }
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(pem.toString())
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "could not replace ${target.name}" }
        }
        DiagnosticsLog.i(TAG, "Psiphon trust store ready: $count system CA certificate(s).")
    }

    private fun copyServerList(context: Context, target: File) {
        val bytes = context.assets.open(ASSET_SERVER_LIST).use { it.readBytes() }
        if (target.isFile && target.length() == bytes.size.toLong()) return
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "could not replace ${target.name}" }
        }
        DiagnosticsLog.i(TAG, "Psiphon embedded server list staged (${bytes.size / 1024} KB).")
    }

    /**
     * Laid over the engine's built-in Psiphon config (`AETHER_PSIPHON_CONFIG`, read
     * from the engine's `--psiphon-config`). The trust store and the resolvers:
     * everything else stays the engine's.
     *
     * `TrustedCACertificatesFilename` is psiphon-tunnel-core's own parameter (the
     * engine's generated config does not set it), and `DNSResolverAlternateServers`
     * is its parameter for "resolvers to use when the system ones are unusable",
     * which on Android with a static linux build is always.
     */
    private fun writeOverlay(dir: File, resolvers: List<String>) {
        val json = JSONObject()
        val bundle = File(dir, CA_BUNDLE)
        if (bundle.isFile && bundle.length() > 0) {
            json.put("TrustedCACertificatesFilename", bundle.absolutePath)
        }
        if (resolvers.isNotEmpty()) {
            json.put("DNSResolverAlternateServers", JSONArray(resolvers))
        }
        val target = File(dir, BASE_CONFIG)
        if (json.length() == 0) {
            target.delete()
            return
        }
        target.writeText(json.toString())
    }
}
