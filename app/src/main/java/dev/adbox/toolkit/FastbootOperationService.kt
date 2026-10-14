package dev.adbox.toolkit

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Shared foreground lifetime for connections, recording and firmware operations. */
class FastbootOperationService:Service(){
    private var lock:PowerManager.WakeLock?=null
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate(){super.onCreate();ensureChannel(this);lock=(getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ADBox:ActiveOperation").apply{setReferenceCounted(false)}}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(!hasUsbPermission(this)){stopSelf();return START_NOT_STICKY}
        val title=synchronized(reasons){reasons.values.lastOrNull()?:"ADBox connections"}
        val notification=notification(this,title,"Tap to return to ADBox",-1)
        if(Build.VERSION.SDK_INT>=29)startForeground(ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)else startForeground(ID,notification)
        val needsWake=synchronized(reasons){reasons.keys.any{it!="connections"}}
        if(needsWake&&lock?.isHeld!=true)lock?.acquire(60*60*1000L)else if(!needsWake&&lock?.isHeld==true)lock?.release()
        return START_NOT_STICKY
    }
    override fun onDestroy(){if(lock?.isHeld==true)lock?.release();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()}
    companion object{
        private const val CHANNEL="adbox-connections";private const val ID=42
        private val reasons=linkedMapOf<String,String>()
        private fun ensureChannel(context:Context){(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL,"Device connections & operations",NotificationManager.IMPORTANCE_LOW))}
        private fun notification(context:Context,title:String,text:String,percent:Int):Notification{
            ensureChannel(context)
            val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE).apply{if(percent==-2)setProgress(0,0,true)else if(percent>=0)setProgress(100,percent.coerceIn(0,100),false)}.build()
        }
        private fun hasUsbPermission(context:Context):Boolean{val usb=context.getSystemService(Context.USB_SERVICE) as UsbManager;return usb.deviceList.values.any{usb.hasPermission(it)}}
        fun retain(context:Context,key:String,title:String){synchronized(reasons){reasons[key]=title};if(hasUsbPermission(context))context.startForegroundService(Intent(context,FastbootOperationService::class.java))}
        fun release(context:Context,key:String){val empty=synchronized(reasons){reasons.remove(key);reasons.isEmpty()};if(empty||!hasUsbPermission(context))context.stopService(Intent(context,FastbootOperationService::class.java))else context.startForegroundService(Intent(context,FastbootOperationService::class.java))}
        fun start(context:Context,title:String)=retain(context,"operation",title)
        fun stop(context:Context)=release(context,"operation")
        fun update(context:Context,title:String,text:String,percent:Int=-1){if(hasUsbPermission(context))(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID,notification(context,title,text,percent))}
    }
}
