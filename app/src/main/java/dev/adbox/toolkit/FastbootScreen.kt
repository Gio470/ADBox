@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.adbox.toolkit

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class FastbootPage(val title:String) { Device("Device"), Flash("Flash"), Slots("Slots"), Partitions("Partitions"), Console("Console") }
private data class FastbootReviewRequest(val command:String,val token:Int,val target:FastbootInfo,val warning:String?=null)

@Composable internal fun FastbootScreen(model:ADBoxViewModel,onConnect:()->Unit,onBootloader:()->Unit,onImage:(Boolean)->Unit) {
    val device=model.fastbootDevice
    var page by rememberSaveable{mutableStateOf(FastbootPage.Device)}
    var command by rememberSaveable{mutableStateOf("getvar:all")}
    var review by remember{mutableStateOf<FastbootReviewRequest?>(null)}
    var erase by remember{mutableStateOf(false)}
    var report by rememberSaveable{mutableStateOf("")}
    val clipboard=LocalClipboardManager.current
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri->if(uri!=null&&report.isNotBlank())model.saveBytes(uri,report.toByteArray());report=""}
    fun run(value:String){
        val target=model.fastbootDevice?:return
        val normalized=ADBoxViewModel.normalizeFastboot(value)
        if(FastbootDiagnostics.readOnlyCommand(normalized))model.runFastboot(normalized)
        else {
            val slot=normalized.removePrefix("set_active:")
            val bad=model.fastbootDiagnostics?.slots?.get(slot)?.get("slot-unbootable")?.lowercase() in listOf("yes","true","1")
            review=FastbootReviewRequest(normalized,model.fastbootConnectionToken,target,if(normalized.startsWith("set_active:")&&bad)"The bootloader marks slot ${slot.uppercase()} as unbootable. Selecting it can leave this device unable to boot."else null)
        }
    }
    LaunchedEffect(model.fastbootConnectionToken){review=null;erase=false}
    val listState=rememberLazyListState()
    LaunchedEffect(page) { listState.scrollToItem(0) }
    Column(Modifier.fillMaxSize()) {
        if(device!=null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FastbootPage.entries.forEach { entry->FilterChip(selected=page==entry,onClick={page=entry},label={Text(entry.title)}) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=listState,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item("heading") {
            ToolkitHeading(if(device==null)"Fastboot"else when(page){FastbootPage.Device->"Device diagnostics";FastbootPage.Flash->"Flash images";FastbootPage.Slots->"A/B slots";FastbootPage.Partitions->"Partitions";FastbootPage.Console->"Fastboot console"},"Bootloader and fastbootd tools") {
                if(device!=null)IconButton(onClick={model.refreshFastboot()},enabled=!model.busy){Icon(Icons.Outlined.Refresh,"Refresh bootloader variables")}
            }
        }
        if(device==null||page==FastbootPage.Device) item("target") {
            ToolkitIntro(Icons.Outlined.DeveloperBoard,device?.product?:"Connect a Fastboot device",if(device==null)"Start the target in bootloader or fastbootd mode, then connect an OTG/data cable."else "${if(device.userspace)"Fastbootd"else "Fastboot"} · ${device.serial}") {
                if(device==null) {
                    Button(onClick=onConnect,enabled=!model.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Icon(Icons.Outlined.Usb,null);Spacer(Modifier.width(8.dp));Text("Connect over USB")}
                    if(model.device!=null&&model.device?.mode!="Sideload")OutlinedButton(onClick=onBootloader,enabled=!model.busy,modifier=Modifier.fillMaxWidth()){Text("Reboot ADB target to bootloader")}
                } else {
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick={page=FastbootPage.Slots},label={Text("Slot ${device.variables["current-slot"]?:"Unavailable"}")})
                        AssistChip(onClick={page=FastbootPage.Device},label={Text("Unlocked: ${device.variables["unlocked"]?:"Unavailable"}")})
                    }
                    Button(onClick={page=FastbootPage.Flash},enabled=!model.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Icon(Icons.Outlined.FileUpload,null);Spacer(Modifier.width(8.dp));Text("Flash an image")}
                }
            }
        }
        if(device!=null){
            if(model.fastbootBusy)item("operation") {
                Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=RoundedCornerShape(20.dp)){
                    Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                        Text(if(model.fastbootProgress>=0)model.fastbootPhase else model.status,fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                        if(model.fastbootProgress>=0)LinearProgressIndicator(progress={model.fastbootProgress},modifier=Modifier.fillMaxWidth())else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Keep the USB cable connected until the operation finishes.",fontSize=11.sp)
                    }
                }
            }
            when(page){
                FastbootPage.Device->{
                    item("device-tools"){FastbootDeviceTools(model,::run)}
                    item("bootloader-tools"){FastbootBootloaderTools(model,::run)}
                }
                FastbootPage.Flash->{
                    item("flash-tools"){
                        FastbootToolCard("Flash a partition image"){
                            Text("Choose an .img file such as dtbo.img, boot.img, init_boot.img, or recovery.img. Review its exact destination partition and slot before writing.",fontSize=12.sp,lineHeight=18.sp)
                            Button(onClick={onImage(false)},enabled=!model.busy,modifier=Modifier.fillMaxWidth().height(53.dp),shape=RoundedCornerShape(20.dp)){Icon(Icons.Outlined.FileUpload,null);Spacer(Modifier.width(8.dp));Text("Choose .img to flash")}
                            OutlinedButton(onClick={onImage(true)},enabled=!model.busy,modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.PlayArrow,null);Spacer(Modifier.width(8.dp));Text("Boot a boot image once")}
                        }
                    }
                    item("firmware-tools"){FirmwareTools(model)}
                }
                FastbootPage.Slots->item("slot-tools"){FastbootSlotTools(model,::run)}
                FastbootPage.Partitions->{
                    item("partition-tools"){FastbootPartitionTools(model)}
                    item{OutlinedButton(onClick={erase=true},enabled=!model.busy,modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.DeleteOutline,null);Spacer(Modifier.width(8.dp));Text("Review partition erase")}}
                    item{FilledTonalButton(onClick={model.screen=Screen.Backup},enabled=!model.busy,modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.Backup,null);Spacer(Modifier.width(8.dp));Text("Partition readback / backup")}}
                    item{TextButton(onClick={page=FastbootPage.Flash},modifier=Modifier.fillMaxWidth()){Text("Open firmware & logical partition tools")}}
                }
                FastbootPage.Console->{
                    item("console-command"){
                        OutlinedTextField(command,{command=it},Modifier.fillMaxWidth(),label={Text("Fastboot command")},placeholder={Text("getvar:all or oem device-info")},singleLine=true,enabled=!model.busy,shape=RoundedCornerShape(20.dp),textStyle=LocalTextStyle.current.copy(fontFamily=FontFamily.Monospace,fontSize=12.sp))
                    }
                    item{
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)){
                            listOf("getvar:all","getvar:product","getvar:unlocked","getvar:current-slot","flashing get_unlock_ability").forEach{preset->AssistChip(onClick={command=preset},label={Text(preset,fontSize=11.sp)})}
                        }
                    }
                    item{
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
                            Button(onClick={run(command)},enabled=!model.busy&&command.isNotBlank(),modifier=Modifier.weight(1f).height(49.dp)){Icon(Icons.Outlined.PlayArrow,null);Spacer(Modifier.width(8.dp));Text("Send command")}
                            FilledTonalIconButton(onClick={clipboard.setText(AnnotatedString(model.fastbootOutput))},enabled=model.fastbootOutput.isNotBlank()){Icon(Icons.Outlined.ContentCopy,"Copy Fastboot output")}
                        }
                    }
                    if(model.fastbootHistory.isNotEmpty())item("console-history"){
                        var expanded by rememberSaveable{mutableStateOf(false)}
                        FastbootToolCard("Recent commands"){
                            Row(verticalAlignment=Alignment.CenterVertically){TextButton(onClick={expanded=!expanded},modifier=Modifier.weight(1f)){Text(if(expanded)"Hide history"else "Show ${model.fastbootHistory.size} commands")};TextButton(onClick={model.clearFastbootHistory()},enabled=!model.busy){Text("Clear")}}
                            if(expanded)model.fastbootHistory.forEach{previous->OutlinedButton(onClick={command=previous},enabled=!model.busy,modifier=Modifier.fillMaxWidth()){Text(previous,fontFamily=FontFamily.Monospace,fontSize=11.sp)}}
                            Text("Tap a command to fill the console, then send it. Device changes always require review.",fontSize=11.sp)
                        }
                    }
                }
            }
            item{
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    AssistChip(onClick={run("reboot")},enabled=!model.busy,label={Text("Reboot Android")})
                    AssistChip(onClick={run("reboot-bootloader")},enabled=!model.busy,label={Text("Bootloader")})
                    AssistChip(onClick={run("reboot-fastboot")},enabled=!model.busy,label={Text("Fastbootd")})
                    AssistChip(onClick={run("reboot-recovery")},enabled=!model.busy,label={Text("Recovery")})
                    AssistChip(onClick={run("continue")},enabled=!model.busy,label={Text("Continue boot")})
                }
            }
            item{
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    OutlinedButton(onClick={clipboard.setText(AnnotatedString(model.fastbootReport()))},enabled=!model.busy,modifier=Modifier.weight(1f)){Icon(Icons.Outlined.ContentCopy,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Copy report",fontSize=12.sp)}
                    FilledTonalButton(onClick={report=model.fastbootReport();export.launch("adbox-fastboot-report.txt")},enabled=!model.busy,modifier=Modifier.weight(1f)){Icon(Icons.Outlined.SaveAlt,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Save report",fontSize=12.sp)}
                }
            }
        }
        if(model.fastbootOutput.isNotBlank()||(device!=null&&page==FastbootPage.Console))item("output") {
            ToolkitConsole(model.fastbootOutput,Modifier.fillMaxWidth().height(300.dp),"fastboot >\n\nReady for a command.")
        }
        }
    }
    review?.let{request->FastbootCommandReview(request.command,request.target,{review=null},enabled=!model.busy&&model.fastbootDevice!=null&&request.token==model.fastbootConnectionToken,warning=request.warning){review=null;model.runFastboot(request.command,request.token)}}
    if(erase)EraseReview(device,{erase=false}){partition->erase=false;run("erase:$partition")}
}

@Composable private fun FastbootToolCard(title:String,content:@Composable ColumnScope.()->Unit) {
    ToolkitCard(title=title,content=content)
}
@Composable private fun FastbootValues(values:Map<String,String>){
    SelectionContainer{Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        values.forEach{(name,value)->Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
            Text(name,Modifier.weight(1f),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value,Modifier.weight(1f),fontSize=12.sp,fontFamily=FontFamily.Monospace)
        }}
    }}
}
@Composable private fun FastbootDeviceTools(model:ADBoxViewModel,run:(String)->Unit){
    var search by rememberSaveable{mutableStateOf("")}
    FastbootToolCard("Device diagnostics"){
        Text("Read bootloader/baseband versions, lock status, battery values, transfer limits, and A/B slot health where reported.",fontSize=12.sp,lineHeight=18.sp)
        Button(onClick={model.readFastbootDiagnostics()},enabled=!model.busy,modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.FactCheck,null);Spacer(Modifier.width(8.dp));Text("Read full diagnostics")}
        if(model.fastbootDiagnosticsTime.isNotBlank())Text("Read: ${model.fastbootDiagnosticsTime}",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(search,{search=it},Modifier.fillMaxWidth(),label={Text("Filter variables")},singleLine=true,leadingIcon={Icon(Icons.Outlined.Search,null)})
        val variables=model.fastbootDevice?.variables.orEmpty()
        val values=remember(variables,search){variables.filter{(name,value)->name.contains(search,true)||value.contains(search,true)}}
        if(values.isEmpty())Text("No matching variables.",fontSize=12.sp)else FastbootValues(values)
        Text("Unavailable means this bootloader did not report the value.",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick={run("getvar:all")},enabled=!model.busy,modifier=Modifier.fillMaxWidth()){Text("Print all bootloader variables")}
    }
}
@Composable private fun FastbootSlotTools(model:ADBoxViewModel,run:(String)->Unit){
    val values=model.fastbootDevice?.variables.orEmpty()
    val slots=FastbootDiagnostics.slotNames(values["slot-count"],values["current-slot"])
    FastbootToolCard("A/B slot manager"){
        Text("Active: ${values["current-slot"]?:"Unavailable"} · Reported slot count: ${values["slot-count"]?:"Unavailable"}",fontSize=12.sp)
        FilledTonalButton(onClick={model.readFastbootDiagnostics()},enabled=!model.busy,modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.Refresh,null);Spacer(Modifier.width(8.dp));Text("Read slot health")}
        if(slots.isEmpty())Text("This target has not reported a supported A/B slot layout. Slot switches are available after the bootloader reports one.",fontSize=12.sp,lineHeight=18.sp)
        slots.forEach{slot->
            Surface(color=if(values["current-slot"]==slot)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,shape=RoundedCornerShape(18.dp)){
                Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Text("Slot ${slot.uppercase()}${if(values["current-slot"]==slot)" · Active"else ""}",fontWeight=FontWeight.Bold)
                    val health=model.fastbootDiagnostics?.slots?.get(slot)
                    if(health==null)Text("Read slot health to see successful, unbootable, and retry-count flags.",fontSize=11.sp)else FastbootValues(health)
                    OutlinedButton(onClick={run("set_active:$slot")},enabled=!model.busy&&values["current-slot"]!=slot,modifier=Modifier.fillMaxWidth()){Text("Review switch to ${slot.uppercase()}")}
                }
            }
        }
        Text("Flags describe the bootloader's last reported state. A slot marked successful is not a guarantee that a new ROM can boot.",fontSize=11.sp,lineHeight=17.sp)
    }
}
@Composable private fun FastbootPartitionTools(model:ADBoxViewModel){
    var partition by rememberSaveable{mutableStateOf("boot")}
    FastbootToolCard("Partition inspector"){
        Text("Read the size, filesystem type, logical-partition flag, and slot support for an exact partition name.",fontSize=12.sp,lineHeight=18.sp)
        OutlinedTextField(partition,{partition=it},Modifier.fillMaxWidth(),label={Text("Exact partition name")},singleLine=true,enabled=!model.busy)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            listOf("dtbo","boot","init_boot","vendor_boot","recovery","vbmeta","system","vendor","super","userdata").forEach{name->AssistChip(onClick={partition=name},enabled=!model.busy,label={Text(name,fontSize=11.sp)})}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){
            listOf("a","b").forEach{slot->OutlinedButton(onClick={partition=partition.removeSuffix("_a").removeSuffix("_b")+"_$slot"},enabled=!model.busy&&partition.isNotBlank(),modifier=Modifier.weight(1f)){Text("Use slot ${slot.uppercase()}")}}
        }
        Button(onClick={model.inspectFastbootPartition(partition)},enabled=!model.busy&&partition.matches(Regex("[A-Za-z0-9_.-]{1,128}")),modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.Search,null);Spacer(Modifier.width(8.dp));Text("Inspect partition")}
        model.fastbootPartition?.let{result->
            HorizontalDivider()
            Text("Result: ${result.name}",fontWeight=FontWeight.SemiBold)
            result.sizeBytes?.let{size->Text("${ADBoxViewModel.formatBytes(size)} · $size bytes",fontSize=16.sp,fontWeight=FontWeight.Bold)}
            FastbootValues(result.variables)
            Text("Read: ${model.fastbootPartitionTime}",fontSize=10.sp)
        }
        Text("Unavailable does not prove a partition is absent. Queries use your exact name; slot suffixes are never chosen automatically.",fontSize=11.sp,lineHeight=17.sp)
    }
}
@Composable private fun FastbootBootloaderTools(model:ADBoxViewModel,run:(String)->Unit){
    var expanded by rememberSaveable{mutableStateOf(false)}
    FastbootToolCard("Bootloader controls"){
        Text("OEM unlocking must be allowed on the target. Availability depends on its bootloader.",fontSize=12.sp,lineHeight=18.sp)
        OutlinedButton(onClick={run("flashing get_unlock_ability")},enabled=!model.busy&&model.fastbootDevice?.userspace==false,modifier=Modifier.fillMaxWidth()){Text("Check unlock eligibility")}
        TextButton(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth()){Text(if(expanded)"Hide lock / unlock controls"else "Show lock / unlock controls")}
        if(expanded){
            Text("Lock changes can erase all device data. Locking a device running an incompatible custom ROM can prevent it from booting. Every change needs a typed confirmation.",fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.error)
            if(model.fastbootDevice?.userspace==true)Text("Reboot to the bootloader to use these controls.",fontSize=12.sp)
            listOf("flashing unlock" to "Unlock bootloader","flashing lock" to "Lock bootloader","flashing unlock_critical" to "Unlock critical partitions","flashing lock_critical" to "Lock critical partitions").forEach{(command,title)->
                OutlinedButton(onClick={run(command)},enabled=!model.busy&&model.fastbootDevice?.userspace==false,modifier=Modifier.fillMaxWidth()){Text(title)}
            }
        }
    }
}

@Composable internal fun FastbootImageReview(model:ADBoxViewModel,uri:Uri,boot:Boolean,expectedToken:Int,onDismiss:()->Unit){
    val name=remember(uri){model.displayName(uri)}
    val target=remember(uri,expectedToken){model.fastbootDevice}
    val suggested=remember(name){FastbootImageSelection.suggestedPartition(name)}
    var partition by rememberSaveable(uri.toString(),boot){mutableStateOf(if(boot)"boot"else suggested)}
    val sameTarget=model.fastbootDevice!=null&&expectedToken==model.fastbootConnectionToken
    val valid=partition.matches(Regex("[A-Za-z0-9_.-]{1,128}"))
    val base=FastbootImageSelection.baseName(partition)
    AlertDialog(onDismissRequest=onDismiss,icon={Icon(if(boot)Icons.Outlined.PlayArrow else Icons.Outlined.FileUpload,null)},title={Text(if(boot)"Boot this image?"else "Flash .img to partition")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){
            Text(name,fontWeight=FontWeight.SemiBold)
            Text("Target: ${target?.product}\n${target?.serial}",fontSize=12.sp)
            if(!sameTarget)Text("The USB connection changed. Cancel and choose the image again after connecting your target.",color=MaterialTheme.colorScheme.error,fontSize=12.sp)
            if(!boot){
                OutlinedTextField(partition,{partition=it},Modifier.fillMaxWidth(),label={Text("Exact destination partition")},singleLine=true,enabled=sameTarget&&!model.busy,supportingText={Text("For example dtbo, dtbo_a, dtbo_b, or boot_a")})
                if(suggested.isBlank())Text("Choose the partition required by your image's installation instructions.",fontSize=12.sp)
                Text("Common partitions",fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
                    listOf("dtbo","boot","init_boot","vendor_boot","recovery","vbmeta","super","system","vendor").forEach{name->FilterChip(selected=base==name,onClick={partition=name},enabled=sameTarget&&!model.busy,label={Text(name,fontSize=11.sp)})}
                }
                Text("Exact name / slot",fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    listOf("" to "Unsuffixed","a" to "Slot A","b" to "Slot B").forEach{(slot,label)->FilterChip(selected=valid&&partition==(base+if(slot.isBlank())""else "_$slot"),onClick={try{partition=FastbootImageSelection.selectSlot(partition,slot)}catch(e:java.io.IOException){model.notice=e.message}},enabled=sameTarget&&!model.busy&&valid&&(slot.isBlank()||base.length<=126),label={Text(label,fontSize=11.sp)})}
                }
                val current=target?.variables?.get("current-slot")?.takeIf{it=="a"||it=="b"}
                if(current!=null)Text("Active slot: ${current.uppercase()}. Select the exact partition required by your ROM; the active slot is not selected automatically.",fontSize=11.sp,lineHeight=17.sp)
                OutlinedButton(onClick={model.inspectFastbootPartition(partition)},enabled=sameTarget&&!model.busy&&valid,modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.Search,null,Modifier.size(18.dp));Spacer(Modifier.width(7.dp));Text("Check destination partition")}
                model.fastbootPartition?.takeIf{it.name==partition&&sameTarget}?.let{result->
                    Text("Reported size: ${result.sizeBytes?.let{ADBoxViewModel.formatBytes(it)}?:"Unavailable"}\nType: ${result.variables["partition-type:$partition"]} · Slotted: ${result.variables["has-slot:${result.baseName}"]}",fontSize=12.sp,lineHeight=19.sp)
                    Text("Read: ${model.fastbootPartitionTime}",fontSize=10.sp)
                }
                if(model.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("This overwrites ${partition.ifBlank{"the chosen partition"}}. Use an image compatible with this target. Only the exact named partition is written; keep USB connected until it finishes.",fontSize=12.sp,lineHeight=19.sp)
            }else Text("Send a compatible raw boot image to device memory for a one-time boot. Images such as dtbo.img must be flashed to their partition instead.",fontSize=12.sp,lineHeight=19.sp)
        }
    },confirmButton={Button(onClick={onDismiss();model.flashFastboot(uri,partition,boot,expectedToken)},enabled=sameTarget&&!model.busy&&(boot||valid)){Text(if(boot)"Boot image"else "Flash ${partition.ifBlank{"image"}}")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}

@Composable internal fun FastbootCommandReview(command:String,device:FastbootInfo?,onDismiss:()->Unit,enabled:Boolean=true,warning:String?=null,onConfirm:()->Unit){
    val lockChange=command.startsWith("flashing unlock")||command.startsWith("flashing lock")||command.startsWith("oem unlock")||command.startsWith("oem lock")
    val locking=lockChange&&!command.contains("unlock")
    val confirmation=if(locking)"LOCK"else "UNLOCK"
    var typed by remember(command){mutableStateOf("")}
    var accepted by remember(command){mutableStateOf(false)}
    val detail=when{
        command.startsWith("erase:")->"This erases partition ${command.removePrefix("erase:")}. Its contents will be lost."
        lockChange->"Changing the bootloader lock state can erase all device data. Back up your files first and follow any confirmation shown on the target device."+if(locking)" Locking with a custom ROM or incompatible images can prevent the device from booting. Restore compatible manufacturer-signed firmware before locking unless your ROM explicitly supports it."else ""
        command.startsWith("set_active:")->"Select slot ${command.substringAfter(':').uppercase()} for the next boot. The slot must contain compatible, complete bootable images."
        command=="continue"||command.startsWith("reboot")->"The device will change boot mode and its USB connection may close. Some bootloaders may not support this destination."
        else->"Send this command to the connected bootloader. It may change the device or its data."
    }
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(lockChange)"Review bootloader change"else "Send this command?")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Text(command,fontFamily=FontFamily.Monospace,fontWeight=FontWeight.SemiBold)
        Text("Target: ${device?.product}\n${device?.serial}",fontSize=12.sp)
        Text(detail,fontSize=12.sp,lineHeight=19.sp)
        warning?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp,lineHeight=19.sp)}
        if(lockChange){
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(accepted,{accepted=it});Text("I understand the data loss${if(locking)" and boot failure"else ""} risk.",fontSize=12.sp)}
            OutlinedTextField(typed,{typed=it},Modifier.fillMaxWidth(),label={Text("Type $confirmation to confirm")},singleLine=true)
        }
    }},confirmButton={Button(onClick=onConfirm,enabled=enabled&&device!=null&&(!lockChange||(accepted&&typed==confirmation))){Text("Send command")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}
@Composable private fun EraseReview(device:FastbootInfo?,onDismiss:()->Unit,onSelect:(String)->Unit){
    var partition by rememberSaveable{mutableStateOf("")}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Choose a partition to erase")},text={Column(verticalArrangement=Arrangement.spacedBy(13.dp)){Text("Target: ${device?.product}",fontSize=12.sp);OutlinedTextField(partition,{partition=it},Modifier.fillMaxWidth(),label={Text("Exact partition name")},singleLine=true);Text("You’ll review the erase command before anything changes.",fontSize=12.sp)}},confirmButton={TextButton(onClick={onSelect(partition)},enabled=partition.matches(Regex("[A-Za-z0-9_.-]{1,128}"))){Text("Review erase")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}
