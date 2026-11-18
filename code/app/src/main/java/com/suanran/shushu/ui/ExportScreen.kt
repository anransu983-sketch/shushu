// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suanran.shushu.MonitorController
import com.suanran.shushu.MonitorController.Range
import com.suanran.shushu.R
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Choose a range, then where to save: the record of that range as a zip of CSV files (see LogExport). */
@Composable
fun ExportScreen(controller: MonitorController, labels: AppLabels, onBack: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A range by name; ALL stands for every record kept.
    var choice by rememberSaveable { mutableStateOf(state.range.name) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var outcome by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val choices = Range.entries.map { it.name } + ALL
    val rangeText = if (choice == ALL) stringResource(R.string.export_range_all) else rangeLabel(Range.valueOf(choice), state.session)
    val readme = stringResource(R.string.export_readme, rangeText,
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        outcome = null
        scope.launch {
            outcome = controller.export(uri, if (choice == ALL) null else Range.valueOf(choice), readme, labels::label) { prefix, id ->
                resourceText(context, prefix, id)
            }
            busy = false
        }
    }

    SubScreen(stringResource(R.string.settings_export), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).selectableGroup(), contentPadding = PaddingValues(16.dp)) {
            item {
                Card {
                    choices.forEachIndexed { index, value ->
                        if (index > 0) InsetDivider(start = 16.dp)
                        SelectionRow(
                            if (value == ALL) stringResource(R.string.export_range_all) else rangeLabel(Range.valueOf(value), state.session),
                            selected = choice == value
                        ) { choice = value; outcome = null }
                    }
                }
            }
            item {
                PrimaryButton(stringResource(R.string.action_export), busy = busy) {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    save.launch("jiumi-$stamp-${choice.lowercase()}.zip")
                }
            }
            outcome?.let { ok ->
                item {
                    Text(stringResource(if (ok) R.string.export_done else R.string.export_failed),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
        }
    }
}

private const val ALL = "ALL"
