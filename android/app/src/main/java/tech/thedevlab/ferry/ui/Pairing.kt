package tech.thedevlab.ferry.ui

import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import tech.thedevlab.ferry.crypto.FerryCrypto
import java.util.concurrent.Executors

/** `ferry://pair?v=1&s=<server>&u=<userId>&c=<code>&k=<UMK>` (§5.3). Contains the key — handle with care. */
class PairingLink(val server: String, val userId: String, val code: String, val umk: ByteArray) {
    fun toUri(): String = Uri.Builder()
        .scheme("ferry")
        .authority("pair")
        .appendQueryParameter("v", "1")
        .appendQueryParameter("s", server)
        .appendQueryParameter("u", userId)
        .appendQueryParameter("c", code)
        .appendQueryParameter("k", FerryCrypto.toB64u(umk))
        .build()
        .toString()

    companion object {
        fun parse(text: String): PairingLink? = runCatching {
            val uri = Uri.parse(text.trim())
            if (uri.scheme != "ferry" || uri.host != "pair" || uri.getQueryParameter("v") != "1") return null
            val umk = FerryCrypto.fromB64u(uri.getQueryParameter("k")!!)
            if (umk.size != FerryCrypto.UMK_BYTES) return null
            PairingLink(uri.getQueryParameter("s")!!, uri.getQueryParameter("u")!!, uri.getQueryParameter("c")!!, umk)
        }.getOrNull()
    }
}

fun qrBitmap(text: String, size: Int = 720): ImageBitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size) { if (matrix[it % size, it / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Camera preview that reports the first QR code it decodes. */
@Composable
fun QrScanner(modifier: Modifier = Modifier, onResult: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
            executor.shutdown()
        }
    }

    AndroidView(modifier = modifier, factory = { ctx ->
        PreviewView(ctx).also { view ->
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                val reader = QRCodeReader()
                val main = Handler(Looper.getMainLooper())
                var done = false
                analysis.setAnalyzer(executor) { image ->
                    image.use {
                        if (!done) decode(reader, it)?.let { text ->
                            done = true
                            main.post { onResult(text) }
                        }
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(ctx))
        }
    })
}

private fun decode(reader: QRCodeReader, image: ImageProxy): String? {
    val plane = image.planes[0]
    val stride = plane.rowStride
    val data = ByteArray(stride * image.height)
    plane.buffer.rewind()
    plane.buffer.get(data, 0, minOf(plane.buffer.remaining(), data.size))
    val source = PlanarYUVLuminanceSource(data, stride, image.height, 0, 0, image.width, image.height, false)
    return try {
        reader.decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true)).text
    } catch (_: NotFoundException) {
        null
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
}
