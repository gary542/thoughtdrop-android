package org.thoughtdrop.notebook

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Button
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.fillMaxSize

fun quickCaptureIntent(context: Context): Intent = Intent().setClassName(context, "${context.packageName}.QuickCapture")
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

class ThoughtWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            Button("Record a thought", onClick = actionStartActivity(quickCaptureIntent(context)),
                modifier = GlanceModifier.fillMaxSize())
        }
    }
}

class WidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = ThoughtWidget()
}

class CaptureTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply { state = Tile.STATE_ACTIVE; updateTile() }
    }
    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        unlockAndRun {
            val intent = quickCaptureIntent(this)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(
                PendingIntent.getActivity(this, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            else startActivityAndCollapse(intent)
        }
    }
}
