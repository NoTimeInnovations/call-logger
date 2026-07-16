package com.mydream.calllogger.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydream.calllogger.export.DateRange
import com.mydream.calllogger.export.Exporter
import com.mydream.calllogger.prefs.SettingsManager
import com.mydream.calllogger.util.BatteryOptimization

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel, onOpenFlowBuilder: () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val permissions = remember {
        buildList {
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        vm.onPermissionsResult(result[Manifest.permission.READ_CALL_LOG] == true)
    }

    // Ask for permissions on first entry (or reflect an already-granted state).
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.onPermissionsResult(true) else launcher.launch(permissions)
    }

    // Once call access is granted, nudge (once) to exempt the app from battery
    // optimisation so background capture keeps running when the app is terminated and
    // the phone is locked (Doze) — otherwise the sync worker gets deferred for long
    // stretches and calls show up late or only after the app is next opened.
    LaunchedEffect(state.hasPermissions) {
        if (state.hasPermissions) {
            val settings = SettingsManager(context)
            if (!settings.batteryPromptShown && !BatteryOptimization.isIgnoring(context)) {
                settings.batteryPromptShown = true
                BatteryOptimization.request(context)
            }
        }
    }

    // Re-sync whenever the app comes back to the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, state.hasPermissions) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && state.hasPermissions) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    LaunchedEffect(state.pendingShare) {
        state.pendingShare?.let { share ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = Exporter.XLSX_MIME
                putExtra(Intent.EXTRA_STREAM, share.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share ${share.fileName}"))
            vm.consumeShare()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                title = {
                    Column {
                        Text("Call Logger", fontWeight = FontWeight.SemiBold)
                        if (state.email.isNotBlank()) {
                            Text(
                                state.email,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenFlowBuilder) {
                        Icon(Icons.Filled.AccountTree, contentDescription = "Call flow")
                    }
                    IconButton(onClick = { vm.sync() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Sync now")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            RangeChips(
                selected = state.selectedRange,
                onSelect = { vm.selectRange(it) }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${state.calls.size} call${if (state.calls.size == 1) "" else "s"} · ${state.selectedRange.label}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { vm.export() },
                    enabled = state.calls.isNotEmpty()
                ) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Excel")
                }
            }

            Divider()

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    !state.hasPermissions -> PermissionPrompt(
                        onGrant = { launcher.launch(permissions) },
                        onOpenSettings = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                )
                            )
                        }
                    )

                    state.calls.isEmpty() && state.loading -> Box(
                        Modifier.fillMaxSize(), Alignment.Center
                    ) { CircularProgressIndicator() }

                    state.calls.isEmpty() -> EmptyState(state.selectedRange)

                    else -> LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                        items(state.calls, key = { it.id }) { call ->
                            CallListItem(call)
                            Divider(
                                modifier = Modifier.padding(start = 70.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeChips(selected: DateRange, onSelect: (DateRange) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(DateRange.entries.toList()) { range ->
            FilterChip(
                selected = range == selected,
                onClick = { onSelect(range) },
                label = { Text(range.label) }
            )
        }
    }
}

@Composable
private fun EmptyState(range: DateRange) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.CallEnd,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "No calls for ${range.label.lowercase()}",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "New calls will appear here automatically.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Call log access needed",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Call Logger needs permission to read your call history so it can record and export your calls.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Text("Grant permission")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Text("Open app settings")
        }
    }
}
