package cs.trade.scheduler.dashboard.web.presentation.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import cs.trade.scheduler.dashboard.web.presentation.theme.JobStateColors
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.dto.StatsOverviewResponse

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun StatsContent(component: StatsComponent) {
    val state by component.model.subscribeAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stats") },
                actions = {
                    OutlinedButton(onClick = component::onBackClicked) { Text("Back") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = component::onRefreshClicked) { Text("Refresh") }
                    Spacer(Modifier.width(8.dp))
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading && state.overview == null -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
                state.error != null -> Text(
                    text = "Error: ${state.error}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
                state.overview != null -> OverviewGrid(state.overview!!)
            }
        }
    }
}

@Composable
private fun OverviewGrid(overview: StatsOverviewResponse) {
    val cards = listOf(
        JobState.SCHEDULED to overview.scheduled,
        JobState.AWAITING_DEPS to overview.awaitingDeps,
        JobState.ENQUEUED to overview.enqueued,
        JobState.PROCESSING to overview.processing,
        JobState.AWAITING_RETRY to overview.awaitingRetry,
        JobState.SUCCEEDED to overview.succeeded,
        JobState.FAILED to overview.failed,
        JobState.CANCELLED to overview.cancelled,
    )
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            cards.take(4).forEach { (st, count) -> StatCard(st, count) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            cards.drop(4).forEach { (st, count) -> StatCard(st, count) }
        }
    }
}

@Composable
private fun StatCard(state: JobState, count: Long) {
    Surface(
        color = JobStateColors.bg(state),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.size(width = 180.dp, height = 100.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = state.name,
                style = MaterialTheme.typography.labelMedium,
                color = JobStateColors.fg(state),
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.headlineMedium,
                color = JobStateColors.fg(state),
            )
        }
    }
}

@Suppress("unused")
private val _placeholder: Color = Color.Transparent
@Suppress("unused")
private val _placeholder2 = Modifier.background(Color.Transparent)
