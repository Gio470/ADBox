@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.adbox.toolkit

import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { val model = (application as ADBoxApplication).model; ADBoxTheme(model) { ADBoxApp(model) } }
    }
    override fun onStart() { super.onStart(); (application as ADBoxApplication).model.setUiVisible(true) }
    override fun onStop() { (application as ADBoxApplication).model.setUiVisible(false); super.onStop() }
}
private fun screenIcon(screen:Screen):ImageVector=when(screen){Screen.Device->Icons.Outlined.Terminal;Screen.Tools->Icons.Outlined.Build;Screen.Shell->Icons.Outlined.Terminal;Screen.Files->Icons.Outlined.Folder;Screen.Apps->Icons.Outlined.Apps;Screen.Logs->Icons.Outlined.Subject;Screen.Fastboot->Icons.Outlined.DeveloperBoard;Screen.Terminal->Icons.Outlined.Terminal;Screen.Mirror->Icons.Outlined.Cast;Screen.Backup->Icons.Outlined.Backup;Screen.Sideload->Icons.Outlined.SystemUpdate;Screen.Scripts->Icons.Outlined.Code}
@Composable private fun ADBoxApp(model:ADBoxViewModel) {
    val snack=remember{SnackbarHostState()}
    val fullscreen=model.screen==Screen.Mirror&&model.mirrorFullscreen&&model.device!=null
    val tab=toolkitTab(model.screen)
    val parent=tab.root
    val states=rememberSaveableStateHolder()
    var settings by remember{mutableStateOf(false)}
    var usb by remember{mutableStateOf(false)}
    var reboot by remember{mutableStateOf(false)}
    val onConnect:()->Unit={model.refreshUsb();usb=true}
    var selectedPackage by remember{mutableStateOf<String?>(null)}
    var uninstall by remember{mutableStateOf<String?>(null)}
    var installUri by remember{mutableStateOf<Uri?>(null)}
    var fastbootUri by remember{mutableStateOf<Uri?>(null)}
    var bootImage by rememberSaveable{mutableStateOf(false)}
    var imageTargetToken by rememberSaveable{mutableIntStateOf(-1)}
    var selectedFile by remember{mutableStateOf<SyncTransfer.Entry?>(null)}
    var exportPackageName by rememberSaveable{mutableStateOf("")}
    var rebootMode by remember{mutableStateOf("")}
    var downloadPath by rememberSaveable{mutableStateOf("")}
    val pull=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->if(uri!=null)model.pull(downloadPath,uri)}
    val push=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)model.push(uri)}
    val install=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->installUri=uri}
    val exportApk=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->if(uri!=null)model.exportPackage(exportPackageName,uri)}
    val exportRecorded=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri->if(uri!=null)model.exportRecording(uri)}
    val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
    val hostContext=LocalContext.current
    val hostView=androidx.compose.ui.platform.LocalView.current
    DisposableEffect(model.sideload.running,hostView){val previous=hostView.keepScreenOn;if(model.sideload.running)hostView.keepScreenOn=true;onDispose{hostView.keepScreenOn=previous}}
    LaunchedEffect(Unit){if(Build.VERSION.SDK_INT>=33&&androidx.core.content.ContextCompat.checkSelfPermission(hostContext,android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)}
    val image=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){if(model.fastbootDevice!=null&&imageTargetToken==model.fastbootConnectionToken)fastbootUri=uri else model.notice="The USB target changed. Connect it and choose the image again."}
    }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri->if(uri!=null)model.saveBytes(uri,model.logs.toByteArray())}
    val saveScreenshot=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")){uri->if(uri!=null)model.screenshot?.let{model.saveBytes(uri,it)}}
    LaunchedEffect(model.notice){val text=model.notice;if(text!=null){snack.showSnackbar(text);if(model.notice==text)model.notice=null}}
    LaunchedEffect(model.screen, model.activeConnectionId) { model.ensurePageData(model.screen) }
    BackHandler(enabled=model.fastbootBusy){model.notice="Wait for the Fastboot operation to finish before closing ADBox."}
    BackHandler(enabled=model.screen!=parent&&!model.fastbootBusy){model.screen=parent}
    BackHandler(enabled=fullscreen){model.mirrorFullscreen=false}
    LaunchedEffect(model.screen){if(model.screen!=Screen.Mirror)model.mirrorFullscreen=false}
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 720.dp && !fullscreen
        Row(Modifier.fillMaxSize()) {
            if (useRail) NavigationRail(Modifier.fillMaxHeight(), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Spacer(Modifier.weight(1f))
                ToolkitTab.entries.forEach { destination ->
                    NavigationRailItem(selected = tab == destination, onClick = { model.screen = destination.root }, enabled = !model.fastbootBusy,
                        icon = { Icon(toolkitIcon(destination), null) }, label = { Text(destination.title) })
                    Spacer(Modifier.height(12.dp))
                }
                Spacer(Modifier.weight(1f))
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                topBar = { if(!fullscreen) TopAppBar(
                    navigationIcon = {
                        if (model.screen != parent) IconButton(onClick = { model.screen = parent }, enabled = !model.fastbootBusy) { Icon(Icons.Outlined.ArrowBack, "Back to ${tab.title}") }
                        else Surface(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(12.dp)) {
                            Icon(painterResource(R.drawable.ic_adbox_mark), "ADBox", Modifier.padding(8.dp).size(28.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    },
                    title = { Column { Text("ADBox", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("USB device toolkit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                    actions = { IconButton(onClick = { settings = true }) { Icon(Icons.Outlined.Settings, "Settings") } }
                ) },
                bottomBar = {
                    if (!useRail && !fullscreen) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        ToolkitTab.entries.forEach { destination -> NavigationBarItem(
                            selected = tab == destination, onClick = { model.screen = destination.root }, enabled = !model.fastbootBusy,
                            icon = { Icon(toolkitIcon(destination), null) }, label = { Text(destination.title) }
                        ) }
                    }
                },
                snackbarHost = { SnackbarHost(snack) }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                    if(!fullscreen)ConnectionsBar(model, onConnect)
                    if (tab == ToolkitTab.Adb) AdbSectionBar(model)
                    if (model.busy && !fullscreen) {
                        Text(model.status, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                    }
                    // A single active composition avoids overlapping Surface/video lifetimes.
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = 1040.dp).fillMaxSize()) {
                            val screen = model.screen
                            states.SaveableStateProvider(screen.name) {
                                when {
                                    screen==Screen.Fastboot->FastbootScreen(model,onConnect,{rebootMode="bootloader";reboot=true}){boot->bootImage=boot;imageTargetToken=model.fastbootConnectionToken;image.launch(arrayOf("*/*"))}
                                    screen==Screen.Sideload->SideloadScreen(model,onConnect){rebootMode="recovery";reboot=true}
                                    screen==Screen.Tools->ToolkitTools(model)
                                    screen==Screen.Backup->BackupScreen(model)
                                    screen==Screen.Scripts->ScriptsScreen(model)
                                    screen==Screen.Device->AdbWorkspace(model,onConnect){rebootMode="";reboot=true}
                                    model.device==null||model.device?.mode=="Sideload"->EmptyTool(screen,model,onConnect)
                                    else->when(screen){
                                        Screen.Shell->ShellScreen(model)
                                        Screen.Files->FilesScreen(model,{push.launch(arrayOf("*/*"))},{selectedFile=it}){entry->downloadPath="${model.remotePath.trimEnd('/')}/${entry.name}";pull.launch(entry.name)}
                                        Screen.Apps->AppsScreen(model,{install.launch(arrayOf("*/*"))}){selectedPackage=it}
                                        Screen.Logs->LogsScreen(model,{exportRecorded.launch("adbox-recorded-logcat.txt")}){export.launch("adbox-logcat.txt")}
                                        Screen.Terminal->TerminalScreen(model)
                                        Screen.Mirror->MirrorScreen(model)
                                        else->Unit
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if(settings)SettingsDialog(model){settings=false}
    if(usb)AlertDialog(onDismissRequest={usb=false},icon={Icon(Icons.Outlined.Usb,null)},title={Text("Connect with USB")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){Text("Use an OTG/data cable. For ADB, enable USB debugging and approve ADBox on the target. For Fastboot, start the target in bootloader or fastbootd mode.");if(model.usbTargets.isEmpty())Text("No ADB or Fastboot USB device found.",color=MaterialTheme.colorScheme.onSurfaceVariant);model.usbTargets.forEach{target->FilledTonalButton(onClick={usb=false;model.requestUsb(target.device)},enabled=!model.busy,modifier=Modifier.fillMaxWidth()){Icon(if(target.fastboot)Icons.Outlined.DeveloperBoard else Icons.Outlined.Usb,null);Spacer(Modifier.width(8.dp));Text("${target.name} · ${if(target.fastboot)"Fastboot"else "ADB"}")}}}},confirmButton={TextButton(onClick={model.refreshUsb()}){Text("Scan again")}},dismissButton={TextButton(onClick={usb=false}){Text("Close")}})
    if(reboot)AlertDialog(onDismissRequest={reboot=false},title={Text("Reboot ${model.device?.model}?")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Text("The ADB connection will close. Unsaved work on the device may be lost.");listOf("" to "Android","recovery" to "Recovery","bootloader" to "Bootloader / Fastboot","fastboot" to "Fastbootd").forEach{(mode,title)->FilterChip(selected=rebootMode==mode,onClick={rebootMode=mode},label={Text(title)})}}},confirmButton={TextButton(onClick={reboot=false;model.reboot(rebootMode)},enabled=!model.busy){Text("Reboot")}},dismissButton={TextButton(onClick={reboot=false}){Text("Cancel")}})
    fastbootUri?.let{uri->FastbootImageReview(model,uri,bootImage,imageTargetToken){fastbootUri=null}}
    selectedFile?.let{entry->FileActionDialog(model,entry,{selectedFile=null}){selectedFile=null;downloadPath="${model.remotePath.trimEnd('/')}/${entry.name}";pull.launch(entry.name)}}
    selectedPackage?.let {pkg->val clipboard=LocalClipboardManager.current;AlertDialog(onDismissRequest={selectedPackage=null},title={Text("App actions")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Text(pkg,fontFamily=FontFamily.Monospace,fontSize=12.sp);FilledTonalButton(onClick={selectedPackage=null;model.runShell("monkey -p ${ADBoxViewModel.quote(pkg)} -c android.intent.category.LAUNCHER 1")},modifier=Modifier.fillMaxWidth()){Text("Launch app")};OutlinedButton(onClick={clipboard.setText(AnnotatedString(pkg));selectedPackage=null},modifier=Modifier.fillMaxWidth()){Text("Copy package name")};OutlinedButton(onClick={selectedPackage=null;exportPackageName=pkg;exportApk.launch("$pkg.apks")},modifier=Modifier.fillMaxWidth()){Text("Export APK bundle")};OutlinedButton(onClick={selectedPackage=null;uninstall=pkg},modifier=Modifier.fillMaxWidth()){Text("Uninstall",color=MaterialTheme.colorScheme.error)}}},confirmButton={TextButton(onClick={selectedPackage=null}){Text("Done")}})}
    uninstall?.let {pkg->AlertDialog(onDismissRequest={uninstall=null},title={Text("Uninstall this app?")},text={Text("$pkg will be removed from ${model.device?.model}, including its app data.")},confirmButton={TextButton(onClick={uninstall=null;model.uninstall(pkg)}){Text("Uninstall")}},dismissButton={TextButton(onClick={uninstall=null}){Text("Cancel")}})}
    installUri?.let {uri->AlertDialog(onDismissRequest={installUri=null},title={Text("Install on your device?")},text={Text("Install ${model.displayName(uri)} on ${model.device?.model}. Existing app data is kept when a compatible update is installed. APK and APKS bundles are supported.")},confirmButton={TextButton(onClick={installUri=null;model.install(uri)}){Text("Install")}},dismissButton={TextButton(onClick={installUri=null}){Text("Cancel")}})}
    model.screenshot?.let { bytes->ScreenshotDialog(bytes,{model.screenshot=null}){saveScreenshot.launch("adbox-screenshot.png")} }

}
@Composable private fun EmptyTool(screen:Screen,model:ADBoxViewModel,onConnect:()->Unit){
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(30.dp),verticalArrangement=Arrangement.spacedBy(18.dp),horizontalAlignment=Alignment.CenterHorizontally){
        Spacer(Modifier.height(20.dp))
        Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(32.dp,16.dp,32.dp,16.dp)){Icon(screenIcon(screen),null,Modifier.padding(27.dp).size(42.dp))}
        Text(screen.title,fontSize=27.sp,fontWeight=FontWeight.Bold)
        Text(when{model.fastbootDevice!=null->"This tool needs ADB. Your selected target is in ${targetMode(model).lowercase()}. Connect a target with USB debugging, or return to Fastboot.";model.device?.mode=="Sideload"->"Recovery is in sideload mode. Send its update from Sideload, or connect a target with a normal ADB shell.";else->"Enable USB debugging, connect an OTG/data cable, and approve the connection on the target."},fontSize=13.sp,lineHeight=20.sp)
        Button(onClick=onConnect,enabled=!model.busy,modifier=Modifier.fillMaxWidth()){Text("Connect ADB device")}
        if(model.fastbootDevice!=null)TextButton(onClick={model.screen=Screen.Fastboot}){Text("Open Fastboot")}
        if(model.device?.mode=="Sideload")TextButton(onClick={model.screen=Screen.Sideload}){Text("Open Sideload")}
    }
}
@Composable private fun ShellScreen(model:ADBoxViewModel) {
    var command by rememberSaveable { mutableStateOf("") }
    var history by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        ToolkitHeading("Console", if(model.shellRunning)"Command running · stop whenever you need"else "Run an ADB shell command") {
            IconButton(onClick={history=!history}) { Icon(Icons.Outlined.History,"Command history") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            listOf("Device" to "getprop","Processes" to "ps -A","Battery" to "dumpsys battery","Storage" to "df -h").forEach { (title,value)->AssistChip(onClick={command=value},label={Text(title)}) }
        }
        if(history)Column(Modifier.heightIn(max=140.dp).verticalScroll(rememberScrollState())) {
            if(model.history.isEmpty())Text("Run a command to start your history.",style=MaterialTheme.typography.bodySmall)
            model.history.forEach { previous->Text(previous,Modifier.fillMaxWidth().clickable{command=previous;history=false}.padding(10.dp),fontFamily=FontFamily.Monospace,fontSize=12.sp) }
        }
        ToolkitConsole(model.output,Modifier.weight(1f).fillMaxWidth(),"adbox >\n\nTry getprop ro.product.model")
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(command,{command=it},modifier=Modifier.weight(1f),label={Text("Command")},placeholder={Text("getprop ro.product.model")},minLines=1,maxLines=3,shape=RoundedCornerShape(18.dp),textStyle=LocalTextStyle.current.copy(fontFamily=FontFamily.Monospace,fontSize=13.sp),enabled=!model.shellRunning)
            FilledTonalIconButton(onClick={if(model.shellRunning)model.stopShell()else model.runShell(command)},enabled=model.shellRunning||command.isNotBlank(),modifier=Modifier.size(52.dp)) {
                Icon(if(model.shellRunning)Icons.Outlined.Stop else Icons.Outlined.PlayArrow,if(model.shellRunning)"Stop command"else "Run command")
            }
        }
    }
}
@Composable private fun FilesScreen(model:ADBoxViewModel,onUpload:()->Unit,onActions:(SyncTransfer.Entry)->Unit,onDownload:(SyncTransfer.Entry)->Unit) {
    var path by remember(model.remotePath) { mutableStateOf(model.remotePath) }
    var query by rememberSaveable { mutableStateOf("") }
    var recursive by rememberSaveable { mutableStateOf(false) }
    val visible=remember(model.files,query,recursive) { if(recursive||query.isBlank())model.files else model.files.filter { it.name.contains(query,true) } }
    Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        ToolkitHeading("Files", "${model.files.size} items · ${model.remotePath}") { IconButton(onClick={model.browse()},enabled=!model.busy){Icon(Icons.Outlined.Refresh,"Refresh folder")} }
        OutlinedTextField(path,{path=it},Modifier.fillMaxWidth(),singleLine=true,label={Text("Device folder")},shape=RoundedCornerShape(18.dp),textStyle=LocalTextStyle.current.copy(fontFamily=FontFamily.Monospace,fontSize=12.sp),trailingIcon={IconButton(onClick={model.browse(path)},enabled=!model.busy&&path.isNotBlank()){Icon(Icons.Outlined.ArrowForward,"Open folder")}})
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            OutlinedIconButton(onClick={model.browse(model.remotePath.trimEnd('/').substringBeforeLast('/',"").ifBlank{"/"})},enabled=!model.busy){Icon(Icons.Outlined.ArrowUpward,"Parent folder")}
            OutlinedTextField(query,{query=it},Modifier.weight(1f),label={Text("Find files")},singleLine=true,shape=RoundedCornerShape(18.dp),leadingIcon={Icon(Icons.Outlined.Search,null)},textStyle=LocalTextStyle.current.copy(fontSize=13.sp))
            FilledTonalIconButton(onClick=onUpload,enabled=!model.busy){Icon(Icons.Outlined.FileUpload,"Upload a file")}
        }
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            FilterChip(selected=recursive,onClick={recursive=!recursive},label={Text("Search subfolders")})
            if(recursive)TextButton(onClick={model.searchFiles(query)},enabled=!model.busy&&query.isNotBlank()){Text("Search device")}
        }
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp),contentPadding=PaddingValues(bottom=12.dp)) {
            if(recursive&&model.searchResults.isNotEmpty()) {
                item("search-label"){ToolkitSectionLabel("Search results")}
                items(model.searchResults.distinct(),key={"search:$it"},contentType={"search"}) { result->
                    Text(result,Modifier.fillMaxWidth().clickable(enabled=!model.busy){path=result.substringBeforeLast('/');model.browse(path)}.padding(12.dp),fontFamily=FontFamily.Monospace,fontSize=12.sp)
                }
                item("folder-label"){ToolkitSectionLabel("Current folder")}
            }
            if(visible.isEmpty()&&!model.busy)item("empty"){ToolkitEmptyMessage(if(query.isBlank())"This folder is empty"else "No matching files",if(query.isBlank())"Upload a file or open another folder."else "Change your search, or search subfolders on the device.")}
            items(visible,key={"file:${it.name}"},contentType={if(it.directory)"folder"else "file"}) { entry->
                Surface(onClick={if(entry.directory)model.browse("${model.remotePath.trimEnd('/')}/${entry.name}")else onDownload(entry)},enabled=!model.busy,color=MaterialTheme.colorScheme.surfaceContainer,shape=RoundedCornerShape(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start=14.dp,end=4.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Icon(if(entry.directory)Icons.Outlined.Folder else Icons.Outlined.Description,null,tint=MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                            Text(entry.name,style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(if(entry.directory)"Folder"else ADBoxViewModel.formatBytes(entry.size),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick={onActions(entry)},enabled=!model.busy){Icon(Icons.Outlined.MoreVert,"Actions for ${entry.name}")}
                    }
                }
            }
        }
    }
}

@Composable private fun AppsScreen(model:ADBoxViewModel,onInstall:()->Unit,onSelect:(String)->Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered=remember(model.packages,query) { model.packages.filter { it.contains(query,true) } }
    Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        ToolkitHeading("Apps", "${model.packages.size} installed packages") { IconButton(onClick=model::refreshApps,enabled=!model.busy){Icon(Icons.Outlined.Refresh,"Refresh packages")} }
        OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),label={Text("Find a package")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true,shape=RoundedCornerShape(18.dp))
        Row(verticalAlignment=Alignment.CenterVertically) {
            FilterChip(selected=model.includeSystem,onClick={model.includeSystem=!model.includeSystem;model.refreshApps()},label={Text("Include system")},enabled=!model.busy)
            Spacer(Modifier.weight(1f))
            FilledTonalButton(onClick=onInstall,enabled=!model.busy){Icon(Icons.Outlined.Add,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Install")}
        }
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp),contentPadding=PaddingValues(bottom=12.dp)) {
            if(filtered.isEmpty()&&!model.busy)item("empty"){ToolkitEmptyMessage("No matching packages", "Change the search or include system packages.")}
            items(filtered,key={it},contentType={"package"}) { pkg->
                Surface(onClick={onSelect(pkg)},enabled=!model.busy,color=MaterialTheme.colorScheme.surfaceContainer,shape=RoundedCornerShape(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Apps,null,tint=MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(pkg.substringAfterLast('.'),style=MaterialTheme.typography.titleSmall)
                            Text(pkg,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Outlined.MoreVert,"App actions")
                    }
                }
            }
        }
    }
}

@Composable private fun LogsScreen(model:ADBoxViewModel,onRecordingExport:()->Unit,onExport:()->Unit) {
    var query by rememberSaveable { mutableStateOf("") };var severity by rememberSaveable { mutableStateOf("All") }
    val lines=remember(model.logs,query,severity) { model.logs.lineSequence().filter { it.contains(query,true)&&(severity=="All"||(severity=="Errors"&&it.contains(" E "))||(severity=="Warnings"&&it.contains(" W "))) }.toList() }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        ToolkitHeading("Logcat", if(model.recording)"Recording in background"else if(model.liveLogs)"Auto refresh while this page is visible"else "Latest 400 device log lines") {
            Row {
                IconButton(onClick={model.refreshLogs()},enabled=!model.busy&&!model.recording){Icon(Icons.Outlined.Refresh,"Refresh logs")}
                Box {
                    IconButton(onClick={menu=true}){Icon(Icons.Outlined.MoreVert,"Log export options")}
                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                        DropdownMenuItem(text={Text("Export log snapshot")},onClick={menu=false;onExport()},enabled=model.logs.isNotBlank()&&!model.busy)
                        DropdownMenuItem(text={Text("Save last recording")},onClick={menu=false;onRecordingExport()},enabled=!model.busy)
                    }
                }
            }
        }
        OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),label={Text("Search logs")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true,shape=RoundedCornerShape(18.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            listOf("All","Errors","Warnings").forEach { FilterChip(selected=severity==it,onClick={severity=it},label={Text(it)}) }
            FilterChip(selected=model.liveLogs,onClick=model::toggleLiveLogs,enabled=!model.recording,label={Text("Auto refresh")})
        }
        ToolkitConsole(remember(lines){lines.joinToString("\n")},Modifier.weight(1f).fillMaxWidth(),if(model.logs.isBlank())"Refresh to read device logs."else "No lines match this filter.")
        FilledTonalButton(onClick=model::toggleRecording,enabled=model.device!=null&&!model.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
            Icon(if(model.recording)Icons.Outlined.Stop else Icons.Outlined.FiberManualRecord,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(if(model.recording)"Stop recording"else "Record in background")
        }
    }
}
