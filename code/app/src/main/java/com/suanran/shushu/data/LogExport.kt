// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.data

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes the record of a time window as a zip of CSV files, split by purpose:
 *
 * - timeline.csv: one readable row per record, as the timeline shows it, for reading and analysis;
 * - the files under raw/: the stored tables as recorded, for auditing what the report was built from;
 * - sessions.csv: the monitoring sessions with their covered time;
 * - README.txt and, when given, diagnostics files.
 *
 * Names (apps, kinds of data, actions) are passed in, in the language of the app; codes such as
 * app states stay as recorded and README.txt explains them. CSV files start with a byte order mark
 * so spreadsheets read them as UTF-8.
 */
class LogExport(
    private val store: JiumiStore,
    private val label: (AppKey) -> String,
    /** The resource text for [prefix] + [id] ("cat_", "group_", "act_"), or null. */
    private val name: (prefix: String, id: String) -> String?
) {
    /** A session with what [Coverage] made of it. */
    class SessionRow(val session: JiumiStore.Session, val coverage: Coverage.Result)

    fun write(
        out: OutputStream,
        from: Long,
        to: Long,
        report: Report,
        sessions: List<SessionRow>,
        readme: String,
        diagnostics: Map<String, ByteArray> = emptyMap()
    ) {
        ZipOutputStream(out).use { zip ->
            fun text(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            fun csv(path: String, header: List<String>, rows: Sequence<List<Any?>>) {
                zip.putNextEntry(ZipEntry(path))
                val writer = zip.bufferedWriter(Charsets.UTF_8)
                writer.write(BOM)
                writer.write(Csv.row(header))
                rows.forEach { writer.write(Csv.row(it)) }
                writer.flush()
                zip.closeEntry()
            }

            text("README.txt", readme)
            csv("timeline.csv", TIMELINE_HEADER, timelineRows(report, label, name))
            csv("sessions.csv", listOf("id", "start", "end", "covered_minutes", "interruptions", "lost_records", "last_seen"),
                sessions.asSequence().map { s ->
                    listOf(s.session.id, Csv.time(s.session.start), Csv.time(s.session.end), s.coverage.coveredMs / 60_000,
                        s.coverage.interruptions, s.coverage.lostEvents, Csv.time(s.coverage.lastSeen))
                })
            csv("raw/accesses.csv", listOf("time", "time_ms", "end_time", "op", "uid", "package", "attribution", "count", "state",
                "screen_on", "result", "proxied", "source"),
                store.accesses(from, to).asSequence().map { a ->
                    listOf(Csv.time(a.t), a.t, Csv.time(a.end), a.op, a.uid, a.pkg, a.attr, a.count, a.state, a.screen, a.result,
                        a.proxied, a.source)
                })
            csv("raw/calls.csv", listOf("time", "time_ms", "end_time", "uid", "pid", "process", "thread", "interface", "method",
                "count", "state", "screen_on"),
                store.calls(from, to).asSequence().map { c ->
                    listOf(Csv.time(c.t), c.t, Csv.time(c.end), c.uid, c.pid, c.process, c.thread, c.iface, c.method, c.count,
                        c.state, c.screen)
                })
            csv("raw/connections.csv", listOf("time", "time_ms", "end_time", "uid", "protocol", "address", "port", "sockets",
                "state", "screen_on", "sent_bytes", "received_bytes", "sent_in_background_bytes"),
                store.connections(from, to).asSequence().map { c ->
                    listOf(Csv.time(c.t), c.t, Csv.time(c.end), c.uid, c.proto, c.addr, c.port, c.count, c.state, c.screen,
                        c.sent, c.received, c.sentUnattended)
                })
            csv("raw/dns.csv", listOf("time", "time_ms", "end_time", "uid", "domain", "query_type", "answers", "result", "lookups"),
                store.lookups(from, to).asSequence().map { l ->
                    listOf(Csv.time(l.t), l.t, Csv.time(l.end), l.uid, l.domain, l.type, l.answers.joinToString(";").ifEmpty { null },
                        l.rcode, l.count)
                })
            csv("raw/process_starts.csv", listOf("time", "time_ms", "pid", "uid", "process", "reason", "component"),
                store.processStarts(from, to).asSequence().map { p ->
                    listOf(Csv.time(p.t), p.t, p.pid, p.uid, p.process, p.reason, p.component)
                })
            csv("raw/monitor_events.csv", listOf("time", "time_ms", "state", "reason"),
                store.monitorEvents(from, to).asSequence().map { e -> listOf(Csv.time(e.t), e.t, e.state, e.reason) })
            diagnostics.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry("diagnostics/$path"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    companion object {
        private const val BOM = "\uFEFF"
        private const val PROVIDER_PREFIX = "ContentProvider@"

        val TIMELINE_HEADER = listOf(
            "time", "end_time", "time_ms", "app", "package", "uid", "app_kind", "routine_system_work", "record",
            "kind_of_data", "data", "action", "api", "count", "app_state", "screen_on", "refused",
            "destination", "address", "protocol", "port", "sent_bytes", "received_bytes", "sent_in_background_bytes",
            "same_action_calls"
        )

        /** Every record of the timeline, segments opened into their records, newest first. */
        fun timelineRows(report: Report, label: (AppKey) -> String, name: (String, String) -> String?): Sequence<List<Any?>> {
            val kinds = report.apps.associate { it.key to it.kind }
            return report.timeline.asSequence().flatMap { entry ->
                val members = (entry.item as? TimelineItem.Segment)?.members ?: listOf(entry.item)
                members.asSequence().map { item -> timelineRow(entry.key, kinds[entry.key], item, report, label, name) }
            }
        }

        private fun timelineRow(
            key: AppKey, kind: AppKind?, item: TimelineItem, report: Report, label: (AppKey) -> String, name: (String, String) -> String?
        ): List<Any?> {
            val routine = kind?.id in report.routineKinds
            val app = listOf(label(key), key.pkg, key.uid, kind?.id, routine)
            fun data(category: String) = listOf(report.groupOf[category]?.let { name("group_", it) ?: it }, name("cat_", category) ?: category)
            return when (item) {
                is TimelineItem.AccessItem -> with(item.access) {
                    listOf(Csv.time(t), Csv.time(end), t) + app + listOf("access") + data(item.category) + listOf(
                        name("act_", op.removePrefix("android:")), op, count, state, screen, denied,
                        null, null, null, null, null, null, null,
                        item.calls.groupBy { "${it.iface}.${it.method}" }.entries.joinToString(";") { (m, cs) -> "$m×${cs.sumOf { it.count }}" }
                            .ifEmpty { null }
                    )
                }
                is TimelineItem.CallItem -> with(item.call) {
                    listOf(Csv.time(t), Csv.time(end), t) + app + listOf("call") + data(item.category) + listOf(
                        if (iface.startsWith(PROVIDER_PREFIX)) name("op_", method)
                        else name("act_", "${iface.lowercase()}_${method.lowercase()}") ?: name("act_", method.lowercase()),
                        "$iface.$method", count, state, screen, item.refused,
                        null, null, null, null, null, null, null, null
                    )
                }
                is TimelineItem.NetItem -> with(item.connection) {
                    listOf(Csv.time(t), Csv.time(end), t) + app + listOf("network", null, null, null, null, count, state, screen, false,
                        item.label.ifEmpty { null }, addr.ifEmpty { null }, proto, port, sent, received, sentUnattended, null)
                }
                is TimelineItem.ProcessItem -> with(item.start) {
                    listOf(Csv.time(t), Csv.time(t), t) + app + listOf("process_start", null, null, reason, component, 1, null, null,
                        false, null, null, null, null, null, null, null, null)
                }
                is TimelineItem.ActionItem -> with(item.action) {
                    listOf(Csv.time(t), Csv.time(t), t) + app + listOf("user_action", null, null, name("manage_done_", action), action, 1,
                        null, null, false, null, null, null, null, null, null, null, null)
                }
                is TimelineItem.Segment -> emptyList()
            }
        }
    }
}
