package monitor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidapp.MainActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import co.za.zarchive.R
import data.SearchResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val CHANNEL_ID = "search_monitors"
private const val NOTIFICATION_ID = 1001

const val EXTRA_MONITOR_HIT_JSON = "monitor_hit_json"
const val EXTRA_MONITOR_OPEN_ALERTS = "monitor_open_alerts"

private val notificationJson = Json { ignoreUnknownKeys = true }

private fun formatZar(v: Double): String {
    val totalCents = Math.round(v * 100)
    val whole = totalCents / 100
    val cents = (totalCents % 100).toString().padStart(2, '0')
    val grouped = whole.toString().reversed().chunked(3).joinToString(" ").reversed()
    return "R$grouped,$cents"
}

private fun ensureChannel(context: Context) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (manager.getNotificationChannel(CHANNEL_ID) == null) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Search monitors", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Alerts when a monitored card appears in stock"
            },
        )
    }
}

// Single hit -> deep-links straight into that card's detail modal (see MainActivity.onNewIntent /
// AndroidApp's pendingMonitorHit). Multiple hits in one check -> deep-links into the same
// monitor-alerts modal desktop shows, listing all of them.
fun postMonitorHitNotification(context: Context, hits: List<SearchResult>) {
    if (hits.isEmpty()) return
    ensureChannel(context)

    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        if (hits.size == 1) {
            putExtra(EXTRA_MONITOR_HIT_JSON, notificationJson.encodeToString(hits.first()))
        } else {
            putExtra(EXTRA_MONITOR_OPEN_ALERTS, true)
        }
    }
    val pendingIntent = PendingIntent.getActivity(
        context, 0, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val builder = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)

    if (hits.size == 1) {
        val hit = hits.first()
        builder
            .setContentTitle("ZArchive found '${hit.title ?: hit.card}'")
            .setContentText(hit.store + (hit.priceZar?.let { " — ${formatZar(it)}" } ?: ""))
    } else {
        builder
            .setContentTitle("ZArchive found ${hits.size} new listings")
            .setStyle(
                NotificationCompat.InboxStyle().also { style ->
                    hits.take(5).forEach { style.addLine("${it.title ?: it.card} — ${it.store}") }
                },
            )
    }

    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }
}
