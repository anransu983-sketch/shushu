// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.data

import android.content.Context
import org.json.JSONObject

/**
 * What the overview raises for attention, from assets/rules/attention.json: each item names the
 * kinds of app it applies to; frequent access is judged, over any 60 minutes, against
 * [thresholdFor] its category ([byCategory], else [frequentPerHour]). Records of
 * [routineKinds] callers are routine system work: listed, but never raised. Accesses noted under one
 * of [onBehalfAttributions] were made for another app (a location provider fetching a fix for its
 * client, whose own access is recorded too), so they are left out.
 * Likewise the [locationProviderCategories] records of an app the system has bound as a location
 * provider: it scans Wi-Fi, cells and Bluetooth to work out fixes for the apps that ask.
 */
class AttentionRules(
    val items: List<Item>, val frequentPerHour: Int, val routineKinds: Set<String> = emptySet(),
    val onBehalfAttributions: Set<String> = emptySet(), val byCategory: Map<String, Int> = emptyMap(),
    /** Bytes an app must send in one conversation while in the background to be raised for it. */
    val sentInBackgroundBytes: Long = DEFAULT_SENT_IN_BACKGROUND_BYTES,
    val locationProviderCategories: Set<String> = emptySet()
) {
    data class Item(val id: String, val kinds: Set<String>)

    /** How many of [category] within 60 minutes count as frequent. */
    fun thresholdFor(category: String): Int = byCategory[category] ?: frequentPerHour

    companion object {
        const val BACKGROUND = "background"
        const val SENT_IN_BACKGROUND = "sent_in_background"
        const val DEFAULT_SENT_IN_BACKGROUND_BYTES = 4096L
        const val FREQUENT = "frequent"
        const val FREQUENT_DENIED = "frequent_denied"

        fun load(context: Context): AttentionRules =
            parse(context.assets.open("rules/attention.json").bufferedReader().use { it.readText() })

        fun parse(text: String): AttentionRules {
            val root = JSONObject(text)
            val items = root.getJSONArray("items")
            return AttentionRules(
                (0 until items.length()).map { i ->
                    val item = items.getJSONObject(i)
                    val kinds = item.getJSONArray("kinds")
                    Item(item.getString("id"), (0 until kinds.length()).map { kinds.getString(it) }.toSet())
                },
                root.getInt("frequentPerHour"),
                strings(root, "routineKinds"),
                strings(root, "onBehalfAttributions"),
                root.optJSONObject("frequentPerHourByCategory")?.let { o ->
                    o.keys().asSequence().associateWith { o.getInt(it) }
                }.orEmpty(),
                root.optLong("sentInBackgroundBytes", DEFAULT_SENT_IN_BACKGROUND_BYTES),
                strings(root, "locationProviderCategories")
            )
        }

        private fun strings(root: JSONObject, name: String): Set<String> =
            root.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
    }
}
