package dev.airmouse.tv

import java.util.concurrent.CopyOnWriteArraySet

/**
 * 电视首页与后台服务之间共享的轻量状态。
 *
 * Android 的无障碍服务和 Activity 生命周期互不相同，用一个线程安全状态
 * 快照传递二维码地址及连接数，避免 Activity 持有服务实例造成生命周期泄漏。
 */
data class TvStatus(
    val pairingUrl: String? = null,
    val clientCount: Int = 0,
    val accessibilityEnabled: Boolean = false,
    val inputMethodEnabled: Boolean = false,
    val inputMethodSelected: Boolean = false,
    val message: String? = null,
)

object TvStatusStore {
    private val lock = Any()
    private val listeners = CopyOnWriteArraySet<(TvStatus) -> Unit>()

    @Volatile
    private var current = TvStatus()

    fun snapshot(): TvStatus = current

    fun update(transform: (TvStatus) -> TvStatus) {
        val updated = synchronized(lock) {
            transform(current).also { current = it }
        }
        listeners.forEach { listener -> listener(updated) }
    }

    fun subscribe(listener: (TvStatus) -> Unit): TvStatus {
        listeners.add(listener)
        val value = current
        listener(value)
        return value
    }

    fun unsubscribe(listener: (TvStatus) -> Unit) {
        listeners.remove(listener)
    }
}
