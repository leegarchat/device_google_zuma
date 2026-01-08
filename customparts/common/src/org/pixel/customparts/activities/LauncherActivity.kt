package org.pixel.customparts.activities

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pixel.customparts.AppConfig
import org.pixel.customparts.R
import org.pixel.customparts.dynamicDarkColorScheme
import org.pixel.customparts.dynamicLightColorScheme
import org.pixel.customparts.ui.GenericSwitchRow
import org.pixel.customparts.ui.InfoDialog
import org.pixel.customparts.ui.SliderSettingFloat
import org.pixel.customparts.ui.SettingsGroupCard
import java.io.DataOutputStream
import org.pixel.customparts.utils.dynamicStringResource
import org.pixel.customparts.ui.ModuleStatus


class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val darkTheme = isSystemInDarkTheme()
            val context = LocalContext.current
            val colorScheme = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LauncherScreen(onBack = { finish() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var infoDialogTitle by remember { mutableStateOf<String?>(null) }
    var infoDialogText by remember { mutableStateOf<String?>(null) }
    var infoDialogVideo by remember { mutableStateOf<String?>(null) }
    var showXposedWarning by remember { mutableStateOf(false) }
    var nativeSearchEnabled by remember { mutableStateOf(LauncherManager.isNativeSearchEnabled(context)) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var isBottomRestartVisible by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = { Text(
                        dynamicStringResource(R.string.launcher_settings_title),
                        fontWeight = FontWeight.Bold
                    ) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                AnimatedVisibility(
                    visible = !isBottomRestartVisible,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Button(
                        onClick = { 
                            scope.launch { LauncherManager.restartLauncher(context) }
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer, 
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Restart Launcher") 
                    }
                }
            }
            item {
                SettingsGroupCard(title = dynamicStringResource(R.string.launcher_search_section)) {
                    GenericSwitchRow(
                        title = dynamicStringResource(R.string.launcher_search_title),
                        checked = nativeSearchEnabled,
                        onCheckedChange = { checked ->
                            nativeSearchEnabled = checked
                            scope.launch { LauncherManager.setNativeSearchEnabled(context, checked) }
                        },
                        summary = null,
                        infoText = dynamicStringResource(R.string.launcher_search_desc),
                        videoResName = "search_fix",
                        onInfoClick = { t, s, v ->
                            infoDialogTitle = t
                            infoDialogText = s
                            infoDialogVideo = v
                        }
                    )
                }
            }
            item {
                ClearAllSection(
                    context = context,
                    scope = scope,
                    refreshKey = refreshKey,
                    onSettingChanged = { refreshKey++ },
                    onInfo = { t, s, v ->
                        infoDialogTitle = t
                        infoDialogText = s
                        infoDialogVideo = v
                    },
                    onShowBottomRestartChange = { isVisible ->
                        isBottomRestartVisible = isVisible
                    },
                    onShowXposedDialog = { showXposedWarning = true }
                )
            }
            item {
                Dt2sUiSection(
                    context = context,
                    scope = scope,
                    onInfoClick = { t, s, v ->
                        infoDialogTitle = t
                        infoDialogText = s
                        infoDialogVideo = v
                    },
                    showXposedDialog = {
                        showXposedWarning = true
                    }
                )
            }
        }
    }

    if (infoDialogTitle != null && infoDialogText != null) {
        InfoDialog(
            title = infoDialogTitle!!,
            text = infoDialogText!!,
            videoResName = infoDialogVideo,
            onDismiss = {
                infoDialogTitle = null
                infoDialogText = null
                infoDialogVideo = null
            }
        )
    }
    
    if (showXposedWarning) {
        AlertDialog(
            onDismissRequest = { showXposedWarning = false },
            title = { Text(dynamicStringResource(R.string.os_dialog_xposed_title)) },
            text = { Text(dynamicStringResource(R.string.os_dialog_xposed_msg)) },
            confirmButton = {
                TextButton(onClick = { showXposedWarning = false }) {
                    Text("OK")
                }
            }
        )
    }
}


@Composable
fun ClearAllSection(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    refreshKey: Int,
    onSettingChanged: () -> Unit,
    onInfo: (String, String, String?) -> Unit,
    onShowBottomRestartChange: (Boolean) -> Unit,
    onShowXposedDialog: () -> Unit
) {
    val keyEnabled = if (AppConfig.IS_XPOSED) "launcher_clear_all_xposed" else "launcher_clear_all"
    val keyMode = "launcher_replace_on_clear"
    val keyMargin = "launcher_clear_all_bottom_margin"
    var enabled by remember(refreshKey) { mutableStateOf(Settings.Secure.getInt(context.contentResolver, keyEnabled, 0) == 1) }
    var mode by remember(refreshKey) { mutableIntStateOf(Settings.Secure.getInt(context.contentResolver, keyMode, 0)) }
    val initialLoadedMargin = remember(refreshKey) { Settings.Secure.getFloat(context.contentResolver, keyMargin, 3.0f) }
    var margin by remember(refreshKey) { mutableFloatStateOf(initialLoadedMargin) }
    var baselineMargin by remember(refreshKey) { mutableFloatStateOf(initialLoadedMargin) }
    val isMarginModified = margin != baselineMargin
    
    LaunchedEffect(isMarginModified) {
        onShowBottomRestartChange(isMarginModified)
    }

    SettingsGroupCard(title = dynamicStringResource(R.string.launcher_clear_all_title)) {
        GenericSwitchRow(
            title = if (enabled) dynamicStringResource(R.string.os_status_active) else dynamicStringResource(R.string.os_status_disabled),
            checked = enabled,
            onCheckedChange = { checked ->
                if (checked && AppConfig.IS_XPOSED && !ModuleStatus.isModuleActive()) {
                    onShowXposedDialog()
                    enabled = false
                } else {
                    enabled = checked
                    scope.launch(Dispatchers.IO) {
                        Settings.Secure.putInt(context.contentResolver, keyEnabled, if (checked) 1 else 0)
                        LauncherManager.restartLauncher(context)
                        launch(Dispatchers.Main) { onSettingChanged() }
                    }
                }
            },
            videoResName = "search_fix",
            infoText = dynamicStringResource(R.string.launcher_clear_all_desc),
            onInfoClick = onInfo
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        val modes = listOf(
            ClearAllMode(0, R.string.launcher_ca_mode_float, Icons.Rounded.CleaningServices),
            ClearAllMode(1, R.string.launcher_ca_mode_screenshot, Icons.Rounded.PhotoCamera),
            ClearAllMode(2, R.string.launcher_ca_mode_select, Icons.Rounded.SelectAll)
        )

        Column(modifier = Modifier.padding(16.dp)) {
            modes.forEach { item ->
                val isSelected = item.id == mode
                val isRowEnabled = enabled
                
                val backgroundColor by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    label = "rowBg"
                )
                
                val contentAlpha = if (isRowEnabled) 1f else 0.4f

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(backgroundColor)
                        .clickable(enabled = isRowEnabled) {
                            if (mode != item.id) {
                                mode = item.id
                                scope.launch(Dispatchers.IO) {
                                    Settings.Secure.putInt(context.contentResolver, keyMode, item.id)
                                    LauncherManager.restartLauncher(context)
                                    launch(Dispatchers.Main) { onSettingChanged() }
                                }
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 14.dp)
                        .alpha(contentAlpha),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                item.icon,
                                null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = dynamicStringResource(item.labelRes),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    AnimatedVisibility(
                        visible = isSelected,
                        enter = scaleIn() + fadeIn(),
                        exit = scaleOut() + fadeOut()
                    ) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        val isSliderActive = enabled && (mode == 0)
        
        SliderSettingFloat(
            title = dynamicStringResource(R.string.launcher_ca_margin),
            value = margin,
            range = 0.1f..7.0f,
            unit = "x",
            enabled = isSliderActive,
            onValueChange = { 
                margin = it
                scope.launch(Dispatchers.IO) {
                    Settings.Secure.putFloat(context.contentResolver, keyMargin, it)
                }
            },
            onDefault = {
                val def = 3.0f
                margin = def
                scope.launch(Dispatchers.IO) {
                    Settings.Secure.putFloat(context.contentResolver, keyMargin, def)
                }
            },
            infoText = dynamicStringResource(R.string.launcher_ca_margin_desc),
            onInfoClick = onInfo
        )

        AnimatedVisibility(
            visible = isMarginModified,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            LauncherManager.restartLauncher(context)
                            withContext(Dispatchers.Main) {
                                baselineMargin = margin
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Apply & Restart")
                }
            }
        }

        if (!isMarginModified) {
            Spacer(Modifier.height(8.dp))
        }
    }
}

data class ClearAllMode(val id: Int, val labelRes: Int, val icon: ImageVector)

object LauncherManager {
    private const val KEY_NATIVE_SEARCH = "pixel_launcher_native_search"

    fun isNativeSearchEnabled(context: Context): Boolean {
        return Settings.Secure.getInt(context.contentResolver, KEY_NATIVE_SEARCH, 1) == 1
    }

    suspend fun setNativeSearchEnabled(context: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        Settings.Secure.putInt(context.contentResolver, KEY_NATIVE_SEARCH, if (enabled) 1 else 0)
        
        val cmdValue = if (enabled) "true" else "false"
        val command = "cmd device_config override launcher enable_one_search $cmdValue && am force-stop com.google.android.apps.nexuslauncher"
        
        runRootCommand(command)
    }

    suspend fun restartLauncher(context: Context) = withContext(Dispatchers.IO) {
        runRootCommand("am force-stop com.google.android.apps.nexuslauncher")
    }

    private fun runRootCommand(command: String) {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor()
            os.close()
        } catch (e: Exception) {
            e.printStackTrace()
             try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }
}