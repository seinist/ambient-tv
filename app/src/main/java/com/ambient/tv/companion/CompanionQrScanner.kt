package com.ambient.tv.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.ambient.tv.R
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton
import java.io.Closeable

/**
 * Phone-side pairing scanner: points the camera at the TV's pairing QR code and hands the link to
 * [CompanionManager.pair], which validates it exactly as a scanned deep link.
 *
 * Deliberately built on the platform's Camera2 API and nothing else, so it adds no dependency
 * beyond the QR decoder. The camera is only opened while this dialog is on screen, frames are
 * decoded in process and never stored, and nothing is sent anywhere: a QR code that is not a
 * pairing invitation is rejected by the parser.
 */
@Composable
internal fun CompanionQrScannerDialog(manager: CompanionManager, onClose: () -> Unit) {
    AppDialog(
        onDismiss = onClose,
        title = stringResource(R.string.companion_scan_qr),
        footer = { TvActionButton(stringResource(R.string.companion_close), onClick = onClose) },
    ) {
        CompanionQrScanner(onDecoded = { text -> manager.pair(text); onClose() })
    }
}

/**
 * Camera permission, preview and decoding, embeddable in a dialog or a page. [onDecoded] fires
 * once; the camera is released as soon as this leaves the composition.
 */
@Composable
internal fun CompanionQrScanner(onDecoded: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var failure by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var decoded by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        granted = allowed
        denied = !allowed
    }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            failure -> Text(stringResource(R.string.companion_scan_camera_failed))
            !granted -> Text(stringResource(R.string.companion_scan_camera_denied))
            else -> {
                Text(stringResource(R.string.companion_scan_qr_hint))
                if (!decoded) CameraPreview(
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    onDecoded = { text -> if (!decoded) { decoded = true; onDecoded(text) } },
                    onFailure = { failure = true },
                )
            }
        }
        if (!granted && !failure) {
            TextButton({ request.launch(Manifest.permission.CAMERA) }) {
                Text(stringResource(R.string.companion_scan_camera_grant))
            }
            // After "Don't ask again" the request returns at once; the app settings still allow it.
            if (denied) TextButton({ openAppSettings(context) }) {
                Text(stringResource(R.string.companion_scan_open_settings))
            }
        }
    }
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", context.packageName, null))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * A live preview whose frames are decoded. The session is torn down when the composable leaves,
 * so the camera is never held by a dialog that is no longer shown.
 */
@Composable
private fun CameraPreview(modifier: Modifier, onDecoded: (String) -> Unit, onFailure: () -> Unit) {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onDecoded)
    val latestFailure by rememberUpdatedState(onFailure)
    val session = remember { QrScanSession(context, onDecoded = { text -> latest(text) }, onFailure = { latestFailure() }) }
    DisposableEffect(session) { onDispose { session.close() } }
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            TextureView(viewContext).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        if (!session.start(this@apply, texture, width, height)) onFailure()
                    }
                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                        session.applyTransform(this@apply, width, height)
                    }
                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        // The view can come back (it is only gone for good once the dialog leaves,
                        // which DisposableEffect handles): stop the camera, stay restartable.
                        session.stop()
                        return true
                    }
                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        },
    )
}

/**
 * One camera session: preview into the view, YUV frames into [CompanionQrDecoder]. Everything the
 * camera hands back arrives on [handler]'s thread; [close] is safe from any thread and more than
 * once.
 */
private class QrScanSession(
    private val context: Context,
    private val onDecoded: (String) -> Unit,
    private val onFailure: () -> Unit,
) : Closeable {
    private val decoder = CompanionQrDecoder()
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var frameSize: Size? = null
    private var sensorOrientation = 0
    @Volatile private var closed = false
    /** Bumped by every start and stop: a camera callback of an older session is stale. */
    private var generation = 0
    /** openCamera() was called and neither onOpened nor onError has arrived yet. */
    private var openPending = false
    private var luminance = ByteArray(0)
    private val lock = Any()

    fun start(view: TextureView, texture: SurfaceTexture, viewWidth: Int, viewHeight: Int): Boolean = synchronized(lock) {
        if (closed || camera != null) return true
        val manager = context.getSystemService(CameraManager::class.java) ?: return false
        return runCatching {
            val cameraId = backCameraId(manager) ?: error("No camera")
            val characteristics = manager.getCameraCharacteristics(cameraId)
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val configuration = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: error("No stream configuration")
            val size = analysisSize(configuration)
            frameSize = size
            texture.setDefaultBufferSize(size.width, size.height)
            applyTransform(view, viewWidth, viewHeight)

            val background = HandlerThread("companion-qr-scan").apply { start() }
            thread = background
            val backgroundHandler = Handler(background.looper)
            handler = backgroundHandler

            val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            reader = imageReader
            imageReader.setOnImageAvailableListener({ source -> onFrame(source) }, backgroundHandler)

            val preview = Surface(texture)
            previewSurface = preview
            generation++
            openPending = true
            openCamera(manager, cameraId, preview, imageReader.surface, backgroundHandler, background, generation)
            true
        }.getOrElse {
            stopLocked()
            false
        }
    }

    private fun openCamera(
        manager: CameraManager,
        cameraId: String,
        preview: Surface,
        analysis: Surface,
        backgroundHandler: Handler,
        background: HandlerThread,
        session: Int,
    ) {
        if (!hasCameraPermission(context)) error("Camera permission missing")
        @Suppress("MissingPermission")
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                synchronized(lock) {
                    if (closed || session != generation) {
                        // Stopped before the camera came up: close it here, or it stays open.
                        runCatching { device.close() }
                        if (thread !== background) runCatching { background.quitSafely() }
                        return
                    }
                    openPending = false
                    camera = device
                }
                runCatching { configureSession(device, preview, analysis, backgroundHandler) }
                    .onFailure { failFor(device) }
            }
            // A camera failure only ends this session; the scanner stays startable.
            override fun onDisconnected(device: CameraDevice) { runCatching { device.close() }; failFor(device, session, background) }
            override fun onError(device: CameraDevice, error: Int) { runCatching { device.close() }; failFor(device, session, background) }
        }, backgroundHandler)
    }

    @Suppress("DEPRECATION")
    private fun configureSession(device: CameraDevice, preview: Surface, analysis: Surface, backgroundHandler: Handler) {
        device.createCaptureSession(listOf(preview, analysis), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                synchronized(lock) {
                    if (closed) { runCatching { configured.close() }; return }
                    captureSession = configured
                }
                runCatching {
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(preview)
                        addTarget(analysis)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }.build()
                    configured.setRepeatingRequest(request, null, backgroundHandler)
                }.onFailure { failFor(device) }
            }
            override fun onConfigureFailed(configured: CameraCaptureSession) { runCatching { configured.close() }; failFor(device) }
        }, backgroundHandler)
    }

    /** One frame: the Y plane is the grey image the decoder needs, so nothing is converted. */
    private fun onFrame(source: ImageReader) {
        val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
        try {
            if (closed) return
            val plane = image.planes.firstOrNull() ?: return
            val rowStride = plane.rowStride
            val buffer = plane.buffer
            val needed = buffer.remaining()
            if (luminance.size < needed) luminance = ByteArray(needed)
            buffer.get(luminance, 0, needed)
            val rows = needed / rowStride
            val text = decoder.decode(luminance, minOf(image.width, rowStride), minOf(image.height, rows), rowStride)
            if (text != null && !closed) {
                // Report first, then release the camera: close() must not swallow this result.
                main.post { onDecoded(text) }
                close()
            }
        } catch (_: Exception) {
            // A frame that cannot be read is simply skipped; the next one follows.
        } finally {
            runCatching { image.close() }
        }
    }

    private fun backCameraId(manager: CameraManager): String? {
        val ids = runCatching { manager.cameraIdList }.getOrDefault(emptyArray())
        return ids.firstOrNull { id ->
            runCatching {
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }.getOrDefault(false)
        } ?: ids.firstOrNull()
    }

    /**
     * Around 720p: enough to resolve the code from across the room, while a frame still decodes
     * fast. Falls back to the largest offered size when nothing is in that range.
     */
    private fun analysisSize(configuration: StreamConfigurationMap): Size {
        val sizes = configuration.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
        require(sizes.isNotEmpty()) { "No YUV output size" }
        val pixels: (Size) -> Long = { it.width.toLong() * it.height }
        return sizes.filter { it.width in 960..1920 && it.height in 540..1080 }.minByOrNull(pixels)
            ?: sizes.maxByOrNull(pixels)!!
    }

    /**
     * Centre-crops the preview so it is not stretched, and turns it upright. A TextureView already
     * compensates the sensor orientation for the device's natural orientation, so only the
     * current display rotation is added here (rotating by the sensor angle as well left the
     * picture a quarter turn off on phones).
     */
    fun applyTransform(view: TextureView, viewWidth: Int, viewHeight: Int) {
        val size = frameSize ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return
        val displayRotation = view.display?.rotation ?: Surface.ROTATION_0
        val w = viewWidth.toFloat()
        val h = viewHeight.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val matrix = Matrix()
        if (displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270) {
            val viewRect = RectF(0f, 0f, w, h)
            val bufferRect = RectF(0f, 0f, size.height.toFloat(), size.width.toFloat())
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = maxOf(h / size.height, w / size.width)
            matrix.postScale(scale, scale, cx, cy)
            matrix.postRotate(90f * (displayRotation - 2), cx, cy)
        } else {
            // Upright already; the content fills the view stretched, so scale it back to its
            // own aspect ratio and crop the overflow around the centre.
            val quarterTurn = sensorOrientation == 90 || sensorOrientation == 270
            val contentWidth = (if (quarterTurn) size.height else size.width).toFloat()
            val contentHeight = (if (quarterTurn) size.width else size.height).toFloat()
            val scale = maxOf(w / contentWidth, h / contentHeight)
            matrix.setScale(contentWidth * scale / w, contentHeight * scale / h, cx, cy)
            if (displayRotation == Surface.ROTATION_180) matrix.postRotate(180f, cx, cy)
        }
        view.post { view.setTransform(matrix) }
    }

    /** Releases the camera but stays usable: a new surface can start a session again. */
    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    /**
     * Ends the session after a camera error and tells the dialog, which otherwise shows a frozen
     * preview. A stale callback (older session, or after close) changes nothing.
     */
    private fun failFor(device: CameraDevice, session: Int = generation, background: HandlerThread? = null) {
        val failed = synchronized(lock) {
            val current = !closed && session == generation && (camera == null || camera === device)
            if (current) { openPending = false; stopLocked() }
            else if (background != null && thread !== background) runCatching { background.quitSafely() }
            current
        }
        if (failed) main.post { if (!closed) onFailure() }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            stopLocked()
        }
    }

    private fun stopLocked() {
        generation++
        runCatching { captureSession?.close() }; captureSession = null
        runCatching { camera?.close() }; camera = null
        runCatching { reader?.close() }; reader = null
        runCatching { previewSurface?.release() }; previewSurface = null
        // While the camera is still opening, its callback thread must stay alive so onOpened can
        // close the device; that callback quits the thread instead.
        if (!openPending) thread?.let { runCatching { it.quitSafely() } }
        openPending = false
        thread = null
        handler = null
    }
}
