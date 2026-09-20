package com.katok.smspush

/**
 * Единый источник кредов SMS_GATEWAY.
 * Используется и в LoginActivity (для автологина),
 * и в SmsGatewayService (для fallback-логина при мёртвом refresh-токене).
 */
object GatewayCredentials {
    const val PHONE = "+79999999999"
    const val PASSWORD = "fghvbn123rty"
}