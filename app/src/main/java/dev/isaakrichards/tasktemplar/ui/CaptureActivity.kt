package dev.isaakrichards.tasktemplar.ui

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android only lets apps use the camera and microphone while they are visible. This invisible,
 * untouchable 1×1 activity becomes "visible" for the moment of capture, then closes.
 */
class CaptureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
        )
        window.setLayout(1, 1)
        val id = intent.getStringExtra("id") ?: run { finish(); return }
        val kind = intent.getStringExtra("kind") ?: "photo"
        lifecycleScope.launch {
            val result = runCatching {
                if (kind == "audio") record(this@CaptureActivity, intent.getLongExtra("seconds", 10))
                else photo(this@CaptureActivity, intent.getBooleanExtra("front", false))
            }
            pending.remove(id)?.complete(result)
            finish()
        }
    }

    companion object {
        private val pending = ConcurrentHashMap<String, CompletableDeferred<Result<String>>>()

        /** Starts a capture and waits for the saved file's location. */
        suspend fun capture(ctx: Context, kind: String, seconds: Long = 10, front: Boolean = false): String {
            val id = UUID.randomUUID().toString()
            val d = CompletableDeferred<Result<String>>()
            pending[id] = d
            try {
                ctx.startActivity(
                    Intent(ctx, CaptureActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                        .putExtra("id", id).putExtra("kind", kind).putExtra("seconds", seconds).putExtra("front", front),
                )
                val r = withTimeoutOrNull((seconds + 30) * 1000) { d.await() }
                    ?: error("Capture didn't start. Grant “Display over other apps” so it can run in the background")
                return r.getOrThrow()
            } finally {
                pending.remove(id)
            }
        }

        private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

        @SuppressLint("MissingPermission")
        suspend fun photo(ctx: Context, front: Boolean): String {
            val cm = ctx.getSystemService(CameraManager::class.java)
            val wantFacing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val camId = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wantFacing }
                ?: cm.cameraIdList.firstOrNull() ?: error("No camera")
            val chars = cm.getCameraCharacteristics(camId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: error("Camera has no outputs")
            val size = map.getOutputSizes(ImageFormat.JPEG).maxByOrNull { it.width.toLong() * it.height } ?: error("No JPEG sizes")
            val thread = HandlerThread("templar-cam").apply { start() }
            val handler = Handler(thread.looper)
            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
            val device = suspendCancellableCoroutine<CameraDevice> { cont ->
                cm.openCamera(camId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) { if (cont.isActive) cont.resume(camera) else camera.close() }
                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera disconnected"))
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera error $error (in use, or permission missing)"))
                    }
                }, handler)
            }
            try {
                @Suppress("DEPRECATION")
                val session = suspendCancellableCoroutine<CameraCaptureSession> { cont ->
                    device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { if (cont.isActive) cont.resume(s) }
                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera setup failed"))
                        }
                    }, handler)
                }
                var warmingUp = true
                val bytes = suspendCancellableCoroutine<ByteArray> { cont ->
                    reader.setOnImageAvailableListener({ r ->
                        val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                        if (warmingUp) {
                            img.close()
                            return@setOnImageAvailableListener
                        }
                        val buf = img.planes[0].buffer
                        val arr = ByteArray(buf.remaining()).also { buf.get(it) }
                        img.close()
                        if (cont.isActive) cont.resume(arr)
                    }, handler)
                    // Let auto-exposure and focus settle on a preview stream, then take the still.
                    val preview = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(reader.surface)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }.build()
                    session.setRepeatingRequest(preview, null, handler)
                    handler.postDelayed({
                        runCatching {
                            session.stopRepeating()
                            warmingUp = false
                            val still = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                addTarget(reader.surface)
                                set(CaptureRequest.JPEG_ORIENTATION, chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90)
                            }.build()
                            session.capture(still, null, handler)
                        }.onFailure { if (cont.isActive) cont.resumeWithException(it) }
                    }, 1500)
                }
                return saveMedia(ctx, "IMG_${stamp()}.jpg", "image/jpeg", Environment.DIRECTORY_PICTURES, bytes)
            } finally {
                device.close()
                reader.close()
                thread.quitSafely()
            }
        }

        @SuppressLint("MissingPermission")
        suspend fun record(ctx: Context, seconds: Long): String {
            val tmp = java.io.File(ctx.cacheDir, "rec-${stamp()}.m4a")
            val rec = newRecorder(ctx)
            try {
                rec.setAudioSource(MediaRecorder.AudioSource.MIC)
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioEncodingBitRate(128_000)
                rec.setAudioSamplingRate(44_100)
                rec.setOutputFile(tmp.absolutePath)
                rec.prepare()
                rec.start()
                delay(seconds.coerceIn(1, 3600) * 1000)
                rec.stop()
            } finally {
                rec.release()
            }
            val bytes = tmp.readBytes()
            tmp.delete()
            return saveMedia(ctx, "REC_${stamp()}.m4a", "audio/mp4", Environment.DIRECTORY_MUSIC, bytes)
        }

        @Suppress("DEPRECATION")
        private fun newRecorder(ctx: Context): MediaRecorder =
            if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()

        /** Saves into Pictures/TaskTemplar or Music/TaskTemplar and returns the readable path. */
        @Suppress("DEPRECATION")
        private fun saveMedia(ctx: Context, name: String, mime: String, dir: String, bytes: ByteArray): String {
            if (Build.VERSION.SDK_INT >= 29) {
                val collection = if (mime.startsWith("image")) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/TaskTemplar")
                }
                val uri = ctx.contentResolver.insert(collection, values) ?: error("Couldn't save $name")
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                return "${Environment.getExternalStorageDirectory()}/$dir/TaskTemplar/$name"
            }
            val f = java.io.File(Environment.getExternalStoragePublicDirectory(dir), "TaskTemplar/$name")
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
            return f.absolutePath
        }
    }
}
