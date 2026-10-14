package dev.adbox.toolkit

import android.os.Build
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable internal fun SettingsDialog(model: ADBoxViewModel, onDismiss: () -> Unit) {
    var notices by remember { mutableStateOf(false) }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.padding(20.dp).widthIn(max=560.dp).fillMaxWidth().heightIn(max=700.dp),shape=RoundedCornerShape(28.dp),color=MaterialTheme.colorScheme.surface) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start=24.dp,end=12.dp,top=12.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("Settings",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    IconButton(onClick=onDismiss) { Icon(Icons.Outlined.Close,"Close settings") }
                }
                Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    ToolkitSectionLabel("Appearance")
                    ToolkitCard {
                        Text("Theme",style=MaterialTheme.typography.titleSmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            listOf("system","light","dark").forEach { theme->FilterChip(selected=model.theme==theme,onClick={model.changeTheme(theme)},label={Text(theme.replaceFirstChar(Char::uppercase))}) }
                        }
                        if(Build.VERSION.SDK_INT>=31)SettingsToggle("Wallpaper colors","Use your phone’s color palette",model.dynamicColors,model::setDynamic)
                    }
                    ToolkitSectionLabel("USB connections")
                    ToolkitCard {
                        SettingsToggle("Queued USB engine","Recommended for long transfers. Disconnect targets before switching engines.",model.queuedUsb,model::changeUsbEngine,enabled=!model.busy&&model.connections.isEmpty())
                        HorizontalDivider()
                        SettingsToggle("Reconnect USB targets","Reconnect attached devices that already have permission",model.autoReconnect,model::changeReconnect)
                        HorizontalDivider()
                        SettingsToggle("Background connections","Keep cable connections and recording active when leaving ADBox",model.backgroundConnections,model::changeBackground)
                    }
                    ToolkitSectionLabel("About")
                    ToolkitCard("ADBox · 1.0.0") {
                        Text("ADB, Fastboot and recovery sideload over USB. No account, ads or in-app updater.",style=MaterialTheme.typography.bodySmall)
                        Text("Your ADB identity stays in ADBox’s private app storage.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(Build.VERSION.SDK_INT>=33)Text("Enable themed icons in your launcher to color the ADBox icon from your wallpaper.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick={notices=true}) { Text("Open-source licenses") }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                TextButton(onClick=onDismiss,modifier=Modifier.align(Alignment.End).padding(12.dp)) { Text("Done") }
            }
        }
    }
    if(notices)OpenSourceDialog { notices=false }
}

@Composable private fun SettingsToggle(title:String,description:String,checked:Boolean,onChange:(Boolean)->Unit,enabled:Boolean=true) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,style=MaterialTheme.typography.titleSmall)
            Text(description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked=checked,onCheckedChange=onChange,enabled=enabled)
    }
}
