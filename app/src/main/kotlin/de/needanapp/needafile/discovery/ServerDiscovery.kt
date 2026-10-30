package de.needanapp.needafile.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import de.needanapp.needafile.remote.SERVER_TYPE_FTP
import de.needanapp.needafile.remote.SERVER_TYPE_SFTP
import de.needanapp.needafile.remote.SERVER_TYPE_SMB
import de.needanapp.needafile.remote.SERVER_TYPE_WEBDAV
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executor

/**
 * A server announced on the local network. [host] is its IPv4 address; [path] is the WebDAV folder a server
 * announced; [isSecure] tells WebDAV over https from http.
 */
data class DiscoveredServer(
    val name: String,
    val type: String,
    val host: String,
    val port: Int,
    val path: String = "",
    val isSecure: Boolean = true,
) {
    val key get() = "$type|$host|$port"
}

/**
 * Looks for file servers on the local network while started: services announced by Bonjour/mDNS (SMB, SFTP/SSH,
 * WebDAV, FTP) via Android's NsdManager, and Windows computers and NAS answering WS-Discovery (both SMB).
 * Nothing is scanned beyond that. [onFound] is called on the main thread, once per server.
 */
class ServerDiscovery(context: Context, private val onFound: (DiscoveredServer) -> Unit) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(NsdManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    // NsdManager's callbacks only hand results to the main thread, so they run where NsdManager calls them; this also
    // still accepts the callbacks NsdManager sends after unregistering
    private val executor = Executor { it.run() }
    private val discoveryListeners = ArrayList<NsdManager.DiscoveryListener>()
    private val infoCallbacks = ArrayList<NsdManager.ServiceInfoCallback>()
    private val reported = HashSet<String>()
    private var wsDiscovery: WsDiscovery? = null

    @Volatile
    private var isRunning = false

    /** Before Android 14 NsdManager resolves one service at a time. */
    private val resolveQueue = ArrayDeque<Pair<NsdServiceInfo, MdnsType>>()
    private var isResolving = false

    fun start() {
        if (isRunning) return
        isRunning = true
        MdnsType.entries.forEach { type ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) = resolve(info, type)
                override fun onServiceLost(info: NsdServiceInfo) = Unit
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
            runCatching {
                nsd.discoverServices(type.serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                discoveryListeners.add(listener)
            }
        }
        searchWindowsComputers()
    }

    /** Asks for Windows computers and NAS once more; mDNS keeps listening by itself. */
    fun searchWindowsComputers() {
        if (!isRunning) return
        wsDiscovery?.stop()
        wsDiscovery = WsDiscovery(localNetwork()) { name, address -> report(DiscoveredServer(name, SERVER_TYPE_SMB, address, SMB_PORT)) }
            .also { it.start() }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        discoveryListeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        discoveryListeners.clear()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            synchronized(infoCallbacks) {
                infoCallbacks.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
                infoCallbacks.clear()
            }
        }
        wsDiscovery?.stop()
        wsDiscovery = null
    }

    private fun resolve(info: NsdServiceInfo, type: MdnsType) {
        if (!isRunning) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolveWithCallback(info, type)
            return
        }
        synchronized(resolveQueue) {
            resolveQueue.addLast(info to type)
            if (isResolving) return
            isResolving = true
        }
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val (info, type) = synchronized(resolveQueue) {
            val next = resolveQueue.removeFirstOrNull()
            if (next == null || !isRunning) {
                isResolving = false
                return
            }
            next
        }
        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                resolved.host?.let { onResolved(resolved, type, listOf(it)) }
                resolveNext()
            }

            override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) = resolveNext()
        }
        runCatching { nsd.resolveService(info, listener) }.onFailure { resolveNext() }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun resolveWithCallback(info: NsdServiceInfo, type: MdnsType) {
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceUpdated(updated: NsdServiceInfo) = onResolved(updated, type, updated.hostAddresses)
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
            override fun onServiceLost() = Unit
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        runCatching {
            nsd.registerServiceInfoCallback(info, executor, callback)
            synchronized(infoCallbacks) { infoCallbacks.add(callback) }
        }
    }

    private fun onResolved(info: NsdServiceInfo, type: MdnsType, addresses: List<InetAddress>) {
        // IPv6 link-local addresses would need their interface, IPv4 is what all servers here speak
        val address = addresses.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress ?: return
        val path = info.attributes["path"]?.decodeToString()?.trim().orEmpty()
        report(DiscoveredServer(info.serviceName, type.serverType, address, info.port, path, type.isSecure))
    }

    private fun report(server: DiscoveredServer) {
        mainHandler.post {
            if (isRunning && reported.add(server.key)) onFound(server)
        }
    }

    /** The Wi-Fi or Ethernet network, where the servers are; the default one may be mobile data. */
    private fun localNetwork(): Network? {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        return connectivity.allNetworks.firstOrNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
    }

    /** The announced service types and what they become; plain SSH counts as SFTP, which nearly every SSH server offers. */
    private enum class MdnsType(val serviceType: String, val serverType: String, val isSecure: Boolean = true) {
        SMB("_smb._tcp", SERVER_TYPE_SMB),
        SFTP("_sftp-ssh._tcp", SERVER_TYPE_SFTP),
        SSH("_ssh._tcp", SERVER_TYPE_SFTP),
        WEBDAVS("_webdavs._tcp", SERVER_TYPE_WEBDAV),
        WEBDAV("_webdav._tcp", SERVER_TYPE_WEBDAV, isSecure = false),
        FTP("_ftp._tcp", SERVER_TYPE_FTP),
    }

    companion object {
        private const val SMB_PORT = 445
    }
}
