package com.villamediana.novelo.remind

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.Notifications
import com.villamediana.novelo.R
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.ui.alarm.AlarmActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * What is ringing: an entry's reminder ([entryId]), or the medicines of a time ([meds]). [task]: it can be
 * marked done from the alarm (a task, or medicines taken).
 */
data class Ringing(
    val title: String,
    val text: String,
    /** The time it is for (shown big). */
    val at: Long,
    val entryId: Long? = null,
    val meds: Boolean = false,
    val task: Boolean = false,
) {
    /** The same alarm (rung again) is the same one. */
    val key: String get() = if (meds) "meds:$at" else "entry:$entryId"
}

/**
 * A reminder that rings like an alarm clock: the phone's alarm sound, from soft to full, and a buzz that comes
 * back, until she stops it, puts it off ten minutes, or marks it done (a task; the medicines taken). After
 * [RING_MS] it goes quiet and stays as the ordinary notification: not lost. Over the lock screen it is a whole
 * screen ([AlarmActivity]); with the phone in her hand, a notification on top with the same buttons. One rings
 * at a time: a new one takes the place, and the one before stays as its ordinary notification.
 *
 * A foreground service (an exact alarm going off may start one from the background), of the kind Android 14
 * gives apps with exact alarms to continue an alarm ("systemExempted").
 */
class AlarmService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var wake: PowerManager.WakeLock? = null
    private var step = 0

    private val rise = object : Runnable {
        override fun run() {
            val p = player ?: return
            step++
            val v = START_VOLUME + (1f - START_VOLUME) * (step.toFloat() / RISE_STEPS).coerceAtMost(1f)
            runCatching { p.setVolume(v, v) }
            if (step < RISE_STEPS) main.postDelayed(this, RISE_MS / RISE_STEPS)
        }
    }

    /** Rang long enough: quiet, and it stays as the ordinary notification. */
    private val enough = Runnable { end(Act.QUIET) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> read(intent)?.let(::ring) ?: leaveIfIdle()
            ACTION_STOP -> end(Act.STOP)
            ACTION_SNOOZE -> end(Act.SNOOZE)
            ACTION_DONE -> end(Act.DONE)
            else -> leaveIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun ring(r: Ringing) {
        val before = _ringing.value
        val shown = runCatching {
            ServiceCompat.startForeground(
                this, Notifications.ID_ALARM, notification(r),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0,
            )
        }.isSuccess
        if (!shown) {
            // Android didn't let it ring: the ordinary notification, at least.
            quiet(r)
            leaveIfIdle()
            return
        }
        _ringing.value = r
        // The one that was ringing: its ordinary notification, so it isn't lost.
        if (before != null && before.key != r.key) quiet(before)
        holdWake()
        play()
        buzz()
        main.removeCallbacks(enough)
        main.postDelayed(enough, RING_MS)
    }

    /** It ends: stopped, put off, done, or rang long enough. */
    private fun end(act: Act) {
        val r = _ringing.value
        silence()
        _ringing.value = null
        if (r != null) {
            // In the app's own scope: this service is about to go.
            val graph = NoveloApplication.graph
            graph.scope.launch {
                runCatching {
                    when (act) {
                        Act.SNOOZE -> if (r.meds) graph.reminders.meds.again(r.at, SNOOZE_MS) else r.entryId?.let { graph.reminders.snooze(it, Reminders.REMIND, SNOOZE_MS) }
                        Act.DONE -> if (r.meds) graph.reminders.meds.takeAll(r.at) else r.entryId?.let { graph.reminders.done(it, Reminders.REMIND, r.at) }
                        Act.QUIET -> quiet(r)
                        Act.STOP -> Unit
                    }
                }
            }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** The ordinary notification of [r] (no sound: it already rang). */
    private fun quiet(r: Ringing) {
        val graph = NoveloApplication.graph
        graph.scope.launch {
            runCatching {
                if (r.meds) graph.reminders.meds.post(r.at, silent = true)
                else r.entryId?.let { id -> graph.db.entries().get(id)?.let { graph.reminders.show(it, Reminders.REMIND, r.at, silent = true) } }
            }
        }
    }

    private fun leaveIfIdle() {
        if (_ringing.value != null) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(r: Ringing): Notification {
        Notifications.createChannels(this)
        val screen = PendingIntent.getActivity(
            this, 0,
            Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, Notifications.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.accent))
            .setContentTitle(r.title)
            .setContentText(r.text.ifBlank { com.villamediana.novelo.ui.components.Dates.time(r.at) })
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setWhen(r.at)
            .setContentIntent(screen)
            // Locked: the whole screen. In use: on top, with the buttons.
            .setFullScreenIntent(screen, true)
            .addAction(0, t(R.string.alarm_stop), act(ACTION_STOP))
            .addAction(0, t(R.string.alarm_snooze), act(ACTION_SNOOZE))
        if (r.task) b.addAction(0, t(if (r.meds) R.string.med_taken_action else R.string.notif_done), act(ACTION_DONE))
        return b.build()
    }

    private fun act(action: String): PendingIntent =
        PendingIntent.getService(this, action.hashCode(), Intent(this, AlarmService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /**
     * The sound she chose (Ajustes › Alarme), or the phone's alarm sound, looping, from soft to full;
     * nothing over a call (the buzz says it). Music pauses. A chosen sound that can't play any more
     * (deleted, on a card taken out) gives way to the phone's: an alarm never stays mute.
     */
    private fun play() {
        stopSound()
        val audio = getSystemService(AudioManager::class.java) ?: return
        if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION) return
        val chosen = runCatching { NoveloApplication.graph.settings.now.value.alarmSound }.getOrDefault("")
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).build().also { audio.requestAudioFocus(it) }
        player = sounds(this, chosen).firstNotNullOfOrNull { uri ->
            val p = MediaPlayer()
            runCatching {
                p.setAudioAttributes(attributes)
                p.setDataSource(this@AlarmService, uri)
                p.isLooping = true
                p.setVolume(START_VOLUME, START_VOLUME)
                p.prepare()
                p.start()
                p
            }.getOrElse { p.release(); null }
        }
        step = 0
        main.removeCallbacks(rise)
        main.postDelayed(rise, RISE_MS / RISE_STEPS)
    }

    /** A long buzz that comes back, as an alarm (it goes through silent mode the way alarms do). */
    private fun buzz() {
        val v = vibrator()?.takeIf { it.hasVibrator() } ?: return
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 600, 900), 1)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            else @Suppress("DEPRECATION") v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)

    private fun stopSound() {
        main.removeCallbacks(rise)
        player?.let { p -> runCatching { p.stop() }; p.release() }
        player = null
        focus?.let { f -> getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(f) }
        focus = null
    }

    private fun silence() {
        main.removeCallbacks(enough)
        stopSound()
        runCatching { vibrator()?.cancel() }
        wake?.takeIf { it.isHeld }?.release()
        wake = null
    }

    /** The CPU awake while it rings (screen off, the phone resting). */
    private fun holdWake() {
        if (wake?.isHeld == true) return
        wake = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "novelo:alarm")?.apply {
            setReferenceCounted(false)
            acquire(RING_MS + 30_000L)
        }
    }

    override fun onDestroy() {
        silence()
        _ringing.value = null
        super.onDestroy()
    }

    /** Android 15 ends a foreground service that ran too long: quiet, as if it rang long enough. */
    override fun onTimeout(startId: Int, fgsType: Int) = end(Act.QUIET)

    private enum class Act { STOP, SNOOZE, DONE, QUIET }

    companion object {
        private const val ACTION_RING = "com.villamediana.novelo.alarm.RING"
        const val ACTION_STOP = "com.villamediana.novelo.alarm.STOP"
        const val ACTION_SNOOZE = "com.villamediana.novelo.alarm.SNOOZE"
        const val ACTION_DONE = "com.villamediana.novelo.alarm.DONE"

        /** It rings this long, then goes quiet as the ordinary notification. */
        const val RING_MS = 2 * 60_000L

        /** "Mais 10 min". */
        const val SNOOZE_MS = 10 * 60_000L

        /** From soft to full in 20 s. */
        private const val START_VOLUME = 0.15f
        private const val RISE_MS = 20_000L
        private const val RISE_STEPS = 20

        /**
         * The sounds to try, in order: the one she [chosen] (a uri from Android's sound picker; empty:
         * none), then the phone's alarm sound, its notification sound, Android's own alarm.
         */
        internal fun sounds(context: Context, chosen: String): List<android.net.Uri> = listOfNotNull(
            chosen.takeIf { it.isNotBlank() }?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() },
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM),
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        ).distinct()

        private val _ringing = MutableStateFlow<Ringing?>(null)

        /** What is ringing now (the whole screen follows it, and leaves when it ends). */
        val ringing: StateFlow<Ringing?> = _ringing

        /**
         * Rings [r]. False when it can't (notifications not allowed — there would be no way to stop it — or
         * Android didn't let the service start): the caller shows the ordinary notification instead.
         */
        fun ring(context: Context, r: Ringing): Boolean {
            val allowed = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!allowed) return false
            val intent = Intent(context, AlarmService::class.java).setAction(ACTION_RING)
                .putExtra("title", r.title).putExtra("text", r.text).putExtra("at", r.at)
                .putExtra("entry", r.entryId ?: -1L).putExtra("meds", r.meds).putExtra("task", r.task)
            return runCatching { ContextCompat.startForegroundService(context, intent) }.isSuccess
        }

        /** What was ringing for [entryId] stops (done or gone in the app). */
        fun stopFor(context: Context, entryId: Long) {
            if (_ringing.value?.entryId == entryId) stop(context)
        }

        /** The medicines of [at] ringing stop (all taken in the app). */
        fun stopMeds(context: Context, at: Long) {
            if (_ringing.value?.let { it.meds && it.at == at } == true) stop(context)
        }

        private fun stop(context: Context) {
            runCatching { context.startService(Intent(context, AlarmService::class.java).setAction(ACTION_STOP)) }
        }

        private fun read(intent: Intent): Ringing? {
            val at = intent.getLongExtra("at", 0L).takeIf { it > 0 } ?: return null
            return Ringing(
                title = intent.getStringExtra("title").orEmpty(),
                text = intent.getStringExtra("text").orEmpty(),
                at = at,
                entryId = intent.getLongExtra("entry", -1L).takeIf { it >= 0 },
                meds = intent.getBooleanExtra("meds", false),
                task = intent.getBooleanExtra("task", false),
            )
        }
    }
}
