// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Report categories, watched operations and traced methods, from assets/rules/access_categories.json. */
class AccessRules(
    val categories: List<Category>,
    val groups: List<Group> = emptyList(),
    /** IContentProvider transaction codes and their operation names. */
    val providerOperations: Map<Int, String> = emptyMap(),
    /** What a traced call needs to return the data its category names; see [callPermission]. */
    val callPermissions: List<CallPermission> = emptyList(),
    /** Other ways into the data an operation guards; see [opPermission]. */
    val opPermissions: List<OpPermission> = emptyList(),
    /** Operations the system also notes when it only checks an app's rights; their refusals say nothing. */
    val checkedOps: Set<String> = emptySet(),
    /** Operations used only while the system marks them active; their plain notes are checks. */
    val activeOnlyOps: Set<String> = emptySet(),
    /** Operations whose notes count as use only beside a traced call of the same category; see [callConfirmedOps]. */
    val callConfirmedOps: Set<String> = emptySet()
) {
    /**
     * [calls] return the data of their category only to an app holding one of [anyOf]. Without, the
     * call is [Without.REFUSED] (the system gave nothing), [Without.REDACTED] (it gave the data with
     * what identifies the user taken out, so nothing to report) or [Without.OWN] (it
     * gave only the app's own information, so nothing to report).
     */
    data class CallPermission(val calls: List<String>, val anyOf: List<String>, val without: Without)

    enum class Without { REFUSED, REDACTED, OWN }

    /** The first rule naming this call, exactly or as Interface.*; null when the call needs nothing particular. */
    fun callPermission(iface: String, method: String): CallPermission? =
        callPermissions.firstOrNull { "$iface.$method" in it.calls || "$iface.*" in it.calls }

    /**
     * The system notes [ops] as one way in among several: an app holding any of [anyOf] gets the data
     * whatever the operation itself answered, so a refusal noted for it refused nothing.
     */
    data class OpPermission(val ops: List<String>, val anyOf: List<String>)

    fun opPermission(op: String): OpPermission? = opPermissions.firstOrNull { op in it.ops }

    data class Category(val id: String, val ops: List<String>, val calls: List<String>, val recordedAs: List<String>)
    /** [icon] names the group's symbol in the app. */
    data class Group(val id: String, val categories: List<String>, val icon: String = "")

    fun groupOf(category: String): Group? = groups.firstOrNull { category in it.categories }

    private val categoryByOp = categories.flatMap { c -> c.ops.map { it to c.id } }.toMap()
    private val categoryByCall = categories.flatMap { c -> c.calls.map { it to c.id } }.toMap()

    val ops: List<String> get() = categories.flatMap { it.ops }
    val calls: List<String> get() = categories.flatMap { it.calls }

    fun categoryOf(op: String): String? = categoryByOp[op]

    fun categoryOfCall(iface: String, method: String): String? =
        categoryByCall["$iface.$method"] ?: categoryByCall["$iface.*"]

    /**
     * Categories whose AppOps records count as the system having recorded a call of [category];
     * empty when the system has no record that could cover it.
     */
    fun recordedAs(category: String): Set<String> {
        val own = categories.firstOrNull { it.id == category } ?: return emptySet()
        val candidates = own.recordedAs.ifEmpty { listOf(own.id) }
        return candidates.filter { id -> categories.any { it.id == id && it.ops.isNotEmpty() } }.toSet()
    }

    fun monitorConfig(callTracing: Boolean, spoolLimitMb: Int): String = JSONObject()
        .put("ops", JSONArray(ops))
        .put("calls", JSONObject().put("enabled", callTracing).put("methods", JSONArray(calls))
            .put("providerOperations", JSONObject(providerOperations.mapKeys { it.key.toString() })))
        .put("network", JSONObject().put("enabled", true).put("intervalMs", CONNECTION_POLL_MS).put("packets", true))
        .put("spool", JSONObject().put("limitMb", spoolLimitMb))
        .toString()

    companion object {
        /** How often the monitor reads the socket tables; shorter connections may be missed. */
        /** Reading the four socket tables takes a few milliseconds; every 2 s catches far more short connections than 5 s. */
        private const val CONNECTION_POLL_MS = 2_000L

        fun load(context: Context): AccessRules =
            parse(context.assets.open("rules/access_categories.json").bufferedReader().use { it.readText() })

        fun parse(text: String): AccessRules {
            val root = JSONObject(text)
            val list = root.getJSONArray("categories")
            fun JSONObject.strings(key: String) = optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val groups = root.optJSONArray("groups") ?: JSONArray()
            return AccessRules(
                (0 until list.length()).map { i ->
                    val item = list.getJSONObject(i)
                    Category(item.getString("id"), item.strings("ops"), item.strings("calls"), item.strings("recordedAs"))
                },
                (0 until groups.length()).map { i ->
                    val item = groups.getJSONObject(i)
                    Group(item.getString("id"), item.strings("categories"), item.optString("icon"))
                },
                root.optJSONObject("providerOperations")?.let { o ->
                    o.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to o.getString(k) } }.toMap()
                }.orEmpty(),
                root.optJSONArray("callPermissions")?.let { a ->
                    (0 until a.length()).map { i ->
                        val item = a.getJSONObject(i)
                        CallPermission(item.strings("calls"), item.strings("anyOf"),
                            when (item.optString("without")) { "own" -> Without.OWN; "redacted" -> Without.REDACTED; else -> Without.REFUSED })
                    }
                }.orEmpty(),
                root.optJSONArray("opPermissions")?.let { a ->
                    (0 until a.length()).map { i -> a.getJSONObject(i).let { OpPermission(it.strings("ops"), it.strings("anyOf")) } }
                }.orEmpty(),
                root.strings("checkedOps").toSet(),
                root.strings("activeOnlyOps").toSet(),
                root.strings("callConfirmedOps").toSet()
            )
        }
    }
}
