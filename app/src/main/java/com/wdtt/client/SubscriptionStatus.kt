package com.wdtt.client

/**
 * Результат проверки подписки пользователя.
 *
 * @param isAccessible  true = сервер вернул HTTP 200
 * @param expireAt      Unix-timestamp (сек) даты истечения, 0 = не предоставлено сервером
 * @param devicesUsed   текущее кол-во устройств, -1 = неизвестно
 * @param devicesLimit  максимальное кол-во устройств, -1 = неизвестно
 * @param httpCode      HTTP-код ответа (403 = лимит/отозвано, 0 = сетевая ошибка)
 * @param errorMessage  человекочитаемое описание ошибки (если !isAccessible)
 */
data class SubscriptionStatus(
    val isAccessible: Boolean,
    val expireAt: Long = 0L,
    val devicesUsed: Int = -1,
    val devicesLimit: Int = -1,
    val httpCode: Int = 0,
    val errorMessage: String = ""
) {
    /** Подписка уже истекла */
    val isExpired: Boolean
        get() = expireAt > 0L && System.currentTimeMillis() / 1000 > expireAt

    /** Осталось дней до истечения (null если дата неизвестна или уже истекла) */
    val daysLeft: Long?
        get() {
            if (expireAt <= 0L) return null
            val nowSec = System.currentTimeMillis() / 1000
            val diff = expireAt - nowSec
            return if (diff > 0) diff / 86400 else null
        }

    /** Лимит устройств точно превышен (данные получены от сервера) */
    val isDeviceLimitExceeded: Boolean
        get() = devicesLimit > 0 && devicesUsed >= devicesLimit

    /** HTTP 403 — устройство отозвано или лимит превышен */
    val isRevoked: Boolean
        get() = !isAccessible && httpCode == 403
}
