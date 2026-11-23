package fr.remotecontrol.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

fun interface ServerDiscovery {
    fun discover(): Flow<ServerAddress>
}

class NsdServerDiscovery(context: Context) : ServerDiscovery {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    override fun discover(): Flow<ServerAddress> = callbackFlow {
        val lock = Any()
        val queue = ArrayDeque<NsdServiceInfo>()
        val seen = mutableSetOf<String>()
        var resolving = false

        fun emit(info: NsdServiceInfo, addresses: List<InetAddress>) {
            // Les IPv6 de lien local portent un identifiant de zone que HttpUrl refuse.
            val a = addresses.firstOrNull { it is Inet4Address }
                ?: addresses.firstOrNull { it is Inet6Address && !it.isLinkLocalAddress && it.scopeId == 0 }
            a?.hostAddress?.let { trySend(ServerAddress(it, info.port)) }
        }

        fun next() {
            val info = synchronized(lock) {
                if (resolving) return
                val n = queue.removeFirstOrNull() ?: return
                resolving = true
                n
            }
            fun done() {
                synchronized(lock) { resolving = false }
                next()
            }
            val resolver = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = done()
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (Build.VERSION.SDK_INT >= 34) {
                        emit(serviceInfo, serviceInfo.hostAddresses)
                    } else {
                        emit(serviceInfo, listOfNotNull(serviceInfo.host))
                    }
                    done()
                }
            }
            try {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, resolver)
            } catch (e: Exception) {
                done()
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IllegalStateException("NSD: démarrage de la découverte impossible ($errorCode)"))
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                synchronized(lock) { if (seen.add(serviceInfo.serviceName)) queue.addLast(serviceInfo) }
                next()
            }
        }
        try {
            nsd.discoverServices("_rc._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            close(e)
        }
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }
}
