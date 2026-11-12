package com.persona.chat
import android.app.*
import android.content.*
import android.net.Uri
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest
import java.util.Calendar

object Media {
    /** Copies a picked file into app storage; identical files (same hash) are stored once. */
    fun import(c: Context, uri: Uri): String? {
        val bytes = c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val h = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(24)
        val mime = c.contentResolver.getType(uri) ?: ""
        val ext = when { "gif" in mime -> "gif"; "png" in mime -> "png"; "webp" in mime -> "webp"; else -> "jpg" }
        val f = File(File(c.filesDir, "media").apply { mkdirs() }, "$h.$ext")
        if (!f.exists()) f.writeBytes(bytes)
        return f.absolutePath
    }
}

object Scheduler {
    fun nextTime(s: Schedule): Long {
        val now = Calendar.getInstance()
        for (i in 0..7) {
            val c = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, i); set(Calendar.HOUR_OF_DAY, s.hour); set(Calendar.MINUTE, s.minute)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            val bit = 1 shl (c.get(Calendar.DAY_OF_WEEK) - 1)
            if (c.after(now) && (s.days and bit) != 0) return c.timeInMillis
        }
        return -1
    }
    private fun pi(c: Context, id: Long) = PendingIntent.getBroadcast(c, id.toInt(),
        Intent(c, AlarmReceiver::class.java).putExtra("id", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    /** Inexact-while-idle alarm: no special permission, but Android may delay it by minutes in Doze/battery saver. */
    fun schedule(c: Context, s: Schedule) {
        val am = c.getSystemService(AlarmManager::class.java); am.cancel(pi(c, s.id))
        val t = nextTime(s); if (!s.enabled || t < 0) return
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(c, s.id))
    }
    fun cancel(c: Context, id: Long) = c.getSystemService(AlarmManager::class.java).cancel(pi(c, id))
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getLongExtra("id", -1); val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDb.get(c).dao(); val s = dao.schedule(id)
                if (s != null && s.enabled) {
                    dao.insert(Msg(fromMe = false, text = s.text))
                    val nm = c.getSystemService(NotificationManager::class.java)
                    nm.createNotificationChannel(NotificationChannel("sched", "Scheduled messages", NotificationManager.IMPORTANCE_DEFAULT))
                    nm.notify(s.id.toInt(), Notification.Builder(c, "sched").setSmallIcon(android.R.drawable.ic_dialog_email)
                        .setContentTitle(s.name.ifBlank { "PersonaChat" }).setContentText(s.text).setAutoCancel(true)
                        .setContentIntent(PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build())
                    Scheduler.schedule(c, s)
                }
            } finally { pending.finish() }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { AppDb.get(c).dao().allSchedules().forEach { Scheduler.schedule(c, it) } } finally { pending.finish() }
        }
    }
}
