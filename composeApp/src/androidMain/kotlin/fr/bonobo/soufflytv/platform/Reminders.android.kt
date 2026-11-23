package fr.bonobo.soufflytv.platform

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import fr.bonobo.soufflytv.data.Reminder
import java.lang.ref.WeakReference

/*
 * Rappels du guide TV sur Android : une alarme 2 minutes avant l'émission affiche une notification
 * (« Ça commence : … sur TF1 à 16:00 »). Un appui ouvre SoufflyTV, qui propose de lancer la chaîne.
 * Sur Android TV, les notifications sont peu visibles : le rappel s'affiche aussi dans l'appli si elle est ouverte.
 */

private const val CHANNEL_ID = "rappels"
private const val EXTRA_TITLE = "titre"
private const val EXTRA_TEXT = "texte"
private const val EXTRA_ID = "id"
private const val NOTICE_MS = 2 * 60_000L

/** Activité en cours (pour demander l'autorisation des notifications). */
object ReminderPermission {
    var activity: WeakReference<Activity>? = null
}

private fun requestCode(reminder: Reminder) = reminder.key.hashCode()

private fun alarmIntent(context: Context, reminder: Reminder): PendingIntent {
    val intent = Intent(context, ReminderReceiver::class.java).apply {
        putExtra(EXTRA_TITLE, "Ça commence : ${reminder.title}")
        putExtra(EXTRA_TEXT, "Sur ${reminder.channelName} à ${formatClock(reminder.start)}")
        putExtra(EXTRA_ID, requestCode(reminder))
    }
    return PendingIntent.getBroadcast(
        context,
        requestCode(reminder),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

actual fun scheduleReminderNotification(reminder: Reminder) {
    val context = AndroidApp.context
    // Android 13+ : autorisation des notifications, demandée au premier rappel
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        ReminderPermission.activity?.get()?.let {
            ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
        }
    }
    val alarms = context.getSystemService(AlarmManager::class.java) ?: return
    val at = (reminder.start - NOTICE_MS).coerceAtLeast(System.currentTimeMillis() + 5_000)
    // alarme non exacte (pas d'autorisation spéciale) : quelques secondes d'écart au plus en temps normal
    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context, reminder))
}

actual fun cancelReminderNotification(reminder: Reminder) {
    val context = AndroidApp.context
    context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context, reminder))
    NotificationManagerCompat.from(context).cancel(requestCode(reminder))
}

/** Reçoit l'alarme et affiche la notification du rappel. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Rappels du guide TV", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Émissions pour lesquelles un rappel a été posé dans le guide TV"
                }
            )
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val content = open?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(intent.getStringExtra(EXTRA_TITLE) ?: "Rappel SoufflyTV")
            .setContentText(intent.getStringExtra(EXTRA_TEXT).orEmpty())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .apply { if (content != null) setContentIntent(content) }
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(intent.getIntExtra(EXTRA_ID, 1), notification)
        }
    }
}
