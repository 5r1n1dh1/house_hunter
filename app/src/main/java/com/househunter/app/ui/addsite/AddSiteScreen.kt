package com.househunter.app.ui.addsite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSiteScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: AddSiteViewModel = hiltViewModel(),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add a site") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Describe any rental site with CSS selectors. \"Test fetch\" hits the real site with " +
                    "your saved state/district/locality — nothing is simulated.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Field("Site name", state.name) { v -> viewModel.update { it.copy(name = v) } }
            Field("Base URL", state.baseUrl, "https://example.com") { v ->
                viewModel.update { it.copy(baseUrl = v) }
            }
            Field(
                label = "Search URL template",
                value = state.searchUrlTemplate,
                placeholder = "https://example.com/rent?city={DISTRICT}&loc={LOCALITY}&page={PAGE}",
            ) { v -> viewModel.update { it.copy(searchUrlTemplate = v) } }

            Text("CSS selectors", style = MaterialTheme.typography.titleSmall)
            Field("Result card", state.listContainer, "div.listing-card") { v ->
                viewModel.update { it.copy(listContainer = v) }
            }
            Field("Title", state.titleSelector, "h2.title") { v ->
                viewModel.update { it.copy(titleSelector = v) }
            }
            Field("Price (optional)", state.priceSelector, "span.price") { v ->
                viewModel.update { it.copy(priceSelector = v) }
            }
            Field("Detail link (optional)", state.detailUrlSelector, "a.details-link") { v ->
                viewModel.update { it.copy(detailUrlSelector = v) }
            }
            Field("Image (optional)", state.imageSelector, "img.src") { v ->
                viewModel.update { it.copy(imageSelector = v) }
            }
            Text(
                text = "Leave price empty if the site hides it behind JavaScript — the app also reads " +
                    "schema.org JSON-LD price data, which MagicBricks-style sites provide.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Field("Max pages", state.maxPages) { v -> viewModel.update { it.copy(maxPages = v) } }
            Field("Seconds between requests", state.rateLimitSeconds) { v ->
                viewModel.update { it.copy(rateLimitSeconds = v) }
            }

            if (state.testing) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator()
                    Text("Fetching…")
                }
            }

            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            state.preview.forEach { line ->
                Text("• $line", style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::testFetch,
                    enabled = state.valid && !state.testing,
                    modifier = Modifier.weight(1f),
                ) { Text("Test fetch") }
                Button(
                    onClick = { viewModel.save(onSaved) },
                    enabled = state.valid,
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String = "",
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = if (placeholder.isBlank()) null else ({ Text(placeholder) }),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}