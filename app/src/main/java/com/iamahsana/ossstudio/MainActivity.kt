package com.iamahsana.ossstudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private enum class AppPage(val label: String, val symbol: String) {
    HOME("Home", "⌂"),
    STUDIO("Studio", "●"),
    EDITOR("Editor", "▣"),
    MIXER("Mixer", "≋"),
    EXTENSIONS("Extensions", "＋"),
    SETTINGS("Settings", "⚙")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OssApp() }
    }
}

@Composable
private fun OssApp() {
    var selectedPage by rememberSaveable { mutableStateOf(AppPage.HOME.name) }
    val page = AppPage.valueOf(selectedPage)

    MaterialTheme(
        colorScheme = lightColorScheme()
    ) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    AppPage.entries.forEach { item ->
                        NavigationBarItem(
                            selected = page == item,
                            onClick = { selectedPage = item.name },
                            icon = { Text(item.symbol) },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        ) { padding ->
            Surface(
                modifier = Modifier.fillMaxSize().padding(padding),
                color = MaterialTheme.colorScheme.background
            ) {
                PageContent(page)
            }
        }
    }
}

@Composable
private fun PageContent(page: AppPage) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("OSS Studio", style = MaterialTheme.typography.headlineMedium)
                Text(page.label, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(pageTitle(page), style = MaterialTheme.typography.titleLarge)
                    Text(pageDescription(page), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (page == AppPage.HOME) {
            item {
                Text("Quick access", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickCard("New project", Modifier.weight(1f))
                    QuickCard("Open project", Modifier.weight(1f))
                }
            }
            item {
                Text("Recent projects", style = MaterialTheme.typography.titleMedium)
                Text("Your projects will appear here.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun QuickCard(title: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(title, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.labelLarge)
    }
}

private fun pageTitle(page: AppPage): String = when (page) {
    AppPage.HOME -> "Your streaming workspace"
    AppPage.STUDIO -> "LIVE control center"
    AppPage.EDITOR -> "Scene Editor"
    AppPage.MIXER -> "Audio Mixer"
    AppPage.EXTENSIONS -> "Extension Center"
    AppPage.SETTINGS -> "App settings"
}

private fun pageDescription(page: AppPage): String = when (page) {
    AppPage.HOME -> "Create a project or continue where you left off."
    AppPage.STUDIO -> "Streaming destinations and session controls will live here."
    AppPage.EDITOR -> "Build the scene that will be sent directly to LIVE."
    AppPage.MIXER -> "Manage audio sources, levels, and LIVE/monitor routing."
    AppPage.EXTENSIONS -> "Discover and manage optional OSS modules."
    AppPage.SETTINGS -> "Manage your account, appearance, storage, and preferences."
}
