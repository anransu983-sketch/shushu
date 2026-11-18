// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.ui

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.suanran.shushu.R

/**
 * Names of rules ids come from string resources named after them (cat_<id>, group_<id>, op_<name>),
 * so the rules file can grow without code changes; an id without a resource shows as itself.
 */
@SuppressLint("DiscouragedApi")
@Composable
private fun named(prefix: String, id: String): String {
    val context = LocalContext.current
    val res = context.resources.getIdentifier("$prefix$id", "string", context.packageName)
    return if (res == 0) id else stringResource(res)
}

/** The resource [prefix]+[id] in [context]'s language, or null when there is none; for text built outside composition. */
@SuppressLint("DiscouragedApi")
fun resourceText(context: android.content.Context, prefix: String, id: String): String? {
    val res = context.resources.getIdentifier("$prefix$id", "string", context.packageName)
    return if (res == 0) null else context.getString(res)
}

/** The resource [prefix]+[id], or null when there is none. */
@SuppressLint("DiscouragedApi")
@Composable
private fun namedOrNull(prefix: String, id: String): String? {
    val context = LocalContext.current
    val res = context.resources.getIdentifier("$prefix$id", "string", context.packageName)
    return if (res == 0) null else stringResource(res)
}

@Composable
fun categoryName(id: String): String = named("cat_", id)

/** A content-provider operation on the database a provider host serves (store_<host with dots as underscores>). */
@Composable
fun providerAction(host: String, operation: String): String? {
    val op = namedOrNull("op_", operation) ?: return null
    val store = namedOrNull("store_", host.replace('.', '_')) ?: return op
    return stringResource(R.string.provider_action, op, store)
}

/** What the category's data is, how apps get it and what it can reveal. */
@Composable
fun categoryDescription(id: String): String? = namedOrNull("catd_", id)

/**
 * The plain action of one record: the permission operation or the traced method in words (act_<op>,
 * act_<interface>_<method> or act_<method>, in lower case), or a database operation with its database
 * ("Delete data (contacts and call log database)").
 */
@Composable
fun actionName(item: com.suanran.shushu.data.TimelineItem): String? = when (item) {
    is com.suanran.shushu.data.TimelineItem.AccessItem -> namedOrNull("act_", item.access.op.removePrefix("android:"))
    is com.suanran.shushu.data.TimelineItem.CallItem ->
        if (item.call.iface.startsWith(PROVIDER_PREFIX)) providerAction(item.call.iface.removePrefix(PROVIDER_PREFIX), item.call.method)
        // A method several interfaces share ("startScan") is named for its interface when it can be.
        else namedOrNull("act_", "${item.call.iface.lowercase()}_${item.call.method.lowercase()}")
            ?: namedOrNull("act_", item.call.method.lowercase())
    is com.suanran.shushu.data.TimelineItem.ActionItem -> namedOrNull("manage_done_", item.action.action)
    else -> null
}

@Composable
fun groupName(id: String): String = named("group_", id)

@Composable
fun providerOperationName(operation: String): String = named("op_", operation)

/** Why the system started a process, as the event log names it ("next-top-activity", "content provider"). */
@Composable
fun startReasonName(reason: String): String {
    val id = reason.lowercase().replace('-', '_').replace(' ', '_')
    // A reason without a name of its own is shown as the system wrote it.
    return named("reason_", id).takeIf { it != id } ?: reason
}

/** Traffic of connections too short for the socket table has no address. */
@Composable
fun destinationLabel(label: String): String = label.ifEmpty { stringResource(R.string.unknown_destination) }

/** Process state at the time of access, in the vocabulary AppOps uses. */
@Composable
fun stateName(state: String?): String? = when (state) {
    "top" -> stringResource(R.string.state_in_use)
    "pers" -> stringResource(R.string.state_persistent)
    "fgsvc" -> stringResource(R.string.state_foreground_service)
    "fg" -> stringResource(R.string.state_visible)
    "bg", "cch", "gone" -> stringResource(R.string.state_background)
    else -> null
}
