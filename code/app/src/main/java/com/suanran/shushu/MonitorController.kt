// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import com.suanran.shushu.adb.AirModePairingManager
import com.suanran.shushu.adb.AirModePairingManager.PairingState
import com.suanran.shushu.adb.AirModePairingManager.PairingTrustState
import com.suanran.shushu.adb.AirPairingFailure
import androidx.core.graphics.drawable.toBitmap
import com.suanran.shushu.access.RootShell
import com.suanran.shushu.data.AppBackups
import com.suanran.shushu.data.AppSource
import com.suanran.shushu.data.ManageAction
import com.suanran.shushu.data.ManageRules
import com.suanran.shushu.data.Management
import com.suanran.shushu.data.PackageFacts
import com.suanran.shushu.data.PackageFactsSource
import com.suanran.shushu.data.Refusal
import com.suanran.shushu.data.Risk
import com.suanran.shushu.data.ShellPackage
import com.suanran.shushu.data.AccessRules
import com.suanran.shushu.data.AppKey
import com.suanran.shushu.data.JiumiStore
import com.suanran.shushu.data.JiumiSync
import com.suanran.shushu.data.PackageOwners
import com.suanran.shushu.data.Report
import com.suanran.shushu.data.ReportBuilder
import com.suanran.shushu.data.AppDetail
import com.suanran.shushu.data.Coverage
import com.suanran.shushu.data.LogExport
import com.suanran.shushu.data.NetworkUsage
import com.suanran.shushu.data.AppKinds
import com.suanran.shushu.data.AttentionRules
import com.suanran.shushu.probe.PackageChangeFailure
import com.suanran.shushu.probe.ProbeClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Starts the monitor through a short-lived ADB session and talks to it afterwards.
 * ADB is opened only for starting the monitor and closed as soon as it is running.
 */
class MonitorController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val pairing: AirModePairingManager,
    private val probe: ProbeClient,
    private val rules: AccessRules,
    private val store: JiumiStore,
    val access: com.suanran.shushu.access.Access
) {
    enum class Busy { STARTING, STOPPING }
    /**
     * SESSION: the chosen monitoring session (the latest unless one was picked); the others reach back
     * from now, TODAY to local midnight. Half a year is the longest the report looks back.
     */
    /** A session, or a run of whole local days ending today: one, seven or thirty, as Screen Time counts them. */
    enum class Range { SESSION, TODAY, WEEK, MONTH }

    /** A monitoring session with how much of it the monitor covered. */
    data class SessionView(val session: JiumiStore.Session, val coverage: Coverage.Result, val latest: Boolean) {
        val running: Boolean get() = session.end == null
    }
    enum class Problem { CONNECT, START, SHIZUKU }

    data class UiState(
        val pairingState: PairingState = PairingState.IDLE,
        val trust: PairingTrustState = PairingTrustState.UNPAIRED,
        val pairingFailure: AirPairingFailure? = null,
        val running: Boolean = false,
        val busy: Busy? = null,
        val problem: Problem? = null,
        val footprint: Footprint? = null,
        val range: Range = Range.SESSION,
        val ledger: Report? = null,
        val callTracing: Boolean = true,
        /** The session the report covers, or the latest one when the report covers all of them. */
        val session: SessionView? = null,
        val spoolLimitMb: Int = DEFAULT_SPOOL_MB,
        /** The time the report covers. */
        val window: Pair<Long, Long> = 0L to 0L,
        /** Preinstalled apps may be removed for this user, not only disabled. */
        val allowRemovePreinstalled: Boolean = false,
        /** Expert mode: most core system components may be changed too, each after a warning. */
        val expertMode: Boolean = false,
        /** How the monitor is started, and whether that way is ready (paired, root granted, Shizuku allowed). */
        val method: com.suanran.shushu.access.AccessMethod = com.suanran.shushu.access.AccessMethod.WIRELESS,
        val ready: Boolean = false,
        val shizuku: com.suanran.shushu.access.ShizukuState = com.suanran.shushu.access.ShizukuState(),
        val root: RootShell.State = RootShell.State.UNCHECKED
    ) {
        /** The user started monitoring and has not stopped it, whether or not the monitor runs now. */
        val sessionOpen: Boolean get() = session?.latest == true && session.running
    }

    private data class Local(
        val busy: Busy? = null,
        val problem: Problem? = null,
        val footprint: Footprint? = null,
        val range: Range = Range.SESSION,
        val ledger: Report? = null,
        val callTracing: Boolean = true,
        val selected: Long? = null,
        val session: SessionView? = null,
        val spoolLimitMb: Int = DEFAULT_SPOOL_MB,
        val window: Pair<Long, Long> = 0L to 0L,
        val allowRemovePreinstalled: Boolean = false,
        val expertMode: Boolean = false
    )

    private val settings = context.getSharedPreferences("shushu_settings", Context.MODE_PRIVATE)
    private val owners = PackageOwners(context.packageManager)
    private val sync = JiumiSync(store, rules)
    private val usage = NetworkUsage(context)
    private val appKinds = AppKinds(context.packageManager)

    /** The apps the system has bound as its location providers, as the monitor last read them; kept across restarts. */
    @Volatile private var locationProviders: Set<String> = readLocationProviders(
        runCatching { java.io.File(context.filesDir, LOCATION_PROVIDERS_FILE).readText() }.getOrNull()
    )
    @Volatile private var providersReadAt = 0L

    /** Reads the location providers again when the last read is old; a monitor without the method leaves them as they were. */
    private fun refreshLocationProviders(service: com.suanran.shushu.probe.IProbeService) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (providersReadAt != 0L && now - providersReadAt < PROVIDERS_MAX_AGE_MS) return
        providersReadAt = now
        val json = runCatching { service.locationProviders() }.getOrNull() ?: return
        locationProviders = readLocationProviders(json)
        runCatching { java.io.File(context.filesDir, LOCATION_PROVIDERS_FILE).writeText(json) }
    }
    private val builder = ReportBuilder(
        store, rules, Process.myUid(), owners::ownerOf,
        traffic = { from, to -> usage.byUid(from, to) },
        kindOf = appKinds::kindOf,
        attentionRules = AttentionRules.load(context),
        holdsPermission = { op, pkg -> holdsPermission(op, pkg) },
        holdsAnyPermission = { pkg, permissions -> holdsAnyPermission(pkg, permissions) },
        packagesOfUid = owners::packagesOf,
        persistentOf = appKinds::persistent,
        locationProviderOf = { pkg -> pkg != null && pkg in locationProviders }
    )
    private val syncLock = Mutex()

    private val local = MutableStateFlow(Local(
        // Call tracing is always on: most kinds of data (device IDs, clipboard, the app list) are seen only by it.
        callTracing = true,
        spoolLimitMb = settings.getInt(KEY_SPOOL_MB, DEFAULT_SPOOL_MB),
        allowRemovePreinstalled = settings.getBoolean(KEY_ALLOW_REMOVE, false),
        expertMode = settings.getBoolean(KEY_EXPERT, false)
    ))

    private val packageFacts = PackageFactsSource(context)
    private val manageRules = ManageRules.load(context)
    private val backups = AppBackups(context)

    /**
     * One app as management sees it: its facts (null once deleted), why it is left alone if it is,
     * what may be done, and its backup when it was deleted from here.
     */
    data class Managed(val pkg: String, val facts: PackageFacts?, val refusal: Refusal?, val actions: List<ManageAction>,
                       val backup: AppBackups.Backup? = null, val risk: Risk? = null,
                       /** A preinstalled app that could be removed once the setting allows it. */
                       val removable: Boolean = false,
                       /** A core component left alone now that expert mode would let through. */
                       val expertUnlocks: Boolean = false)

    /** Where an app stands in the Apps tab. */
    enum class AppState { ACTIVE, DISABLED, SUSPENDED, DELETED }

    /** An app as the Apps tab lists it; [backup] for an app deleted from here, which is listed by it. */
    data class ListedApp(val pkg: String, val uid: Int, val preinstalled: Boolean, val state: AppState,
                         val backup: AppBackups.Backup? = null,
                         /** Where it came from; an app deleted from here was one the user added. */
                         val source: AppSource = AppSource.SIDELOADED,
                         /** The store or other package that installed it, when the system knows. */
                         val installer: String? = null,
                         /** Part of the phone rather than an app one opens: no launcher icon, or the platform itself. */
                         val component: Boolean = false)

    suspend fun managed(pkg: String): Managed? = withContext(Dispatchers.IO) { managedNow(pkg) }

    private fun managedNow(pkg: String): Managed? {
        val backup = backups.get(pkg)
        val facts = packageFacts.facts(pkg, keptShellPackages())
        if (facts == null) return backup?.let { Managed(pkg, null, null, listOf(ManageAction.REINSTALL), it) }
        val expert = local.value.expertMode
        val refusal = Management.refusal(facts, manageRules, context.packageName, expert)
        val actions = Management.actions(facts, local.value.allowRemovePreinstalled)
            .filter { refusal == null || Management.isUndo(it) }
        val risk = if (refusal == null) Management.risk(facts, manageRules) else null
        val removable = refusal == null && facts.preinstalled && facts.installed && !local.value.allowRemovePreinstalled
        val unlocks = !expert && refusal?.reason == Refusal.Reason.PLATFORM && Management.expertMay(facts, manageRules)
        return Managed(pkg, facts, refusal, actions, backup, risk, removable, unlocks)
    }

    /**
     * Every app that may be managed: installed apps with code of their own, not part of the platform,
     * preinstalled ones only when they show in the launcher or were disabled or removed; and apps
     * deleted from here that have a backup.
     */
    suspend fun listedApps(fresh: Boolean = false, includeSystem: Boolean = false): List<ListedApp> = withContext(Dispatchers.IO) {
        val saved = backups.list().associateBy { it.pkg }
        val shell = if (fresh) shellPackages() else keptShellPackages()
        val present = packageFacts.all(shell).filter { f ->
            val reason = Management.refusal(f, manageRules, context.packageName)?.reason
            // Resource overlays and the like have no code and are not apps; this app is not listed.
            f.hasCode && reason != Refusal.Reason.SELF && (includeSystem ||
                ((!f.preinstalled || f.launchable || f.disabledByUser || f.suspendedByShell || !f.installed) && reason != Refusal.Reason.PLATFORM))
        }.mapNotNull { f ->
            val state = when {
                !f.installed -> if (f.preinstalled) AppState.DELETED else return@mapNotNull null
                f.suspendedByShell -> AppState.SUSPENDED
                f.disabledByUser -> AppState.DISABLED
                else -> AppState.ACTIVE
            }
            val platform = Management.refusal(f, manageRules, context.packageName)?.reason == Refusal.Reason.PLATFORM
            ListedApp(f.pkg, f.uid, f.preinstalled, state, source = Management.source(f), installer = f.installer,
                component = platform || (f.preinstalled && !f.launchable))
        }
        val known = present.map { it.pkg }.toSet()
        present + saved.values.filter { it.pkg !in known }.map { ListedApp(it.pkg, it.uid, false, AppState.DELETED, it) }
    }

    /**
     * The package list as the monitor sees it: read fresh while it runs and kept, so the Apps tab
     * stays complete on phones that hide apps from one another after the monitor stops.
     */
    private fun shellPackages(): List<ShellPackage> {
        val file = java.io.File(context.filesDir, SHELL_PACKAGES_FILE)
        probe.service.value?.let { service ->
            runCatching { service.listPackages() }.getOrNull()?.let { json ->
                val parsed = ShellPackage.parse(json)
                if (parsed.isNotEmpty()) {
                    file.writeText(json)
                    shellReadAt = android.os.SystemClock.elapsedRealtime()
                    return parsed
                }
            }
        }
        return keptShellPackages()
    }

    /** When the monitor last listed the packages; cleared by each change, so the next list asks again. */
    @Volatile private var shellReadAt = 0L

    /**
     * Whether the Apps tab should ask the running monitor again. Its list costs a few shell commands,
     * so it is read once a minute at most, and after every change made here.
     */
    fun shellListStale(): Boolean = probe.service.value != null &&
        (shellReadAt == 0L || android.os.SystemClock.elapsedRealtime() - shellReadAt > SHELL_LIST_MAX_AGE_MS)

    private fun keptShellPackages(): List<ShellPackage> =
        runCatching { ShellPackage.parse(java.io.File(context.filesDir, SHELL_PACKAGES_FILE).readText()) }.getOrDefault(emptyList())

    fun backupIcon(pkg: String): android.graphics.Bitmap? = backups.icon(pkg)

    /** How often [pkg]'s alarms woke the phone since it started; null while the monitor is not running. */
    suspend fun wakeups(pkg: String): com.suanran.shushu.data.Wakeups? = withContext(Dispatchers.IO) {
        val service = probe.service.value ?: return@withContext null
        runCatching { service.alarmWakeups(pkg) }.getOrNull()
            ?.let { com.suanran.shushu.data.Wakeups.parse(it, pkg, System.currentTimeMillis()) }
    }

    /** How a management action ended. */
    enum class ManageStatus { DONE, DISABLED, REMOVED, NO_MONITOR, FAILED, PERMISSION_DENIED, NOT_APPLIED, UNVERIFIED }

    data class ManageResult(val status: ManageStatus, val running: Boolean? = null)

    private val manageLock = Mutex()

    /**
     * Carries out [action] on [pkg] through the monitor, which alone may change packages, and
     * records it. A monitor that is not running is started first, continuing the open session as
     * resuming would. An app deleted from here is first backed up, so it can be installed again.
     */
    suspend fun manage(pkg: String, action: ManageAction): ManageResult = withContext(Dispatchers.IO) {
        manageLock.withLock {
            val failed = ManageResult(ManageStatus.FAILED)
            val m = managedNow(pkg) ?: return@withLock failed
            if (action !in m.actions) return@withLock failed
            val service = ensureMonitor() ?: return@withLock ManageResult(ManageStatus.NO_MONITOR)
            if (action == ManageAction.UNINSTALL) {
                val pm = context.packageManager
                val app = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@withLock failed
                val icon = runCatching { pm.getApplicationIcon(app).toBitmap(192, 192) }.getOrNull()
                backups.create(app, app.loadLabel(pm).toString(), icon) ?: return@withLock failed
            }
            val response = runCatching { org.json.JSONObject(service.managePackage(action.id, pkg)) }.getOrNull()
            val ok = response?.optBoolean("ok") == true
            when {
                ok && action == ManageAction.REINSTALL -> backups.delete(pkg)
                !ok && action == ManageAction.UNINSTALL -> backups.delete(pkg)
            }
            shellReadAt = 0L
            val status = when (response?.optString("failure")) {
                PackageChangeFailure.PERMISSION_DENIED.name -> ManageStatus.PERMISSION_DENIED
                PackageChangeFailure.NOT_APPLIED.name -> ManageStatus.NOT_APPLIED
                PackageChangeFailure.UNVERIFIED.name -> ManageStatus.UNVERIFIED
                else -> ManageStatus.FAILED
            }
            if (ok || status == ManageStatus.UNVERIFIED) runCatching { shellPackages() }
            if (ok) store.insertUserAction(JiumiStore.UserAction(System.currentTimeMillis(), m.facts?.uid ?: m.backup?.uid ?: -1, pkg, action.id))
            refresh()
            if (!ok) return@withLock ManageResult(status)
            val running = response?.opt("running") as? Boolean
            when (action) {
                ManageAction.DISABLE -> ManageResult(ManageStatus.DISABLED, running)
                ManageAction.REMOVE -> ManageResult(ManageStatus.REMOVED, running)
                else -> ManageResult(ManageStatus.DONE)
            }
        }
    }

    suspend fun appAccess(key: com.suanran.shushu.data.AppKey, action: String, permission: String = ""):
        org.json.JSONObject? = withContext(Dispatchers.IO) {
        val pkg = key.pkg ?: return@withContext null
        val service = ensureMonitor() ?: return@withContext null
        runCatching { org.json.JSONObject(service.appAccess(pkg, key.uid, action, permission)) }.getOrNull()
    }

    /** The running monitor, started (or resumed) first when it is not; null when it would not start. */
    private suspend fun ensureMonitor(): com.suanran.shushu.probe.IProbeService? {
        probe.service.value?.let { return it }
        startMonitor()
        withTimeoutOrNull(CONNECT_TIMEOUT_MS + START_TIMEOUT_MS + 5_000) {
            combine(probe.service, local) { service, l -> service != null || (l.busy == null && l.problem != null) }.first { it }
        }
        return probe.service.value
    }

    /** Throws away the backup of a deleted app; it can no longer be installed again from here. */
    suspend fun discardBackup(pkg: String) = withContext(Dispatchers.IO) {
        backups.delete(pkg)
        refresh()
    }

    /** ADB persistence: the monitor can be started without Wi-Fi, through a local port, until a restart. */
    val adbPersistence: StateFlow<Boolean> get() = pairing.persistenceEnabled

    /** The local port needs USB debugging on as well; null when the phone will not say. */
    fun usbDebuggingEnabled(): Boolean? = pairing.isUsbDebuggingEnabled()

    /**
     * Turns ADB persistence on or off. Turning it on while the monitor runs opens the local port right
     * away with a short connection (otherwise the next start opens it); off closes the port.
     */
    fun setAdbPersistence(on: Boolean) {
        if (!on) {
            scope.launch(Dispatchers.IO) { pairing.disablePersistence() }
            return
        }
        pairing.setPersistenceEnabled(true)
        if (probe.service.value == null || !isPaired || local.value.busy != null) return
        scope.launch(Dispatchers.IO) {
            if (!pairing.reconnect(restoreWirelessDebugging = false)) return@launch
            withTimeoutOrNull(CONNECT_TIMEOUT_MS + 25_000) {
                pairing.state.first { it == PairingState.CONNECTED || pairing.failure.value != null }
            }
            // The monitor does not depend on ADB; the port stays open without this connection.
            pairing.disconnect()
        }
    }

    fun setExpertMode(on: Boolean) {
        settings.edit().putBoolean(KEY_EXPERT, on).apply()
        local.update { it.copy(expertMode = on) }
    }

    fun setAllowRemovePreinstalled(on: Boolean) {
        settings.edit().putBoolean(KEY_ALLOW_REMOVE, on).apply()
        local.update { it.copy(allowRemovePreinstalled = on) }
    }

    private data class Env(val method: com.suanran.shushu.access.AccessMethod, val trust: PairingTrustState,
                           val root: RootShell.State, val shizuku: com.suanran.shushu.access.ShizukuState)

    private val env = combine(access.method, pairing.pairingTrustState, access.root.state, access.shizuku.state, ::Env)

    val state: StateFlow<UiState> = combine(
        pairing.state, pairing.failure, probe.service, local, env
    ) { pairingState, failure, service, l, e ->
        val ready = when (e.method) {
            com.suanran.shushu.access.AccessMethod.WIRELESS -> e.trust == PairingTrustState.PAIRED
            com.suanran.shushu.access.AccessMethod.ROOT -> e.root == RootShell.State.READY
            com.suanran.shushu.access.AccessMethod.SHIZUKU -> e.shizuku.ready
        }
        val problem = l.problem?.takeUnless { ready && it == Problem.SHIZUKU }
        UiState(pairingState, e.trust, failure, service != null, l.busy, problem, l.footprint, l.range,
            l.ledger, l.callTracing, l.session, l.spoolLimitMb, l.window, l.allowRemovePreinstalled, l.expertMode,
            e.method, ready, e.shizuku, e.root)
    }.stateIn(scope, SharingStarted.Eagerly, UiState())

    init {
        // A fresh pairing connects automatically; continue straight into starting the monitor.
        scope.launch {
            pairing.state.collect { s ->
                if (s == PairingState.CONNECTED && probe.service.value == null && local.value.busy == null &&
                    access.method.value == com.suanran.shushu.access.AccessMethod.WIRELESS) startMonitor()
            }
        }
        scope.launch {
            probe.service.collect { service ->
                if (service == null) {
                    local.update { l -> l.copy(footprint = null) }
                    showIndicator()
                } else {
                    // The APK this monitor runs from; an update stops it (see [resumeAfterUpdate]).
                    settings.edit().putLong(KEY_MONITOR_APK, installedAt()).apply()
                    configure(service)
                    refreshFootprint()
                    refresh()
                }
            }
        }
        refresh()
        // Some makers (Xiaomi among them) do not deliver the update broadcast to an app without
        // autostart; opening the app then picks the session up instead.
        if (settings.getLong(KEY_MONITOR_APK, 0L).let { it != 0L && it != installedAt() }) resumeAfterUpdate()
    }

    /** When this APK was installed or last updated. */
    private fun installedAt(): Long =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)

    /** Paired now, read straight from pairing rather than from [state], which may not have caught up. */
    val isPaired: Boolean get() = pairing.pairingTrustState.value == PairingTrustState.PAIRED

    fun startPairing(): Boolean {
        local.update { it.copy(problem = null) }
        return pairing.startRequiredPairing()
    }

    fun cancelPairing() = pairing.stopDiscovery()

    /**
     * After this app was updated: the monitor ran from the old APK and stopped with it. When a session
     * is still open, start it again from the new one, so an update does not silently end monitoring.
     * Only an ADB channel that is still up is used; Wireless debugging is not turned on unasked.
     */
    fun resumeAfterUpdate(): kotlinx.coroutines.Job = scope.launch(Dispatchers.IO) {
        // The old monitor may still be offering itself for a moment; give it time to go.
        kotlinx.coroutines.delay(RESUME_DELAY_MS)
        val skip = when {
            probe.service.value != null -> "monitor running"
            local.value.busy != null -> "busy ${local.value.busy}"
            store.sessions().none { it.end == null } -> "no open session"
            !canStartUnattended() -> "${access.method.value} not ready"
            else -> null
        }
        android.util.Log.i(TAG, "resume after update: ${skip ?: "starting"}")
        if (skip != null) return@launch
        // Asked once per update, whether or not the start works; after that it is the user's to resume.
        settings.edit().putLong(KEY_MONITOR_APK, installedAt()).apply()
        startMonitor(restoreWirelessDebugging = false)
        // Held until the start is settled, so the process is not let go halfway.
        withTimeoutOrNull(CONNECT_TIMEOUT_MS + START_TIMEOUT_MS) { local.first { it.busy == null } }
    }

    fun startMonitor(restoreWirelessDebugging: Boolean = true) {
        if (local.value.busy != null) return
        local.update { it.copy(busy = Busy.STARTING, problem = null) }
        scope.launch(Dispatchers.IO) {
            var problem: Problem? = null
            try {
                if (probe.service.value != null) {
                    openSessionIfNone()
                    return@launch
                }
                problem = when (access.method.value) {
                    com.suanran.shushu.access.AccessMethod.WIRELESS -> startOverWireless(restoreWirelessDebugging)
                    com.suanran.shushu.access.AccessMethod.ROOT -> startOverRoot()
                    com.suanran.shushu.access.AccessMethod.SHIZUKU -> startOverShizuku()
                }
                if (problem == null) openSessionIfNone()
            } finally {
                local.update { it.copy(busy = null, problem = problem) }
            }
        }
    }

    /** Over Wireless debugging (or its local channel): connect, start, and let ADB go again. */
    private suspend fun startOverWireless(restoreWirelessDebugging: Boolean): Problem? {
        try {
            if (!pairing.isShellReady) {
                pairing.reconnect(restoreWirelessDebugging = restoreWirelessDebugging)
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    pairing.state.first { it == PairingState.CONNECTED || pairing.failure.value != null }
                }
                if (!pairing.isShellReady) return Problem.CONNECT
            }
            pairing.shell(ownPermissionsCommand())
            pairing.shell(probe.startCommand())
            return waitForProbe()
        } finally {
            // The monitor does not depend on ADB; never keep the session open.
            pairing.disconnect()
        }
    }

    private suspend fun startOverRoot(): Problem? {
        val command = ownPermissionsCommand() + "\n" + probe.startCommand()
        if (!access.root.shell(command, PROBE_SHELL_MS)) return Problem.START
        return waitForProbe()
    }

    private suspend fun startOverShizuku(): Problem? {
        if (access.shizuku.shell("id -u", PROBE_SHELL_MS).trim() != "2000") return Problem.SHIZUKU
        access.shizuku.shell(ownPermissionsCommand(), PROBE_SHELL_MS)
        if (access.shizuku.shell(probe.startCommand(), START_TIMEOUT_MS).startsWith("[SHIZUKU_")) return Problem.SHIZUKU
        val problem = waitForProbe()
        access.shizuku.release()
        return problem
    }

    private suspend fun waitForProbe(): Problem? =
        if (withTimeoutOrNull(START_TIMEOUT_MS) { probe.service.first { it != null } } == null) Problem.START else null

    /** Whether the chosen way can start the monitor without asking the user anything. */
    private fun canStartUnattended(): Boolean = when (access.method.value) {
        com.suanran.shushu.access.AccessMethod.WIRELESS -> isPaired
        com.suanran.shushu.access.AccessMethod.ROOT -> access.root.wasReady
        com.suanran.shushu.access.AccessMethod.SHIZUKU -> access.shizuku.refresh().ready
    }

    private val checkGate = com.suanran.shushu.access.AccessCheckGate()

    /** Asks the root manager for root (its own dialog, where supported), or checks whether root was granted. */
    fun authorizeRoot() {
        if (local.value.busy != null || !checkGate.beginRoot()) return
        local.update { it.copy(problem = null) }
        scope.launch(Dispatchers.IO) {
            try {
                access.root.authorize()
            } finally {
                checkGate.endRoot()
            }
        }
    }

    /** Asks Shizuku to let this app use it (Shizuku's own dialog). */
    fun authorizeShizuku() {
        if (!checkGate.beginShizuku()) return
        scope.launch {
            try {
                val ok = access.shizuku.requestAuthorization()
                local.update {
                    it.copy(problem = if (ok || access.method.value != com.suanran.shushu.access.AccessMethod.SHIZUKU) null else Problem.SHIZUKU)
                }
            } finally {
                checkGate.endShizuku()
            }
        }
    }

    fun selectAccess(method: com.suanran.shushu.access.AccessMethod) {
        val changed = access.method.value != method
        access.select(method)
        local.update { it.copy(problem = null) }
        when (method) {
            com.suanran.shushu.access.AccessMethod.WIRELESS -> Unit
            com.suanran.shushu.access.AccessMethod.ROOT -> {
                if (checkGate.onSelectRoot(changed, access.root.ready)) authorizeRoot()
            }
            com.suanran.shushu.access.AccessMethod.SHIZUKU -> {
                if (checkGate.onSelectShizuku(access.shizuku.refresh())) authorizeShizuku()
            }
        }
    }

    /** Records that the user left Jiumi (including via a floating window) while no prompt of ours was running. */
    fun onAppPaused() = checkGate.onPaused()

    /** Updates Root or Shizuku readiness when returning from a root manager or Shizuku without restarting. */
    fun onAppResumed() {
        when (access.method.value) {
            com.suanran.shushu.access.AccessMethod.WIRELESS -> checkGate.onResumeWireless()
            com.suanran.shushu.access.AccessMethod.ROOT -> {
                if (checkGate.onResumeRoot(access.root.ready)) authorizeRoot()
            }
            com.suanran.shushu.access.AccessMethod.SHIZUKU -> {
                val s = access.shizuku.refresh()
                val shouldAsk = checkGate.onResumeShizuku(s)
                if (s.ready) {
                    local.update { if (it.problem == Problem.SHIZUKU) it.copy(problem = null) else it }
                } else if (shouldAsk) {
                    authorizeShizuku()
                }
            }
        }
    }

    /** Ends the session; the monitor stops if it runs (after an interruption it may not). */
    fun stopMonitor() {
        local.update { it.copy(busy = Busy.STOPPING) }
        scope.launch(Dispatchers.IO) {
            probe.service.value?.let { service ->
                // Collect what it recorded before it goes.
                syncLock.withLock { runCatching { sync.sync(service) } }
                runCatching { service.shutdown() }
                withTimeoutOrNull(5_000) { probe.service.first { it == null } }
            }
            val now = System.currentTimeMillis()
            store.sessions().filter { it.end == null }.forEach { store.endSession(it.id, now) }
            showIndicator()
            local.update { it.copy(busy = null) }
            refresh()
        }
    }

    /**
     * The persistent notification follows the monitor: shown while it runs within an open session,
     * removed otherwise. Shown once per session start, so refreshes do not restart it.
     */
    private fun showIndicator() {
        val open = store.sessions().firstOrNull { it.end == null }
        val since = open?.start?.takeIf { probe.service.value != null }
        if (since == shownSince) return
        // A start refused while the app is in the background is tried again on the next refresh.
        if (since == null) MonitorNotificationService.hide(context)
        else if (!MonitorNotificationService.show(context, since)) return
        shownSince = since
    }

    @Volatile private var shownSince: Long? = null

    /** Posts the notification again, for when it was posted before notifications were allowed. */
    fun reshowIndicator() {
        shownSince = null
        scope.launch(Dispatchers.IO) { showIndicator() }
    }

    /** Starting begins a session; resuming after an interruption continues the open one. */
    private fun openSessionIfNone() {
        if (store.sessions().any { it.end == null }) return
        store.startSession(System.currentTimeMillis())
        local.update { it.copy(range = Range.SESSION, selected = null) }
        // Shown as running at once: the first sync imports the system's history and can take a while.
        scope.launch(Dispatchers.IO) {
            syncLock.withLock { rebuild() }
            refresh()
        }
    }

    /** Every session, newest first, for the monitoring record. */
    suspend fun sessions(): List<SessionView> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        store.sessions().mapIndexed { i, s -> SessionView(s, coverage(s, now), latest = i == 0) }
    }

    fun selectSession(id: Long) {
        local.update { it.copy(range = Range.SESSION, selected = id) }
        refresh()
    }

    /** Pulls what the monitor recorded (when it runs) and rebuilds the report from the store. */
    fun refresh() {
        scope.launch(Dispatchers.IO) {
            syncLock.withLock {
                probe.service.value?.let { service ->
                    runCatching { sync.sync(service) }.onFailure { android.util.Log.w(TAG, "sync failed", it) }
                    refreshLocationProviders(service)
                }
                rebuild()
            }
        }
    }

    /**
     * Called every few seconds while the app is on screen: pulls what the monitor recorded since and,
     * only when something new came in, rebuilds the report, so every view follows a running session.
     * Skipped while another refresh is under way.
     */
    suspend fun refreshLive() = withContext(Dispatchers.IO) {
        val service = probe.service.value ?: return@withContext
        if (!syncLock.tryLock()) return@withContext
        try {
            val stored = runCatching { sync.sync(service) }.onFailure { android.util.Log.w(TAG, "sync failed", it) }.getOrDefault(0)
            refreshLocationProviders(service)
            pendingLive += stored
            // A long range costs more to rebuild; give the phone a breather in proportion.
            if (pendingLive > 0 && System.currentTimeMillis() - rebuiltAt >= rebuildCostMs * 4) rebuild()
        } finally {
            syncLock.unlock()
        }
    }

    private fun rebuild() {
        val now = System.currentTimeMillis()
        val session = chosenSession(now)
        val window = window(session, now)
        val report = builder.report(window.first, window.second)
        local.update { it.copy(ledger = report, session = session, window = window) }
        rebuiltAt = System.currentTimeMillis()
        rebuildCostMs = rebuiltAt - now
        pendingLive = 0
        showIndicator()
    }

    // Kept under syncLock.
    private var rebuiltAt = 0L
    private var rebuildCostMs = 0L
    private var pendingLive = 0

    /** Most space the monitor's spool may use while the app is not opened; 0 means no limit. */
    fun setSpoolLimit(mb: Int) {
        settings.edit().putInt(KEY_SPOOL_MB, mb).apply()
        local.update { it.copy(spoolLimitMb = mb) }
        val service = probe.service.value ?: return
        scope.launch(Dispatchers.IO) { configure(service) }
    }

    private fun configure(service: com.suanran.shushu.probe.IProbeService) {
        runCatching { service.configure(rules.monitorConfig(local.value.callTracing, local.value.spoolLimitMb)) }
    }

    fun setRange(range: Range) {
        local.update { it.copy(range = range, selected = if (range == Range.SESSION) it.selected else null) }
        refresh()
    }

    suspend fun detail(key: AppKey): AppDetail = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val (from, to) = window(chosenSession(now), now)
        builder.detail(key, from, to)
    }

    /** The picked session, or else the latest; null before the first session. */
    private fun chosenSession(now: Long): SessionView? {
        val all = store.sessions()
        val picked = local.value.selected?.let { id -> all.firstOrNull { it.id == id } } ?: all.firstOrNull() ?: return null
        return SessionView(picked, coverage(picked, now), latest = picked.id == all.first().id)
    }

    private fun coverage(s: JiumiStore.Session, now: Long) =
        Coverage.compute(s.start, s.end, store.monitorEvents(s.start - Coverage.MAX_GAP_MS, s.end ?: now), now)

    /** The time the report covers: one session, or a span reaching back from now. */
    private fun window(session: SessionView?, now: Long, range: Range = local.value.range): Pair<Long, Long> = when (range) {
        Range.SESSION -> session?.let { it.session.start to (it.session.end ?: now) } ?: (now to now)
        Range.TODAY -> daysEndingToday(now, 1) to now
        Range.WEEK -> daysEndingToday(now, 7) to now
        Range.MONTH -> daysEndingToday(now, 30) to now
    }

    /** Local midnight [days] - 1 days before today's. */
    private fun daysEndingToday(now: Long, days: Int): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = now
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        add(java.util.Calendar.DAY_OF_YEAR, 1 - days)
    }.timeInMillis

    /**
     * Writes the record of [range] (null: everything kept) to [uri] as a zip of CSV files; see
     * [LogExport]. [label] and [name] give app names and texts in the language the user sees.
     */
    suspend fun export(
        uri: android.net.Uri,
        range: Range?,
        readme: String,
        label: (AppKey) -> String,
        name: (prefix: String, id: String) -> String?
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            val (from, to) = if (range == null) 0L to now else window(chosenSession(now), now, range)
            val sessions = store.sessions().filter { s -> s.start <= to && (s.end ?: now) >= from }
                .map { LogExport.SessionRow(it, coverage(it, now)) }
            val pairingLog = java.io.File(context.filesDir, "logs/adb.log").takeIf { it.isFile }?.readBytes()
            context.contentResolver.openOutputStream(uri)!!.use { out ->
                LogExport(store, label, name).write(out, from, to, builder.report(from, to), sessions, readme,
                    listOfNotNull(pairingLog?.let { "pairing.log" to it }).toMap())
            }
        }.onFailure { android.util.Log.w(TAG, "export failed", it) }.isSuccess
    }

    fun unpair() {
        pairing.resetPairing()
        local.update { it.copy(problem = null) }
    }

    private fun refreshFootprint() {
        val service = probe.service.value ?: return
        scope.launch(Dispatchers.IO) {
            val footprint = runCatching { ProbeJson.footprint(service.footprint()) }.getOrNull()
            local.update { it.copy(footprint = footprint) }
        }
    }

    /** These grants change only this app: restoring Wireless debugging later and reading per-app traffic. */
    private fun ownPermissionsCommand(): String {
        val pkg = context.packageName
        return "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS; " +
            "appops set $pkg GET_USAGE_STATS allow" +
            (if (definesPermission(GET_INSTALLED_APPS)) "; pm grant $pkg $GET_INSTALLED_APPS" else "")
    }

    private fun definesPermission(name: String) = runCatching { context.packageManager.getPermissionInfo(name, 0) }.isSuccess

    /** Whether [pkg] holds the permission AppOps defers [op] to; null for ops without one. */
    private fun holdsPermission(op: String, pkg: String?): Boolean? {
        pkg ?: return null
        val permission = permissionOfOp[op] ?: return null
        // Only an installed package can be judged; a daemon's name is not one.
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.getOrNull() ?: return null
        return context.packageManager.checkPermission(permission, pkg) == PackageManager.PERMISSION_GRANTED
    }

    /** Whether installed [pkg] holds any of [permissions]; null for anything that is not an installed package. */
    private fun holdsAnyPermission(pkg: String?, permissions: List<String>): Boolean? {
        pkg ?: return null
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.getOrNull() ?: return null
        return permissions.any { context.packageManager.checkPermission(it, pkg) == PackageManager.PERMISSION_GRANTED }
    }

    /** The permission behind each op, from the platform's own permission list ("android:get_usage_stats" → PACKAGE_USAGE_STATS). */
    private val permissionOfOp: Map<String, String> by lazy {
        runCatching {
            context.packageManager.getPackageInfo("android", PackageManager.GET_PERMISSIONS).permissions.orEmpty()
                .mapNotNull { p -> AppOpsManager.permissionToOp(p.name)?.let { op -> op to p.name } }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    private companion object {
        const val TAG = "JiumiSync"
        const val KEY_SPOOL_MB = "spool_limit_mb"
        const val KEY_ALLOW_REMOVE = "allow_remove_preinstalled"
        const val KEY_MONITOR_APK = "monitor_apk_installed_at"
        const val KEY_EXPERT = "expert_mode"
        const val DEFAULT_SPOOL_MB = 256
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val RESUME_DELAY_MS = 3_000L
        const val START_TIMEOUT_MS = 15_000L
        const val PROBE_SHELL_MS = 30_000L
        const val SHELL_PACKAGES_FILE = "shell_packages.json"
        const val LOCATION_PROVIDERS_FILE = "location_providers.json"
        const val PROVIDERS_MAX_AGE_MS = 10 * 60_000L

        /** The packages of a monitor's location-provider list; empty for none or anything unreadable. */
        fun readLocationProviders(json: String?): Set<String> = runCatching {
            val a = org.json.JSONArray(json ?: return emptySet())
            (0 until a.length()).map { a.getJSONObject(it).getString("pkg") }.toSet()
        }.getOrDefault(emptySet())
        const val SHELL_LIST_MAX_AGE_MS = 60_000L
        /** The app-list permission some phone makers add; granted where the phone defines it. */
        const val GET_INSTALLED_APPS = "com.android.permission.GET_INSTALLED_APPS"
    }
}
