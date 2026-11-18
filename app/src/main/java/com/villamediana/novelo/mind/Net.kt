package com.villamediana.novelo.mind

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Whether the phone has a network at all, and the moment one comes back. With none (the metro, the
 * airplane mode) what needs the internet isn't even tried: it waits in the line and says so at
 * once, instead of a request hanging until it gives up. A network that is there but goes nowhere
 * still counts as one here (Android's own "validated" isn't trusted to say no: it fails on
 * networks that work) — that one is found out by trying.
 */
class Net(context: Context, private val scope: CoroutineScope) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    /** True unless the phone is plainly without a network. */
    fun online(): Boolean {
        val m = manager ?: return true
        return runCatching {
            // Only a phone with no network at all, or one that goes nowhere by its own account, is
            // "without": anything Android can't tell counts as having one (it is found out by trying).
            val caps = m.getNetworkCapabilities(m.activeNetwork ?: return false) ?: return true
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)
    }

    private var pending: Job? = null

    /**
     * [back] runs a moment after a network that reaches the internet shows up (a moment: the phone
     * changing antennas says so several times in a row, and the first instants of a network are
     * the worst ones to use it).
     */
    fun whenBack(back: () -> Unit) {
        val m = manager ?: return
        fun soon() {
            synchronized(this) {
                pending?.cancel()
                pending = scope.launch {
                    delay(SETTLE_MS)
                    back()
                }
            }
        }
        runCatching {
            m.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                private var reached = false

                override fun onAvailable(network: Network) {
                    reached = false
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val reaches = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (reaches && !reached) soon()
                    reached = reaches
                }

                override fun onLost(network: Network) {
                    reached = false
                }
            })
        }
    }

    private companion object {
        const val SETTLE_MS = 2_000L
    }
}
