package com.github.soundpod.ui.screens.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.github.innertube.Innertube
import com.github.innertube.requests.searchPage
import com.github.innertube.utils.from
import com.github.soundpod.LocalPlayerPadding
import com.github.soundpod.utils.DiscoveryPreference
import com.github.soundpod.utils.OnboardingArtist
import com.github.soundpod.utils.completeOnboarding
import com.github.soundpod.utils.skipOnboarding
import kotlinx.coroutines.launch

private val GENRE_OPTIONS = listOf(
    "Thai Rock", "Rock", "Pop", "Metal", "Indie", "Hip-Hop", "R&B", "Jazz", "Electronic", "Classical"
)
private val ERA_OPTIONS = listOf("80s", "90s", "2000s", "2010s", "Current")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    onSkipped: () -> Unit = onFinished
) {
    val context = LocalContext.current
    val playerPadding = LocalPlayerPadding.current
    var step by remember { mutableStateOf(0) }
    var selectedGenres by remember { mutableStateOf(setOf<String>()) }
    var selectedArtists by remember { mutableStateOf(listOf<OnboardingArtist>()) }
    var selectedEras by remember { mutableStateOf(setOf<String>()) }
    var discovery by remember { mutableStateOf(DiscoveryPreference.BALANCED) }

    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Innertube.ArtistItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun doSearch() {
        if (query.isBlank()) return
        isSearching = true
        searchError = null
        scope.launch {
            try {
                val res = Innertube.searchPage(
                    query = query,
                    params = Innertube.SearchFilter.Artist.value,
                    fromMusicShelfRendererContent = Innertube.ArtistItem.Companion::from
                )?.getOrNull()
                searchResults = res?.items ?: emptyList()
                if (searchResults.isEmpty()) searchError = "No results"
            } catch (e: Exception) {
                searchError = e.message ?: "Search failed"
                searchResults = emptyList()
            } finally {
                isSearching = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = playerPadding)
            .imePadding()
    ) {
        // Compact header
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when (step) {
                    0 -> "Step 1/5 — Genres"
                    1 -> "Step 2/5 — Artists"
                    2 -> "Step 3/5 — Era"
                    3 -> "Step 4/5 — Discovery"
                    else -> "Step 5/5 — Finish"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            TextButton(
                onClick = {
                    context.skipOnboarding()
                    onSkipped()
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { Text("Skip", style = MaterialTheme.typography.labelMedium) }
        }

        // Scrollable body
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (step) {
                0 -> {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 4.dp)
                    ) {
                        Text("Choose 3-5 genres", style = MaterialTheme.typography.titleSmall)
                        Text("Selected ${selectedGenres.size}/5", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            GENRE_OPTIONS.forEach { g ->
                                val selected = selectedGenres.contains(g)
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        selectedGenres = if (selected) selectedGenres - g else if (selectedGenres.size < 5) selectedGenres + g else selectedGenres
                                    },
                                    label = { Text(g, style = MaterialTheme.typography.labelMedium) },
                                    colors = FilterChipDefaults.filterChipColors()
                                )
                            }
                        }
                        if (selectedGenres.size < 3) {
                            Spacer(Modifier.height(6.dp))
                            Text("Select at least 3", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
                1 -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text("Choose 3-5 artists (search)", style = MaterialTheme.typography.titleSmall)
                        Text("Selected ${selectedArtists.size}/5: ${selectedArtists.joinToString { it.name }}", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = { Text("Artist name", style = MaterialTheme.typography.bodySmall) },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { doSearch() }, enabled = query.isNotBlank() && !isSearching) { Text("Search", style = MaterialTheme.typography.labelMedium) }
                        }
                        Spacer(Modifier.height(8.dp))
                        if (isSearching) {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
                        }
                        searchError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(searchResults, key = { it.key }) { artist ->
                                val already = selectedArtists.any { (it.browseId != null && it.browseId == artist.key) || it.name == artist.info?.name }
                                val browseId = artist.key
                                Row(
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                                        if (already) {
                                            selectedArtists = selectedArtists.filterNot { it.browseId == browseId || it.name == artist.info?.name }
                                        } else {
                                            if (selectedArtists.size < 5) {
                                                val name = artist.info?.name ?: return@clickable
                                                selectedArtists = selectedArtists + OnboardingArtist(name = name, browseId = browseId.ifBlank { null })
                                            }
                                        }
                                    }.padding(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AsyncImage(
                                        model = artist.thumbnail?.url,
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp).clip(CircleShape)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(artist.info?.name ?: "Unknown", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                        Text(artist.subscribersCountText ?: browseId, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (already) Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    else Text("+", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (selectedArtists.size < 3) {
                            Spacer(Modifier.height(4.dp))
                            Text("Select at least 3 artists", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                2 -> {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                        Text("Choose eras (optional, multi-select)", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ERA_OPTIONS.forEach { e ->
                                val sel = selectedEras.contains(e)
                                FilterChip(selected = sel, onClick = { selectedEras = if (sel) selectedEras - e else selectedEras + e }, label = { Text(e, style = MaterialTheme.typography.labelMedium) })
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
                3 -> {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                        Text("Discovery preference", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        listOf(
                            DiscoveryPreference.FAMILIAR to "Familiar — more of what you like",
                            DiscoveryPreference.BALANCED to "Balanced — mix of familiar and new",
                            DiscoveryPreference.EXPLORATORY to "Exploratory — more discovery"
                        ).forEach { (pref, label) ->
                            Row(modifier = Modifier.fillMaxWidth().clickable { discovery = pref }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = discovery == pref, onClick = { discovery = pref })
                                Spacer(Modifier.width(8.dp))
                                Text(label, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
                4 -> {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                        Text("Ready!", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Genres: ${selectedGenres.joinToString()}", style = MaterialTheme.typography.bodySmall)
                        Text("Artists: ${selectedArtists.joinToString { it.name + (if (it.browseId != null) " (${it.browseId})" else "") }}", style = MaterialTheme.typography.bodySmall)
                        Text("Eras: ${if (selectedEras.isEmpty()) "any" else selectedEras.joinToString()}", style = MaterialTheme.typography.bodySmall)
                        Text("Discovery: ${discovery.name}", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Text("You can change these later in settings. Tap Finish to continue.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }

        // Persistent bottom actions above mini player
        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { if (step > 0) step-- }, enabled = step > 0) { Text("Back", style = MaterialTheme.typography.labelMedium) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (step < 4) {
                        Button(
                            onClick = { step++ },
                            enabled = when (step) {
                                0 -> selectedGenres.size in 3..5
                                1 -> selectedArtists.size in 3..5
                                else -> true
                            }
                        ) { Text("Next", style = MaterialTheme.typography.labelMedium) }
                    } else {
                        Button(onClick = {
                            context.completeOnboarding(selectedGenres.toList(), selectedArtists, selectedEras.toList(), discovery)
                            onFinished()
                        }) { Text("Finish", style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
            Text("Selection ${step + 1}/5", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
