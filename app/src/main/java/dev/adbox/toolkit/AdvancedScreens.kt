@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.adbox.toolkit

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.input.key.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.*

@Composable internal fun ConnectionsBar(model:ADBoxViewModel,onUsb:()->Unit) {
    val connected=model.device!=null||model.fastbootDevice!=null
    var menu by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp),color=if(connected)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,shape=RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(start=14.dp,end=6.dp,top=4.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Usb,null,Modifier.size(22.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(model.device?.model?:model.fastbootDevice?.product?:"No USB target",style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(if(connected)"${targetMode(model)} · USB cable"else "Connect an OTG/data cable",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            if(!connected) TextButton(onClick=onUsb,enabled=!model.busy){Text("Connect")}
            else Box {
                IconButton(onClick={menu=true},enabled=!model.busy){Icon(Icons.Outlined.MoreVert,"USB target options")}
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                    model.connections.forEach { connection->DropdownMenuItem(text={Text("${if(model.activeConnectionId==connection.id)"✓ "else ""}${connection.label} · ${connection.transport}")},onClick={menu=false;model.selectConnection(connection.id)},enabled=!model.busy) }
                    if(model.connections.isNotEmpty())HorizontalDivider()
                    DropdownMenuItem(text={Text("Connect another target")},leadingIcon={Icon(Icons.Outlined.Add,null)},onClick={menu=false;onUsb()})
                    DropdownMenuItem(text={Text("Disconnect selected target")},leadingIcon={Icon(Icons.Outlined.Close,null)},onClick={menu=false;model.disconnect()})
                }
            }
            if(!connected&&model.connections.isNotEmpty()) Box {
                IconButton(onClick={menu=true},enabled=!model.busy){Icon(Icons.Outlined.SwapHoriz,"Choose an open connection")}
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                    model.connections.forEach { connection->DropdownMenuItem(text={Text(connection.label)},onClick={menu=false;model.selectConnection(connection.id)},enabled=!model.busy) }
                }
            }
        }
    }
}
@Composable internal fun TerminalScreen(model:ADBoxViewModel){
    var input by rememberSaveable{mutableStateOf("")}
    val vertical=rememberScrollState();val horizontal=rememberScrollState()
    LaunchedEffect(model.terminalText){vertical.scrollTo(vertical.maxValue)}
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        ToolkitHeading("Terminal", "Interactive shell · 80 × 24")
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={if(model.terminalRunning)model.stopTerminal()else model.startTerminal()},enabled=model.device!=null){Text(if(model.terminalRunning)"Close shell"else "Open shell")};Text("80 × 24 · VT100",Modifier.align(Alignment.CenterVertically),fontSize=11.sp)}
        Surface(Modifier.weight(1f).fillMaxWidth(),shape=RoundedCornerShape(22.dp),color=Color(0xFF201B2C)){
            SelectionContainer{Text(model.terminalText.ifBlank{"Open a shell to interact with your device."},Modifier.fillMaxSize().verticalScroll(vertical).horizontalScroll(horizontal).padding(14.dp),fontFamily=FontFamily.Monospace,fontSize=12.sp,lineHeight=17.sp,color=Color(0xFFDDF5B0))}
        }
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)){
            listOf("Tab" to "\t","Ctrl-C" to "\u0003","Ctrl-D" to "\u0004","Ctrl-Z" to "\u001a","Esc" to "\u001b","↑" to "\u001b[A","↓" to "\u001b[B","←" to "\u001b[D","→" to "\u001b[C").forEach{(label,key)->AssistChip(onClick={if(key=="\t"){model.terminalInput(input+key);input=""}else model.terminalInput(key)},enabled=model.terminalRunning,label={Text(label,fontSize=11.sp)})}
        }
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){
            OutlinedTextField(input,{input=it},Modifier.weight(1f).onPreviewKeyEvent{event->if(event.type!=KeyEventType.KeyDown||!model.terminalRunning)false else when{event.isCtrlPressed->{val code=event.nativeKeyEvent.keyCode;val c=code-android.view.KeyEvent.KEYCODE_A+1;if(c in 1..26){model.terminalInput(c.toChar().toString());true}else false};event.key==Key.Tab->{model.terminalInput(input+"\t");input="";true};event.key==Key.Enter->{model.terminalInput(input+"\r");input="";true};else->false}},label={Text("Shell input")},singleLine=true,enabled=model.terminalRunning,shape=RoundedCornerShape(18.dp),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={model.terminalInput(input+"\r");input=""}))
            FilledTonalIconButton(onClick={model.terminalInput(input+"\r");input=""},enabled=model.terminalRunning){Icon(Icons.Outlined.KeyboardReturn,"Send input and Enter")}
        }
    }
}
@Composable internal fun ScriptsScreen(model:ADBoxViewModel){
    var name by rememberSaveable{mutableStateOf("")};var body by rememberSaveable{mutableStateOf("")};var review by remember{mutableStateOf<ADBoxViewModel.SavedScript?>(null)}
    var reviewToken by remember { mutableIntStateOf(-1) }
    LaunchedEffect(model.connectionToken) { review=null }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(13.dp)){
        item{ToolkitHeading("Scripts", "Reusable commands for the selected ADB target")}
        item{Text("Run scripts as the target’s shell. Read the script before running; output opens in Console.",style=MaterialTheme.typography.bodySmall)}
        if(model.scripts.isEmpty())item{ToolkitEmptyMessage("Your scripts will appear here", "Create a named script below to reuse a command sequence.")}
        items(model.scripts,key={it.name}){script->Card{Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(script.name,fontWeight=FontWeight.Bold);Text(script.body.take(200),fontFamily=FontFamily.Monospace,fontSize=11.sp);Row{TextButton(onClick={name=script.name;body=script.body}){Text("Edit")};TextButton(onClick={reviewToken=model.connectionToken;review=script},enabled=model.device!=null&&model.device?.mode!="Sideload"&&!model.shellRunning){Text("Run")};TextButton(onClick={model.deleteScript(script.name)}){Text("Delete")}}}}}
        item{OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text("Script name")},singleLine=true)}
        item{OutlinedTextField(body,{body=it},Modifier.fillMaxWidth(),label={Text("Shell script")},minLines=5,maxLines=12,textStyle=LocalTextStyle.current.copy(fontFamily=FontFamily.Monospace,fontSize=12.sp))}
        item{Button(onClick={model.saveScript(name,body);name="";body=""},enabled=name.isNotBlank()&&body.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("Save script")}}
    }
    review?.let{script->AlertDialog(onDismissRequest={review=null},title={Text("Run ${script.name}?")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Text("Target: ${model.device?.model} · ${model.device?.serial}",fontSize=12.sp);Text(script.body,Modifier.heightIn(max=260.dp).verticalScroll(rememberScrollState()),fontFamily=FontFamily.Monospace,fontSize=12.sp)}},confirmButton={Button(onClick={review=null;model.runScript(script)},enabled=reviewToken==model.connectionToken&&model.device!=null&&!model.shellRunning){Text("Run script")}},dismissButton={TextButton(onClick={review=null}){Text("Cancel")}})}
}
@Composable internal fun MirrorScreen(model:ADBoxViewModel){
    var viewSize by remember{mutableStateOf(IntSize.Zero)}
    val bitmap=model.mirrorFrame
    val width=model.mirrorWidth;val height=model.mirrorHeight
    val video=model.mirrorVideo;val token=model.mirrorSessionToken
    DisposableEffect(model){onDispose{model.stopMirror()}}
    val hostView=androidx.compose.ui.platform.LocalView.current
    DisposableEffect(model.mirrorRunning,hostView){val previous=hostView.keepScreenOn;if(model.mirrorRunning)hostView.keepScreenOn=true;onDispose{hostView.keepScreenOn=previous}}
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Text("Screen control",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);IconButton(onClick={model.mirrorFullscreen=!model.mirrorFullscreen}){Icon(if(model.mirrorFullscreen)Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,if(model.mirrorFullscreen)"Exit full screen"else "Full screen")};TextButton(onClick={if(model.mirrorRunning)model.stopMirror()else model.startMirror()},enabled=model.device!=null&&model.device?.mode!="Sideload"&&!model.busy){Text(if(model.mirrorRunning)"Pause"else "Start")}}
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            FilterChip(selected=video,onClick={model.changeMirrorVideo(true)},enabled=!model.mirrorRunning,label={Text("USB video")})
            FilterChip(selected=!video,onClick={model.changeMirrorVideo(false)},enabled=!model.mirrorRunning,label={Text("Compatibility")})
            if(video)ScreenQuality.entries.forEach{quality->FilterChip(selected=model.mirrorQuality==quality,onClick={model.changeMirrorQuality(quality)},enabled=!model.mirrorRunning,label={Text(quality.label)})}
        }
        if(!model.mirrorFullscreen)Text(if(video)"Live taps, swipes and long presses over USB. Choose Fast while paused to reduce video bandwidth."else "Screenshot mode for targets that cannot start video. Updates are slower than USB video.",fontSize=11.sp,lineHeight=16.sp)
        Text("${model.mirrorStatus}${if(model.mirrorReady)" · ${model.mirrorFps} fps · ${width}×$height"else ""}",fontSize=11.sp,lineHeight=16.sp,maxLines=3)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color.Black,RoundedCornerShape(20.dp)).onSizeChanged{viewSize=it}.pointerInput(width,height,viewSize,model.mirrorReady,video,token){
            if(width<1||height<1||!model.mirrorReady||viewSize.width<1||viewSize.height<1)return@pointerInput
            val scale=minOf(viewSize.width.toFloat()/width,viewSize.height.toFloat()/height)
            val left=(viewSize.width-width*scale)/2;val top=(viewSize.height-height*scale)/2
            fun point(offset:Offset,clamp:Boolean=false):Pair<Int,Int>?{
                val x=((offset.x-left)/scale).toInt();val y=((offset.y-top)/scale).toInt()
                return if(clamp)x.coerceIn(0,width-1) to y.coerceIn(0,height-1) else if(offset.x>=left&&offset.y>=top&&offset.x<left+width*scale&&offset.y<top+height*scale)x.coerceIn(0,width-1) to y.coerceIn(0,height-1)else null
            }
            awaitEachGesture{
                val down=awaitFirstDown();val first=point(down.position)
                if(first!=null){
                    down.consume();var last:Pair<Int,Int> = first;var time=down.uptimeMillis;var lastMove=down.uptimeMillis;var pressed=true
                    if(video)model.screenTouch(0,first.first,first.second,width,height,token)
                    try{
                        do{
                            val change=awaitPointerEvent().changes.firstOrNull{it.id==down.id}?:break
                            last=point(change.position,true)!!;time=change.uptimeMillis;change.consume();pressed=change.pressed
                            if(video){
                                if(!pressed)model.screenTouch(1,last.first,last.second,width,height,token)
                                else if(time-lastMove>=16){model.screenTouch(2,last.first,last.second,width,height,token);lastMove=time}
                            }
                        }while(pressed)
                        if(!video&&!pressed)model.sendTouch(first.first,first.second,last.first,last.second,(time-down.uptimeMillis).toInt())
                    }finally{if(video&&pressed){model.screenTouch(3,last.first,last.second,width,height,token);model.screenTouch(1,last.first,last.second,width,height,token)}}
                }
            }
        },contentAlignment=Alignment.Center){
            if(video){
                val ratio=if(width>0&&height>0)width.toFloat()/height else 9f/16
                val w=minOf(maxWidth,maxHeight*ratio);val h=w/ratio
                androidx.compose.ui.viewinterop.AndroidView(factory={context->android.view.SurfaceView(context).apply{
                    holder.addCallback(object:android.view.SurfaceHolder.Callback{
                        override fun surfaceCreated(holder:android.view.SurfaceHolder){model.attachMirrorSurface(holder.surface)}
                        override fun surfaceChanged(holder:android.view.SurfaceHolder,format:Int,width:Int,height:Int){model.attachMirrorSurface(holder.surface)}
                        override fun surfaceDestroyed(holder:android.view.SurfaceHolder){model.detachMirrorSurface(holder.surface)}
                    })
                }},modifier=Modifier.size(w,h))
            }else if(bitmap!=null)Image(bitmap.asImageBitmap(),"Live target device screen",Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
            if(!model.mirrorRunning)Text("Start to view your device",color=Color.White,fontSize=12.sp)
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
            listOf(Triple("Back",4,Icons.Outlined.ArrowBack),Triple("Home",3,Icons.Outlined.Home),Triple("Recent apps",187,Icons.Outlined.FilterNone),Triple("Power",26,Icons.Outlined.PowerSettingsNew)).forEach { (name,key,icon)->
                FilledTonalIconButton(onClick={model.sendKey(key)},enabled=model.device!=null&&model.device?.mode!="Sideload"&&!model.busy){Icon(icon,name)}
            }
        }

    }
}
@Composable internal fun SideloadScreen(model:ADBoxViewModel,onConnect:()->Unit,onRecovery:()->Unit) {
    var update by remember { mutableStateOf<Uri?>(null) }
    var pickerToken by rememberSaveable { mutableIntStateOf(-1) }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri->
        if(uri!=null) {
            if(pickerToken==model.connectionToken&&model.device?.mode in listOf("Recovery","Sideload"))update=uri
            else model.notice="The USB target changed. Reconnect recovery and choose the ZIP again."
        }
    }
    val scroll=rememberScrollState()
    LaunchedEffect(model.connectionToken) { update=null }
    LaunchedEffect(model.sideload.running) { if(model.sideload.running)scroll.animateScrollTo(0) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        ToolkitHeading("ADB sideload", "Install a ROM or update ZIP through recovery")
        if(model.sideload.phase!=SideloadPhase.Idle)SideloadProgressCard(model.sideload)
        if(!model.sideload.running) {
            ToolkitIntro(Icons.Outlined.SystemUpdate,"Ready for recovery","Follow your ROM maintainer’s installation steps. On the target, enter recovery and select Apply update from ADB before starting the transfer.") {
                when {
                    model.device?.mode in listOf("Recovery","Sideload")->Button(onClick={pickerToken=model.connectionToken;picker.launch(arrayOf("*/*"))},enabled=!model.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Icon(Icons.Outlined.FolderOpen,null);Spacer(Modifier.width(8.dp));Text("Choose ROM / update ZIP")}
                    model.device?.mode=="Android"->Button(onClick=onRecovery,enabled=!model.busy,modifier=Modifier.fillMaxWidth()){Text("Reboot target into recovery")}
                    else->Button(onClick=onConnect,enabled=!model.busy,modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.Usb,null);Spacer(Modifier.width(8.dp));Text("Connect recovery USB")}
                }
            }
            ToolkitCard("Before you start") {
                Text("1. Select Apply update from ADB on the target.\n2. Connect the USB OTG/data cable.\n3. Choose the compatible ROM or update ZIP.",style=MaterialTheme.typography.bodyMedium)
                Text("${model.device?.mode?:"No recovery target"} · ${if(model.queuedUsb)"Queued USB"else "Legacy USB"}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(model.device!=null)TextButton(onClick=onConnect,enabled=!model.busy){Text("Choose another USB target")}
            }
        }
        Text("Keep the cable connected during transfer. Recovery verifies and installs the package. Check its screen for the installation result.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    update?.let { uri->AlertDialog(onDismissRequest={update=null},title={Text("Sideload this ZIP?")},text={Text("${model.displayName(uri)}\n\nTarget: ${model.device?.model} · ${model.device?.serial}\n\nKeep USB connected and follow the recovery screen.")},confirmButton={Button(onClick={update=null;model.adbSideload(uri)},enabled=!model.busy&&pickerToken==model.connectionToken&&model.device?.mode in listOf("Recovery","Sideload")){Text("Start sideload")}},dismissButton={TextButton(onClick={update=null}){Text("Cancel")}}) }
}
@Composable private fun SideloadProgressCard(progress:SideloadStatus){
    val clipboard=LocalClipboardManager.current
    val failed=progress.phase==SideloadPhase.Failed
    var details by rememberSaveable(progress.name) { mutableStateOf(false) }
    val title=when(progress.phase){
        SideloadPhase.Preparing->"Preparing your update"
        SideloadPhase.Serving->"Recovery is reading the ZIP"
        SideloadPhase.Waiting->"Waiting for recovery"
        SideloadPhase.Complete->"Transfer complete"
        SideloadPhase.Failed->"Sideload stopped"
        SideloadPhase.Idle->"ADB sideload"
    }
    Card(shape=RoundedCornerShape(28.dp),colors=CardDefaults.cardColors(containerColor=if(failed)MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer)){
        Column(Modifier.fillMaxWidth().padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                if(progress.running)CircularProgressIndicator(Modifier.size(25.dp),strokeWidth=3.dp)
                else Icon(if(failed)Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,null,Modifier.size(28.dp))
                Text(title,fontSize=19.sp,fontWeight=FontWeight.Bold)
            }
            Text(progress.name,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)
            if(progress.target.isNotBlank())Text("Target: ${progress.target}",fontSize=11.sp,lineHeight=17.sp)
            when(progress.phase){
                SideloadPhase.Preparing,SideloadPhase.Waiting->LinearProgressIndicator(Modifier.fillMaxWidth())
                SideloadPhase.Serving->LinearProgressIndicator(progress={progress.coverage},modifier=Modifier.fillMaxWidth())
                else->Unit
            }
            if(progress.totalBytes>0){
                Text("${progress.percent}% of ZIP read",fontSize=24.sp,fontWeight=FontWeight.Bold)
                Text("${ADBoxViewModel.formatBytes(progress.uniqueBytes)} / ${ADBoxViewModel.formatBytes(progress.totalBytes)} unique data",fontSize=12.sp)
                Text("${ADBoxViewModel.formatBytes(progress.transferredBytes)} sent over USB · includes repeated block reads",fontSize=11.sp,lineHeight=17.sp)
            }
            Text(progress.message,fontSize=12.sp,lineHeight=19.sp,maxLines=if(details)Int.MAX_VALUE else 4,overflow=TextOverflow.Ellipsis)
            if(progress.message.length>180)TextButton(onClick={details=!details}){Text(if(details)"Hide details"else "Show transfer details")}
            if(failed)TextButton(onClick={clipboard.setText(AnnotatedString("Target: ${progress.target}\nZIP: ${progress.name}\n${progress.message}"))}){Text("Copy transfer details")}
            if(progress.running&&progress.totalBytes>0)Text("ZIP coverage measures data read, not installation progress.",fontSize=11.sp,lineHeight=17.sp)
        }
    }
}
@Composable internal fun BackupScreen(model:ADBoxViewModel){
    var partition by rememberSaveable{mutableStateOf(false)};var root by rememberSaveable{mutableStateOf(false)}
    var path by rememberSaveable{mutableStateOf("/sdcard")};var restore by remember{mutableStateOf<Uri?>(null)}
    var fastbootPartition by rememberSaveable{mutableStateOf("boot_a")}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->if(uri!=null){if(model.fastbootDevice!=null)model.fastbootBackup(fastbootPartition,uri)else model.backupAdb(path,partition,root,uri)}}
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){restore=it}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(15.dp)){
        ToolkitHeading("Backup & restore", "Files, raw partitions and bootloader readback")
        Text("Target: ${model.fastbootDevice?.product?:model.device?.model?:"No device"}",fontSize=12.sp)
        if(model.fastbootDevice!=null){
            Text("Fastboot partition readback uses fetch. Support varies by bootloader; some devices expose only selected partitions or require an unlocked bootloader.",fontSize=12.sp,lineHeight=19.sp)
            OutlinedTextField(fastbootPartition,{fastbootPartition=it},Modifier.fillMaxWidth(),label={Text("Exact partition name")},singleLine=true)
            Button(onClick={save.launch("$fastbootPartition.img")},enabled=!model.busy&&fastbootPartition.matches(Regex("[A-Za-z0-9_.-]{1,128}")),modifier=Modifier.fillMaxWidth()){Text("Save raw partition image")}
            Text("Restore this backup through Fastboot’s Flash image action, reviewing its exact partition.",fontSize=12.sp)
        }else{
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(selected=!partition,onClick={partition=false;path="/sdcard"},label={Text("Files · TAR")});FilterChip(selected=partition,onClick={partition=true;path="/dev/block/by-name/boot_a"},label={Text("Partition · IMG")})}
            OutlinedTextField(path,{path=it},Modifier.fillMaxWidth(),label={Text(if(partition)"Block-device path"else "Backup folder / restore destination")},supportingText={Text(if(partition)"For example /dev/block/by-name/boot_a"else "Backup saves folder contents; restore extracts into this destination")},singleLine=true)
            Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Use root on target",fontWeight=FontWeight.SemiBold);Text("Approve su on the target. Recovery may already run ADB as root.",fontSize=11.sp)};Switch(root,{root=it})}
            Text(if(partition)"Partition access requires root or recovery permissions. Use a raw image for restore."else "Shared files are accessible through ADB. App-private data requires target root/recovery access. TAR restore accepts regular files and directories; links and extended metadata are rejected.",fontSize=12.sp,lineHeight=19.sp)
            Button(onClick={save.launch(if(partition)"adbox-partition.img"else "adbox-files.tar")},enabled=model.device!=null&&model.device?.mode!="Sideload"&&!model.busy,modifier=Modifier.fillMaxWidth()){Text("Save backup")}
            OutlinedButton(onClick={open.launch(arrayOf("*/*"))},enabled=model.device!=null&&model.device?.mode!="Sideload"&&!model.busy,modifier=Modifier.fillMaxWidth()){Text("Choose backup to restore")}
        }
        if(model.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(model.advancedProgress,fontSize=12.sp)
    }
    restore?.let{uri->AlertDialog(onDismissRequest={restore=null},title={Text("Restore this backup?")},text={Text("${model.displayName(uri)}\n\nTarget: ${model.device?.model}\nDestination: $path\n\n${if(partition)"This overwrites the selected partition."else "Existing files with matching names may be overwritten."} Keep USB connected until it finishes.")},confirmButton={Button(onClick={restore=null;model.restoreAdb(path,partition,root,uri)}){Text("Restore backup")}},dismissButton={TextButton(onClick={restore=null}){Text("Cancel")}})}
}
@Composable internal fun FirmwareTools(model:ADBoxViewModel){
    var plan by remember{mutableStateOf(false)};var confirm by remember{mutableStateOf(false)};var logical by remember{mutableStateOf(false)}
    val zip=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){model.prepareFirmware(listOf(uri),true);plan=true}}
    val batch=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->if(uris.isNotEmpty()){model.prepareFirmware(uris,false);plan=true}}
    Card{Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("Firmware & partitions",fontSize=20.sp,fontWeight=FontWeight.Bold)
        Text("Review an image bundle or several images as one flash plan. Embedded desktop scripts are not executed.",fontSize=11.sp,lineHeight=17.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){FilledTonalButton(onClick={zip.launch(arrayOf("*/*"))},enabled=!model.busy){Text("Firmware ZIP",fontSize=11.sp)};OutlinedButton(onClick={batch.launch(arrayOf("*/*"))},enabled=!model.busy){Text("Image batch",fontSize=11.sp)}}
        Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){TextButton(onClick={logical=true},enabled=!model.busy){Text("Logical partitions",fontSize=11.sp)};TextButton(onClick={model.screen=Screen.Backup}){Text("Partition backup",fontSize=11.sp)}}
        if(model.firmware.isNotEmpty())TextButton(onClick={plan=true}){Text("Review prepared flash plan")}
        if(model.advancedProgress.isNotBlank())Text(model.advancedProgress,fontSize=11.sp)
    }}
    if(plan)AlertDialog(onDismissRequest={if(!model.busy)plan=false},title={Text("Your firmware plan")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Text("Target: ${model.fastbootDevice?.product} · ${model.fastbootDevice?.serial}",fontSize=12.sp);if(model.busy)LinearProgressIndicator(Modifier.fillMaxWidth());LazyColumn(Modifier.heightIn(max=350.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){items(model.firmware.size){index->val item=model.firmware[index];Column{Text("${item.name} · ${ADBoxViewModel.formatBytes(item.file.length())}",fontSize=11.sp);OutlinedTextField(item.partition,{model.editFirmware(index,it)},Modifier.fillMaxWidth(),label={Text("Exact partition")},singleLine=true,enabled=!model.busy);TextButton(onClick={model.removeFirmware(index)},enabled=!model.busy){Text("Exclude image")}}}}}},confirmButton={Button(onClick={confirm=true},enabled=model.firmware.isNotEmpty()&&!model.busy&&model.firmware.all{it.partition.matches(Regex("[A-Za-z0-9_.-]{1,128}"))}){Text("Review flash")}},dismissButton={TextButton(onClick={plan=false},enabled=!model.busy){Text("Close")}})
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Flash ${model.firmware.size} images?")},text={Text("Target: ${model.fastbootDevice?.product} · ${model.fastbootDevice?.serial}\n\n"+model.firmware.joinToString("\n"){"${it.name} → ${it.partition}"}+"\n\nThese partitions will be overwritten in order. A failure stops the plan; earlier writes remain. Use images for this exact device and keep USB connected.",Modifier.heightIn(max=350.dp).verticalScroll(rememberScrollState()),fontSize=12.sp)},confirmButton={Button(onClick={confirm=false;plan=false;model.flashFirmware()}){Text("Flash plan")}},dismissButton={TextButton(onClick={confirm=false}){Text("Cancel")}})
    if(logical)LogicalPartitionTools(model){logical=false}
}
@Composable private fun LogicalPartitionTools(model:ADBoxViewModel,onDismiss:()->Unit){
    val target=remember{model.fastbootDevice};val token=remember{model.fastbootConnectionToken}
    var action by rememberSaveable{mutableStateOf("create")};var name by rememberSaveable{mutableStateOf("")};var size by rememberSaveable{mutableStateOf("")};var review by remember{mutableStateOf<String?>(null)}
    val megabytes=size.toLongOrNull()?.takeIf{it>0&&it<Long.MAX_VALUE/1048576}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Logical partition tools")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){Text("These operations normally require fastbootd and an unlocked device. Deleting a logical partition removes its data.",fontSize=12.sp);Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)){listOf("create","resize","delete").forEach{FilterChip(selected=action==it,onClick={action=it},label={Text(it)})}};OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text("Exact logical partition name")},singleLine=true);if(action!="delete")OutlinedTextField(size,{size=it.filter(Char::isDigit)},Modifier.fillMaxWidth(),label={Text("Size in MiB")},singleLine=true)}},confirmButton={Button(onClick={review="$action-logical-partition:$name"+if(action=="delete")""else ":${megabytes!!*1048576}"},enabled=name.matches(Regex("[A-Za-z0-9_.-]{1,128}"))&&(action=="delete"||megabytes!=null)){Text("Review command")}},dismissButton={TextButton(onClick=onDismiss){Text("Close")}})
    review?.let{command->FastbootCommandReview(command,target,{review=null},enabled=!model.busy&&model.fastbootDevice!=null&&token==model.fastbootConnectionToken){review=null;onDismiss();model.runFastboot(command,token)}}
}

@Composable internal fun FileActionDialog(model:ADBoxViewModel,entry:SyncTransfer.Entry,onDismiss:()->Unit,onDownload:()->Unit){
    var name by rememberSaveable{mutableStateOf(entry.name)};var erase by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,title={Text(entry.name)},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("${model.remotePath.trimEnd('/')}/${entry.name}",fontSize=11.sp,fontFamily=FontFamily.Monospace)
        if(!entry.directory)FilledTonalButton(onClick=onDownload,modifier=Modifier.fillMaxWidth()){Text("Download file")}
        OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text("File name")},singleLine=true)
        OutlinedButton(onClick={onDismiss();model.renameFile(entry.name,name)},enabled=name.isNotBlank()&&name!=entry.name,modifier=Modifier.fillMaxWidth()){Text("Rename")}
        OutlinedButton(onClick={erase=true},modifier=Modifier.fillMaxWidth()){Text(if(entry.directory)"Delete folder and contents"else "Delete file",color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(onClick=onDismiss){Text("Close")}})
    if(erase)AlertDialog(onDismissRequest={erase=false},title={Text("Delete ${entry.name}?")},text={Text("Target: ${model.device?.model}\n${model.remotePath.trimEnd('/')}/${entry.name}\n\n${if(entry.directory)"This removes the folder and all its contents."else "This removes the selected file."}")},confirmButton={Button(onClick={erase=false;onDismiss();model.deleteFile(entry.name)}){Text("Delete")}},dismissButton={TextButton(onClick={erase=false}){Text("Cancel")}})
}

@Composable internal fun OpenSourceDialog(onDismiss:()->Unit){
    val context=androidx.compose.ui.platform.LocalContext.current
    val text by produceState("Loading licenses…",context){value=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){"AndroidX — The Android Open Source Project. Apache License 2.0. https://github.com/androidx/androidx\nKotlin and kotlinx.coroutines — JetBrains and contributors. Apache License 2.0. https://github.com/JetBrains/kotlin\nscrcpy server 3.3.4 — Genymobile, Romain Vimont and contributors. Apache License 2.0. https://github.com/Genymobile/scrcpy/tree/v3.3.4\n\n" + context.assets.list("licenses").orEmpty().sorted().joinToString("\n\n"){name->name+"\n\n"+context.assets.open("licenses/$name").bufferedReader().use{it.readText()}}}}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Open-source licenses")},text={SelectionContainer{Text(text,Modifier.heightIn(max=450.dp).verticalScroll(rememberScrollState()),fontSize=10.sp,lineHeight=15.sp)}},confirmButton={TextButton(onClick=onDismiss){Text("Close")}})
}
