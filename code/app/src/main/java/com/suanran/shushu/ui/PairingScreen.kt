// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suanran.shushu.MonitorController
import com.suanran.shushu.R
import com.suanran.shushu.adb.AirModePairingManager.PairingState
import com.suanran.shushu.adb.AirModePairingManager.PairingTrustState
import com.suanran.shushu.adb.messageRes
import com.suanran.shushu.ui.icons.JiumiIcons
import com.suanran.shushu.ui.theme.Ink
import com.suanran.shushu.ui.theme.StatusWarning

/**
 * Wireless debugging, on a page of its own for first setup and Settings alike: where pairing
 * stands, the three steps it takes, and one action at the foot of the page.
 */
@Composable
fun PairingScreen(
    controller: MonitorController,
    onBack: () -> Unit,
    onPair: () -> Unit,
    onOpenWirelessDebugging: () -> Unit
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val inFlow = state.pairingState in setOf(PairingState.DISCOVERING, PairingState.AWAITING_CODE, PairingState.PAIRING)
    val paired = state.trust == PairingTrustState.PAIRED
    var confirmUnpair by rememberSaveable { mutableStateOf(false) }

    SubScreen(
        stringResource(R.string.row_pairing), onBack,
        bottomBar = {
            when {
                inFlow -> BottomAction(stringResource(R.string.action_cancel), filled = false, onClick = controller::cancelPairing)
                paired -> BottomAction(stringResource(R.string.action_done), onClick = onBack)
                else -> BottomAction(stringResource(
                    if (state.trust == PairingTrustState.PAIRING_REQUIRED) R.string.pairing_required else R.string.action_start_pairing
                ), onClick = onPair)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)
        ) {
            item {
                Text(stringResource(R.string.pairing_intro), style = MaterialTheme.typography.bodySmall, color = Ink.secondary,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp))
            }
            item {
                Card {
                    ListRow(
                        title = stringResource(R.string.row_pairing),
                        subtitle = stringResource(when {
                            inFlow -> R.string.pairing_in_progress
                            state.pairingState == PairingState.CONNECTING -> R.string.pairing_connecting
                            paired -> R.string.pairing_paired
                            state.trust == PairingTrustState.PAIRING_REQUIRED -> R.string.pairing_required
                            else -> R.string.pairing_unpaired
                        }),
                        leading = { IconTile(JiumiIcons.Wifi) }
                    ) {
                        when {
                            inFlow -> PillButton(stringResource(R.string.action_open_wireless_debugging), onClick = onOpenWirelessDebugging)
                            paired -> PillButton(stringResource(R.string.action_unpair), enabled = state.busy == null) { confirmUnpair = true }
                        }
                    }
                    when (state.pairingState) {
                        PairingState.DISCOVERING, PairingState.AWAITING_CODE -> {
                            InsetDivider()
                            HintRow(stringResource(R.string.pairing_hint), progress = state.pairingState == PairingState.DISCOVERING)
                        }
                        PairingState.PAIRING, PairingState.CONNECTING -> {
                            InsetDivider()
                            HintRow(stringResource(if (state.pairingState == PairingState.PAIRING) R.string.pairing_in_progress
                                else R.string.pairing_connecting), progress = true)
                        }
                        else -> Unit
                    }
                }
                state.pairingFailure?.let {
                    Text(stringResource(it.messageRes()), style = MaterialTheme.typography.bodySmall, color = StatusWarning,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp))
                }
            }
            if (!paired) {
                item { GroupTitle(stringResource(R.string.pairing_steps), Modifier.padding(top = 24.dp)) }
                item {
                    Card {
                        StepRow(1, stringResource(R.string.pairing_step1_title), stringResource(R.string.pairing_step1_desc))
                        InsetDivider(start = 58.dp)
                        StepRow(2, stringResource(R.string.pairing_step2_title), stringResource(R.string.pairing_step2_desc))
                        InsetDivider(start = 58.dp)
                        StepRow(3, stringResource(R.string.pairing_step3_title), stringResource(R.string.pairing_step3_desc))
                    }
                }
            }
        }
    }

    if (confirmUnpair) {
        // Unpairing deletes the trusted key; the next start needs a new pairing code.
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.unpair_title)) },
            text = { Text(stringResource(R.string.unpair_text)) },
            confirmButton = {
                TextButton(onClick = { confirmUnpair = false; controller.unpair() }) {
                    Text(stringResource(R.string.action_unpair), color = MaterialTheme.colorScheme.onSurface)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpair = false }) {
                    Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

/** What to do now while pairing runs, with a spinner while the phone is working. */
@Composable
private fun HintRow(text: String, progress: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (progress) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Ink.secondary)
        else Icon(JiumiIcons.Notifications, null, tint = Ink.primary, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Ink.secondary)
    }
}

@Composable
private fun StepRow(index: Int, title: String, desc: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(30.dp).background(Ink.fill, CircleShape), contentAlignment = Alignment.Center) {
            Text(index.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Ink.secondary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Ink.primary)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = Ink.secondary, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
