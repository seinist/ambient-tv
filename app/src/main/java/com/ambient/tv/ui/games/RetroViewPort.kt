package com.ambient.tv.ui.games

import android.content.Context
import android.graphics.Bitmap
import android.view.SurfaceView
import androidx.lifecycle.LifecycleObserver
import com.ambient.tv.games.GameVirtualFile
import com.ambient.tv.games.RetroShaderConfig
import kotlinx.coroutines.flow.Flow

/** The only frontend objects exposed to the host are Android views/lifecycle
 * interfaces and neutral values. Native queue acceptance stays with the view.
 */
internal data class RetroViewConfig(
    val coreFilePath: String,
    val gameFilePath: String?,
    val gameVirtualFiles: List<GameVirtualFile>,
    val systemDirectory: String,
    val savesDirectory: String,
    val variables: Array<RetroVariable>,
    val saveRAMState: ByteArray?,
    val sdrToHdrReferenceWhiteNits: Int,
    val shader: RetroShaderConfig,
    val forceSoftwareTiming: Boolean,
    val preferVulkan: Boolean,
    val configuredHdrOutputMode: Int,
    val preferHighPrecisionVulkanFrames: Boolean,
)
internal data class RetroVariable(val key: String?, val value: String?, val description: String? = null)
internal data class RetroController(val id: Int, val description: String?)
internal data class RetroRumble(val port: Int, val strengthWeak: Float, val strengthStrong: Float)
internal enum class RetroRenderEvent { SurfaceCreated, FrameRendered, Other }
internal data class RetroViewError(val code: Int, val loadLibrary: Boolean)

internal interface RetroFrontendPort : RetroCorePort {
    fun createView(context: Context, config: RetroViewConfig): RetroViewPort
    fun bindSharedShader(resolver: Long)
}

internal interface RetroViewPort {
    val surface: SurfaceView
    val lifecycleObserver: LifecycleObserver
    val sdrToHdrActive: Boolean
    var shader: RetroShaderConfig
    var beforeDetach: (() -> Unit)?
    var suppressAutoResume: Boolean
    var isRewinding: Boolean
    var frameSpeed: Int
    var rewindEnabled: Boolean
    var aspectRatioOverride: Float
    var integerScalingAuto: Boolean
    var integerScaling: Boolean
    var sdrToHdrReferenceWhiteNits: Int
    var achievementUnlockListener: ((Long) -> Unit)?
    var netplayTick: (() -> Unit)?
    fun pauseEmulation(): Unit
    fun resumeEmulation(): Unit
    fun destroyNative(onComplete: (Throwable?) -> Unit): Unit
    fun reset(): Unit
    fun clearRewindBuffer(): Unit
    fun initRewindBuffer(maxSlots: Int, budgetBytes: Long): Unit
    fun destroyRewindBuffer(): Unit
    fun getRewindBufferHistoryFrames(): Long
    fun previewRewind(target: Long): Long
    fun serializeState(): ByteArray
    fun unserializeState(bytes: ByteArray): Boolean
    fun unserializePersistedState(bytes: ByteArray): Boolean
    fun getSerializeSize(): Long
    fun enablePausedPresentationOnDemand(): Unit
    fun setControllerType(port: Int, type: Int): Unit
    fun getAvailableDisks(): Int
    fun getCurrentDisk(): Int
    fun changeDisk(index: Int): Unit
    fun queueEvent(task: () -> Unit)
    fun captureRawFrame(maxWidth: Int): Bitmap?
    fun getVariables(): Array<RetroVariable>
    fun updateVariables(vararg variables: RetroVariable)
    fun getControllers(): Array<Array<RetroController>>
    fun getGLRetroEvents(): Flow<RetroRenderEvent>
    fun getGLRetroErrors(): Flow<RetroViewError>
    fun getRumbleEvents(): Flow<RetroRumble>
    fun stepForNetplay()
}
