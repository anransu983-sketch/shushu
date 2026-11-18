// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suanran.shushu.MonitorController
import com.suanran.shushu.R
import com.suanran.shushu.data.AppKey
import com.suanran.shushu.data.AttentionRules
import com.suanran.shushu.ui.theme.Ink
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

/** The apps behind one attention item, most affected first. */
@Composable
fun AttentionScreen(controller: MonitorController, labels: AppLabels, id: String, onBack: () -> Unit, onOpenApp: (AppKey) -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val ledger = state.ledger
    val byKey = ledger?.apps.orEmpty().associateBy { it.key }
    val apps = ledger?.attention?.firstOrNull { it.id == id }?.apps.orEmpty().mapNotNull { byKey[it] }

    val rule = attentionRule(id)
    SubScreen(attentionName(id), onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp)
        ) {
            rule?.let { rule ->
                item {
                    Text(rule, style = MaterialTheme.typography.bodySmall, color = Ink.secondary,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp))
                }
            }
            if (apps.isEmpty()) {
                item { EmptyCard(stringResource(R.string.attention_none)) }
            } else item {
                Card {
                    apps.forEachIndexed { index, app ->
                        if (index > 0) InsetDivider(start = TIME_COLUMN_WIDTH + 60.dp)
                        // Counts only; when and how is on the app's own page.
                        AppRow(app, labels, onClick = { onOpenApp(app.key) })
                    }
                }
            }
        }
    }
}
