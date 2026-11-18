// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.suanran.shushu.adb.AdbPairingHost
import com.suanran.shushu.adb.AirModeNotificationController
import com.suanran.shushu.adb.AirModePairingManager
import com.suanran.shushu.adb.PairingLog
import com.suanran.shushu.data.AccessRules
import com.suanran.shushu.data.JiumiStore
import com.suanran.shushu.probe.ProbeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ShushuApp : Application(), AdbPairingHost {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log by lazy { PairingLog(File(filesDir, "logs/adb.log")) }
    val notifications by lazy { AirModeNotificationController(this, log) }
    override val pairingManager by lazy { AirModePairingManager(this, appScope, notifications, log) }
    val probeClient by lazy { ProbeClient(this) }
    val rules by lazy { AccessRules.load(this) }
    val store by lazy { JiumiStore(this) }
    val preferences by lazy { AppPreferences(this) }
    val access by lazy { com.suanran.shushu.access.Access(this) }
    val controller by lazy { MonitorController(this, appScope, pairingManager, probeClient, rules, store, access) }

    private val wifiNetworks = ConcurrentHashMap.newKeySet<Network>()

    override fun onCreate() {
        super.onCreate()
        preferences.applyTheme()
        probeClient.register()
        watchWifi()
    }

    /** Pairing and the ADB connection run over Wi-Fi; the manager only needs to know if it exists. */
    private fun watchWifi() {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        connectivity.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifiNetworks += network
                pairingManager.onWifiNetworkChanged("available", true)
            }

            override fun onLost(network: Network) {
                wifiNetworks -= network
                pairingManager.onWifiNetworkChanged("lost", wifiNetworks.isNotEmpty())
            }
        })
    }
}
