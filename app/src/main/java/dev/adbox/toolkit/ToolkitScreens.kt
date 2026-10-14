@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.adbox.toolkit

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

internal enum class ToolkitTab(val title:String,val root:Screen){
    Adb("ADB",Screen.Device),Fastboot("Fastboot",Screen.Fastboot),Sideload("Sideload",Screen.Sideload),Tools("Tools",Screen.Tools)
}
internal val adbPages=listOf(Screen.Device,Screen.Shell,Screen.Terminal,Screen.Files,Screen.Apps,Screen.Logs)
internal fun toolkitTab(screen:Screen)=when(screen){
    Screen.Fastboot->ToolkitTab.Fastboot
    Screen.Sideload->ToolkitTab.Sideload
    Screen.Tools,Screen.Backup,Screen.Scripts,Screen.Mirror->ToolkitTab.Tools
    else->ToolkitTab.Adb
}
internal fun toolkitIcon(tab:ToolkitTab):ImageVector=when(tab){
    ToolkitTab.Adb->Icons.Outlined.Terminal
    ToolkitTab.Fastboot->Icons.Outlined.DeveloperBoard
    ToolkitTab.Sideload->Icons.Outlined.SystemUpdate
    ToolkitTab.Tools->Icons.Outlined.Build
}
internal fun targetMode(model:ADBoxViewModel):String=when{
    model.fastbootDevice?.userspace==true->"FASTBOOTD"
    model.fastbootDevice!=null->"FASTBOOT"
    model.device?.mode=="Sideload"->"SIDELOAD"
    model.device?.mode=="Recovery"->"RECOVERY ADB"
    model.device!=null->"ADB"
    else->"NO DEVICE"
}
@Composable internal fun AdbSectionBar(model:ADBoxViewModel){
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){
        adbPages.forEach{page->FilterChip(selected=model.screen==page,onClick={model.screen=page},label={Text(if(page==Screen.Device)"Overview"else page.title,fontSize=11.sp)})}
    }
}
private data class ToolDestination(val screen: Screen, val title: String, val description: String, val group: String, val icon: ImageVector)
private val destinations = listOf(
    ToolDestination(Screen.Shell, "Console", "Commands, presets and command history", "ADB", Icons.Outlined.Terminal),
    ToolDestination(Screen.Terminal, "Interactive terminal", "Live shell, control keys and tab completion", "ADB", Icons.Outlined.Keyboard),
    ToolDestination(Screen.Files, "Files", "Browse, transfer, search and manage device files", "ADB", Icons.Outlined.Folder),
    ToolDestination(Screen.Apps, "Apps", "Install APK/APKS, uninstall and export packages", "ADB", Icons.Outlined.Apps),
    ToolDestination(Screen.Logs, "Logcat", "Filter, record and export device logs", "ADB", Icons.Outlined.Subject),
    ToolDestination(Screen.Mirror, "Screen control", "USB video, touch gestures and device keys", "DEVICE TOOLS", Icons.Outlined.Cast),
    ToolDestination(Screen.Backup, "Backup & restore", "ADB folders and partitions, Fastboot readback", "DEVICE TOOLS", Icons.Outlined.Backup),
    ToolDestination(Screen.Scripts, "Saved scripts", "Create and run reusable shell commands", "DEVICE TOOLS", Icons.Outlined.Code),
    ToolDestination(Screen.Fastboot, "Fastboot & firmware", "Flash images, inspect partitions and manage slots", "RECOVERY & FIRMWARE", Icons.Outlined.DeveloperBoard),
    ToolDestination(Screen.Sideload, "ADB sideload", "Send a ROM or update ZIP to recovery", "RECOVERY & FIRMWARE", Icons.Outlined.SystemUpdate)
)

@Composable internal fun AdbWorkspace(model:ADBoxViewModel,onConnect:()->Unit,onReboot:()->Unit) {
    val device=model.device
    val usable=device!=null&&device.mode!="Sideload"
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item("heading") { ToolkitHeading("ADB", "Commands, files and apps on the selected USB target") }
        item("target") {
            ToolkitIntro(Icons.Outlined.Terminal, if(usable)device!!.model else "Connect an Android device", when {
                usable->"${if(device!!.mode=="Recovery")"Recovery ADB"else "Android ${device.android}"} · ${device.serial.ifBlank{"USB device"}}"
                model.fastbootDevice!=null->"Your target is in ${targetMode(model).lowercase()}. Open Fastboot for bootloader tools."
                device?.mode=="Sideload"->"Recovery is waiting for an update. Open Sideload to send its ZIP."
                else->"Enable USB debugging, connect an OTG/data cable, and approve ADBox on your target."
            }) {
                when {
                    usable->Button(onClick={model.screen=Screen.Shell},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Open console");Spacer(Modifier.weight(1f));Icon(Icons.Outlined.ArrowForward,null)}
                    model.fastbootDevice!=null->Button(onClick={model.screen=Screen.Fastboot},modifier=Modifier.fillMaxWidth()){Text("Open Fastboot")}
                    device?.mode=="Sideload"->Button(onClick={model.screen=Screen.Sideload},modifier=Modifier.fillMaxWidth()){Text("Open Sideload")}
                    else->Button(onClick=onConnect,enabled=!model.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Icon(Icons.Outlined.Usb,null);Spacer(Modifier.width(8.dp));Text("Connect over USB")}
                }
            }
        }
        if(usable&&device!!.mode=="Android") item("metrics") {
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                WorkspaceMetric("Battery",device.battery,Modifier.weight(1f))
                WorkspaceMetric("Memory",device.ram,Modifier.weight(1f))
            }
        }
        item("tools-label") { ToolkitSectionLabel("ADB tools") }
        items(destinations.filter { it.group=="ADB" },key={it.screen.name},contentType={"tool"}) { tool->ToolkitTool(tool.icon,tool.title,tool.description){model.screen=tool.screen} }
        if(usable) item("actions") {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick={model.captureScreenshot()},enabled=!model.busy,modifier=Modifier.weight(1f)){Text("Screenshot")}
                OutlinedButton(onClick=onReboot,enabled=!model.busy,modifier=Modifier.weight(1f)){Text("Reboot")}
            }
        }
    }
}

@Composable internal fun ToolkitTools(model:ADBoxViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered=remember(query) { destinations.filter { query.isBlank() || "${it.title} ${it.description} ${it.group}".contains(query.trim(),true) } }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item("heading") { ToolkitHeading("Tools", "Find a tool for your selected USB target") }
        item("search") { OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),singleLine=true,label={Text("Search tools")},leadingIcon={Icon(Icons.Outlined.Search,null)},trailingIcon={if(query.isNotEmpty())IconButton(onClick={query=""}){Icon(Icons.Outlined.Close,"Clear search")}},shape=RoundedCornerShape(20.dp)) }
        if(filtered.isEmpty()) item("empty") { ToolkitEmptyMessage("No matching tools", "Try a name such as files, flash, backup or screen.") }
        listOf("DEVICE TOOLS","RECOVERY & FIRMWARE","ADB").forEach { group->
            val tools=filtered.filter { it.group==group }
            if(tools.isNotEmpty()) {
                item("label-$group") { ToolkitSectionLabel(group.lowercase().replaceFirstChar(Char::uppercase)) }
                items(tools,key={it.screen.name},contentType={"tool"}) { tool->ToolkitTool(tool.icon,tool.title,tool.description){model.screen=tool.screen} }
            }
        }
    }
}
@Composable private fun WorkspaceMetric(label:String,value:String,modifier:Modifier) {
    Surface(modifier,color=MaterialTheme.colorScheme.secondaryContainer,shape=RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(label,style=MaterialTheme.typography.labelMedium)
            Text(value,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
        }
    }
}
