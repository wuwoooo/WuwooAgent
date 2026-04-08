package com.agentime.ime.host.capture

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager

/**
 * 录屏授权与 [MediaProjection] 单例。
 *
 * 截图管线见 [MediaProjectionSession]：同一授权只 [getMediaProjection] 一次，只 [createVirtualDisplay] 一次。
 */
object ProjectionPermissionStore {
    private val lock = Any()

    @Volatile
    var resultCode: Int? = null

    @Volatile
    var dataIntent: Intent? = null

    @Volatile
    private var mediaProjection: MediaProjection? = null

    fun update(resultCode: Int, data: Intent?) {
        MediaProjectionSession.tearDown()
        synchronized(lock) {
            this.resultCode = resultCode
            this.dataIntent = data
        }
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

    /** [MediaProjectionSession] 在 stop 投影后仅清空当前投影实例，保留授权结果以便重新建管线。 */
    internal fun onProjectionSessionEnded() {
        synchronized(lock) {
            mediaProjection = null
        }
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
