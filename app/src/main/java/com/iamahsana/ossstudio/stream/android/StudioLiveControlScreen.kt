package com.iamahsana.ossstudio.stream.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iamahsana.ossstudio.scene.model.SceneProject
import com.iamahsana.ossstudio.scene.model.SceneSourceType
import com.iamahsana.ossstudio.stream.LiveSessionController
import com.iamahsana.ossstudio.stream.rtmp.RtmpDestinationProfile
import com.iamahsana.ossstudio.stream.rtmp.RtmpDestinationProfileStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun StudioLiveControlScreen(context: Context, project: SceneProject?) {
    val controller = remember(context) { LiveSessionControllerRegistry.getOrCreate(context) }
    var sessionState by remember { mutableStateOf(LiveSessionControllerRegistry.state) }
    var detail by remember { mutableStateOf(LiveSessionControllerRegistry.detail) }
    var profiles by remember { mutableStateOf(loadProfiles(context).first) }
    var profileError by remember { mutableStateOf(loadProfiles(context).second) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    LaunchedEffect(controller) {
        while (isActive) {
            sessionState = LiveSessionControllerRegistry.state
            detail = LiveSessionControllerRegistry.detail
            delay(200L)
        }
    }

    val activeScene = project?.scenes?.firstOrNull { it.id == project.activeSceneId }
        ?: project?.scenes?.firstOrNull()
    val sourceById = project?.sources?.associateBy { it.id }.orEmpty()
    val requiresProjection = activeScene?.elements?.any { element ->
        element.visible && sourceById[element.sourceId]?.let {
            it.enabled && it.type == SceneSourceType.SHARE_SCREEN
        } == true
    } == true
    val projectionReady = !requiresProjection || ProjectionServiceRegistry.service?.getActiveProjection() != null
    val enabledProfiles = profiles.filter { it.enabled }
    val busy = sessionState in setOf(
        LiveSessionController.State.PREPARING,
        LiveSessionController.State.STARTING,
        LiveSessionController.State.STOPPING
    )
    val canStart = project != null && activeScene != null && enabledProfiles.isNotEmpty() &&
        profileError == null && projectionReady && !busy &&
        sessionState in setOf(
            LiveSessionController.State.IDLE,
            LiveSessionController.State.STOPPED,
            LiveSessionController.State.FAILED
        )

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("LIVE control", style = MaterialTheme.typography.titleLarge)
            Text("Status: ${sessionState.name}", style = MaterialTheme.typography.titleMedium)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                project?.let { "Project: ${it.name}" } ?: "Pilih project dari Home sebelum memulai LIVE.",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Tujuan aktif: ${enabledProfiles.size}", style = MaterialTheme.typography.bodySmall)
            profileError?.let { Text("Vault RTMP: $it", color = MaterialTheme.colorScheme.error) }
            if (requiresProjection && !projectionReady) {
                Text(
                    "Scene memakai Share Screen. Aktifkan izin Share Screen pada panel di atas sebelum LIVE.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            actionMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = canStart, onClick = {
                    val endpoint = enabledProfiles.firstOrNull()?.endpoint()
                    if (endpoint == null) {
                        actionMessage = "Tambahkan dan aktifkan minimal satu tujuan RTMP."
                    } else {
                        actionMessage = null
                        runCatching { controller.start(endpoint) }
                            .onSuccess { started ->
                                if (!started) actionMessage = LiveSessionControllerRegistry.detail ?: "LIVE gagal dimulai."
                            }
                            .onFailure { actionMessage = it.message ?: "LIVE gagal dimulai." }
                    }
                }) { Text("Mulai LIVE") }
                OutlinedButton(
                    enabled = !busy && sessionState !in setOf(
                        LiveSessionController.State.IDLE,
                        LiveSessionController.State.STOPPED
                    ),
                    onClick = {
                        actionMessage = "Menghentikan LIVE dan membersihkan resource…"
                        controller.stopAsync { result ->
                            mainHandler.post {
                                actionMessage = if (result.completed) "LIVE berhenti dan resource dibersihkan."
                                else "Cleanup belum lengkap. Coba hentikan lagi sebelum memulai sesi baru."
                            }
                        }
                    }
                ) { Text("Hentikan") }
                OutlinedButton(onClick = {
                    val result = loadProfiles(context)
                    profiles = result.first
                    profileError = result.second
                    actionMessage = null
                }) { Text("Muat ulang") }
            }
            Text(
                "Semua tujuan aktif akan dicoba saat LIVE. Status setiap tujuan RTMP dipantau oleh runtime.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun loadProfiles(context: Context): Pair<List<RtmpDestinationProfile>, String?> =
    runCatching { RtmpDestinationProfileStore.load(context) }
        .fold({ it to null }, { emptyList<RtmpDestinationProfile>() to (it.message ?: "Gagal membaca vault RTMP") })
