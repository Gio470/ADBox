package dev.adbox.toolkit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun ScreenshotDialog(bytes:ByteArray,onDismiss:()->Unit,onSave:()->Unit) {
    var bitmap by remember(bytes) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(bytes) { mutableStateOf(true) }
    LaunchedEffect(bytes) {
        bitmap=withContext(Dispatchers.IO) {
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            var sample=1
            while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1600)sample*=2
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample })
        }
        loading=false
    }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Screenshot")},text={
        when {
            loading->CircularProgressIndicator()
            bitmap!=null->Image(bitmap!!.asImageBitmap(),"Screenshot of the target device",Modifier.fillMaxWidth().heightIn(max=430.dp).clip(RoundedCornerShape(18.dp)),contentScale=ContentScale.Fit)
            else->Text("Screenshot preview could not be displayed. You can still save the original PNG.")
        }
    },confirmButton={TextButton(onClick=onSave){Text("Save PNG")}},dismissButton={TextButton(onClick=onDismiss){Text("Close")}})
}
