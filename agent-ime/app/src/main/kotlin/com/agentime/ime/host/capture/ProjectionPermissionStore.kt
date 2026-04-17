package com.agentime.ime.host.capture

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log

/**
 * 录屏授权与 [MediaProjection] 单例。
 *
 * 截图管线见 [MediaProjectionSession]：同一授权只 [getMediaProjection] 一次，只 [createVirtualDisplay] 一次。
 *
 * 重要约束：
 * - 每次 [update] 必须传入真正新的授权结果（应用层负责确保）。
 * - Android 14 起，[resultCode]/[data] 是一次性令牌：[getMediaProjection] 调用后即告消耗，
 *   不可重复使用。因此 [tearDown] 后必须重新 [update]（用户重新授权）才能再次截图。
 */
object ProjectionPermissionStore {
    private val lock = Any()
    private const val TAG = "ProjectionPermStore"

    @Volatile
    var resultCode: Int? = null
        private set

    @Volatile
    var dataIntent: Intent? = null
        private set

    @Volatile
    private var mediaProjection: MediaProjection? = null

    /**
     * 存储新的授权结果。
     * - 如果与当前已存储数据相同，跳过，避免无谓的 tearDown。
     * - 如果是全新授权数据，先 tearDown 旧管线再存储。
     */
    fun update(resultCode: Int, data: Intent?) {
        synchronized(lock) {
            if (this.resultCode == resultCode && this.dataIntent === data) {
                Log.i(TAG, "update: 授权数据未变，跳过 tearDown")
                return
            }
        }
        MediaProjectionSession.tearDown()
        synchronized(lock) {
            this.resultCode = resultCode
            this.dataIntent = data
        }
        Log.i(TAG, "update: 已存储新授权数据")
    }

    fun hasPermission(): Boolean = resultCode != null && dataIntent != null

    fun acquireMediaProjection(appContext: Context): MediaProjection {
        synchronized(lock) {
            mediaProjection?.let { return it }
            val rc = resultCode
                ?: throw IllegalStateException("未授权录屏，请先在主界面点击「授权截图(MediaProjection)」")
            val data = dataIntent
                ?: throw IllegalStateException("录屏授权数据丢失，请重新授权")
            val pm =
                appContext.applicationContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = pm.getMediaProjection(rc, data)
                ?: throw IllegalStateException("MediaProjection 初始化失败，请重新授权")
            mediaProjection = p
            return p
        }
    }

    /**
     * [MediaProjectionSession.tearDown] 完成后调用。
     * 清空投影实例与授权 token——Android 14 下 resultCode/data 是一次性令牌，
     * [getMediaProjection] 调用后即消耗，tearDown 后不可复用，必须用户重新授权。
     */
    internal fun onProjectionSessionEnded() {
        synchronized(lock) {
            mediaProjection = null
            resultCode = null
            dataIntent = null
        }
        Log.i(TAG, "onProjectionSessionEnded: 已清空投影实例和授权 token，需用户重新授权")
    }

    fun releaseMediaProjection() {
        MediaProjectionSession.tearDown()
    }

    fun clearPermissionGrant() {
        MediaProjectionSession.tearDown()
        synchronized(lock) {
            resultCode = null
            dataIntent = null
        }
    }
}
