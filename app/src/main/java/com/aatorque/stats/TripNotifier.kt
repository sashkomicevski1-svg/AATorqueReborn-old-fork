package com.aatorque.stats

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aatorque.prefs.SettingsActivity
import timber.log.Timber

/** Posts the trip summary on the phone when Android Auto ends. */
object TripNotifier {
    private const val CHANNEL = "trip_summary"
    private const val ID = 4201

    /** Trips shorter than this are kept in the log but not announced. */
    const val MIN_KM = 0.5

    fun text(context: Context, trip: TripRecord): Pair<String, String> {
        val minutes = trip.durationMs / 60_000
        val title = context.getString(R.string.trip_title, trip.distanceKm, minutes)
        val parts = mutableListOf<String>()
        trip.averageSpeed?.let { parts += context.getString(R.string.trip_avg_speed, it) }
        trip.maxSpeed?.let { parts += context.getString(R.string.trip_max_speed, it) }
        trip.maxCoolant?.let { parts += context.getString(R.string.trip_max_coolant, it) }
        if (trip.regenerations > 0) {
            parts +=
                context.getString(R.string.trip_regens, trip.regenerations, trip.regenCompleted)
        }
        if (trip.faultCodes.isNotEmpty()) {
            parts += context.getString(R.string.trip_codes, trip.faultCodes.joinToString())
        }
        return title to parts.joinToString(" · ")
    }

    fun post(context: Context, trip: TripRecord) {
        if (trip.distanceKm < MIN_KM) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Timber.i("Trip: no notification permission")
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.trip_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val (title, body) = text(context, trip)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID, notification)
        } catch (e: SecurityException) {
            Timber.w(e, "Trip: notification refused")
        }
    }
}
