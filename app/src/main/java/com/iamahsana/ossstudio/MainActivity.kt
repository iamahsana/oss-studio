package com.iamahsana.ossstudio

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import com.iamahsana.ossstudio.logging.OssLogger
import com.iamahsana.ossstudio.logging.OssLogLevel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.core.content.ContextCompat
import com.iamahsana.ossstudio.scene.source.screen.ShareScreenSessionService
import com.iamahsana.ossstudio.scene.model.SceneProject
import com.iamahsana.ossstudio.scene.persistence.ProjectHomeScreen
import com.iamahsana.ossstudio.scene.persistence.SceneProjectRepository
import com.iamahsana.ossstudio.scene.persistence.SceneProjectStoreProvider
import com.iamahsana.ossstudio.scene.editor.SceneEditorScreen
import com.iamahsana.ossstudio.audio.AudioMixerScreen
import com.iamahsana.ossstudio.stream.rtmp.RtmpDestinationProfilesScreen
import com.iamahsana.ossstudio.stream.android.ProjectionServiceRegistry
import com.iamahsana.ossstudio.stream.android.ActiveSceneProjectRegistry
import com.iamahsana.ossstudio.stream.android.LiveSessionControllerRegistry
import com.iamahsana.ossstudio.stream.LiveSessionController
import com.iamahsana.ossstudio.stream.android.StudioLiveControlScreen
import com.iamahsana.ossstudio.extensions.OssExtensionCenterScreen
import com.iamahsana.ossstudio.settings.AppSettings
import com.iamahsana.ossstudio.settings.AppSettingsRepository
import com.iamahsana.ossstudio.settings.OssThemeMode
import com.iamahsana.ossstudio.settings.ossColorScheme
import com.iamahsana.ossstudio.settings.SettingsScreen

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
        OssLogger.initialize(applicationContext)
        OssLogger.log(
            level = OssLogLevel.INFO,
            subsystem = "OSS/App",
            component = "MainActivity",
            event = "activity_created",
            message = "OSS Studio launch activity created"
        )
        setContent { OssApp() }
    }
}

@Composable
private fun OssApp() {
    var selectedPage by rememberSaveable { mutableStateOf(AppPage.HOME.name) }
    var activeProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var activeProject by remember { mutableStateOf<SceneProject?>(null) }
    val page = AppPage.valueOf(selectedPage)
    val context = LocalContext.current
    val settingsRepository = remember(context) { AppSettingsRepository(context.applicationContext) }
    val appSettings by settingsRepository.settings.collectAsState(initial = AppSettings())
    val darkTheme = when (appSettings.themeMode) {
        OssThemeMode.SYSTEM -> isSystemInDarkTheme()
        OssThemeMode.LIGHT -> false
        OssThemeMode.DARK -> true
    }
    SideEffect { ActiveSceneProjectRegistry.publish(activeProject) }
    val projectLoader = remember(context) {
        SceneProjectRepository(SceneProjectStoreProvider.database(context))
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(projectLoader) {
        onDispose { projectLoader.close() }
    }
    androidx.compose.runtime.LaunchedEffect(activeProjectId) {
        val projectId = activeProjectId
        if (projectId != null && activeProject?.id != projectId) {
            projectLoader.load(projectId) { result ->
                mainHandler.post {
                    result.fold(
                        onSuccess = { activeProject = it },
                        onFailure = { activeProject = null }
                    )
                }
            }
        }
    }

    MaterialTheme(colorScheme = ossColorScheme(darkTheme, appSettings.accent)) {
        Scaffold(
            bottomBar = {
                if (page != AppPage.EDITOR) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            AppPage.entries.forEach { item ->
                                val selected = page == item
                                Surface(
                                    modifier = Modifier
                                        .width(64.dp)
                                        .then(if (item == AppPage.SETTINGS) Modifier.testTag("nav-settings") else Modifier)
                                        .clickable {
                                            OssLogger.log(
                                                level = OssLogLevel.INFO,
                                                subsystem = "OSS/Navigation",
                                                component = "BottomNavigation",
                                                event = "page_changed",
                                                message = "Navigation changed from $selectedPage to ${item.name}"
                                            )
                                            selectedPage = item.name
                                        },
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surface
                                ) {
                                    Column(
                                        modifier = Modifier.padding(vertical = 7.dp, horizontal = 4.dp),
                                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            item.symbol,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            item.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Surface(
                modifier = Modifier.fillMaxSize().padding(padding),
                color = MaterialTheme.colorScheme.background
            ) {
                PageContent(
                    page = page,
                    context = context,
                    activeProject = activeProject,
                    activeProjectId = activeProjectId,
                    canDeleteActiveProject = {
                        LiveSessionControllerRegistry.state in setOf(
                            LiveSessionController.State.IDLE,
                            LiveSessionController.State.STOPPED
                        )
                    },
                    settingsRepository = settingsRepository,
                    onOpenProject = { project ->
                        activeProjectId = project.id
                        activeProject = project
                        selectedPage = AppPage.EDITOR.name
                    },
                    onProjectChange = { activeProject = it },
                    onExitEditor = { selectedPage = AppPage.HOME.name },
                    onProjectDeleted = { deletedId ->
                        if (activeProjectId == deletedId || activeProject?.id == deletedId) {
                            activeProject = null
                            activeProjectId = null
                            selectedPage = AppPage.HOME.name
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun PageContent(page: AppPage, context: Context, activeProject: SceneProject?, activeProjectId: String?, canDeleteActiveProject: () -> Boolean, settingsRepository: AppSettingsRepository, onOpenProject: (SceneProject) -> Unit, onProjectChange: (SceneProject) -> Unit, onExitEditor: () -> Unit, onProjectDeleted: (String) -> Unit) {
    if (page == AppPage.EDITOR) {
        Column(modifier = Modifier.fillMaxSize().testTag("editor-workspace")) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                TextButton(onClick = onExitEditor) { Text("← Home") }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text("Scene Editor", style = MaterialTheme.typography.titleMedium)
                    Text(
                        activeProject?.name ?: "Belum ada project aktif",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (activeProject == null) {
                Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text("Pilih atau buat project dari Home terlebih dahulu.")
                        Text(pageDescription(page), style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    SceneEditorScreen(context, activeProject, onProjectChange)
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp).testTag("page-content"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    "OSS  /  STUDIO",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
                Text(pageTitle(page), style = MaterialTheme.typography.headlineSmall)
                Text(
                    pageDescription(page),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (page == AppPage.STUDIO) {
            item { RtmpDestinationProfilesScreen(context) }
            item { ShareScreenPermissionPanel(context) }
            item { StudioLiveControlScreen(context, activeProject, onProjectChange) }
        }
        if (page == AppPage.MIXER) {
            item { AudioPermissionPanel(context) }
            item { ShareScreenPermissionPanel(context) }
            item { AudioMixerScreen(context) }
        }
        if (page == AppPage.HOME) {
            item {
                ProjectHomeScreen(
                    context = context,
                    activeProjectId = activeProjectId,
                    canDeleteActiveProject = canDeleteActiveProject,
                    onOpenProject = onOpenProject,
                    onProjectDeleted = onProjectDeleted
                )
            }
        }
        if (page == AppPage.EXTENSIONS) {
            item { OssExtensionCenterScreen(context) }
        }
        if (page == AppPage.SETTINGS) {
            item { SettingsScreen(context, settingsRepository) }
        }
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


@Composable
private fun AudioPermissionPanel(context: Context) {
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    var statusMessage by remember {
        mutableStateOf(
            if (permissionGranted) {
                "Izin mikrofon aktif. OSS dapat memulai kanal mic dan mencoba internal audio capture."
            } else {
                "Izin mikrofon belum diberikan. Mic dan Android playback capture belum dapat dimulai."
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        statusMessage = if (granted) {
            "Izin audio aktif. OSS akan memulai semua kanal yang didukung secara otomatis."
        } else {
            "Izin audio ditolak. Kanal mic dan internal audio tetap tidak tersedia."
        }
        OssLogger.log(
            level = if (granted) OssLogLevel.INFO else OssLogLevel.WARN,
            subsystem = "OSS/Permissions/Audio",
            component = "AudioPermissionPanel",
            event = if (granted) "record_audio_granted" else "record_audio_denied",
            errorCode = if (granted) null else "AUDIO_RECORD_PERMISSION_DENIED",
            message = statusMessage
        )
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Audio capture permission", style = MaterialTheme.typography.titleLarge)
            Text(statusMessage, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Satu izin ini dipakai oleh kanal mikrofon dan Android playback capture. OSS tidak meminta pilihan Mic/Internal sebelum LIVE; semua kanal yang diizinkan akan dimulai otomatis. Beberapa aplikasi dapat melarang playback capture melalui kebijakan Android.",
                style = MaterialTheme.typography.bodySmall
            )
            androidx.compose.material3.Button(
                enabled = !permissionGranted,
                onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
            ) {
                Text(if (permissionGranted) "Izin aktif" else "Izinkan audio")
            }
        }
    }
}

@Composable
private fun ShareScreenPermissionPanel(context: Context) {
    var projectionActive by remember { mutableStateOf(false) }
    var serviceBound by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Izin perekaman layar belum diberikan.") }
    var binder by remember { mutableStateOf<ShareScreenSessionService.LocalBinder?>(null) }

    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val localBinder = service as? ShareScreenSessionService.LocalBinder
                binder = localBinder
                serviceBound = localBinder != null
                localBinder?.observeProjectionState { active ->
                    projectionActive = active
                    statusMessage = if (active) {
                        "Sesi izin perekaman layar aktif."
                    } else {
                        "Sesi perekaman layar tidak aktif."
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
                serviceBound = false
                projectionActive = false
                statusMessage = "Koneksi ke layanan Share Screen terputus."
            }
        }
    }

    DisposableEffect(context) {
        val bound = context.bindService(
            Intent(context, ShareScreenSessionService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
        onDispose {
            binder?.clearProjectionStateListener()
            if (bound) context.unbindService(connection)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val resultData = result.data
        if (result.resultCode == Activity.RESULT_OK && resultData != null) {
            statusMessage = "Izin diterima. Memulai layanan perekaman layar..."
            runCatching {
                ShareScreenSessionService.start(context, result.resultCode, resultData)
            }.onFailure { error ->
                statusMessage = "Gagal memulai layanan: ${error.message ?: "kesalahan tidak diketahui"}"
                OssLogger.log(
                    level = OssLogLevel.ERROR,
                    subsystem = "OSS/Permissions/Screen",
                    component = "ShareScreenPermissionPanel",
                    event = "service_start_request_failed",
                    errorCode = "SCREEN_SERVICE_START_REQUEST_FAILED",
                    message = error.message ?: "Unable to request Share Screen service start",
                    throwable = error
                )
            }
        } else {
            statusMessage = "Izin perekaman layar ditolak atau dibatalkan."
            OssLogger.log(
                level = OssLogLevel.WARN,
                subsystem = "OSS/Permissions/Screen",
                component = "ShareScreenPermissionPanel",
                event = "permission_denied",
                errorCode = "SCREEN_PERMISSION_DENIED",
                message = "User denied or cancelled Android screen capture permission"
            )
        }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Share Screen", style = MaterialTheme.typography.titleLarge)
            Text(statusMessage, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Panel ini mengaktifkan izin proyeksi Android yang dipakai Share Screen dan internal audio capture. Sesi proyeksi akan digunakan saat LIVE dimulai.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.Button(
                    enabled = !projectionActive && serviceBound,
                    onClick = {
                        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                            as MediaProjectionManager
                        permissionLauncher.launch(manager.createScreenCaptureIntent())
                    }
                ) {
                    Text("Aktifkan")
                }
                androidx.compose.material3.OutlinedButton(
                    enabled = projectionActive,
                    onClick = {
                        val stopped = binder?.stopProjectionSession() == true
                        statusMessage = if (stopped) {
                            "Share Screen sudah dihentikan dan resource proyeksi dilepas."
                        } else {
                            "Belum bisa memastikan proyeksi berhenti. Periksa koneksi layanan."
                        }
                    }
                ) {
                    Text("Hentikan")
                }
            }
        }
    }
}
