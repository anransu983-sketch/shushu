// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.data

data class AppKey(val uid: Int, val pkg: String?)

/**
 * [byApp]: event count per app, largest first. [background] counts events while the app was in the
 * background or the screen was off; [last] is the latest event end.
 */
data class CategorySummary(
    val id: String, val count: Int, val byApp: List<Pair<AppKey, Int>>, val background: Int = 0, val last: Long = 0
) {
    val apps: Int get() = byApp.size
}

data class GroupSummary(val id: String, val categories: List<CategorySummary>, val icon: String = "") {
    val count: Int get() = categories.sumOf { it.count }
    val apps: Int get() = categories.flatMap { c -> c.byApp.map { it.first } }.toSet().size
    val background: Int get() = categories.sumOf { it.background }
    val last: Long get() = categories.maxOfOrNull { it.last } ?: 0L
}

/** An item the overview raises, with the apps it concerns, most affected first. */
data class Attention(val id: String, val apps: List<AppKey>)

data class AppSummary(
    val key: AppKey,
    val accesses: Int,
    val calls: Int,
    val categories: List<String>,
    val last: Long,
    val background: Int,
    val denied: Int,
    val processStarts: Int,
    val destinations: Int,
    val traffic: NetworkUsage.Traffic?,
    /** TCP bytes sent over the recorded connections (lower bound). */
    val uploaded: Long = 0,
    /** Conversations in which the app sent at least the attention threshold while in the background. */
    val sentInBackground: Int = 0,
    val kind: AppKind = AppKind.THIRD_PARTY,
    /** First-level kinds of information the app touched, most frequent first. */
    val groups: List<String> = emptyList(),
    /** The busiest 60 minutes of one category that reached its threshold, counting what went through. */
    val frequent: Peak? = null,
    /** The same for refused attempts: an app trying again and again after being refused. */
    val frequentDenied: Peak? = null,
    /** What the system itself counted over the window's days, per kind of data; empty when it counted nothing. */
    val systemCounts: List<SystemCount> = emptyList()
) {
    /** The system's counts of kinds of data Jiumi can name; a maker's own operations are listed, not counted here. */
    val systemBackground: Long get() = systemCounts.filter { !it.vendor }.sumOf { it.bg }
    val systemTotal: Long get() = systemCounts.filter { !it.vendor }.sumOf { it.fg + it.bg }
}

/**
 * How often an app reached one kind of data by the system's own long-term count: in the foreground,
 * in the background, and refused. The system counts every caller, also before monitoring began, but
 * keeps no times.
 */
data class SystemCount(val category: String, val fg: Long, val bg: Long, val rejected: Long) {
    /** [category] is a maker's own operation, such as "vendor:MIUIOP(10036)", whose meaning is not published. */
    val vendor: Boolean get() = isVendor(category)

    companion object {
        fun isVendor(op: String) = op.startsWith("vendor:")
    }
}

/** [count] records of [category] between [from] and [to], all within 60 minutes. */
data class Peak(val category: String, val count: Int, val from: Long, val to: Long)

/** One record or behavior segment of [key], as the cross-app timeline lists it. */
data class LogEntry(val key: AppKey, val item: TimelineItem)

/**
 * [timeline]: every app's records over the window, newest first. [groupOf] maps each category to its
 * first-level kind and [groupIcons] names each kind's symbol.
 */
data class Report(
    val from: Long, val groups: List<GroupSummary>, val apps: List<AppSummary>, val attention: List<Attention> = emptyList(),
    val timeline: List<LogEntry> = emptyList(),
    val groupOf: Map<String, String> = emptyMap(),
    val groupIcons: Map<String, String> = emptyMap(),
    /** Kinds of caller whose records are routine system work, folded away unless asked for. */
    val routineKinds: Set<String> = emptySet()
) {
    fun isRoutine(key: AppKey): Boolean = apps.firstOrNull { it.key == key }?.kind?.id in routineKinds

    val networkApps: List<AppSummary> get() = apps.filter { it.traffic != null || it.destinations > 0 }
        .sortedWith(compareByDescending<AppSummary> { it.sentInBackground }.thenByDescending { it.uploaded }
            .thenByDescending { it.traffic?.total ?: 0L })
}

/**
 * One address an app talked to over a window: how many conversations, when it last did, and the
 * bytes when the platform counts them per connection. [latest] opens the record of the last one.
 */
data class Destination(
    val label: String, val conversations: Int, val last: Long, val sent: Long?, val received: Long?,
    val ports: List<Int>, val latest: TimelineItem.NetItem,
    /** Times the app looked this domain up, when domain recording is on and [label] is that domain. */
    val lookups: Int = 0
)

/**
 * [items]: the app's behaviors, network conversations aside; [groupOf] maps each category to its
 * first-level kind, for filtering. [destinations] and [traffic] tell its network activity.
 */
data class AppDetail(
    val items: List<TimelineItem>,
    val groupOf: Map<String, String> = emptyMap(),
    /** Symbol of each first-level kind, by kind id. */
    val groupIcons: Map<String, String> = emptyMap(),
    /** Per kind of data, most worrying first. */
    val summaries: List<BehaviorSummary> = emptyList(),
    /** The app is a caller whose records are routine system work. */
    val routine: Boolean = false,
    /** Every behavior over the window per time bucket, for the app's chart. */
    val chart: Buckets? = null,
    /** Addresses contacted, latest first. */
    val destinations: List<Destination> = emptyList(),
    /** Bytes the system counted for the app over the window. */
    val traffic: NetworkUsage.Traffic? = null,
    /**
     * Domains the app looked up but was not seen connecting to (a prefetch, a failed lookup, or a
     * connection too short to see), most often first; looked-up domains it did reach are counted on
     * their [destinations]. Empty unless domain recording is on.
     */
    val lookedUpOnly: List<DomainSummary> = emptyList(),
    /** The system's own counts over the window's days, most first. */
    val systemCounts: List<SystemCount> = emptyList(),
    /** Every package that runs under the app's uid, when more than one does: records by uid alone cannot tell them apart. */
    val sharedUid: List<String> = emptyList()
)

sealed interface TimelineItem {
    val t: Long

    /** [calls]: method calls of the same action, counted as this access (see [Overlap]). */
    data class AccessItem(
        val access: JiumiStore.Access, val category: String, val calls: List<JiumiStore.Call> = emptyList()
    ) : TimelineItem {
        override val t get() = access.t
    }

    /** [refused]: the app lacks the permission the call needs, so it got nothing. */
    data class CallItem(val call: JiumiStore.Call, val category: String, val refused: Boolean = false) : TimelineItem {
        override val t get() = call.t
    }

    data class ProcessItem(val start: JiumiStore.ProcessStart) : TimelineItem {
        override val t get() = start.t
    }

    /** Repeated records of one behavior, newest first; [head] stands for them in lists. */
    data class Segment(val members: List<TimelineItem>) : TimelineItem {
        val head: TimelineItem get() = members.first()
        val first: Long get() = members.minOf { it.t }
        override val t get() = members.maxOf { it.t }
    }

    /** Something the user did to the app from here: disabled, enabled, uninstalled, removed or restored it. */
    data class ActionItem(val action: JiumiStore.UserAction) : TimelineItem {
        override val t get() = action.t
    }

    /**
     * A conversation with a destination and its circumstances. [sentInBackground]: the app sent at
     * least the attention threshold while in the background. [label] is the domain looked up for the
     * address when one was recorded, else the address itself.
     */
    data class NetItem(
        val connection: JiumiStore.Connection, val label: String, val sentInBackground: Boolean
    ) : TimelineItem {
        override val t get() = connection.t
    }
}

/**
 * Builds report views from the store. [ownerOf] maps a calling process to its package; [excludedUid]
 * is this app, which is not part of its own record.
 */
class ReportBuilder(
    private val store: JiumiStore,
    private val rules: AccessRules,
    private val excludedUid: Int,
    private val ownerOf: (uid: Int, process: String?) -> String?,
    private val traffic: (from: Long, to: Long) -> Map<Int, NetworkUsage.Traffic> = { _, _ -> emptyMap() },
    private val kindOf: (AppKey) -> AppKind = { AppKind.THIRD_PARTY },
    private val attentionRules: AttentionRules? = null,
    /** Whether [pkg] holds the permission behind [op]; null when unknown or the op has none. */
    private val holdsPermission: (op: String, pkg: String?) -> Boolean? = { _, _ -> null },
    /** Whether [pkg] holds any of [permissions]; null when it is not an installed package. */
    private val holdsAnyPermission: (pkg: String?, permissions: List<String>) -> Boolean? = { _, _ -> null },
    /** The packages that run under a uid. */
    private val packagesOfUid: (uid: Int) -> List<String> = { emptyList() },
    /** Whether [pkg] is a persistent system app, always running as part of the system (the status bar, the phone service). */
    private val persistentOf: (pkg: String?) -> Boolean = { false },
    /** Whether [pkg] is bound by the system as one of its location providers. */
    private val locationProviderOf: (pkg: String?) -> Boolean = { false }
) {
    /** One sensitive access or call, for summaries. */
    private class Event(
        val category: String, val key: AppKey, val count: Int, val t: Long, val end: Long, val unattended: Boolean,
        val denied: Boolean = false
    )

    /** Calls left to count on their own, accesses, and the calls each access absorbed as the same action. */
    private class Classified(
        val calls: List<Pair<JiumiStore.Call, String>>,
        val accesses: List<Pair<JiumiStore.Access, String>>,
        val absorbed: Map<JiumiStore.Access, List<JiumiStore.Call>> = emptyMap(),
        /** Calls that could not return their data: the app lacks the permission they need. */
        val refusedCalls: Set<JiumiStore.Call> = emptySet()
    )

    fun report(from: Long, to: Long): Report {
        val data = classify(store.accesses(from, to), store.calls(from, to))
        val starts = store.processStartCounts(from, to)
        val keyedAccesses = data.accesses.map { AppKey(it.first.uid, it.first.pkg) to it }
        val keyedCalls = data.calls.map { AppKey(it.first.uid, ownerOf(it.first.uid, it.first.process)) to it }

        val events = data.accesses.map { (a, c) ->
            // A refused attempt gave the app nothing, wherever it was.
            Event(c, AppKey(a.uid, a.pkg), a.count, a.t, maxOf(a.t, a.end), !a.denied && isUnattended(a.state, a.screen), a.denied)
        } + keyedCalls.map { (key, c) ->
            val refused = c.first in data.refusedCalls
            Event(c.second, key, c.first.count, c.first.t, maxOf(c.first.t, c.first.end),
                !refused && isUnattended(c.first.state, c.first.screen), refused)
        }
        val eventsByKey = events.groupBy { it.key }
        val categories = events.groupBy { it.category }.map { (id, list) ->
            val byApp = list.groupBy({ it.key }, { it.count }).map { (k, v) -> k to v.sum() }.sortedByDescending { it.second }
            CategorySummary(id, list.sumOf { it.count }, byApp, list.filter { it.unattended }.sumOf { it.count }, list.maxOf { it.end })
        }.associateBy { it.id }
        val groups = rules.groups.mapNotNull { g ->
            g.categories.mapNotNull { categories[it] }.sortedByDescending { it.count }.takeIf { it.isNotEmpty() }
                ?.let { GroupSummary(g.id, it, g.icon) }
        }

        val names = DomainNames(store.lookups(from - DomainNames.LOOKBACK_MS, to))
        val connections = store.connections(from, to).filter { it.uid != excludedUid && !isLookupTraffic(it) }.map(::persistent)
            .groupBy { AppKey(it.uid, ownerOf(it.uid, null)) }
        val trafficByKey = traffic(from, to).filterKeys { it >= 0 && it != excludedUid }.entries
            .groupBy({ AppKey(it.key, ownerOf(it.key, null)) }, { it.value })
            .mapValues { (_, list) -> list.reduce(NetworkUsage.Traffic::plus) }

        val opCounts = store.opCounts(from, to).filter { it.uid != excludedUid }.groupBy { AppKey(it.uid, it.pkg) }
        val systemByKey = opCounts.mapValues { systemCounts(it.value) }.filterValues { it.isNotEmpty() }
        val keys = (keyedAccesses.map { it.first } + keyedCalls.map { it.first } + connections.keys +
            trafficByKey.keys + systemByKey.keys).toSet()
        val accessesByKey = keyedAccesses.groupBy({ it.first }, { it.second })
        val callsByKey = keyedCalls.groupBy({ it.first }, { it.second })
        val timeline = ArrayList<LogEntry>()
        val apps = keys.map { key ->
            val contacts = connections[key].orEmpty()
            val accesses = accessesByKey[key].orEmpty()
            val calls = callsByKey[key].orEmpty()
            val nets = conversations(contacts, labeler(names, key.uid))
            items(accesses, calls, nets, emptyList(), data)
                .mapTo(timeline) { LogEntry(key, it) }
            val categoryWeights = (accesses.map { it.second to it.first.count } + calls.map { it.second to it.first.count })
                .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
            AppSummary(
                key = key,
                accesses = accesses.sumOf { it.first.count },
                calls = calls.sumOf { it.first.count },
                categories = categoryWeights.entries.sortedByDescending { it.value }.map { it.key },
                last = maxOf(accesses.maxOfOrNull { maxOf(it.first.t, it.first.end) } ?: 0L,
                    calls.maxOfOrNull { maxOf(it.first.t, it.first.end) } ?: 0L,
                    contacts.maxOfOrNull { it.end } ?: 0L).takeIf { it > 0 }
                    // Known only from the system's counts: the end of the last day it counted anything.
                    ?: opCounts[key]?.maxOfOrNull { minOf(it.to, to) } ?: 0L,
                background = accesses.filter { !it.first.denied && isUnattended(it.first.state, it.first.screen) }
                    .sumOf { it.first.count } +
                    calls.filter { it.first !in data.refusedCalls && isUnattended(it.first.state, it.first.screen) }
                        .sumOf { it.first.count },
                denied = accesses.filter { it.first.denied }.sumOf { it.first.count } +
                    calls.filter { it.first in data.refusedCalls }.sumOf { it.first.count },
                processStarts = starts[key] ?: 0,
                destinations = contacts.map { labeler(names, key.uid)(it.addr) }.toSet().size,
                traffic = trafficByKey[key],
                uploaded = nets.sumOf { it.connection.sent ?: 0L },
                sentInBackground = nets.count { it.sentInBackground },
                kind = kindOf(key),
                groups = (categoryWeights.entries.sortedByDescending { it.value }.map { it.key } +
                    systemByKey[key].orEmpty().filter { !it.vendor }.map { it.category }).mapNotNull { rules.groupOf(it)?.id }.distinct(),
                systemCounts = systemByKey[key].orEmpty(),
                frequent = attentionRules?.let { r -> peak(eventsByKey[key].orEmpty().filter { !it.denied }, r) },
                frequentDenied = attentionRules?.let { r -> peak(eventsByKey[key].orEmpty().filter { it.denied }, r) }
            )
        }.sortedWith(compareByDescending<AppSummary> { it.sentInBackground }
            .thenByDescending { it.background }
            .thenByDescending { it.systemBackground }
            .thenByDescending { it.accesses + it.calls }.thenByDescending { it.traffic?.total ?: 0L })
        store.userActions(from, to).mapTo(timeline) { LogEntry(AppKey(it.uid, it.pkg), TimelineItem.ActionItem(it)) }
        timeline.sortByDescending { it.item.t }
        return Report(from, groups, apps, attention(apps), timeline, categoryGroups(), rules.groups.associate { it.id to it.icon },
            attentionRules?.routineKinds.orEmpty())
    }

    fun detail(key: AppKey, from: Long, to: Long): AppDetail {
        val data = classify(store.accesses(from, to, key.uid), store.calls(from, to, key.uid))
        val contacts = store.connections(from, to, key.uid).filter { ownerOf(it.uid, null) == key.pkg && !isLookupTraffic(it) }.map(::persistent)
        val accessRows = data.accesses.filter { it.first.pkg == key.pkg }
        val callRows = data.calls.filter { ownerOf(it.first.uid, it.first.process) == key.pkg }
        val names = DomainNames(store.lookups(from - DomainNames.LOOKBACK_MS, to))
        val nets = conversations(contacts, labeler(names, key.uid))
        val starts = store.processStarts(from, to, key.uid)
            .filter { key.pkg == null || it.appKey == key }
        // Behaviors and network activity are told apart: a connection is not something the app read.
        val actions = key.pkg?.let { store.userActions(from, to, it) }.orEmpty().map { TimelineItem.ActionItem(it) }
        val items = (items(accessRows, callRows, emptyList(), starts, data) + actions).sortedByDescending { it.t }
        val routine = kindOf(key).id in attentionRules?.routineKinds.orEmpty()
        // Network activity is one list: where it connected, each with the lookups of its domain, then
        // what it only looked up.
        val domains = domains(store.lookups(from, to, key.uid).filter { ownerOf(it.uid, null) == key.pkg }).associateBy { it.domain }
        val destinations = nets.groupBy { it.label }.map { (label, list) ->
            val latest = list.maxBy { it.connection.end }
            Destination(label, list.size, latest.connection.end, sumOrNull(list.map { it.connection.sent }),
                sumOrNull(list.map { it.connection.received }), list.map { it.connection.port }.distinct().sorted(), latest,
                lookups = domains[label]?.lookups ?: 0)
        }.sortedByDescending { it.last }
        val reached = destinations.map { it.label }.toSet()
        val traffic = if (key.uid == excludedUid) null else traffic(from, to)[key.uid]
        return AppDetail(items, categoryGroups(), rules.groups.associate { it.id to it.icon },
            Behavior.summaries(items, from, to, routine), routine, Behavior.chart(items, from, to, routine), destinations, traffic,
            domains.values.filter { it.domain !in reached }.sortedWith(compareByDescending<DomainSummary> { it.lookups }.thenByDescending { it.last }),
            systemCounts = key.pkg?.let { pkg -> systemCounts(store.opCounts(from, to, key.uid).filter { it.pkg == pkg }) }.orEmpty(),
            sharedUid = if (key.uid < 0) emptyList() else packagesOfUid(key.uid).sorted().takeIf { it.size > 1 }.orEmpty())
    }

    /** The system's counts per kind of data, most first; operations outside every kind are left out. */
    private fun systemCounts(rows: List<JiumiStore.OpCount>): List<SystemCount> =
        rows.mapNotNull { r -> (rules.categoryOf(r.op) ?: r.op.takeIf(SystemCount::isVendor))?.let { it to r } }
            .groupBy({ it.first }, { it.second })
            .map { (category, list) -> list.filter { it.op !in rules.activeOnlyOps && it.op !in rules.callConfirmedOps }.let { used ->
                SystemCount(category, used.sumOf { it.fg }, used.sumOf { it.bg }, list.sumOf { rejected(it) })
            } }
            .filter { it.fg + it.bg + it.rejected > 0 }
            // Kinds of data first, then the maker's own operations.
            .sortedWith(compareBy<SystemCount> { it.vendor }.thenByDescending { it.fg + it.bg + it.rejected })

    /** The system's refusals of one way in among several count for nothing when the app holds another. */
    private fun rejected(r: JiumiStore.OpCount): Long {
        // A check refused nothing the app asked for; what a maker's own operation guards is not published.
        if (r.op in rules.checkedOps || r.op in rules.activeOnlyOps || SystemCount.isVendor(r.op)) return 0
        val other = rules.opPermission(r.op)
        if (other != null && r.uid >= FIRST_APP_UID && holdsAnyPermission(r.pkg, other.anyOf) == true) return 0
        return r.fgRejected + r.bgRejected
    }

    private fun domains(lookups: List<JiumiStore.Lookup>): List<DomainSummary> =
        lookups.groupBy { it.domain }.map { (domain, list) ->
            DomainSummary(domain, list.sumOf { it.count }, list.maxOf { it.end }, list.any { it.rcode == 0 && it.answers.isNotEmpty() })
        }.sortedWith(compareByDescending<DomainSummary> { it.lookups }.thenByDescending { it.last })

    private fun sumOrNull(values: List<Long?>): Long? = values.filterNotNull().takeIf { it.isNotEmpty() }?.sum()

    /** One app's records as behavior segments, with its network conversations [nets]. */
    private fun items(
        accessRows: List<Pair<JiumiStore.Access, String>>,
        callRows: List<Pair<JiumiStore.Call, String>>,
        nets: List<TimelineItem.NetItem>,
        starts: List<JiumiStore.ProcessStart>,
        data: Classified
    ): List<TimelineItem> {
        val accesses = accessRows.map { TimelineItem.AccessItem(it.first, it.second, data.absorbed[it.first].orEmpty()) }
        val calls = callRows.map { TimelineItem.CallItem(it.first, it.second, refused = it.first in data.refusedCalls) }
        return Segments.merge(accesses + calls + starts.map { TimelineItem.ProcessItem(it) } + nets)
    }

    private fun categoryGroups(): Map<String, String> =
        rules.groups.flatMap { g -> g.categories.map { it to g.id } }.toMap()

    /**
     * One app's connections as conversations, each named by [label] and marked when the app sent at
     * least the threshold while in the background. Only what was measured is told: nothing is
     * guessed about what was sent or why.
     */
    private fun conversations(contacts: List<JiumiStore.Connection>, label: (String) -> String): List<TimelineItem.NetItem> {
        val threshold = attentionRules?.sentInBackgroundBytes ?: AttentionRules.DEFAULT_SENT_IN_BACKGROUND_BYTES
        return NetCorrelation.conversations(contacts).map { c ->
            TimelineItem.NetItem(c, label(c.addr), sentInBackground = (c.sentUnattended ?: 0L) >= threshold)
        }
    }

    /** A destination by the domain [uid] looked up for it when one was recorded, else by its address. */
    private fun labeler(names: DomainNames, uid: Int): (String) -> String =
        if (names.isEmpty) { addr -> addr } else { addr -> names.domainOf(uid, addr) ?: addr }

    /**
     * The busiest 60 minutes of any category in [events] that reached its threshold, the one furthest
     * past it; null when none did. The window slides over the records, so a burst across the hour
     * counts in full. A record counts at its start.
     */
    private fun peak(events: List<Event>, rules: AttentionRules): Peak? =
        events.groupBy { it.category }.mapNotNull { (category, list) ->
            busiestHour(category, list.map { it.t to it.count })?.takeIf { it.count >= rules.thresholdFor(category) }
        }.maxByOrNull { it.count.toDouble() / rules.thresholdFor(it.category) }

    private fun attention(apps: List<AppSummary>): List<Attention> = attentionRules?.items.orEmpty().mapNotNull { item ->
        val measure: (AppSummary) -> Int = when (item.id) {
            // The monitor's count, or the system's when it counted more (it also covers time the monitor
            // was not running); never both added up, as they overlap.
            AttentionRules.BACKGROUND -> { a -> maxOf(a.background.toLong(), a.systemBackground).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
            AttentionRules.SENT_IN_BACKGROUND -> { a -> a.sentInBackground }
            AttentionRules.FREQUENT -> { a -> a.frequent?.count ?: 0 }
            AttentionRules.FREQUENT_DENIED -> { a -> a.frequentDenied?.count ?: 0 }
            else -> return@mapNotNull null
        }
        Attention(item.id, apps.filter { it.kind.id in item.kinds && measure(it) > 0 }.sortedByDescending(measure).map { it.key })
    }

    private fun classify(accessRows: List<JiumiStore.Access>, callRows: List<JiumiStore.Call>): Classified {
        val serving = attentionRules?.onBehalfAttributions.orEmpty()
        // A location provider locating whichever app asked: that app's own access is noted as well.
        val locating = attentionRules?.locationProviderCategories.orEmpty()
        // Work done for another app is that app's access, recorded for it as well: left out here.
        val recordedAccesses = accessRows.filter { it.uid != excludedUid }.map(::effective).filter { countsAsUse(it, rules) }
            .map { if (persistentOf(it.pkg)) it.copy(state = PERSISTENT) else it }
            .mapNotNull { row -> rules.categoryOf(row.op)?.let { row to it } }
            .filterNot { (a, c) -> a.proxied || a.attr in serving || c in locating && locationProviderOf(a.pkg) }
        // The privacy record shows only what went through.
        val byUid = recordedAccesses.filterNot { it.first.denied }.groupBy { it.first.uid }
        val refused = HashSet<JiumiStore.Call>()
        // A call the monitor could not tie to any caller tells nothing about anyone: left out.
        val traced = callRows.filter { it.uid != excludedUid && it.uid >= 0 }
            .map { if (persistentOf(ownerOf(it.uid, it.process))) it.copy(state = PERSISTENT) else it }.mapNotNull { call ->
            val category = rules.categoryOfCall(call.iface, call.method) ?: return@mapNotNull null
            if (category in locating && locationProviderOf(ownerOf(call.uid, call.process))) return@mapNotNull null
            // An installed app without the permission a call needs got nothing from it, or only its own.
            when (callVerdict(call)) {
                AccessRules.Without.OWN, AccessRules.Without.REDACTED -> return@mapNotNull null
                AccessRules.Without.REFUSED -> { refused += call; return@mapNotNull call to category }
                null -> Unit
            }
            // Where the system records a kind of data whenever it hands it over, a call its record does
            // not show around then may have returned nothing (a clipboard read from the background, a
            // media query for the app's own files, a last location not yet known): what it got cannot
            // be told, so it is left out.
            val recordedAs = rules.recordedAs(category)
            if (recordedAs.isNotEmpty() && byUid[call.uid].orEmpty().none { (a, c) ->
                    c in recordedAs && a.t - MATCH_MS <= call.end && call.t <= maxOf(a.end, a.t) + MATCH_MS
                }) return@mapNotNull null
            call to category
        }
        // A note the system also makes when it only checks an app's rights (usage access, asked while
        // the app reads its own traffic) says what the app may read, not what it read: it counts only
        // beside a traced call of the same kind of data.
        val callsByUid = traced.filterNot { it.first in refused }.groupBy { it.first.uid }
        val accesses = recordedAccesses.filter { (a, c) ->
            a.op !in rules.callConfirmedOps || callsByUid[a.uid].orEmpty().any { (call, cc) ->
                cc == c && a.t - MATCH_MS <= call.end && call.t <= maxOf(a.end, a.t) + MATCH_MS
            }
        }
        val calls = traced
        // A refused access gave nothing, so it cannot stand for a call made at the same time.
        val overlap = Overlap.absorb(accesses.filterNot { it.first.denied }, calls.filterNot { it.first in refused }, rules::recordedAs)
        return Classified(calls.filter { it.first in refused } + overlap.calls, accesses, overlap.absorbed, refused)
    }

    /** What a call without the permission it needs amounts to; null when it needs none or the app holds one. */
    private fun callVerdict(call: JiumiStore.Call): AccessRules.Without? {
        if (call.uid < FIRST_APP_UID) return null
        val rule = rules.callPermission(call.iface, call.method) ?: return null
        val pkg = ownerOf(call.uid, call.process) ?: return null
        return if (holdsAnyPermission(pkg, rule.anyOf) == false) rule.without else null
    }

    /**
     * AppOps answers "default" when the decision rests on a permission; an app without that
     * permission was refused (an app without usage access asking for usage stats). Only installed
     * apps are judged: system daemons hold their rights by uid, not by a granted permission.
     */
    /** A persistent system app is part of the system wherever its process stands; nothing it sends is sent while not in use. */
    private fun persistent(c: JiumiStore.Connection): JiumiStore.Connection =
        if (persistentOf(ownerOf(c.uid, null))) c.copy(state = PERSISTENT, sentUnattended = c.sentUnattended?.let { 0L }) else c

    private fun effective(row: JiumiStore.Access): JiumiStore.Access = effective(row, rules, holdsPermission, holdsAnyPermission)

    companion object {
        /**
         * What an access amounted to for an installed app. The operation may be one way in among
         * several, and an app holding another was not refused. AppOps answers "default" when the
         * decision rests on the operation's own permission; an app without it was refused.
         */
        fun effective(
            row: JiumiStore.Access,
            rules: AccessRules,
            holdsPermission: (op: String, pkg: String?) -> Boolean?,
            holdsAnyPermission: (pkg: String?, permissions: List<String>) -> Boolean?
        ): JiumiStore.Access {
            if (row.uid < FIRST_APP_UID) return row
            val other = rules.opPermission(row.op)
            if (other != null && (row.denied || row.result == MODE_DEFAULT) && holdsAnyPermission(row.pkg, other.anyOf) == true)
                return row.copy(result = MODE_ALLOWED)
            return if (row.result == MODE_DEFAULT && holdsPermission(row.op, row.pkg) == false)
                row.copy(result = MODE_IGNORED, withoutPermission = true) else row
        }

        private const val MATCH_MS = 60_000L
        private const val MODE_ALLOWED = 0
        private const val MODE_IGNORED = 1
        private const val MODE_DEFAULT = 3
        private const val FIRST_APP_UID = 10_000
        private const val HOUR_MS = 3_600_000L

        /**
         * The app was not in front of the user: one kept up by a foreground service (a notification is
         * all that shows), or a background, cached or gone process. A persistent system process (the status bar,
         * the phone service) is part of the system on screen, not an app working behind the user. Only
         * "top" and "fg" (visible, or serving what is on screen, such as the keyboard) count as in use.
         */
        fun isBackground(state: String?) = state in UNATTENDED_STATES

        /**
         * Not in use: in the background, or with the screen off. A persistent system process is the
         * system itself, so neither applies to it.
         */
        fun isUnattended(state: String?, screen: Boolean?): Boolean =
            state != PERSISTENT && (isBackground(state) || screen == false)

        const val PERSISTENT = "pers"

        /**
         * Whether an access tells what the app did. The system notes some operations when it only checks
         * an app's rights while serving something else: a refusal of those refused nothing the app asked
         * for, and for an operation used only while active, a plain note is such a check.
         */
        fun countsAsUse(row: JiumiStore.Access, rules: AccessRules): Boolean {
            if (row.denied && row.op in rules.checkedOps) return false
            if (row.op in rules.activeOnlyOps) return row.source == "active" || row.source == "system" && row.end > row.t
            return true
        }

        /**
         * DNS on port 53, to the network's resolver or to the domain recorder's own address: a
         * lookup, not a place the app went. The system resolver sends it on the app's behalf; the
         * lookup itself is told by domain recording when that is on.
         */
        fun isLookupTraffic(c: JiumiStore.Connection): Boolean =
            c.port == 53 || c.addr.startsWith(DOMAIN_RECORDER_PREFIX) || isClosingTail(c)

        /**
         * The kernel lists a socket its app already closed under uid 0 while it finishes; the monitor
         * now skips these, but records from before show them as root's, with nothing sent or received.
         */
        private fun isClosingTail(c: JiumiStore.Connection): Boolean =
            c.uid == 0 && (c.sent ?: 0L) == 0L && (c.received ?: 0L) == 0L

        /** The private addresses of the domain recorder's VPN (see DomainVpnService). */
        private const val DOMAIN_RECORDER_PREFIX = "10.215.173."

        /** The 60 minutes with the most of [category], from (time, count) records; null for none. */
        fun busiestHour(category: String, records: List<Pair<Long, Int>>): Peak? {
            val sorted = records.sortedBy { it.first }
            var start = 0
            var sum = 0
            var best: Peak? = null
            for (i in sorted.indices) {
                sum += sorted[i].second
                while (sorted[i].first - sorted[start].first >= HOUR_MS) sum -= sorted[start++].second
                if (sum > (best?.count ?: 0)) best = Peak(category, sum, sorted[start].first, sorted[i].first)
            }
            return best
        }
        private val UNATTENDED_STATES = setOf("fgsvc", "bg", "cch", "gone")
    }
}
