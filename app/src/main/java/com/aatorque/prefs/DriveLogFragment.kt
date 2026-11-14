package com.aatorque.prefs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.aatorque.stats.CarSession
import com.aatorque.stats.R
import com.aatorque.stats.RegenRecord
import com.aatorque.ui.AppTheme
import java.text.DateFormat
import java.util.Date

/** Settings → DPF assistant → Drive log: the regeneration history kept by the car session. */
class DriveLogFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            AppTheme(isSystemInDarkTheme()) { DriveLog() }
        }
    }
}

/** Averages of regeneration intervals: the latest five against the ones before. */
data class RegenSummary(
    val total: Int,
    val completed: Int,
    val interrupted: Int,
    val paused: Int,
    val recentIntervalKm: Double?,
    val earlierIntervalKm: Double?
) {
    companion object {
        fun of(records: List<RegenRecord>): RegenSummary {
            val intervals = records.mapNotNull { it.intervalKm?.takeIf { km -> km > 0 } }
            val recent = intervals.takeLast(5)
            val earlier = intervals.dropLast(5)
            return RegenSummary(
                total = records.size,
                completed = records.count { it.outcome == RegenRecord.Outcome.COMPLETED },
                interrupted = records.count {
                    it.outcome == RegenRecord.Outcome.INTERRUPTED ||
                        it.outcome == RegenRecord.Outcome.OPEN_AT_SHUTDOWN
                },
                paused = records.count { it.outcome == RegenRecord.Outcome.PAUSED },
                recentIntervalKm = recent.takeIf { it.isNotEmpty() }?.average(),
                earlierIntervalKm = earlier.takeIf { it.isNotEmpty() }?.average()
            )
        }
    }
}

@Composable
private fun DriveLog() {
    val context = LocalContext.current
    val records = remember { CarSession.dpfHistory(context).load().reversed() }
    val summary = remember(records) { RegenSummary.of(records) }
    val dtc = remember { CarSession.dtcStore(context).load().lastOrNull() }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    LazyColumn(modifier = Modifier.padding(16.dp)) {
        item {
            Text(stringResource(R.string.dtc_title), style = MaterialTheme.typography.titleLarge)
            if (dtc == null) {
                Text(stringResource(R.string.dtc_never))
            } else {
                Text(stringResource(R.string.dtc_last_read, dateFormat.format(Date(dtc.wall))))
                if (dtc.codes.isEmpty()) Text(stringResource(R.string.dtc_none))
                dtc.codes.forEach { c ->
                    val pending = if (c.pending) {
                        " (" + stringResource(
                            R.string.dtc_pending
                        ) + ")"
                    } else {
                        ""
                    }
                    Text(
                        "${c.code}$pending  ${c.description}",
                        color = if (c.pending) Color(0xFFF2A900) else Color(0xFFE3242B)
                    )
                }
            }
            Divider(modifier = Modifier.padding(vertical = 8.dp))
        }
        item {
            Text(
                stringResource(R.string.drive_log_dpf),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                stringResource(
                    R.string.drive_log_dpf_counts,
                    summary.total,
                    summary.completed,
                    summary.paused,
                    summary.interrupted
                )
            )
            summary.recentIntervalKm?.let { recent ->
                val earlier = summary.earlierIntervalKm
                Text(
                    if (earlier != null) {
                        stringResource(R.string.drive_log_interval_trend, recent, earlier)
                    } else {
                        stringResource(R.string.drive_log_interval, recent)
                    }
                )
            }
            if (records.isEmpty()) {
                Text(
                    stringResource(R.string.drive_log_empty),
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            Divider(modifier = Modifier.padding(vertical = 8.dp))
        }
        items(records) { r -> RegenRow(r, dateFormat) }
    }
}

@Composable
private fun RegenRow(r: RegenRecord, dateFormat: DateFormat) {
    val (label, colour) = when (r.outcome) {
        RegenRecord.Outcome.COMPLETED -> R.string.regen_completed to Color(0xFF4CAF50)
        RegenRecord.Outcome.PAUSED -> R.string.regen_paused to Color(0xFFF2A900)
        RegenRecord.Outcome.INTERRUPTED -> R.string.regen_interrupted to Color(0xFFE3242B)
        RegenRecord.Outcome.OPEN_AT_SHUTDOWN -> R.string.regen_open to Color(0xFFE3242B)
        RegenRecord.Outcome.IN_PROGRESS -> R.string.regen_in_progress to Color(0xFF3FA9F5)
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(dateFormat.format(Date(r.startWall)), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(label), color = colour)
        val minutes = r.durationMs / 60_000
        val seconds = (r.durationMs / 1000) % 60
        Text(
            stringResource(R.string.regen_row, minutes, seconds, r.distanceKm) +
                (
                    r.intervalKm?.let {
                        " · " + stringResource(R.string.regen_row_interval, it)
                    } ?: ""
                    ) +
                if (r.sootStart != null && r.sootEnd != null) {
                    " · " + stringResource(R.string.regen_row_soot, r.sootStart, r.sootEnd!!)
                } else {
                    ""
                }
        )
        Divider(modifier = Modifier.padding(top = 6.dp))
    }
}
