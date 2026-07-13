package ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.theme.Mono
import ui.theme.OnSurface
import ui.theme.OnSurfaceVariant
import ui.theme.OutlineVariant
import ui.theme.Primary
import ui.theme.SurfaceContainerHigh
import ui.theme.SurfaceContainerLow

// Android-styled port of desktop's SearchMonitorsPane (App.kt) -- same fields/semantics
// (vm.monitorEnabled/monitorQuery/monitorIntervalHours/monitorStores are shared jvmCommonMain
// state), just Material3/phone-styled. Unlike desktop, flipping the switch on here also requests
// the POST_NOTIFICATIONS runtime permission (API 33+) since MonitorWorker's local notifications
// need it -- the monitor still runs and collects hits even if the user declines, it just won't be
// able to notify until granted.
@Composable
fun SearchMonitorsScreen(vm: SearchViewModel) {
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* no-op either way -- see comment above */ }
    var showHistory by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Box(Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Background search monitor", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = OnSurface)
                Text(
                    when {
                        !vm.monitorEnabled -> "Off"
                        vm.monitorLastCheckedAt == 0L -> "On — checking soon…"
                        else -> "On — last checked " +
                            java.text.SimpleDateFormat("HH:mm").format(java.util.Date(vm.monitorLastCheckedAt)) +
                            (vm.monitorStatusText.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
                    },
                    fontSize = 12.sp,
                    color = OnSurfaceVariant,
                )
            }
            // Opens the last ~10 checks (timestamp/status/hit count, success or failure) --
            // MonitorWorker runs headless on Android, so this is the only way to confirm it's
            // actually firing on schedule instead of guessing from a single "last checked" line.
            IconButton(onClick = { vm.refreshMonitorHistory(); showHistory = true }) {
                Icon(Icons.Default.History, "Search history", tint = OnSurfaceVariant)
            }
            Switch(
                checked = vm.monitorEnabled,
                onCheckedChange = { enabled ->
                    vm.monitorEnabled = enabled
                    // Explicit user action -- always restart the periodic timer's anchor from now
                    // (unlike AndroidApp's passive launch/resume sync, which deliberately doesn't).
                    monitor.MonitorScheduler.reschedule(context, enabled, vm.monitorIntervalHours)
                    if (enabled) monitor.MonitorScheduler.checkNow(context)
                    if (enabled && Build.VERSION.SDK_INT >= 33) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                colors = SwitchDefaults.colors(checkedThumbColor = Primary, checkedTrackColor = Primary.copy(alpha = 0.4f)),
            )
        }
        HorizontalDivider(color = OutlineVariant)

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Card(s) to monitor", fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.6f))
            var focused by remember { mutableStateOf(false) }
            BasicTextField(
                value = vm.monitorQuery,
                onValueChange = { vm.monitorQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .onFocusChanged { focused = it.isFocused },
                textStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = OnSurface),
                cursorBrush = SolidColor(Primary),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(SurfaceContainerLow, RoundedCornerShape(4.dp))
                            .border(1.dp, if (focused) Primary else OutlineVariant, RoundedCornerShape(4.dp))
                            .padding(12.dp),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        if (vm.monitorQuery.isEmpty()) {
                            Text(
                                "One card name per line — same format as the main search",
                                color = OnSurfaceVariant.copy(alpha = 0.3f),
                                fontFamily = Mono,
                                fontSize = 12.sp,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Check every", fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.6f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .height(36.dp)
                        .background(SurfaceContainerLow, RoundedCornerShape(4.dp))
                        .border(1.dp, OutlineVariant, RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    var intervalText by remember(vm.monitorIntervalHours) { mutableStateOf(vm.monitorIntervalHours.toString()) }
                    BasicTextField(
                        value = intervalText,
                        onValueChange = { raw ->
                            val digits = raw.filter { it.isDigit() }
                            intervalText = digits
                            digits.toIntOrNull()?.let { if (it >= 1) vm.monitorIntervalHours = it }
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = OnSurface, fontFamily = Mono, fontSize = 13.sp),
                        cursorBrush = SolidColor(Primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("hour(s) — minimum 1", fontSize = 12.sp, color = OnSurfaceVariant)
            }
        }

        HorizontalDivider(color = OutlineVariant.copy(alpha = 0.4f))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionsSectionHeader("Stores to monitor")
            val allStores = data.STORES.keys.sorted()
            val allSelected = allStores.all { it in vm.monitorStores }
            Surface(shape = RoundedCornerShape(6.dp), color = SurfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { vm.monitorStores = if (allSelected) emptySet() else allStores.toSet() }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        if (allSelected) "Deselect all stores" else "Select all stores",
                        fontSize = 13.sp, fontWeight = FontWeight.Medium, color = OnSurface, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        if (allSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                        null, tint = if (allSelected) Primary else OutlineVariant, modifier = Modifier.size(18.dp),
                    )
                }
            }
            allStores.forEach { store ->
                OptionToggle(checked = store in vm.monitorStores, label = store, onChange = { vm.setMonitorStoreEnabled(store, it) })
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (showHistory) {
        ModalScrim(onDismiss = { showHistory = false }) {
            MonitorHistoryModal(history = vm.monitorHistory, onDismiss = { showHistory = false })
        }
    }
    }
}
