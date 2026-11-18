// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.adb

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * Wireless-debugging pairing and the on-demand ADB shell connection.
 *
 * The connection exists only to start the shell-identity monitor; the monitor outlives it.
 * Unlike Droidance there is no classic TCP listener: `adb tcpip` would make adbd listen on a
 * network port, which this app must never do.
 */
class AirModePairingManager(
    private val context: Context,
    private val appScope: CoroutineScope,
    private val notificationController: AirModeNotificationController,
    private val log: PairingLog
) {
    enum class PairingState { IDLE, DISCOVERING, AWAITING_CODE, PAIRING, CONNECTING, CONNECTED }
    enum class PairingTrustState { UNPAIRED, PAIRED, PAIRING_REQUIRED }

    data class ActiveAirEndpoint(val host: String, val port: Int, val source: String)

    private companion object {
        const val HEALTH_CHECK_MARKER = "jiumi_adb_ready"
        const val HEALTH_CHECK_COMMAND = "echo $HEALTH_CHECK_MARKER"
        const val LOCAL_ADB_HOST = "127.0.0.1"
        const val TLS_PAIRING_SERVICE = "_adb-tls-pairing._tcp"
        const val TLS_CONNECT_SERVICE = "_adb-tls-connect._tcp"
        const val PAIRING_DISCOVERY_REFRESH_MS = 8_000L
        const val PAIRING_DISCOVERY_RESTART_GAP_MS = 250L
        const val RECONNECT_DELAY_MS = 1_500L
        const val CONNECT_RETRY_DELAY_MS = 5_000L
        /** Seconds to wait for Wireless debugging to answer before saying it cannot be reached. */
        const val CONNECT_GRACE_S = 5
        const val CONNECT_GRACE_AFTER_ENABLE_S = 8
        /** Seconds to wait for the local port after opening it, long enough to allow the key once. */
        const val LOCAL_OPEN_ATTEMPTS = 20
        const val KEY_PAIRED = "adb_paired"
        const val KEY_PAIRING_REQUIRED = "adb_pairing_required"
        const val KEY_LAST_CONNECT_PORT = "adb_last_connect_port"
        const val KEY_PERSISTENCE = "adb_persistence"
        const val KEY_LOCAL_PORT = "adb_local_port"
        const val KEY_LOCAL_PORT_BOOT = "adb_local_port_boot"
        const val LOCAL_PORT_SOURCE = "local_tcpip"
    }

    private val prefs = context.getSharedPreferences("jiumi_adb", Context.MODE_PRIVATE)

    /**
     * ADB persistence: after one connection over Wireless debugging, adbd also listens on a local TCP
     * port, so later connections go to 127.0.0.1 and need neither Wi-Fi nor Wireless debugging until
     * the phone restarts. It needs USB debugging on, and the system asks once to allow the key. On by
     * default: once the local port is open it is the channel used, and Wireless debugging the fallback.
     */
    private val _persistenceEnabled = MutableStateFlow(prefs.getBoolean(KEY_PERSISTENCE, true))
    val persistenceEnabled: StateFlow<Boolean> = _persistenceEnabled.asStateFlow()

    /** A local port opened in this boot, ready to connect to without Wi-Fi. */
    val hasLocalAdbPort: Boolean get() = _persistenceEnabled.value && savedLocalPort() != null
    @Volatile private var automaticReconnectSuspended = true
    private val _state = MutableStateFlow(PairingState.IDLE)
    val state: StateFlow<PairingState> = _state.asStateFlow()
    private val _pairingTrustState = MutableStateFlow(initialPairingTrustState())
    val pairingTrustState: StateFlow<PairingTrustState> = _pairingTrustState.asStateFlow()
    val isPairingRequired: Boolean
        get() = _pairingTrustState.value == PairingTrustState.PAIRING_REQUIRED
    val isShellReady: Boolean
        get() = _state.value == PairingState.CONNECTED && activeConnection != null

    // Blocking shell reads run on recyclable threads so a timed-out read never pins a caller.
    private val shellTimeoutExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "adb-shell-timeout").apply { isDaemon = true }
    }

    private val _failure = MutableStateFlow<AirPairingFailure?>(null)
    val failure: StateFlow<AirPairingFailure?> = _failure.asStateFlow()

    val isPreviouslyPaired: Boolean
        get() = _pairingTrustState.value == PairingTrustState.PAIRED && privateKeyFile().exists()

    @Volatile private var pendingHost: String? = null
    @Volatile private var pendingPort: Int = 0
    @Volatile private var activeConnection: AirAdbClient? = null
    @Volatile private var activeEndpoint: ActiveAirEndpoint? = null
    @Volatile private var pairingTimeoutJob: Job? = null
    @Volatile private var discoveryTimeoutJob: Job? = null
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var queuedPairCode: String? = null
    // A callback has not established Wi-Fi availability at process startup.
    @Volatile private var wifiTransportAvailable: Boolean? = null

    @Volatile private var pairingDiscovery: AirMdnsDiscovery? = null
    @Volatile private var pairingDiscoveryRefreshJob: Job? = null
    private val pairingDiscoveryGeneration = AtomicLong(0L)
    private val pairingAttemptGeneration = AtomicLong(0L)
    @Volatile private var connectDiscovery: AirMdnsDiscovery? = null
    @Volatile private var connectScanJob: Job? = null
    private val connectionMutex = Mutex()
    private val connectAttemptGeneration = AtomicLong()

    init {
        if (isPairingRequired) {
            prefs.edit().remove(KEY_PAIRED).putBoolean(KEY_PAIRING_REQUIRED, true).apply()
            _failure.value = AirPairingFailure.PAIRING_REQUIRED
        }
    }

    private fun privateKeyFile(): File = File(context.filesDir, "adb_key")
    private fun publicKeyFile(): File = File(context.filesDir, "adb_key.pub")

    private fun initialPairingTrustState(): PairingTrustState = when {
        prefs.getBoolean(KEY_PAIRING_REQUIRED, false) -> PairingTrustState.PAIRING_REQUIRED
        prefs.getBoolean(KEY_PAIRED, false) && privateKeyFile().exists() -> PairingTrustState.PAIRED
        prefs.getBoolean(KEY_PAIRED, false) -> PairingTrustState.PAIRING_REQUIRED
        else -> PairingTrustState.UNPAIRED
    }

    private fun loadOrCreateAdbKey(): AirAdbKey = AirAdbKey.loadOrCreate(privateKeyFile(), publicKeyFile())

    fun notificationAvailability(): AirPairingNotificationAvailability = notificationController.availability()

    fun startDiscovery(): Boolean {
        automaticReconnectSuspended = false
        if (_state.value in setOf(PairingState.DISCOVERING, PairingState.AWAITING_CODE) && queuedPairCode == null) {
            return refreshPairingNotificationIfAwaiting()
        }
        if (_state.value != PairingState.IDLE) return false
        val availability = notificationController.availability()
        if (availability != AirPairingNotificationAvailability.AVAILABLE) {
            _failure.value = pairingNotificationFailure(availability)
            log.event("pairing discovery withheld: notification=${availability.name.lowercase()}")
            return false
        }
        _failure.value = null
        queuedPairCode = null
        clearPendingPairingEndpoint()
        stopConnectAttempt()
        _state.value = PairingState.DISCOVERING
        log.event(
            "pairing discovery started manufacturer=${android.os.Build.MANUFACTURER} " +
                "model=${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT} " +
                "wireless=${isWirelessDebuggingEnabled()}"
        )
        // Prepare a silent RemoteInput while foreground so it remains usable if frozen.
        // Alert only after discovering the service opened by the system pairing-code dialog.
        if (!showPairingCodeNotification(alert = false)) return false
        // Connecting before pairing grants trust can reject the new key and reset this flow.
        startPairingDiscovery()
        return true
    }

    private fun startPairingDiscovery() {
        pairingDiscoveryRefreshJob?.cancel()
        installPairingDiscovery()
        pairingDiscoveryRefreshJob = appScope.launch {
            while (_state.value == PairingState.DISCOVERING && pendingPort <= 0) {
                delay(PAIRING_DISCOVERY_REFRESH_MS)
                if (_state.value != PairingState.DISCOVERING || pendingPort > 0) break
                pairingDiscovery?.stop()
                pairingDiscovery = null
                delay(PAIRING_DISCOVERY_RESTART_GAP_MS)
                if (_state.value == PairingState.DISCOVERING && pendingPort <= 0) installPairingDiscovery()
            }
        }
    }

    private fun installPairingDiscovery() {
        val generation = pairingDiscoveryGeneration.incrementAndGet()
        pairingDiscovery?.stop()
        pairingDiscovery = AirMdnsDiscovery(
            context = context,
            appScope = appScope,
            serviceType = TLS_PAIRING_SERVICE,
            onResolved = { host, port ->
                if (_state.value != PairingState.DISCOVERING || generation != pairingDiscoveryGeneration.get()) {
                    return@AirMdnsDiscovery
                }
                pendingHost = host
                pendingPort = port
                val queued = queuedPairCode
                if (queued != null) doExecutePairing(host, port, queued) else moveToAwaitingCode()
            },
            onDiagnostic = { detail -> log.event("pairing mDNS: $detail") }
        ).also { it.start() }
    }

    fun stopDiscovery() {
        pairingAttemptGeneration.incrementAndGet()
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        pairingDiscoveryGeneration.incrementAndGet()
        queuedPairCode = null
        clearPendingPairingEndpoint()
        pairingDiscoveryRefreshJob?.cancel()
        pairingDiscoveryRefreshJob = null
        pairingDiscovery?.stop()
        pairingDiscovery = null
        if (_state.value !in setOf(PairingState.CONNECTED, PairingState.PAIRING)) {
            notificationController.cancel()
            _state.value = PairingState.IDLE
        }
    }

    /**
     * Opens an ADB shell with the trusted key. [restoreWirelessDebugging] turns the system switch
     * back on and is reserved for an explicit user request; background paths never change it.
     */
    fun reconnect(restoreWirelessDebugging: Boolean): Boolean {
        if (!isPreviouslyPaired) return false
        if (isShellReady) return true
        if (_state.value != PairingState.IDLE) return _state.value == PairingState.CONNECTING
        automaticReconnectSuspended = false
        if (wifiTransportAvailable == false && !hasLocalAdbPort) {
            _failure.value = AirPairingFailure.WIFI_UNAVAILABLE
            return false
        }
        _failure.value = null
        _state.value = PairingState.CONNECTING
        startConnectDiscovery(restoreWirelessDebugging)
        return true
    }

    fun onWifiNetworkChanged(trigger: String, wifiAvailable: Boolean) {
        wifiTransportAvailable = wifiAvailable
        if (!isPreviouslyPaired || automaticReconnectSuspended) return
        log.event("wifi transport changed trigger=${trigger.take(40)} available=$wifiAvailable state=${_state.value}")
        // The local channel does not ride on Wi-Fi: a shell on it, or one being opened, carries on.
        if (hasLocalAdbPort && (activeEndpoint?.source == LOCAL_PORT_SOURCE || _state.value == PairingState.CONNECTING)) {
            if (!wifiAvailable && _failure.value == AirPairingFailure.WIFI_UNAVAILABLE) _failure.value = null
            return
        }
        stopConnectAttempt()
        markShellDown("Wi-Fi transport changed", scheduleReconnect = false)
        if (wifiAvailable || hasLocalAdbPort) {
            _failure.value = null
            scheduleReconnectIfPaired(delayMs = 0)
        } else {
            _failure.value = AirPairingFailure.WIFI_UNAVAILABLE
        }
    }

    fun hasSecureSettingsPermission(): Boolean =
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    fun isWirelessDebuggingEnabled(): Boolean? = runCatching {
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled") == 1
    }.getOrNull()

    private fun restoreWirelessDebugging(): Boolean {
        if (!isPreviouslyPaired || wifiTransportAvailable == false || !hasSecureSettingsPermission()) return false
        if (isWirelessDebuggingEnabled() == true) return true
        log.event("wireless debugging restored on user request")
        return runCatching { Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1) }.getOrDefault(false)
    }

    fun requestPairingFromNotification(session: String?, input: String?, hasResults: Boolean) {
        fun record(result: String) = log.event(
            "pairing notification input result=$result has_results=$hasResults input_length=${input?.length ?: -1}"
        )
        if (session == null || session != notificationController.currentPairingSession()) {
            record("stale_session")
            return
        }
        if (_state.value in setOf(PairingState.PAIRING, PairingState.CONNECTING, PairingState.CONNECTED)) {
            record("already_in_progress")
            return
        }
        val code = input?.trim()
        val failure = when {
            input == null -> AirPairingFailure.NOTIFICATION_INPUT_MISSING
            code == null || code.length != 6 || !code.all { it in '0'..'9' } -> AirPairingFailure.INVALID_PAIRING_CODE
            else -> null
        }
        if (failure != null) {
            record(if (!hasResults) "missing_results" else if (input == null) "missing_field" else "invalid_input")
            _failure.value = failure
            notificationController.showPairingFailure(failure, allowRetry = true)
            return
        }
        if (_state.value == PairingState.IDLE && !isPreviouslyPaired) {
            automaticReconnectSuspended = false
            stopConnectAttempt()
            _state.value = PairingState.DISCOVERING
            record("session_restored")
        } else {
            record("received")
        }
        requestPairing(checkNotNull(code))
    }

    private fun requestPairing(code: String) {
        if (_state.value !in setOf(PairingState.DISCOVERING, PairingState.AWAITING_CODE)) {
            _failure.value = AirPairingFailure.PAIRING_REQUEST_UNAVAILABLE
            runCatching { notificationController.showPairingUnavailable() }
            return
        }
        _failure.value = null
        queuedPairCode = code
        // The system allocates a fresh pairing port when its code dialog is reopened. Never
        // bind a submitted code to the endpoint that happened to be discovered earlier.
        pairingDiscoveryGeneration.incrementAndGet()
        clearPendingPairingEndpoint()
        _state.value = PairingState.DISCOVERING
        runCatching { notificationController.showPairingWaitingForService() }
        // Time the submitted request, not the user's time spent in system settings.
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = appScope.launch {
            delay(120_000)
            if (_state.value == PairingState.DISCOVERING && queuedPairCode != null) {
                resumePairingDiscovery(
                    AirPairingFailure.PAIRING_SERVICE_NOT_FOUND,
                    "pairing endpoint discovery timed out after code submission"
                )
            }
        }
        startPairingDiscovery()
    }

    private fun moveToAwaitingCode() {
        if (showPairingCodeNotification(alert = true)) {
            pairingDiscoveryRefreshJob?.cancel()
            pairingDiscoveryRefreshJob = null
            _state.value = PairingState.AWAITING_CODE
        }
    }

    private fun showPairingCodeNotification(alert: Boolean): Boolean {
        val postResult = notificationController.showPairingRequest(_failure.value, alert = alert)
        if (postResult.posted) return true
        _failure.value = pairingNotificationFailure(postResult.availability)
        log.event("pairing code notification unavailable: ${postResult.availability.name.lowercase()} ${postResult.detail.take(160)}")
        stopDiscovery()
        return false
    }

    private fun refreshPairingNotificationIfAwaiting(): Boolean {
        if (_state.value !in setOf(PairingState.DISCOVERING, PairingState.AWAITING_CODE) || queuedPairCode != null) return false
        // Discovery can remain active after rejection or an invalid code. Restore RemoteInput
        // immediately without requiring another settings navigation or reusing a stale endpoint.
        return showPairingCodeNotification(alert = false)
    }

    private fun pairingNotificationFailure(availability: AirPairingNotificationAvailability): AirPairingFailure =
        when (availability) {
            AirPairingNotificationAvailability.PERMISSION_REQUIRED -> AirPairingFailure.NOTIFICATION_PERMISSION_REQUIRED
            AirPairingNotificationAvailability.APP_NOTIFICATIONS_DISABLED -> AirPairingFailure.APP_NOTIFICATIONS_DISABLED
            AirPairingNotificationAvailability.CHANNEL_DISABLED -> AirPairingFailure.NOTIFICATION_CHANNEL_DISABLED
            AirPairingNotificationAvailability.POST_FAILED,
            AirPairingNotificationAvailability.AVAILABLE -> AirPairingFailure.NOTIFICATION_POST_FAILED
        }

    private fun clearPendingPairingEndpoint() {
        pendingHost = null
        pendingPort = 0
    }

    private fun resumePairingDiscovery(failure: AirPairingFailure, diagnostic: String) {
        pairingAttemptGeneration.incrementAndGet()
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = null
        pairingDiscoveryGeneration.incrementAndGet()
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        queuedPairCode = null
        clearPendingPairingEndpoint()
        _failure.value = failure
        _state.value = PairingState.DISCOVERING
        log.event(diagnostic)
        runCatching { notificationController.showPairingFailure(failure, allowRetry = true) }
        startPairingDiscovery()
    }

    private fun doExecutePairing(host: String, port: Int, code: String) {
        val attemptGeneration = pairingAttemptGeneration.incrementAndGet()
        queuedPairCode = null
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        _failure.value = null
        runCatching { notificationController.showPairingSubmitted() }
        pairingDiscoveryRefreshJob?.cancel()
        pairingDiscoveryRefreshJob = null
        _state.value = PairingState.PAIRING
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = appScope.launch(Dispatchers.IO) {
            delay(20_000)
            if (_state.value == PairingState.PAIRING && pairingAttemptGeneration.get() == attemptGeneration) {
                resumePairingDiscovery(AirPairingFailure.PAIRING_TIMEOUT, "pairing timed out waiting for the system service")
            }
        }
        appScope.launch(Dispatchers.IO) {
            runCatching {
                AirPairingClient(host, port, code, loadOrCreateAdbKey()).pair()
            }.onSuccess { success ->
                if (pairingAttemptGeneration.get() != attemptGeneration) return@onSuccess
                pairingTimeoutJob?.cancel()
                pairingTimeoutJob = null
                if (success) {
                    markPairingTrusted()
                    runCatching { notificationController.showPairingSucceeded() }
                    _state.value = PairingState.CONNECTING
                    startConnectDiscovery(restoreWirelessDebugging = false)
                } else {
                    resumePairingDiscovery(AirPairingFailure.PAIRING_REJECTED, "pairing rejected by the system service")
                }
            }.onFailure { e ->
                if (pairingAttemptGeneration.get() != attemptGeneration) return@onFailure
                resumePairingDiscovery(
                    AirPairingFailure.PAIRING_CONNECTION_FAILED,
                    "pairing connection failed: ${e::class.java.simpleName}: ${e.message.orEmpty().take(120)}"
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getWifiIpAddress(): String? = runCatching {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val ip = wm?.connectionInfo?.ipAddress?.takeIf { it != 0 } ?: return null
        "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }.getOrNull()

    private fun getAdbWifiPort(): Int? = runCatching {
        Settings.Global.getString(context.contentResolver, "adb_wifi_port")?.toIntOrNull()?.takeIf { it > 0 }
    }.getOrNull()

    private fun isConnectionBlockedByPairingRequirement(): Boolean =
        !isPreviouslyPaired || _state.value in setOf(PairingState.DISCOVERING, PairingState.AWAITING_CODE, PairingState.PAIRING)

    private fun failConnection(connection: AirAdbClient?, failure: AirPairingFailure, diagnostic: String) {
        markShellDown(diagnostic, connection, scheduleReconnect = false)
        _failure.value = failure
        log.event(diagnostic)
        runCatching { notificationController.showConnectionFailure(failure) }
    }

    private fun markPairingRequired(connection: AirAdbClient?, diagnostic: String) {
        reconnectJob?.cancel()
        reconnectJob = null
        connectScanJob?.cancel()
        connectScanJob = null
        connectDiscovery?.stop()
        connectDiscovery = null
        markShellDown(diagnostic, connection, scheduleReconnect = false)
        prefs.edit().remove(KEY_PAIRED).putBoolean(KEY_PAIRING_REQUIRED, true).apply()
        _pairingTrustState.value = PairingTrustState.PAIRING_REQUIRED
        _failure.value = AirPairingFailure.PAIRING_REQUIRED
        log.event("pairing authorization rejected; explicit re-pairing required")
        runCatching { notificationController.showPairingRequired() }
    }

    private fun markPairingTrusted(connectPort: Int? = null) {
        val editor = prefs.edit().putBoolean(KEY_PAIRED, true).remove(KEY_PAIRING_REQUIRED)
        if (connectPort != null) editor.putInt(KEY_LAST_CONNECT_PORT, connectPort)
        editor.apply()
        _pairingTrustState.value = PairingTrustState.PAIRED
    }

    private fun stopConnectAttempt() {
        connectAttemptGeneration.incrementAndGet()
        reconnectJob?.cancel()
        reconnectJob = null
        connectScanJob?.cancel()
        connectScanJob = null
        connectDiscovery?.stop()
        connectDiscovery = null
    }

    private fun markShellDown(
        message: String,
        connection: AirAdbClient? = activeConnection,
        scheduleReconnect: Boolean = true
    ) {
        if (connection != null && activeConnection != null && connection !== activeConnection) {
            connection.runCatching { close() }
            return
        }
        activeConnection?.runCatching { close() }
        if (connection !== activeConnection) connection?.runCatching { close() }
        activeConnection = null
        activeEndpoint = null
        if (_state.value == PairingState.CONNECTED || _state.value == PairingState.CONNECTING) {
            _state.value = PairingState.IDLE
        }
        log.event("shell down: ${message.take(200)}")
        if (scheduleReconnect) scheduleReconnectIfPaired()
    }

    private fun scheduleReconnectIfPaired(delayMs: Long = RECONNECT_DELAY_MS) {
        if (!isPreviouslyPaired || automaticReconnectSuspended || (wifiTransportAvailable == false && !hasLocalAdbPort) ||
            reconnectJob?.isActive == true) return
        reconnectJob = appScope.launch(Dispatchers.IO) {
            delay(delayMs)
            if (_state.value != PairingState.IDLE || !isPreviouslyPaired || automaticReconnectSuspended) return@launch
            _state.value = PairingState.CONNECTING
            startConnectDiscovery(restoreWirelessDebugging = false)
        }
    }

    private suspend fun doConnect(host: String, port: Int, source: String, generation: Long) = connectionMutex.withLock {
        currentCoroutineContext().ensureActive()
        check(generation == connectAttemptGeneration.get() && !automaticReconnectSuspended &&
            !isConnectionBlockedByPairingRequirement()) { "ADB pairing authorization must be restored before reconnecting" }
        if (isShellReady) return
        activeConnection?.runCatching { close() }
        activeConnection = null
        activeEndpoint = null
        val local = source == LOCAL_PORT_SOURCE
        // The local listener is classic ADB: the key the TLS pairing trusted may need one approval there.
        val connection = AirAdbClient(host, port, loadOrCreateAdbKey(), allowUserAuthorization = local)
        try {
            try {
                connection.connect()
            } catch (error: Throwable) {
                if (!local && generation == connectAttemptGeneration.get() && !automaticReconnectSuspended &&
                    AirAdbConnectionFailurePolicy.requiresPairing(error)) {
                    markPairingRequired(connection, "ADB authorization rejected: ${error.message.orEmpty().take(120)}")
                } else {
                    connection.runCatching { close() }
                }
                throw error
            }
            currentCoroutineContext().ensureActive()
            val healthOutput = runCatching { connection.shell(HEALTH_CHECK_COMMAND) }.getOrElse { error ->
                failConnection(connection, AirPairingFailure.ADB_HEALTH_CHECK_FAILED,
                    "ADB health check failed: ${error::class.java.simpleName}: ${error.message.orEmpty().take(120)}")
                throw error
            }
            if (!healthOutput.contains(HEALTH_CHECK_MARKER)) {
                failConnection(connection, AirPairingFailure.ADB_HEALTH_RESPONSE_MISSING, "ADB health check missing marker")
                throw IllegalStateException("ADB health check did not return ready marker")
            }
            val identityOutput = runCatching { connection.shell("id") }.getOrElse { error ->
                failConnection(connection, AirPairingFailure.ADB_IDENTITY_CHECK_FAILED,
                    "ADB identity check failed: ${error::class.java.simpleName}: ${error.message.orEmpty().take(120)}")
                throw error
            }
            if (!identityOutput.contains("uid=")) {
                failConnection(connection, AirPairingFailure.ADB_IDENTITY_RESPONSE_MISSING, "ADB identity check missing uid")
                throw IllegalStateException("ADB shell identity check did not return uid")
            }
            currentCoroutineContext().ensureActive()
            check(generation == connectAttemptGeneration.get()) { "ADB connection attempt was superseded" }
            // With persistence on, the first TLS connection opens the local listener before anyone
            // uses the shell: adbd restarts to do it, which ends this connection, so the attempt
            // continues on the local port instead and is reported connected only once that holds.
            if (!local && wantsLocalListener() && openLocalListener(connection)) {
                connection.runCatching { close() }
                val localPort = savedLocalPort() ?: error("Local port was not recorded")
                markPairingTrusted(connectPort = port)
                connectLocalAfterOpening(localPort, generation)
                return
            }
            activeConnection = connection
            activeEndpoint = ActiveAirEndpoint(host, port, source)
            connectDiscovery?.stop()
            connectDiscovery = null
            // Local reconnects keep the TLS bootstrap port as the one to try when they are not possible.
            markPairingTrusted(connectPort = port.takeUnless { local })
            pairingTimeoutJob?.cancel()
            pairingTimeoutJob = null
            _failure.value = null
            log.event("ADB shell connected source=$source identity=${identityOutput.trim().take(80)}")
            _state.value = PairingState.CONNECTED
        } finally {
            if (activeConnection !== connection) connection.close()
        }
    }

    private fun startConnectDiscovery(restoreWirelessDebugging: Boolean) {
        if (isConnectionBlockedByPairingRequirement() || automaticReconnectSuspended) return
        val generation = connectAttemptGeneration.incrementAndGet()
        connectScanJob?.cancel()
        connectDiscovery?.stop()
        connectDiscovery = null
        connectScanJob = appScope.launch(Dispatchers.IO) {
            if (generation != connectAttemptGeneration.get() || isConnectionBlockedByPairingRequirement() ||
                automaticReconnectSuspended) return@launch
            _state.value = PairingState.CONNECTING
            // The local port first: it needs no network and no Wireless debugging.
            val localPort = savedLocalPort()?.takeIf { _persistenceEnabled.value }
            if (localPort != null) {
                val attempt = runCatching { doConnect(LOCAL_ADB_HOST, localPort, LOCAL_PORT_SOURCE, generation) }
                    .onFailure { if (it is CancellationException) throw it; log.event("local port $localPort connect failed: ${it.javaClass.simpleName}") }
                if (attempt.isSuccess && isShellReady) return@launch
            }
            if (wifiTransportAvailable == false) {
                _failure.value = if (localPort != null) AirPairingFailure.LOCAL_ADB_CONNECTION_FAILED else AirPairingFailure.WIFI_UNAVAILABLE
                _state.value = PairingState.IDLE
                return@launch
            }
            val turnedOn = restoreWirelessDebugging && isWirelessDebuggingEnabled() == false && restoreWirelessDebugging()
            // With Wireless debugging off and no way to turn it on there is nothing to find: say so now.
            if (isWirelessDebuggingEnabled() == false && !turnedOn) {
                log.event("connect skipped: wireless debugging off")
                _failure.value = AirPairingFailure.WIRELESS_DEBUGGING_OFF
                _state.value = PairingState.IDLE
                return@launch
            }
            connectDiscovery = AirMdnsDiscovery(
                context = context, appScope = appScope, serviceType = TLS_CONNECT_SERVICE,
                onResolved = { host, port ->
                    if (generation == connectAttemptGeneration.get() && !isConnectionBlockedByPairingRequirement() &&
                        _state.value == PairingState.CONNECTING) {
                        appScope.launch(Dispatchers.IO) { runCatching { doConnect(host, port, "mdns", generation) } }
                    }
                },
                onDiagnostic = { detail -> log.event("connect mDNS: $detail") }
            ).also { it.start() }
            val ports = AirAdbPortPolicy.connectPorts(
                lastPort = prefs.getInt(KEY_LAST_CONNECT_PORT, -1).takeIf { it in 1..65535 },
                settingsPort = getAdbWifiPort()
            )
            for (port in ports) {
                for (host in listOfNotNull(LOCAL_ADB_HOST, getWifiIpAddress()).distinct()) {
                    currentCoroutineContext().ensureActive()
                    if (_state.value != PairingState.CONNECTING || isConnectionBlockedByPairingRequirement()) return@launch
                    runCatching { doConnect(host, port, "port_scan", generation) }
                        .onFailure { if (it is CancellationException) throw it }
                    if (_state.value != PairingState.CONNECTING || isPairingRequired) return@launch
                }
            }
            // mDNS answers within a second or two once adbd listens; a moment longer when it was just turned on.
            repeat(if (turnedOn) CONNECT_GRACE_AFTER_ENABLE_S else CONNECT_GRACE_S) {
                delay(1_000)
                if (_state.value != PairingState.CONNECTING || isConnectionBlockedByPairingRequirement()) return@launch
            }
            connectDiscovery?.stop()
            connectDiscovery = null
            _failure.value = AirPairingFailure.ADB_ENDPOINT_NOT_FOUND
            log.event("ADB connect discovery timed out without a usable endpoint")
            _state.value = PairingState.IDLE
            scheduleReconnectIfPaired(CONNECT_RETRY_DELAY_MS)
        }
    }

    private fun bootCount(): Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    /** The local port opened in this boot; a restart closes it, so one from an earlier boot is ignored. */
    private fun savedLocalPort(): Int? = prefs.getInt(KEY_LOCAL_PORT, -1)
        .takeIf { it in 49152..65535 && bootCount() >= 0 && prefs.getInt(KEY_LOCAL_PORT_BOOT, -2) == bootCount() }

    fun isUsbDebuggingEnabled(): Boolean? = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED) == 1
    }.getOrNull()

    private fun wantsLocalListener() = _persistenceEnabled.value && isUsbDebuggingEnabled() == true

    fun setPersistenceEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_PERSISTENCE, on).apply()
        _persistenceEnabled.value = on
        log.event("persistence ${if (on) "enabled" else "disabled"}")
    }

    private fun chooseLocalPort(): Int {
        val random = SecureRandom()
        repeat(32) {
            val candidate = 49152 + random.nextInt(16384)
            if (runCatching { ServerSocket().use { it.bind(InetSocketAddress(LOCAL_ADB_HOST, candidate)) } }.isSuccess) return candidate
        }
        error("No available high TCP port")
    }

    /**
     * Asks adbd, over [connection], to listen on this boot's local port as well. True when it was asked
     * to (adbd restarts, ending [connection]); false when the port is already open.
     */
    private fun openLocalListener(connection: AirAdbClient): Boolean {
        val port = savedLocalPort() ?: chooseLocalPort().also {
            check(bootCount() >= 0) { "Cannot identify the current boot" }
            // Recorded before the request: adbd may restart before it answers.
            check(prefs.edit().putInt(KEY_LOCAL_PORT, it).putInt(KEY_LOCAL_PORT_BOOT, bootCount()).commit())
        }
        val current = runCatching { connection.shell("getprop service.adb.tcp.port").trim().toIntOrNull() }.getOrNull()
        if (current == port) return false
        log.event("local listener opening port=$port")
        val response = runCatching { connection.restartTcpip(port) }
        log.event("local listener open response=${response.getOrNull()?.take(120)} error=${response.exceptionOrNull()?.javaClass?.simpleName}")
        return true
    }

    /** After adbd restarts for the local port: connect to it, giving the user time to allow the key. */
    private suspend fun connectLocalAfterOpening(port: Int, generation: Long) {
        repeat(LOCAL_OPEN_ATTEMPTS) {
            delay(1_000)
            currentCoroutineContext().ensureActive()
            val connection = AirAdbClient(LOCAL_ADB_HOST, port, loadOrCreateAdbKey(), allowUserAuthorization = true)
            try {
                connection.connect()
                if (!connection.shell(HEALTH_CHECK_COMMAND).contains(HEALTH_CHECK_MARKER)) error("health marker missing")
                check(generation == connectAttemptGeneration.get()) { "ADB connection attempt was superseded" }
                activeConnection = connection
                activeEndpoint = ActiveAirEndpoint(LOCAL_ADB_HOST, port, LOCAL_PORT_SOURCE)
                connectDiscovery?.stop()
                connectDiscovery = null
                _failure.value = null
                log.event("ADB shell connected source=$LOCAL_PORT_SOURCE port=$port")
                _state.value = PairingState.CONNECTED
                return
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                connection.runCatching { close() }
                log.event("local listener probe failed: ${error.javaClass.simpleName}: ${error.message.orEmpty().take(80)}")
            }
        }
        // The listener stays recorded; the next attempt tries it first, and Wireless debugging remains.
        throw IllegalStateException("Local ADB port did not accept the connection")
    }

    /**
     * Turns persistence off and closes the local port, by asking adbd to return to USB mode. The port
     * stays recorded when that cannot be confirmed, so it can be closed later.
     */
    suspend fun disablePersistence(): Boolean = connectionMutex.withLock {
        setPersistenceEnabled(false)
        val port = savedLocalPort() ?: run {
            prefs.edit().remove(KEY_LOCAL_PORT).remove(KEY_LOCAL_PORT_BOOT).apply()
            return@withLock true
        }
        val connection = activeConnection ?: AirAdbClient(LOCAL_ADB_HOST, port, loadOrCreateAdbKey(), allowUserAuthorization = true)
        val closed = runCatching {
            if (connection !== activeConnection) connection.connect()
            connection.restartUsb()
            repeat(20) {
                if (runCatching { java.net.Socket().use { it.connect(InetSocketAddress(LOCAL_ADB_HOST, port), 250) } }.isFailure) return@runCatching true
                delay(250)
            }
            false
        }.getOrDefault(false)
        if (connection !== activeConnection) connection.runCatching { close() }
        log.event("local listener close port=$port closed=$closed")
        if (closed) {
            prefs.edit().remove(KEY_LOCAL_PORT).remove(KEY_LOCAL_PORT_BOOT).apply()
            markShellDown("local listener closed", scheduleReconnect = false)
        } else {
            _failure.value = AirPairingFailure.LOCAL_PORT_CLOSE_FAILED
        }
        closed
    }

    /** Runs a command on the live shell; failures close the connection and return a marker. */
    fun shell(command: String, timeoutMs: Long = 15_000L): String {
        val conn = activeConnection ?: return "[ADB_DISCONNECTED]"
        val future = shellTimeoutExecutor.submit<String> { conn.shell(command) }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            markShellDown("shell timeout after ${timeoutMs}ms", conn, scheduleReconnect = false)
            "[ADB_TIMEOUT]"
        } catch (e: Exception) {
            markShellDown("shell error: ${e.cause?.message ?: e.message}", conn, scheduleReconnect = false)
            "[ADB_ERROR] ${e.cause?.message ?: e.message}"
        }
    }

    /** Closes the shell and stops all reconnection; the monitor keeps running on its own. */
    fun disconnect() {
        pairingAttemptGeneration.incrementAndGet()
        pairingDiscoveryGeneration.incrementAndGet()
        automaticReconnectSuspended = true
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        stopConnectAttempt()
        queuedPairCode = null
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = null
        pairingDiscoveryRefreshJob?.cancel()
        pairingDiscoveryRefreshJob = null
        pairingDiscovery?.stop()
        pairingDiscovery = null
        markShellDown("ADB session closed", scheduleReconnect = false)
        _state.value = PairingState.IDLE
        notificationController.cancel()
    }

    fun resetPairing() {
        disconnect()
        prefs.edit().clear().apply()
        privateKeyFile().delete()
        publicKeyFile().delete()
        _pairingTrustState.value = PairingTrustState.UNPAIRED
        _failure.value = null
    }

    fun startRequiredPairing(): Boolean {
        if (!isPairingRequired) return startDiscovery()
        val availability = notificationController.availability()
        if (availability != AirPairingNotificationAvailability.AVAILABLE) {
            _failure.value = pairingNotificationFailure(availability)
            return false
        }
        disconnect()
        return startDiscovery()
    }
}
