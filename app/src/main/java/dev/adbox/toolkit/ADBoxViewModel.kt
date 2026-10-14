package dev.adbox.toolkit

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.*
import java.util.UUID
import java.util.zip.ZipInputStream

data class DeviceInfo(val model: String, val android: String, val serial: String, val battery: String, val ram: String, val transport: String, val mode:String="Android")
data class UsbTarget(val device: UsbDevice, val name: String, val fastboot: Boolean)
data class FastbootInfo(val product:String,val serial:String,val userspace:Boolean,val variables:Map<String,String>)
enum class Screen(val title: String) { Device("ADB"), Shell("Console"), Files("Files"), Apps("Apps"), Logs("Logs"), Fastboot("Fastboot"), Terminal("Terminal"), Mirror("Screen"), Backup("Backup"), Sideload("Sideload"), Scripts("Scripts"), Tools("Tools") }

private interface Session : Closeable {
    val banner:String get()=""
    val serialHint:String get()=""
    fun open(service: String, deferredAcks:Boolean=false): AdbChannel
    fun connected(): Boolean
}
private class UsbTransport(private val manager: UsbManager, private val device: UsbDevice, queued:Boolean) : Closeable {
    private val face = (0 until device.interfaceCount).map(device::getInterface).firstOrNull {
        it.interfaceClass == 255 && it.interfaceSubclass == 66 && it.interfaceProtocol == 1
    } ?: throw IOException("This USB device has no ADB interface. Enable USB debugging on the target.")
    private val endpoints = (0 until face.endpointCount).map(face::getEndpoint).filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
    private val inputEndpoint = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: throw IOException("Missing USB input endpoint")
    private val outputEndpoint = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: throw IOException("Missing USB output endpoint")
    private val connection = manager.openDevice(device) ?: throw IOException("USB access was not granted")
    @Volatile private var closed = false
    init { if (!connection.claimInterface(face, true)) { connection.close(); throw IOException("Cannot claim the USB ADB interface") } }
    private val asynchronous = if(queued)QueuedUsbTransport(AndroidQueuedUsbDriver(manager,device,connection,inputEndpoint,outputEndpoint),outputEndpoint.maxPacketSize,60000)else null
    val input:InputStream = asynchronous?.input ?: object : InputStream() {
        private var buffer = ByteArray(16384)
        private var size = 0
        private var position = 0
        override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255 }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            while (position >= size) {
                if (closed || !manager.deviceList.containsKey(device.deviceName)) return -1
                size = connection.bulkTransfer(inputEndpoint, buffer, buffer.size, 1000)
                position = 0
                if (size <= 0) continue
            }
            val n = minOf(length, size - position)
            buffer.copyInto(bytes, offset, position, position + n); position += n
            return n
        }
    }
    val output:OutputStream = asynchronous?.output ?: UsbAdbOutputStream(outputEndpoint.maxPacketSize){bytes,offset,length->
        if(closed||!manager.deviceList.containsKey(device.deviceName))throw IOException("USB disconnected")
        val started=android.os.SystemClock.elapsedRealtime()
        val result=connection.bulkTransfer(outputEndpoint,bytes,offset,length,30000)
        if(result<0)throw IOException("USB endpoint 0x${outputEndpoint.address.toString(16)} returned $result for $length B after ${android.os.SystemClock.elapsedRealtime()-started} ms (30 s limit; host ${Build.MODEL}, Android ${Build.VERSION.RELEASE}). Re-enter Apply update from ADB and reconnect the cable.")
        result
    }
    override fun close() { if (!closed) { closed = true;if(asynchronous!=null)asynchronous.close()else{connection.releaseInterface(face);connection.close()} } }
}
private class UsbSession(val wire: AdbWireConnection,override val banner:String,override val serialHint:String) : Session {
    override fun open(service: String, deferredAcks:Boolean) = wire.open(service,deferredAcks)
    override fun connected() = wire.isConnected
    override fun close() = wire.close()
}

enum class SideloadPhase { Idle, Preparing, Serving, Waiting, Complete, Failed }
data class SideloadStatus(
    val phase:SideloadPhase=SideloadPhase.Idle,
    val name:String="",
    val uniqueBytes:Long=0,
    val transferredBytes:Long=0,
    val totalBytes:Long=0,
    val message:String="",
    val target:String=""
){
    val running:Boolean get()=phase in listOf(SideloadPhase.Preparing,SideloadPhase.Serving,SideloadPhase.Waiting)
    val coverage:Float get()=if(totalBytes>0)(uniqueBytes.toDouble()/totalBytes).toFloat().coerceIn(0f,1f)else 0f
    val percent:Int get()=if(totalBytes>0)(uniqueBytes*100/totalBytes).toInt().coerceIn(0,100)else 0
}

// Discover the previous private settings store by its schema rather than a
// product name, then copy all value types before removing the previous store.
private fun openAppPreferences(app: Application): android.content.SharedPreferences {
    val current = app.getSharedPreferences("adbox", Context.MODE_PRIVATE)
    val directory = File(app.applicationInfo.dataDir, "shared_prefs")
    if (File(directory, "adbox.xml").exists() || File(directory, "adbox.xml.bak").exists()) return current
    val knownKeys = setOf("theme", "dynamic", "reconnect", "background", "queuedUsb", "mirrorVideo", "mirrorQuality", "history", "fastbootHistory", "scripts")
    val candidates = directory.listFiles().orEmpty().filter { it.isFile && (it.name.endsWith(".xml") || it.name.endsWith(".xml.bak")) }
        .map { it.name.removeSuffix(".bak").removeSuffix(".xml") }.distinct().filter { it != "adbox" }
        .map { it to app.getSharedPreferences(it, Context.MODE_PRIVATE) }
        .filter { (_, previous) -> previous.all.keys.any { it in knownKeys } }
    val (name, previous) = candidates.singleOrNull() ?: return current
    val editor = current.edit()
    previous.all.forEach { (key, value) -> when (value) {
        is String -> editor.putString(key, value)
        is Boolean -> editor.putBoolean(key, value)
        is Int -> editor.putInt(key, value)
        is Long -> editor.putLong(key, value)
        is Float -> editor.putFloat(key, value)
        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        else -> Unit
    } }
    if (editor.commit()) app.deleteSharedPreferences(name)
    return current
}

class ADBoxViewModel(app: Application) : AndroidViewModel(app) {
    private val context = app.applicationContext
    private val preferences = openAppPreferences(app)
    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val permissionAction = "${app.packageName}.USB_PERMISSION"
    private val permissionRequestExtra = "${app.packageName}.USB_PERMISSION_REQUEST"
    private data class UsbPermissionRequest(val target: UsbDevice, val token: String, val callback: PendingIntent)
    private var pendingUsbPermission: UsbPermissionRequest? = null
    private var session: Session? = null
    private var fastboot: FastbootClient? = null
    private var connectedUsbName: String? = null
    private var generation = 0
    private var uiVisible = false
    private var filesLoaded = false
    private var appsLoadedSystem: Boolean? = null
    private var logsLoaded = false
    private var fileLoadKey: Pair<Int, String>? = null
    private var appLoadKey: Pair<Int, Boolean>? = null
    private var logReadJob: Job? = null
    private var browseRequest = 0
    private var appRequest = 0
    private var searchRequest = 0
    private var activeShell: AdbChannel? = null
    private var shellJob: Job? = null
    private var logJob: Job? = null
    private var connectionWatch: Job? = null
    private var operations by mutableIntStateOf(0)
    val busy: Boolean get() = operations > 0
    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        if (!visible) stopMirror()
    }
    fun ensurePageData(page: Screen) {
        if (device == null || device?.mode == "Sideload") return
        when (page) {
            Screen.Files -> if (!filesLoaded) browse()
            Screen.Apps -> if (appsLoadedSystem != includeSystem) refreshApps()
            Screen.Logs -> if (!logsLoaded && !recording) refreshLogs()
            else -> Unit
        }
    }
    var status by mutableStateOf("Ready when you are")
        private set
    var notice by mutableStateOf<String?>(null)
    var screen by mutableStateOf(Screen.Device)
    var device by mutableStateOf<DeviceInfo?>(null)
        private set
    var fastbootDevice by mutableStateOf<FastbootInfo?>(null)
        private set
    var fastbootOutput by mutableStateOf("")
        private set
    var fastbootProgress by mutableFloatStateOf(-1f)
        private set
    var fastbootPhase by mutableStateOf("")
        private set
    val fastbootBusy:Boolean get()=busy&&fastboot!=null
    val fastbootConnectionToken:Int get()=generation
    val connectionToken:Int get()=generation
    var fastbootDiagnostics by mutableStateOf<FastbootDiagnostics.Snapshot?>(null);private set
    var fastbootPartition by mutableStateOf<FastbootDiagnostics.Partition?>(null);private set
    var fastbootDiagnosticsTime by mutableStateOf("");private set
    var fastbootPartitionTime by mutableStateOf("");private set
    var fastbootHistory by mutableStateOf(preferences.getString("fastbootHistory", "")!!.split('\n').filter(String::isNotBlank).take(20));private set
    fun clearFastbootHistory(){fastbootHistory=emptyList();preferences.edit().remove("fastbootHistory").apply()}
    private fun resetFastbootDiagnostics(){fastbootDiagnostics=null;fastbootPartition=null;fastbootDiagnosticsTime="";fastbootPartitionTime=""}
    private fun updateFastbootProfile(){activeConnectionId?.let{id->profiles[id]?.let{profiles[id]=it.copy(bootInfo=fastbootDevice)}}}
    fun readFastbootDiagnostics(){if(busy)return;work("Reading device and slot diagnostics"){
        val client=requireFastboot();val epoch=generation
        val result=withContext(Dispatchers.IO){FastbootDiagnostics.device(client)}
        if(epoch==generation){
            fastbootDiagnostics=result;fastbootDiagnosticsTime=java.time.Instant.now().toString()
            val values=result.variables
            fastbootDevice=FastbootInfo(values["product"]?.takeUnless{it==FastbootDiagnostics.UNAVAILABLE}?:"Fastboot device",values["serialno"]?.takeUnless{it==FastbootDiagnostics.UNAVAILABLE}?:"USB device",values["is-userspace"]?.lowercase() in listOf("yes","true","1"),values)
            updateFastbootProfile();status="Device and slot diagnostics updated"
        }
    }}
    fun inspectFastbootPartition(name:String){if(busy)return;work("Inspecting partition $name"){
        val client=requireFastboot();val epoch=generation
        val result=withContext(Dispatchers.IO){FastbootDiagnostics.partition(client,name)}
        if(epoch==generation){fastbootPartition=result;fastbootPartitionTime=java.time.Instant.now().toString();status="Partition query completed"}
    }}
    fun fastbootReport():String=buildString{
        append("ADBox Fastboot report · 1.0.0\nExported: ${java.time.Instant.now()}\n")
        append("Target: ${fastbootDevice?.product} · ${fastbootDevice?.serial}\nMode: ${if(fastbootDevice?.userspace==true)"Fastbootd"else "Bootloader"}\n\n")
        append("Device variables (read: ${fastbootDiagnosticsTime.ifBlank{"connection refresh"}})\n")
        fastbootDevice?.variables?.forEach{(name,value)->append("$name: $value\n")}
        fastbootDiagnostics?.slots?.forEach{(slot,values)->append("\nSlot ${slot.uppercase()}\n");values.forEach{(name,value)->append("$name: $value\n")}}
        fastbootPartition?.let{result->append("\nPartition ${result.name} (read: $fastbootPartitionTime)\n");result.variables.forEach{(name,value)->append("$name: $value\n")};result.sizeBytes?.let{append("size-bytes: $it\n")}}
        append("\nUnavailable means unsupported or unreported; it does not establish partition absence or slot health.\n")
        if(fastbootOutput.isNotBlank())append("\nLatest command output\n$fastbootOutput")
    }
    var usbTargets by mutableStateOf<List<UsbTarget>>(emptyList())
        private set
    var shellRunning by mutableStateOf(false)
        private set
    var output by mutableStateOf("")
        private set
    var history by mutableStateOf(preferences.getString("history", "")!!.split('\n').filter(String::isNotBlank).take(20))
        private set
    var remotePath by mutableStateOf("/sdcard")
        private set
    var files by mutableStateOf<List<SyncTransfer.Entry>>(emptyList())
        private set
    var packages by mutableStateOf<List<String>>(emptyList())
        private set
    var includeSystem by mutableStateOf(false)
    var logs by mutableStateOf("")
        private set
    var liveLogs by mutableStateOf(false)
        private set
    var screenshot by mutableStateOf<ByteArray?>(null)
    var theme by mutableStateOf(preferences.getString("theme", "system")!!)
        private set
    var dynamicColors by mutableStateOf(preferences.getBoolean("dynamic", false))
        private set
    data class ConnectionSummary(val id:String,val label:String,val transport:String)
    private data class Profile(val id:String,val adb:Session?,val boot:FastbootClient?,val info:DeviceInfo?,val bootInfo:FastbootInfo?,val target:UsbDevice){fun connected()=adb?.connected()==true||boot?.isConnected==true}
    private val profiles=linkedMapOf<String,Profile>()
    private val desired=linkedMapOf<String,Profile>()
    var connections by mutableStateOf<List<ConnectionSummary>>(emptyList());private set
    var activeConnectionId by mutableStateOf<String?>(null);private set
    var autoReconnect by mutableStateOf(preferences.getBoolean("reconnect",true));private set
    var backgroundConnections by mutableStateOf(preferences.getBoolean("background",true));private set
    var queuedUsb by mutableStateOf(preferences.getBoolean("queuedUsb",true));private set
    fun changeUsbEngine(enabled:Boolean){if(busy||connections.isNotEmpty()){notice="Disconnect USB targets before changing the USB engine";return};queuedUsb=enabled;preferences.edit().putBoolean("queuedUsb",enabled).apply()}
    private fun rememberConnection(id:String,target:UsbDevice){
        val profile=Profile(id,session,fastboot,device,fastbootDevice,target)
        profiles[id]?.takeIf{it.adb!==session||it.boot!==fastboot}?.let{old->viewModelScope.launch(Dispatchers.IO){runCatching{old.adb?.close()};runCatching{old.boot?.close()}}}
        profiles[id]=profile;desired[id]=profile;activeConnectionId=id;publishConnections()
    }
    private fun publishConnections(){
        connections=profiles.values.filter{it.connected()}.map{ConnectionSummary(it.id,it.info?.model?:it.bootInfo?.product?:"Device",it.info?.transport?:"Fastboot")}
        // A connectedDevice foreground service requires a current USB permission grant.
        val attached=profiles.values.any{it.connected()&&usb.deviceList.containsKey(it.target.deviceName)&&usb.hasPermission(it.target)}
        if(backgroundConnections&&attached)FastbootOperationService.retain(context,"connections","${connections.size} USB device connection(s)")
        else FastbootOperationService.release(context,"connections")
    }
    fun selectConnection(id:String){
        if(busy){notice="Wait for the current operation to finish";return};val profile=profiles[id]?.takeIf{it.connected()}?:return
        generation++;stopShell();stopLogs();stopTerminal();stopMirror();session=profile.adb;fastboot=profile.boot;device=profile.info;fastbootDevice=profile.bootInfo;connectedUsbName=profile.target.deviceName;activeConnectionId=id;resetDeviceTools();resetFastbootDiagnostics();fastbootOutput="";screen=if(profile.boot!=null)Screen.Fastboot else if(profile.info?.mode=="Sideload")Screen.Sideload else Screen.Device;status="Connected to ${profile.info?.model?:profile.bootInfo?.product}"
    }
    fun changeReconnect(enabled:Boolean){autoReconnect=enabled;preferences.edit().putBoolean("reconnect",enabled).apply();publishConnections()}
    fun changeBackground(enabled:Boolean){backgroundConnections=enabled;preferences.edit().putBoolean("background",enabled).apply();publishConnections()}
    private fun startConnectionWatch(){
        connectionWatch=viewModelScope.launch{
            var tick=0
            while(isActive){
                delay(1500);tick++
                val lost=profiles.values.filter{!it.connected()}
                lost.forEach{profile->
                    if(profile.id==activeConnectionId){disconnect(true);notice="${profile.info?.model?:"USB device"} disconnected"}
                    else{profiles.remove(profile.id);withContext(Dispatchers.IO){runCatching{profile.adb?.close()};runCatching{profile.boot?.close()}};publishConnections()}
                }
                if(autoReconnect&&tick%7==0&&!busy&&!terminalRunning&&!mirrorRunning&&activeConnectionId==null){
                    val pending=desired.values.filter{it.id !in profiles};if(pending.isEmpty())continue
                    val wanted=pending[(tick/7)%pending.size]
                    val target=usb.deviceList.values.firstOrNull{it.vendorId==wanted.target.vendorId&&it.productId==wanted.target.productId&&usb.hasPermission(it)}
                    if(target!=null){desired.remove(wanted.id);requestUsb(target)}
                }
            }
        }
    }
    data class SavedScript(val name:String,val body:String)
    data class FirmwareItem(val file:File,val name:String,val partition:String)
    var terminalRunning by mutableStateOf(false);private set
    var terminalText by mutableStateOf("");private set
    private var terminalJob:Job?=null
    private var terminalChannel:AdbChannel?=null
    private var terminalInputs:Channel<String>?=null
    private var terminalInputJob:Job?=null
    private var terminalV2=true
    private var terminalToken=0
    private var mirrorToken=0
    private var mirrorChannel:AdbChannel?=null
    var mirrorRunning by mutableStateOf(false);private set
    var mirrorFullscreen by mutableStateOf(false)
    var mirrorFrame by mutableStateOf<android.graphics.Bitmap?>(null);private set
    private var mirrorJob:Job?=null
    private var mirrorClient:RemoteScreenSession?=null
    private var mirrorSurface:android.view.Surface?=null
    var mirrorVideo by mutableStateOf(preferences.getBoolean("mirrorVideo",true));private set
    internal var mirrorQuality by mutableStateOf(runCatching{ScreenQuality.valueOf(preferences.getString("mirrorQuality","Balanced")!!)}.getOrDefault(ScreenQuality.Balanced));private set
    var mirrorWidth by mutableIntStateOf(0);private set
    var mirrorHeight by mutableIntStateOf(0);private set
    var mirrorFps by mutableIntStateOf(0);private set
    var mirrorReady by mutableStateOf(false);private set
    var mirrorStatus by mutableStateOf("Ready for USB screen control");private set
    var mirrorSessionToken by mutableIntStateOf(0);private set
    internal fun changeMirrorQuality(value:ScreenQuality){if(mirrorRunning)return;mirrorQuality=value;preferences.edit().putString("mirrorQuality",value.name).apply()}
    fun changeMirrorVideo(value:Boolean){if(mirrorRunning)return;mirrorVideo=value;mirrorFrame=null;mirrorWidth=0;mirrorHeight=0;preferences.edit().putBoolean("mirrorVideo",value).apply()}
    fun attachMirrorSurface(surface:android.view.Surface){mirrorSurface=surface}
    fun detachMirrorSurface(surface:android.view.Surface){if(mirrorSurface===surface){mirrorSurface=null;if(mirrorVideo)stopMirror()}}
    var searchResults by mutableStateOf<List<String>>(emptyList());private set
    var scripts by mutableStateOf<List<SavedScript>>(emptyList());private set
    var firmware by mutableStateOf<List<FirmwareItem>>(emptyList());private set
    private var firmwareDir:File?=null
    private var firmwareTarget:String?=null
    var recordingIds by mutableStateOf<Set<String>>(emptySet());private set
    val recording:Boolean get()=activeConnectionId in recordingIds
    private val recordJobs=mutableMapOf<String,Job>()
    private val recordChannels=java.util.concurrent.ConcurrentHashMap<String,AdbChannel>()
    private var lastRecording:File?=context.filesDir.listFiles()?.filter{it.name.startsWith("logcat-")&&it.name.endsWith(".txt")}?.maxByOrNull{it.lastModified()}
    var advancedProgress by mutableStateOf("");private set
    var sideload by mutableStateOf(SideloadStatus());private set
    private var sideloadToken=0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val target = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (intent.action == permissionAction) {
                handleUsbPermission(intent, target)
            } else if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                if (target?.deviceName == pendingUsbPermission?.target?.deviceName) clearUsbPermissionRequest()
                if (target?.deviceName == connectedUsbName) disconnect(true)
            }
            refreshUsb()
        }
    }
    init {
        val filter = IntentFilter(permissionAction).apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        androidx.core.content.ContextCompat.registerReceiver(context,receiver,filter,androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        preferences.edit().remove("host").remove("wirelessTargets").apply()
        refreshUsb(); loadScripts(); startConnectionWatch()
    }
    fun refreshUsb() {
        usbTargets = usb.deviceList.values.filter { d -> (0 until d.interfaceCount).any { val i=d.getInterface(it);i.interfaceClass==255&&i.interfaceSubclass==66&&(i.interfaceProtocol==1||i.interfaceProtocol==3) } }
            .map { UsbTarget(it, it.productName ?: "USB Android device",isFastboot(it)) }
    }
    private fun isFastboot(target:UsbDevice)=(0 until target.interfaceCount).any{val i=target.getInterface(it);i.interfaceClass==255&&i.interfaceSubclass==66&&i.interfaceProtocol==3}
    private fun clearUsbPermissionRequest() {
        pendingUsbPermission?.callback?.cancel()
        pendingUsbPermission = null
    }
    private fun handleUsbPermission(intent: Intent, reportedTarget: UsbDevice?) {
        val request = pendingUsbPermission ?: return
        // Ignore delayed results from an earlier request or another USB device.
        if (intent.getStringExtra(permissionRequestExtra) != request.token) return
        if (reportedTarget != null && (reportedTarget.deviceName != request.target.deviceName || reportedTarget.deviceId != request.target.deviceId)) return
        clearUsbPermissionRequest()
        val attachedTarget = usb.deviceList[request.target.deviceName]?.takeIf { it.deviceId == request.target.deviceId }
        if (attachedTarget == null) {
            notice = "The USB device disconnected before permission completed. Reconnect the cable."
        } else if (usb.hasPermission(attachedTarget)) {
            // Actual permission is authoritative, even if callback extras are missing.
            connectUsb(attachedTarget)
        } else if (intent.hasExtra(UsbManager.EXTRA_PERMISSION_GRANTED) && !intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            notice = "USB permission was declined. You can try connecting again."
        } else {
            notice = "USB permission could not be verified. Try connecting again."
        }
    }
    fun requestUsb(target: UsbDevice) {
        if (busy || pendingUsbPermission != null) return
        val existing=profiles["usb:${target.deviceName}"];if(existing?.connected()==true){selectConnection(existing.id);return}
        if (usb.hasPermission(target)) {
            connectUsb(target)
            return
        }
        val token = UUID.randomUUID().toString()
        val intent = Intent(permissionAction).setPackage(context.packageName).putExtra(permissionRequestExtra, token)
        // UsbManager fills EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED when sending
        // this callback. An immutable PendingIntent silently drops those extras.
        val flags = PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_ONE_SHOT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val callback = PendingIntent.getBroadcast(context, target.deviceId, intent, flags)
        pendingUsbPermission = UsbPermissionRequest(target, token, callback)
        try {
            usb.requestPermission(target, callback)
        } catch (e: Exception) {
            clearUsbPermissionRequest()
            notice = e.message ?: "USB permission could not be requested. Try connecting again."
        }
    }
    private fun work(label: String, action: suspend () -> Unit): Job = viewModelScope.launch {
        operations++; status = label
        try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            notice = e.message ?: "The operation failed"; status = "Needs attention"
            if (session?.connected() != true && device != null) { device = null; files = emptyList(); packages = emptyList(); stopLogs() }
            if(fastboot?.isConnected!=true&&fastbootDevice!=null)fastbootDevice=null
        } finally { operations-- }
    }
    private fun identity() = AdbIdentity.loadOrCreate(File(context.filesDir, "adb-identity"))
    private fun requireSession(): Session = session?.takeIf(Session::connected) ?: throw IOException("Connect a device first")
    private fun connectUsb(target: UsbDevice) {
        if (busy) return
        if(isFastboot(target)){connectFastboot(target);return}
        val epoch = ++generation
        work("Approve ADBox on your target device") {
            closePreviousConnection()
            val next = withContext(Dispatchers.IO) {
                session?.close()
                val transport = UsbTransport(usb, target,queuedUsb)
                try { val wire = AdbWireConnection(transport.input, transport.output, transport, identity()); val banner=wire.connect(); UsbSession(wire,banner,runCatching{target.serialNumber}.getOrNull()?:target.deviceName) }
                catch (e: Exception) { transport.close(); throw e }
            }
            if (epoch != generation) { withContext(Dispatchers.IO) { next.close() }; return@work }
            session = next; connectedUsbName=target.deviceName; loadInfo("USB", epoch); rememberConnection("usb:${target.deviceName}",target=target);screen=if(device?.mode=="Sideload")Screen.Sideload else Screen.Device;status="${device?.mode} connected";notice="USB device connected"
        }
    }
    private suspend fun readInfo(s:Session,transport:String):DeviceInfo {
        if(s.banner.startsWith("sideload::")||s.banner.startsWith("rescue::"))return DeviceInfo(Regex("ro.product.model=([^;]+)").find(s.banner)?.groupValues?.get(1)?:"Recovery sideload","—",s.serialHint.ifBlank{"Recovery device"},"—","—",transport,"Sideload")
        return try{
            val text=withContext(Dispatchers.IO){readText(s,"getprop ro.product.model; getprop ro.build.version.release; getprop ro.serialno; dumpsys battery | grep level; head -n 1 /proc/meminfo",64000)}
            val rows=text.lines();val battery=Regex("level:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.plus("%")?:"—"
            val ram=Regex("MemTotal:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()?.let{String.format(java.util.Locale.US,"%.1f GB",it/1048576.0)}?:"—"
            DeviceInfo(rows.getOrNull(0).orEmpty().ifBlank{"Android device"},rows.getOrNull(1).orEmpty(),rows.getOrNull(2).orEmpty(),battery,ram,transport,if(s.banner.startsWith("recovery::"))"Recovery"else "Android")
        }catch(e:Exception){if(!s.connected())throw e;DeviceInfo("Recovery / restricted ADB","—",s.serialHint.ifBlank{"ADB device"},"—","—",transport,"Recovery")}
    }
    private suspend fun loadInfo(transport:String,epoch:Int){val info=readInfo(requireSession(),transport);if(epoch!=generation)return;device=info;resetDeviceTools()}
    private fun resetDeviceTools(){remotePath="/sdcard";files=emptyList();packages=emptyList();logs="";screenshot=null;searchResults=emptyList();filesLoaded=false;appsLoadedSystem=null;logsLoaded=false;fileLoadKey=null;appLoadKey=null;browseRequest++;appRequest++;searchRequest++}
    fun disconnect(reconnect:Boolean=false){
        val id=activeConnectionId;val old=session;val oldFastboot=fastboot
        if(id!=null){recordJobs.remove(id)?.cancel();recordChannels.remove(id)?.let{c->viewModelScope.launch(Dispatchers.IO){runCatching{c.close()}}}}
        if(id!=null){profiles.remove(id);if(!reconnect)desired.remove(id)}
        generation++;stopShell();stopLogs();stopTerminal();stopMirror();session=null;fastboot=null;fastbootDevice=null;device=null;connectedUsbName=null;activeConnectionId=null;fastbootProgress=-1f;resetDeviceTools();resetFastbootDiagnostics();status="Disconnected"
        viewModelScope.launch(Dispatchers.IO){runCatching{old?.close()};runCatching{oldFastboot?.close()}}
        publishConnections()
    }
    private suspend fun closePreviousConnection(){stopShell();stopLogs();stopTerminal();stopMirror();session=null;fastboot=null;device=null;fastbootDevice=null;connectedUsbName=null;activeConnectionId=null;resetFastbootDiagnostics()}
    private fun requireFastboot()=fastboot?.takeIf{it.isConnected}?:throw IOException("Connect a USB Fastboot device first")
    private fun connectFastboot(target:UsbDevice){
        val epoch=++generation
        work("Connecting to Fastboot"){
            closePreviousConnection()
            val client=withContext(Dispatchers.IO){FastbootClient(UsbFastbootTransport(usb,target))}
            if(epoch!=generation){withContext(Dispatchers.IO){client.close()};return@work}
            fastboot=client;connectedUsbName=target.deviceName;fastbootOutput="";fastbootProgress=-1f
            try{loadFastbootInfo(client,epoch)}catch(e:Exception){withContext(Dispatchers.IO){client.close()};throw e}
            rememberConnection("usb:${target.deviceName}",target=target);screen=Screen.Fastboot;status="Fastboot connected";notice="${if(fastbootDevice?.userspace==true)"Fastbootd"else "Fastboot"} device connected"

        }
    }
    private suspend fun loadFastbootInfo(client:FastbootClient,epoch:Int){
        val values=withContext(Dispatchers.IO){linkedMapOf<String,String>().apply{for(name in listOf("product","serialno","version","version-bootloader","unlocked","secure","current-slot","slot-count","is-userspace","max-download-size")){put(name,try{client.variable(name).ifBlank{"Unavailable"}}catch(e:FastbootClient.Rejected){"Unavailable"})}}}
        if(epoch==generation)fastbootDevice=FastbootInfo(values["product"]!!.takeUnless{it=="Unavailable"}?:"Fastboot device",values["serialno"]!!.takeUnless{it=="Unavailable"}?:"USB device",values["is-userspace"]?.lowercase() in listOf("yes","true","1"),values)
    }
    fun refreshFastboot(){if(busy)return;work("Reading bootloader variables"){
        val client=requireFastboot();loadFastbootInfo(client,generation);resetFastbootDiagnostics();updateFastbootProfile();status="Bootloader variables updated"
    }}
    private fun fastbootLog(epoch:Int,message:String){viewModelScope.launch{if(epoch==generation)fastbootOutput=(fastbootOutput+message).takeLast(256000)}}
    fun runFastboot(command:String,expectedToken:Int?=null){
        if(busy)return
        if(expectedToken!=null&&expectedToken!=generation){notice="The USB target changed. Review this command again.";return}
        val normalized=normalizeFastboot(command)
        if(normalized.isBlank())return
        if(normalized.length>4096||normalized.any{it.code<32||it.code>126}){notice="Enter a Fastboot command with 1–4096 printable ASCII characters";return}
        if(listOf("download:","download ","flash:","flash ","boot ","upload","fetch:","fetch ").any{normalized.startsWith(it)}||normalized=="boot"||normalized=="download"||normalized=="flash"){notice="Use Flash .img file, one-time Boot image, or Partition backup for file transfers.";return}
        work("Running Fastboot command"){
            val client=requireFastboot();val epoch=generation;fastbootOutput="$ $normalized\n\n";fastbootPhase="Sending $normalized"
            fastbootHistory=(listOf(normalized)+fastbootHistory.filter{it!=normalized}).take(20)
            preferences.edit().putString("fastbootHistory",fastbootHistory.joinToString("\n")).apply()
            val changesDevice=!FastbootDiagnostics.readOnlyCommand(normalized)
            try{
                if(changesDevice)FastbootOperationService.start(context,"Fastboot: $normalized")
                val reply=withContext(Dispatchers.IO){client.execute(normalized){message->fastbootLog(epoch,message)}}
                if(epoch==generation)fastbootOutput+="OKAY${if(reply.value.isBlank())""else " · ${reply.value}"}\n"
                status="Fastboot command accepted"
                if(normalized=="continue"||normalized.startsWith("reboot")){disconnect();screen=Screen.Fastboot;notice="Bootloader accepted $normalized"}
                else if(changesDevice&&client.isConnected){loadFastbootInfo(client,epoch);resetFastbootDiagnostics();updateFastbootProfile()}
            }catch(e:Exception){if(epoch==generation)fastbootOutput+="FAIL · ${e.message}\n";throw e}
            finally{if(changesDevice)FastbootOperationService.stop(context)}
        }
    }
    fun flashFastboot(uri:Uri,partition:String,bootImage:Boolean,expectedToken:Int?=null){
        if(busy)return
        if(expectedToken!=null&&expectedToken!=generation){notice="The USB target changed. Choose and review this image again.";return}
        if(!bootImage)try{FastbootClient.validatePartition(partition)}catch(e:IOException){notice=e.message;return}
        work(if(bootImage)"Preparing boot image"else "Preparing image for $partition"){
            val client=requireFastboot();val epoch=generation;val label=if(bootImage)"Boot image"else "Flash $partition"
            val temp=File.createTempFile("adbox-fastboot-",".img",context.cacheDir)
            fastbootOutput="${if(bootImage)"Boot"else "Flash $partition"} · ${displayName(uri)}\n\n";fastbootProgress=-1f;fastbootPhase="Preparing image"
            try{
                FastbootOperationService.start(context,label)
                withContext(Dispatchers.IO){
                    val max=try{FastbootClient.byteSize(client.variable("max-download-size"))}catch(e:FastbootClient.Rejected){null}
                    val limit=32L*1024*1024*1024
                    context.contentResolver.openInputStream(uri)?.use{source->temp.outputStream().use{out->val buffer=ByteArray(65536);var copied=0L;while(true){val n=source.read(buffer);if(n<0)break;copied+=n;if(copied>limit)throw IOException("The selected image exceeds ADBox’s ${formatBytes(limit)} staging limit.");out.write(buffer,0,n)}}}?:throw IOException("Cannot open the selected image")
                    if(!bootImage){val size=try{FastbootClient.byteSize(client.variable("partition-size:$partition"))}catch(e:FastbootClient.Rejected){null};if(size!=null&&SparseImage(temp).expandedSize>size)throw IOException("The image is larger than partition $partition (${formatBytes(size)})")}
                    var last=0L
                    val progress=FastbootClient.Progress{sent,total->val now=System.nanoTime();if(now-last>150_000_000L||sent==total){last=now;viewModelScope.launch{if(epoch==generation){fastbootProgress=sent.toFloat()/total.toFloat();fastbootPhase="Transferring ${formatBytes(sent)} / ${formatBytes(total)}"}};FastbootOperationService.update(context,label,"Transferring image",(sent*100/total).toInt())}}
                    val log=java.util.function.Consumer<String>{message->fastbootLog(epoch,message);if(message.startsWith("Writing partition")||message.startsWith("Booting")){viewModelScope.launch{if(epoch==generation){fastbootProgress=-1f;fastbootPhase=message.trim()}};FastbootOperationService.update(context,label,message.trim())}}
                    if(bootImage){if(max!=null&&temp.length()>max)throw IOException("The boot image exceeds the device download buffer");client.boot(temp,log,progress)}else flashPrepared(client,temp,partition,log,progress)
                }
                if(epoch==generation){fastbootOutput+="OKAY · ${if(bootImage)"Boot request accepted"else "Image flashed to $partition"}\n";fastbootPhase="Completed";fastbootProgress=-1f}
                status=if(bootImage)"Boot request accepted"else "Image flashed";notice=status
                if(bootImage){disconnect();screen=Screen.Fastboot}
            }catch(e:Exception){if(epoch==generation){fastbootOutput+="FAIL · ${e.message}\n";fastbootPhase="Failed"};throw e}
            finally{withContext(NonCancellable+Dispatchers.IO){temp.delete()};FastbootOperationService.stop(context);fastbootProgress=-1f}
        }
    }
    private fun readText(s: Session, command: String, limit: Int = 256000): String {
        val marker=AdbResponses.marker()
        return s.open("shell:${AdbResponses.shell(command,marker)}").use { channel ->
        val result=AdbResponses.readShell(channel.input(),marker,limit,null)
        if (!s.connected()) throw IOException("The device disconnected during the command")
        if(result.exitCode!=0&&!result.truncated)throw IOException(result.text.trim().ifBlank{"Command exited with status ${result.exitCode}"})
        result.text
        }
    }
    /** Retain only the latest full snapshot instead of scheduling one UI task per USB read. */
    private suspend fun <T> coalescedText(
        accepts: () -> Boolean,
        publish: (String) -> Unit,
        read: suspend ((String) -> Unit) -> T
    ): T = coroutineScope {
        val updates = Channel<String>(Channel.CONFLATED)
        val renderer = launch {
            for (text in updates) {
                if (accepts()) publish(text)
                delay(50)
            }
        }
        try {
            read { updates.trySend(it) }
        } finally {
            updates.close()
            renderer.cancelAndJoin()
            updates.tryReceive().getOrNull()?.let { if (accepts()) publish(it) }
        }
    }
    fun runShell(command: String, unlimited:Boolean=false) {
        if (shellRunning || command.isBlank()) return
        val s = try { requireSession() } catch (e: Exception) { notice=e.message;return }
        val epoch = generation
        history = (listOf(command.replace('\n',' ')) + history).distinct().take(20)
        preferences.edit().putString("history",history.joinToString("\n")).apply()
        output = "$ $command\n\n"; shellRunning = true; screen = Screen.Shell
        shellJob = viewModelScope.launch {
            if(unlimited)FastbootOperationService.retain(context,"script:$epoch","Running saved script")
            var timeout: Job? = null
            var ownChannel:AdbChannel?=null
            val reason="script:$epoch"
            try {
                val marker=AdbResponses.marker()
                val channel = withContext(Dispatchers.IO) { s.open("shell:${AdbResponses.shell(command,marker)}").also{ownChannel=it} }; activeShell=channel
                if(!unlimited)timeout = launch { delay(60000); withContext(Dispatchers.IO) { channel.close() }; notice="Command stopped after 60 seconds. Use Logs for continuous log capture." }
                val result=coalescedText({epoch==generation}, {text->output="$ $command\n\n$text"}) { update ->
                    withContext(Dispatchers.IO) {
                        val result=AdbResponses.readShell(channel.input(),marker,256000,{text->update(text)},unlimited,50)
                        channel.close()
                        if (!s.connected()) throw IOException("Device disconnected")
                        result
                    }
                }
                if(epoch==generation){output="$ $command\n\n${result.text}";status=if(result.truncated&&unlimited)"Command finished · exit ${result.exitCode} · earlier output clipped"else if(result.truncated)"Output limit reached"else "Command finished · exit ${result.exitCode}"}
            } catch (e: CancellationException) { throw e } catch (e: Exception) { if(epoch==generation){output+="\n${e.message ?: "Command failed"}";notice=e.message} }
            finally { timeout?.cancel();withContext(NonCancellable+Dispatchers.IO){runCatching{ownChannel?.close()}};if(activeShell===ownChannel){activeShell=null;shellRunning=false};if(unlimited)FastbootOperationService.release(context,reason) }
        }
    }
    fun stopShell() { shellJob?.cancel();val channel=activeShell;viewModelScope.launch(Dispatchers.IO){runCatching{channel?.close()}} }
    fun browse(path: String = remotePath) {
        val epoch=generation
        val key=epoch to path
        if(fileLoadKey==key)return
        fileLoadKey=key
        val request=++browseRequest
        work("Reading device files") {
            try {
                val s=requireSession();val result=withContext(Dispatchers.IO){SyncTransfer.list(s.open("sync:"),path)}
                if(epoch==generation&&request==browseRequest){remotePath=path;files=result;filesLoaded=true;searchResults=emptyList();status="${result.size} items"}
            } finally { if(request==browseRequest)fileLoadKey=null }
        }
    }
    fun pull(path: String, destination: Uri) = work("Saving file") {
        val s=requireSession()
        val count=withContext(Dispatchers.IO){context.contentResolver.openOutputStream(destination)?.use{SyncTransfer.pull(s.open("sync:"),path,it)}?:throw IOException("Cannot open the selected destination")}
        status="File saved";notice="Saved ${formatBytes(count)}"
    }
    fun displayName(uri: Uri): String {
        context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor -> if(cursor.moveToFirst())return cursor.getString(0) }
        return "upload.bin"
    }
    fun push(uri: Uri) = work("Uploading file") {
        val s=requireSession();val path=remotePath;val name=displayName(uri).replace(Regex("[/\\\\\\u0000]"),"_")
        withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)?.use{SyncTransfer.push(s.open("sync:"),"${path.trimEnd('/')}/$name",it)}?:throw IOException("Cannot open the selected file")}
        notice="Uploaded $name";browse(path)
    }
    fun refreshApps() {
        val epoch=generation;val system=includeSystem
        val key=epoch to system
        if(appLoadKey==key)return
        appLoadKey=key
        val request=++appRequest
        work("Reading installed apps") {
            try {
                val s=requireSession();val result=withContext(Dispatchers.IO){readText(s,"pm list packages ${if(system)"" else "-3"}",512000)}
                if(epoch==generation&&request==appRequest){packages=result.lines().filter{it.startsWith("package:")}.map{it.removePrefix("package:").trim()}.filter(String::isNotEmpty).distinct().sorted();appsLoadedSystem=system;status="${packages.size} apps"}
            } finally { if(request==appRequest)appLoadKey=null }
        }
    }
    fun uninstall(packageName: String) = work("Uninstalling app") {
        val s=requireSession();val result=withContext(Dispatchers.IO){readText(s,"pm uninstall ${quote(packageName)}")}
        if(!result.trim().startsWith("Success"))throw IOException(result.trim().ifBlank{"The device did not confirm the uninstall"})
        notice="App uninstalled";refreshApps()
    }
    fun install(uri: Uri) = work("Preparing APKs") {
        val s=requireSession();val temp=File(context.cacheDir,"install-${UUID.randomUUID()}").apply{mkdirs()};val remote=mutableListOf<String>();var installSession:String?=null;var committed=false
        try {
            withContext(Dispatchers.IO) {
                val chosen=displayName(uri);val artifacts=mutableListOf<File>();var total=0L
                fun stage(input:InputStream):File {
                    val file=File(temp,"part-${artifacts.size}.apk")
                    file.outputStream().use { output -> val bytes=ByteArray(65536);while(true){val n=input.read(bytes);if(n<0)break;total+=n;if(total>1024L*1024*1024)throw IOException("The selected install exceeds 1 GB");output.write(bytes,0,n)} }
                    artifacts+=file;return file
                }
                val source=context.contentResolver.openInputStream(uri)?:throw IOException("Cannot read the selected package")
                if(chosen.endsWith(".apk",true))source.use(::stage)
                else source.use { raw -> ZipInputStream(raw).use { zip -> while(true){val entry=zip.nextEntry?:break;if(!entry.isDirectory&&entry.name.endsWith(".apk",true)){if(artifacts.size>=256)throw IOException("Too many APK splits");stage(zip)};zip.closeEntry()} } }
                if(artifacts.isEmpty())throw IOException("Choose an APK or an APKS/ZIP bundle containing APK splits")
                val create=readText(s,"pm install-create -r -S $total")
                installSession=Regex("\\[(\\d+)]").find(create)?.groupValues?.get(1)?:throw IOException(create.trim())
                for((index,file)in artifacts.withIndex()){
                    val destination="/data/local/tmp/adbox-${UUID.randomUUID()}-$index.apk";remote+=destination
                    file.inputStream().use { SyncTransfer.push(s.open("sync:"),destination,it) }
                    val write=readText(s,"pm install-write -S ${file.length()} $installSession part$index.apk ${quote(destination)}")
                    if(!write.trim().startsWith("Success"))throw IOException(write.trim())
                }
                val result=readText(s,"pm install-commit $installSession")
                if(!result.trim().startsWith("Success"))throw IOException(result.trim())
                committed=true
            }
            notice="App installed";status="Installation complete";refreshApps()
        } finally {
            withContext(NonCancellable+Dispatchers.IO){
                if(!committed&&installSession!=null)runCatching{readText(s,"pm install-abandon $installSession")}
                for(path in remote)runCatching{readText(s,"rm -f ${quote(path)}")}
                temp.deleteRecursively()
            }
        }
    }
    fun refreshLogs(background: Boolean = false): Job {
        logReadJob?.takeIf { it.isActive }?.let { return it }
        val epoch=generation
        val action: suspend () -> Unit = { val s=requireSession();val result=withContext(Dispatchers.IO){readText(s,"logcat -d -v threadtime -t 400",256000)};if(epoch==generation){logs=result;logsLoaded=true;if(!background)status="Logs updated"} }
        val job=if(background)viewModelScope.launch {
            try { action() } catch(e:CancellationException) { throw e } catch(e:Exception) { if(epoch==generation){notice="Log refresh stopped: ${e.message}";stopLogs()} }
        } else work("Reading logcat",action)
        logReadJob=job
        return job
    }
    fun toggleLiveLogs() {
        if(liveLogs){stopLogs();return}
        if(device==null){notice="Connect a device first";return}
        liveLogs=true
        logJob=viewModelScope.launch { while(isActive&&session?.connected()==true){if(uiVisible&&screen==Screen.Logs&&!recording&&!busy)refreshLogs(background=true).join();delay(3000)};liveLogs=false }
    }
    private fun stopLogs(){liveLogs=false;logJob?.cancel();logJob=null;logReadJob?.cancel();logReadJob=null}
    fun captureScreenshot() = work("Capturing device screen") {
        val epoch=generation
        val s=requireSession()
        val bytes=withContext(Dispatchers.IO){s.open("exec:screencap -p").use{channel->AdbResponses.readPng(channel.input(),32*1024*1024)}}
        if(epoch==generation){screenshot=bytes;status="Screenshot captured"}
    }
    fun saveBytes(uri:Uri,bytes:ByteArray) = work("Saving export") {withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use{it.write(bytes)}?:throw IOException("Cannot save to the selected destination")};notice="Export saved"}
    fun changeTheme(value:String){theme=value;preferences.edit().putString("theme",value).apply()}
    fun setDynamic(value:Boolean){dynamicColors=value;preferences.edit().putBoolean("dynamic",value).apply()}
    fun reboot(mode:String) = work("Rebooting device") {val s=requireSession();withContext(Dispatchers.IO){s.open("reboot:$mode").close()};disconnect();if(mode=="bootloader"||mode=="fastboot")screen=Screen.Fastboot;notice="Reboot requested"}
    fun startTerminal(){
        if(terminalRunning)return
        val s=try{requireSession()}catch(e:Exception){notice=e.message;return};val epoch=generation
        val token=++terminalToken;val reason="terminal:$epoch:$token"
        terminalRunning=true;terminalText="Opening an interactive shell…";screen=Screen.Terminal
        terminalJob=viewModelScope.launch{
            var channel:AdbChannel?=null
            try{
                channel=withContext(Dispatchers.IO){(try{terminalV2=true;s.open("shell,v2,TERM=xterm-256color,pty:")}catch(e:Exception){terminalV2=false;s.open("shell:")}).also{channel=it}}
                terminalChannel=channel;val c=channel;val buffer=TerminalBuffer(24,80)
                val inputs=Channel<String>(64);terminalInputs=inputs;val v2=terminalV2
                terminalInputJob=launch(Dispatchers.IO) {
                    try {
                        for(text in inputs){val bytes=text.toByteArray();if(v2)ShellV2.write(c.output(),0,bytes)else{c.output().write(bytes);c.output().flush()}}
                    } catch(e:CancellationException) { throw e } catch(e:Exception) {
                        withContext(Dispatchers.Main){if(epoch==generation&&token==terminalToken)notice=e.message}
                        runCatching{c.close()}
                    }
                }
                FastbootOperationService.retain(context,reason,"Interactive ADB terminal")
                coalescedText({epoch==generation&&token==terminalToken},{terminalText=it}) { update -> withContext(Dispatchers.IO){
                    if(terminalV2)ShellV2.resize(c.output(),24,80)
                    val input=if(!terminalV2)c.input()else object:InputStream(){var bytes=ByteArray(0);var offset=0;var ended=false
                        override fun read():Int{val one=ByteArray(1);return if(read(one,0,1)<0)-1 else one[0].toInt()and 255}
                        override fun read(out:ByteArray,start:Int,length:Int):Int{while(offset>=bytes.size){if(ended)return -1;val p=ShellV2.read(c.input());if(p.id==3){ended=true;return -1};if(p.id==1||p.id==2){bytes=p.data;offset=0}}
                            val n=minOf(length,bytes.size-offset);bytes.copyInto(out,start,offset,offset+n);offset+=n;return n}
                    }
                    val reader=InputStreamReader(input,Charsets.UTF_8);val chars=CharArray(2048)
                    while(true){val n=reader.read(chars);if(n<0)break;buffer.feed(String(chars,0,n));update(buffer.snapshot())}
                } }
            }catch(e:CancellationException){throw e}catch(e:Exception){if(epoch==generation)notice=e.message}
            finally{if(terminalChannel===channel){terminalInputs?.close();terminalInputs=null;terminalInputJob?.cancel();terminalInputJob=null};withContext(NonCancellable+Dispatchers.IO){runCatching{channel?.close()}};if(terminalChannel===channel){terminalChannel=null;terminalRunning=false};FastbootOperationService.release(context,reason)}
        }
    }
    fun terminalInput(text:String){val inputs=terminalInputs?:return;if(inputs.trySend(text).isFailure)notice="Shell input is busy. Wait for the target and try again."}
    fun stopTerminal(){terminalToken++;terminalInputs?.close();terminalInputs=null;terminalInputJob?.cancel();terminalInputJob=null;terminalJob?.cancel();val c=terminalChannel;terminalChannel=null;terminalRunning=false;viewModelScope.launch(Dispatchers.IO){runCatching{c?.close()}}}
    fun loadScripts(){try{val a=org.json.JSONArray(preferences.getString("scripts","[]"));scripts=(0 until a.length()).map{val o=a.getJSONObject(it);SavedScript(o.getString("name"),o.getString("body"))}}catch(_:Exception){scripts=emptyList()}}
    fun saveScript(name:String,body:String){if(name.isBlank()||body.isBlank())return;scripts=(scripts.filter{it.name!=name}+SavedScript(name.trim(),body)).sortedBy{it.name};persistScripts()}
    fun deleteScript(name:String){scripts=scripts.filter{it.name!=name};persistScripts()}
    private fun persistScripts(){val a=org.json.JSONArray();scripts.forEach{a.put(org.json.JSONObject().put("name",it.name).put("body",it.body))};preferences.edit().putString("scripts",a.toString()).apply()}
    fun runScript(script:SavedScript){runShell(script.body,true)}
    fun startMirror(){
        if(mirrorRunning||busy)return
        val s=try{requireSession()}catch(e:Exception){notice=e.message;return}
        if(device?.mode=="Sideload"){notice="Screen control requires a normal Android ADB target";return}
        val surface=mirrorSurface
        if(mirrorVideo&&(surface==null||!surface.isValid)){notice="Wait for the screen view to be ready";return}
        val epoch=generation;val token=++mirrorToken;mirrorSessionToken=token
        val reason="mirror:$epoch:$token";val video=mirrorVideo;val quality=mirrorQuality
        mirrorRunning=true;mirrorReady=false;mirrorFps=0;mirrorStatus=if(video)"Preparing USB video"else "Capturing device screen";screen=Screen.Mirror
        val client=if(video)RemoteScreenSession(context){service,defer->s.open(service,defer)}else null
        mirrorClient=client
        mirrorJob=viewModelScope.launch{
            val ticker=launch{var previous=0L;while(isActive){delay(1000);val count=client?.decoder?.rendered?.get()?:0L;if(epoch==generation&&token==mirrorToken&&video)mirrorFps=(count-previous).toInt().coerceAtLeast(0);previous=count}}
            try{
                FastbootOperationService.retain(context,reason,"USB screen control")
                if(client!=null){
                    withContext(Dispatchers.IO){client.run(surface!!,quality,{message->viewModelScope.launch{if(epoch==generation&&token==mirrorToken){mirrorStatus=message;mirrorReady=message.startsWith("USB video")}}},{w,h->viewModelScope.launch{if(epoch==generation&&token==mirrorToken){mirrorWidth=w;mirrorHeight=h}}})}
                }else{
                    var count=0;var stamp=System.nanoTime()
                    while(isActive&&epoch==generation&&token==mirrorToken){
                        if(busy){delay(100);continue}
                        val started=System.nanoTime()
                        val frame=withContext(Dispatchers.IO){
                            val c=s.open("exec:screencap -p");mirrorChannel=c
                            try{c.use{val bytes=AdbResponses.readPng(it.input(),32*1024*1024)
                                val bounds=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true};android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                                val options=android.graphics.BitmapFactory.Options().apply{inSampleSize=2}
                                val bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)?:throw IOException("Cannot decode device screen")
                                Triple(bitmap,bounds.outWidth,bounds.outHeight)
                            }}finally{if(mirrorChannel===c)mirrorChannel=null}
                        }
                        if(epoch==generation&&token==mirrorToken){mirrorFrame=frame.first;mirrorWidth=frame.second;mirrorHeight=frame.third;mirrorReady=true;mirrorStatus="Compatibility screenshots"}
                        count++;val now=System.nanoTime();if(now-stamp>=1_000_000_000L){if(token==mirrorToken)mirrorFps=count;count=0;stamp=now}
                        val remaining=100-(System.nanoTime()-started)/1_000_000L;if(remaining>0)delay(remaining)
                    }
                }
            }catch(e:CancellationException){throw e}catch(e:Exception){if(epoch==generation&&token==mirrorToken){mirrorStatus=(e.message?:"Screen control stopped").take(2000);notice="Screen control stopped. ${e.message.orEmpty().take(160)}"}}
            finally{ticker.cancel();withContext(NonCancellable+Dispatchers.IO){runCatching{client?.close()}};if(token==mirrorToken){mirrorRunning=false;mirrorReady=false;mirrorFps=0;mirrorClient=null};FastbootOperationService.release(context,reason)}
        }
    }
    fun stopMirror(){
        mirrorToken++;mirrorSessionToken=mirrorToken;mirrorJob?.cancel();mirrorJob=null;mirrorRunning=false;mirrorReady=false;mirrorFps=0;mirrorStatus="Paused"
        val c=mirrorChannel;mirrorChannel=null;val client=mirrorClient;mirrorClient=null
        viewModelScope.launch(Dispatchers.IO){runCatching{client?.close()};runCatching{c?.close()}}
    }
    fun screenTouch(action:Int,x:Int,y:Int,width:Int,height:Int,expectedToken:Int){
        if(!mirrorRunning||!mirrorReady||expectedToken!=mirrorToken)return
        try{mirrorClient?.touch(action,x.coerceIn(0,width-1),y.coerceIn(0,height-1),width,height)}catch(e:Exception){notice=e.message}
    }
    fun sendTouch(x1:Int,y1:Int,x2:Int=x1,y2:Int=y1,duration:Int=0){val command=if(duration<80&&kotlin.math.abs(x2-x1)+kotlin.math.abs(y2-y1)<12)"input tap $x1 $y1"else "input swipe $x1 $y1 $x2 $y2 ${duration.coerceIn(80,3000)}";deviceInput(command)}
    fun sendKey(code:Int){val client=mirrorClient;if(client!=null&&mirrorReady){runCatching{client.key(code)}.onFailure{notice=it.message}}else deviceInput("input keyevent $code")}
    private fun deviceInput(command:String){val s=session?:return;val epoch=generation;viewModelScope.launch{try{withContext(Dispatchers.IO){readText(s,command,64000)}}catch(e:Exception){if(epoch==generation)notice=e.message}}}
    fun searchFiles(query:String){val request=++searchRequest;if(query.isBlank()){searchResults=emptyList();return};val s=session?:return;val path=remotePath;val epoch=generation;work("Searching files"){val result=withContext(Dispatchers.IO){readText(s,"find ${quote(path)} -iname ${quote("*$query*")} 2>/dev/null | head -n 200",128000).lineSequence().filter{it.startsWith('/')}.take(200).distinct().toList()};if(epoch==generation&&path==remotePath&&request==searchRequest)searchResults=result}}
    fun renameFile(name:String,newName:String){if(newName.isBlank()||newName in listOf(".","..")||newName.any{it=='/'||it=='\u0000'}){notice="Enter a file name without slashes";return};val s=session?:return;val path=remotePath;work("Renaming file"){withContext(Dispatchers.IO){readText(s,"mv -- ${quote("${path.trimEnd('/')}/$name")} ${quote("${path.trimEnd('/')}/$newName")}")};browse(path)}}
    fun deleteFile(name:String){if(name.isBlank()||name in listOf(".","..")||name.contains('/'))return;val s=session?:return;val path=remotePath;work("Deleting $name"){withContext(Dispatchers.IO){readText(s,"rm -rf -- ${quote("${path.trimEnd('/')}/$name")}")};browse(path)}}
    fun exportPackage(pkg:String,uri:Uri){val s=session?:return;work("Exporting APK splits"){withContext(Dispatchers.IO){val paths=readText(s,"pm path ${quote(pkg)}").lines().filter{it.startsWith("package:")}.map{it.removePrefix("package:").trim()};if(paths.isEmpty())throw IOException("No readable APK paths returned");context.contentResolver.openOutputStream(uri)?.use{raw->java.util.zip.ZipOutputStream(raw).use{zip->paths.forEachIndexed{i,path->zip.putNextEntry(java.util.zip.ZipEntry(if(i==0)"base.apk"else "split-$i.apk"));SyncTransfer.pull(s.open("sync:"),path,zip);zip.closeEntry()};zip.putNextEntry(java.util.zip.ZipEntry("adbox-export.json"));zip.write(org.json.JSONObject().put("package",pkg).put("device",device?.serial).toString().toByteArray());zip.closeEntry()}}?:throw IOException("Cannot write APK export")};notice="APK bundle exported"}}
    fun toggleRecording() {
        val id=activeConnectionId?:return
        if(id in recordingIds){recordJobs.remove(id)?.cancel();recordChannels.remove(id)?.let{c->viewModelScope.launch(Dispatchers.IO){runCatching{c.close()}}};return}
        val s=session?:return
        val initialText=logs
        val file=File(context.filesDir,"logcat-${device?.serial?.replace(Regex("[^A-Za-z0-9_.-]"),"_")?.take(32)}-${System.currentTimeMillis()}.txt")
        lastRecording=file;recordingIds=recordingIds+id
        recordJobs[id]=viewModelScope.launch {
            FastbootOperationService.retain(context,"log:$id","Recording device logcat")
            try {
                coalescedText({activeConnectionId==id},{logs=it;logsLoaded=true}) { update->withContext(Dispatchers.IO) {
                    val c=s.open("shell,v2,raw:logcat -v threadtime");recordChannels[id]=c
                    c.use { file.outputStream().buffered(65536).use { out->
                        val preview=StringBuilder(initialText);var written=0L;var lastPreview=0L;var lastFlush=System.nanoTime()
                        while(true) {
                            val packet=ShellV2.read(c.input());if(packet.id==3)break
                            if(packet.id==1||packet.id==2) {
                                if(written+packet.data.size>256L*1024*1024)throw IOException("Recording reached 256 MB; export it before starting another")
                                out.write(packet.data);written+=packet.data.size
                                preview.append(String(packet.data,Charsets.UTF_8));if(preview.length>256000)preview.delete(0,preview.length-256000)
                                val now=System.nanoTime()
                                if(now-lastPreview>=50_000_000L){update(preview.toString());lastPreview=now}
                                if(now-lastFlush>=1_000_000_000L){out.flush();lastFlush=now}
                            }
                        }
                        update(preview.toString())
                    } }
                } }
            } catch(e:CancellationException){throw e} catch(e:Exception){notice="Log recording stopped: ${e.message}"}
            finally {
                withContext(NonCancellable+Dispatchers.IO){runCatching{recordChannels.remove(id)?.close()}}
                recordingIds=recordingIds-id;recordJobs.remove(id);FastbootOperationService.release(context,"log:$id")
            }
        }
    }
    fun exportRecording(uri:Uri){val file=lastRecording?:run{notice="No recording yet";return};work("Exporting recorded logcat"){withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use{out->file.inputStream().use{it.copyTo(out)}}?:throw IOException("Cannot save recording")};notice="Recorded logcat exported"}}
    private fun rootCommand(command:String,root:Boolean)=if(root)"su -c ${quote(command)}"else command
    private suspend fun stage(uri:Uri,suffix:String=".bin"):File=withContext(Dispatchers.IO){
        val file=File.createTempFile("adbox-stage-",suffix,context.cacheDir)
        try{context.contentResolver.openInputStream(uri)?.use{input->file.outputStream().use{out->val bytes=ByteArray(65536);var copied=0L;while(true){val n=input.read(bytes);if(n<0)break;if(n==0)continue;copied+=n;if(copied>32L*1024*1024*1024)throw IOException("File exceeds 32 GB");out.write(bytes,0,n)}}}?:throw IOException("Cannot open selected file");if(file.length()==0L)throw IOException("Selected file is empty");file}catch(e:Exception){file.delete();throw e}
    }
    fun adbSideload(uri:Uri){
        if(busy)return
        val s=session?.takeIf{it.connected()}?:run{notice="Connect recovery in Apply update from ADB mode";return}
        val epoch=generation;val token=++sideloadToken
        work("Preparing recovery update"){
            var file:File?=null
            sideload=SideloadStatus(SideloadPhase.Preparing,displayName(uri),message="Copying and checking the update ZIP",target="${device?.model} · ${device?.serial}")
            var latest=sideload
            try{
                FastbootOperationService.start(context,"ADB sideload")
                FastbootOperationService.update(context,"ADB sideload","Preparing update ZIP",-2)
                val staged=stage(uri,".zip");file=staged
                withContext(Dispatchers.IO){java.util.zip.ZipFile(staged).use{if(!it.entries().hasMoreElements())throw IOException("Empty update ZIP")}}
                if(epoch!=generation||!s.connected())throw IOException("USB disconnected while preparing the update")
                latest=sideload.copy(phase=SideloadPhase.Serving,totalBytes=staged.length(),message="Recovery is reading and verifying the update")
                sideload=latest;status="Serving recovery update"
                withContext(Dispatchers.IO){
                    var lastUpdate=0L;var lastPhase=SideloadPhase.Serving
                    SideloadClient.serve(s.open(SideloadClient.service(staged.length())),staged){unique,sent,total->
                        val phase=if(unique==total)SideloadPhase.Waiting else SideloadPhase.Serving
                        latest=latest.copy(phase=phase,uniqueBytes=unique,transferredBytes=sent,totalBytes=total,message=if(phase==SideloadPhase.Waiting)"Full ZIP read. Waiting for recovery to finish; it may read blocks again."else "Recovery is reading and verifying the update")
                        val now=android.os.SystemClock.elapsedRealtime()
                        if(lastUpdate==0L||now-lastUpdate>=150L||phase!=lastPhase){
                            lastUpdate=now;lastPhase=phase;val snapshot=latest
                            viewModelScope.launch{if(token==sideloadToken&&sideload.running)sideload=snapshot}
                            FastbootOperationService.update(context,"ADB sideload",if(phase==SideloadPhase.Waiting)"ZIP read · waiting for recovery"else "${snapshot.percent}% of ZIP read · ${formatBytes(sent)} sent",if(phase==SideloadPhase.Waiting)-2 else snapshot.percent)
                        }
                    }
                }
                sideload=latest.copy(phase=SideloadPhase.Complete,message="Transfer complete. Check recovery's screen for the installation result.")
                status="Sideload transfer complete";notice=sideload.message
            }catch(e:CancellationException){
                sideload=latest.copy(phase=SideloadPhase.Failed,message="Sideload interrupted. Check recovery before trying again.");throw e
            }catch(e:Exception){
                sideload=latest.copy(phase=SideloadPhase.Failed,message=e.message?:"Sideload stopped. Check the cable and recovery screen.")
                if(!s.connected())disconnect(true)
                status="Sideload stopped";notice="Sideload stopped. Details are in the progress card."
            }finally{
                withContext(NonCancellable+Dispatchers.IO){file?.delete()}
                FastbootOperationService.stop(context)
            }
        }
    }
    fun backupAdb(path:String,partition:Boolean,root:Boolean,uri:Uri){val s=session?:return;work("Backing up $path"){
        FastbootOperationService.start(context,"Device backup")
        try{withContext(Dispatchers.IO){if(!path.startsWith('/')||path.contains('\u0000'))throw IOException("Enter an absolute device path");val command=if(partition)"dd if=${quote(path)} bs=65536"else "tar -C ${quote(path)} -cf - .";context.contentResolver.openOutputStream(uri)?.use{out->val size=ShellV2.run(s.open("shell,v2,raw:${rootCommand(command,root)}"),null,out);if(size==0L)throw IOException("Device returned an empty backup")}?:throw IOException("Cannot open backup destination")};notice="Backup saved"}
        finally{FastbootOperationService.stop(context)}
    }}
    fun restoreAdb(path:String,partition:Boolean,root:Boolean,uri:Uri){val s=session?:return;work("Restoring $path"){
        var file:File?=null;FastbootOperationService.start(context,"Device restore")
        try{file=stage(uri);withContext(Dispatchers.IO){if(!path.startsWith('/')||path=="/"||path.contains('\u0000'))throw IOException("Enter a destination below the device root");if(partition){if(FastbootClient.isSparse(file))throw IOException("Restore a raw partition image; use Fastboot for sparse images");val size=readText(s,rootCommand("blockdev --getsize64 ${quote(path)}",root)).trim().toLongOrNull()?:throw IOException("Cannot read the target partition size");if(file!!.length()>size)throw IOException("Image is larger than the destination partition")}else TarSafety.validate(file!!)
            val command=if(partition)"dd of=${quote(path)} bs=65536 conv=fsync"else "tar -C ${quote(path)} -xf -"
            file!!.inputStream().use{input->val errors=ByteArrayOutputStream();ShellV2.run(s.open("shell,v2,raw:${rootCommand(command,root)}"),input,errors)}
        };notice="Restore complete"}finally{withContext(NonCancellable+Dispatchers.IO){file?.delete()};FastbootOperationService.stop(context)}
    }}
    fun fastbootBackup(partition:String,uri:Uri){if(busy)return;work("Reading back $partition"){
        val client=requireFastboot();val epoch=generation;FastbootOperationService.start(context,"Partition backup")
        try{withContext(Dispatchers.IO){val size=FastbootClient.byteSize(client.variable("partition-size:$partition"))?:throw IOException("The bootloader must expose the partition size for readback");val max=try{FastbootClient.byteSize(client.variable("max-fetch-size"))}catch(_:FastbootClient.Rejected){null};val chunk=minOf(max?:64L*1024*1024,0xffffffffL)
            context.contentResolver.openOutputStream(uri)?.use{out->var offset=0L;while(offset<size){val n=minOf(chunk,size-offset);client.fetch(partition,offset,n,out,{message->fastbootLog(epoch,message)}){got,_->viewModelScope.launch{advancedProgress="Read ${formatBytes(offset+got)} / ${formatBytes(size)}"}};offset+=n};out.flush()}?:throw IOException("Cannot write partition backup")
        };notice="Partition backup saved"}finally{FastbootOperationService.stop(context)}
    }}
    fun prepareFirmware(uris:List<Uri>,zip:Boolean){if(busy||uris.isEmpty())return;work("Inspecting firmware images"){
        discardFirmware();val dir=File(context.cacheDir,"firmware-${UUID.randomUUID()}").apply{mkdirs()};firmwareDir=dir
        try{val images=mutableListOf<FirmwareItem>();withContext(Dispatchers.IO){
            fun extract(archive:File,depth:Int){if(depth>2)throw IOException("Firmware archives are nested too deeply");java.util.zip.ZipFile(archive).use{zf->val entries=zf.entries();while(entries.hasMoreElements()){val e=entries.nextElement();if(e.isDirectory)continue;val image=e.name.endsWith(".img",true);val nested=e.name.endsWith(".zip",true);if(!image&&!nested)continue;if(images.size>=128)throw IOException("Too many firmware images");val dest=File(dir,"${UUID.randomUUID()}.${if(image)"img"else "zip"}");zf.getInputStream(e).use{input->dest.outputStream().use{out->val bytes=ByteArray(65536);var copied=0L;while(true){val n=input.read(bytes);if(n<0)break;if(n==0)continue;copied+=n;if(copied>32L*1024*1024*1024)throw IOException("Firmware image exceeds 32 GB");out.write(bytes,0,n)}}};if(nested){extract(dest,depth+1);dest.delete()}else{val name=e.name.substringAfterLast('/');images+=FirmwareItem(dest,name,name.substringBeforeLast('.'))}}}}
            for(uri in uris){val file=stage(uri,if(zip)".zip"else ".img");if(zip){try{extract(file,0)}finally{file.delete()}}else{val name=displayName(uri);val dest=File(dir,"${images.size}.img");if(!file.renameTo(dest)){file.copyTo(dest);file.delete()};images+=FirmwareItem(dest,name,name.substringBeforeLast('.'))}}
        };if(images.isEmpty())throw IOException("No .img files found in this firmware bundle");firmware=images;firmwareTarget=activeConnectionId;notice="Review each image and exact destination partition"}
        catch(e:Exception){discardFirmware();throw e}
    }}
    fun editFirmware(index:Int,partition:String){firmware=firmware.mapIndexed{i,item->if(i==index)item.copy(partition=partition)else item}}
    fun removeFirmware(index:Int){firmware.getOrNull(index)?.file?.delete();firmware=firmware.filterIndexed{i,_->i!=index}}
    fun discardFirmware(){firmware=emptyList();firmwareDir?.deleteRecursively();firmwareDir=null;firmwareTarget=null}
    private fun flashPrepared(client:FastbootClient,file:File,partition:String,log:java.util.function.Consumer<String>,progress:FastbootClient.Progress){
        val image=SparseImage(file);val size=try{FastbootClient.byteSize(client.variable("partition-size:$partition"))}catch(_:FastbootClient.Rejected){null};if(size!=null&&image.expandedSize>size)throw IOException("Image is larger than partition $partition")
        val limit=try{FastbootClient.byteSize(client.variable("max-download-size"))}catch(_:FastbootClient.Rejected){null}?:256L*1024*1024
        if(file.length()<=minOf(limit,0xffffffffL)){client.flash(partition,file,log,progress);return}
        val part=File.createTempFile("adbox-sparse-",".img",context.cacheDir);try{var number=0;while(image.next(part,minOf(limit,0xffffffffL))){log.accept("Sparse segment ${++number} → $partition\n");client.flash(partition,part,log,progress)}}finally{part.delete()}
    }
    fun flashFirmware(){if(busy)return;if(activeConnectionId!=firmwareTarget){notice="Prepare a new flash plan for the currently connected device";return};val plan=firmware.toList();work("Flashing firmware plan"){
        val client=requireFastboot();val epoch=generation;FastbootOperationService.start(context,"Firmware flash")
        try{withContext(Dispatchers.IO){plan.forEach{FastbootClient.validatePartition(it.partition);val image=SparseImage(it.file);val size=try{FastbootClient.byteSize(client.variable("partition-size:${it.partition}"))}catch(_:FastbootClient.Rejected){null};if(size!=null&&image.expandedSize>size)throw IOException("${it.name} is larger than partition ${it.partition}")};for((index,item)in plan.withIndex()){withContext(Dispatchers.Main){advancedProgress="${index+1}/${plan.size}: ${item.name} → ${item.partition}"};flashPrepared(client,item.file,item.partition,java.util.function.Consumer{fastbootLog(epoch,it)},FastbootClient.Progress{sent,total->FastbootOperationService.update(context,"Flashing ${item.partition}","${index+1}/${plan.size}",(sent*100/total).toInt())})}};notice="Firmware plan completed";discardFirmware()}
        finally{FastbootOperationService.stop(context)}
    }}

    override fun onCleared() {clearUsbPermissionRequest();stopMirror();context.unregisterReceiver(receiver);shellJob?.cancel();logJob?.cancel();connectionWatch?.cancel();runCatching{activeShell?.close()};runCatching{session?.close()};runCatching{fastboot?.close()};FastbootOperationService.stop(context);super.onCleared()}
    companion object {
        fun quote(value:String)=AdbResponses.quote(value)
        fun normalizeFastboot(value:String):String {
            val input=value.trim().removePrefix("fastboot ").trim()
            val words=input.split(Regex("\\s+"))
            return when{words.size==2&&words[0]in listOf("getvar","erase","set_active")->"${words[0]}:${words[1]}";input=="reboot bootloader"->"reboot-bootloader";input=="reboot fastboot"->"reboot-fastboot";input=="reboot recovery"->"reboot-recovery";else->input}
        }
        fun formatBytes(bytes:Long):String=when{bytes>=1073741824->String.format(java.util.Locale.US,"%.1f GB",bytes/1073741824.0);bytes>=1048576->String.format(java.util.Locale.US,"%.1f MB",bytes/1048576.0);bytes>=1024->String.format(java.util.Locale.US,"%.1f KB",bytes/1024.0);else->"$bytes B"}
    }
}
