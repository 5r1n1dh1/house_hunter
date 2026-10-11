package com.househunter.app.ui.sites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.househunter.app.data.scrape.SiteConfig
import com.househunter.app.ui.components.Pill
import com.househunter.app.CrashLog
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SitesScreen(
    onBack: () -> Unit,
    onAddSite: () -> Unit,
    viewModel: SitesViewModel = hiltViewModel(),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sites") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddSite,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add site") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "Enabled sites are fetched one after another with rate limiting. " +
                        "If a site returns nothing, open it in your browser to confirm the listings " +
                        "are there, then tune its selectors.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(state.sites, key = { it.id }) { site ->
                SiteRow(
                    site = site,
                    onToggle = { enabled -> viewModel.toggle(site.id, enabled) },
                    onDelete = if (site.isCustom) {
                        { viewModel.delete(site.id) }
                    } else {
                        null
                    },
                    onOpen = {
                        // Always-available fallback: blocked sources still open in the browser.
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(site.browserUrl),
                                ),
                            )
                        }
                    },
                )
            }

            item {
                DiagnosticsCard()
            }
        }
    }
}

/**
 * Shows the last crash recorded by [CrashLog] and lets the user share it.
 *
 * A crash on a specific handset (different SoC, Android build, Play Services
 * state) cannot be reproduced from CI, so the stack trace has to be
 * collectible from the device itself. Nothing leaves the phone unless the user
 * taps Share.
 */
@Composable
private fun DiagnosticsCard() {
    val context = LocalContext.current
    var text by remember { mutableStateOf(CrashLog.read(context)) }
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Diagnostics", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (text == null) {
                    "No crashes recorded on this device."
                } else {
                    "Last crash recorded on this device. Share it if the app closes unexpectedly."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (text != null) {
                Spacer(Modifier.height(10.dp))
                if (expanded) {
                    Text(
                        text = text!!.takeLast(4000),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Hide log" else "Show log")
                    }
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent.createChooser(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_SEND,
                                    ).apply {
                                        type = "text/plain"
                                        putExtra(
                                            android.content.Intent.EXTRA_TEXT,
                                            "HouseHunter crash log:\n\n${text.orEmpty()}",
                                        )
                                    },
                                    "Share crash log",
                                ),
                            )
                        }
                    }) { Text("Share") }
                    TextButton(onClick = {
                        CrashLog.clear(context)
                        text = CrashLog.read(context)
                    }) { Text("Clear") }
                }
            }
        }
    }
}

@Composable
private fun SiteRow(
    site: SiteConfig,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)?,
    onOpen: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = site.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = site.baseUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                }
                Switch(checked = site.enabled, onCheckedChange = onToggle)
            }

            if (site.note.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = site.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(
                    text = when (site.mode) {
                        "json" -> "embedded JSON"
                        "sitemap" -> "sitemap"
                        else -> "cards"
                    },
                )
                if (site.needsBrowser) {
                    Spacer(Modifier.padding(horizontal = 6.dp))
                    Pill(
                        text = "browser engine",
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = onOpen) {
                    Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.height(16.dp))
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text("Open in browser")
                }
            }
        }
    }
}