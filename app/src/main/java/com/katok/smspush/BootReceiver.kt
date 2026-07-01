package com.katok.smspush

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.katok.smspush.SmsGatewayService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            val serviceIntent = Intent(context, SmsGatewayService::class.java).apply {
                action = SmsGatewayService.ACTION_START
            }
            context.startService(serviceIntent)
        }
    }
}