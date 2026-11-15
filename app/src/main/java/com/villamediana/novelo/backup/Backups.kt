package com.villamediana.novelo.backup

import com.villamediana.novelo.R
import com.villamediana.novelo.settings.tn
import com.villamediana.novelo.settings.t
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.mind.Mind
import com.villamediana.novelo.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The backup as the app uses it: the folder she chose in the system picker (the permission kept
 * across restarts), the automatic one on WorkManager, the last result in Ajustes, the import.
 */
class Backups(
    private val context: Context,
    private val core: Backup,
    private val settings: Settings,
    private val mind: Mind,
    private val scope: CoroutineScope,
    /** The backup in the cloud (Novelo+), beside or instead of the folder's. */
    val cloud: CloudBackup? = null,
    /** One diary a day, after an import too (an older backup may have days with two). */
    private val joinDays: suspend () -> Unit = {},
) {
    private val _busy = MutableStateFlow<String?>(null)
    /** "Fazendo o backup…" / "Importando…" while it goes; null when it doesn't. */
    val busy: StateFlow<String?> = _busy

    private val resolver get() = context.contentResolver

    private fun root(uri: String): Shelf = TreeShelf.root(resolver, Uri.parse(uri))

    /**
     * Something changed (one waiting at most: a change while a backup runs makes one more after
     * it, never none — the backup started may have looked before the change).
     */
    private val changes = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    @Volatile private var lastChange = 0L
    private val runner: Job by lazy {
        scope.launch {
            for (change in changes) {
                // A moment of quiet first, and nothing going through: a talk (heard, thought, kept —
                // each step a change) makes one backup, of what came of it, not one at every step.
                while (true) {
                    val quiet = android.os.SystemClock.elapsedRealtime() - lastChange
                    if (quiet >= QUIET_MS && !mind.working) break
                    kotlinx.coroutines.delay(if (quiet < QUIET_MS) QUIET_MS - quiet else BUSY_MS)
                }
                if (settings.current().backupFolder.isNotBlank()) save()
                if (settings.current().cloud) cloudSave()
            }
        }
    }

    /**
     * Every change in what she keeps (something new, changed, taken out; its photos, connections,
     * steps) makes a backup: a few seconds after the last of a burst, so one talk is one backup.
     * Room says when the tables change; WorkManager finishes it if the app goes away first.
     */
    fun watch(db: com.villamediana.novelo.data.NoveloDatabase) {
        db.invalidationTracker.addObserver(object : androidx.room.InvalidationTracker.Observer(arrayOf("entries", "captures", "entities", "entry_topics", "links", "progress")) {
            override fun onInvalidated(tables: Set<String>) = soon()
        })
    }

    /** A backup soon (see [watch]). */
    fun soon() {
        lastChange = android.os.SystemClock.elapsedRealtime()
        runCatching { BackupWorker.soon(context) }
        if (settings.now.value.cloud) runCatching { CloudWorker.soon(context) }
        runner.start()
        changes.trySend(Unit)
    }

    /** The folder chosen in the picker: kept, the old one let go, the automatic backup set, and a backup right away. */
    fun choose(uri: Uri): Job = scope.launch {
        link(uri)
        save()
    }

    /** The folder kept (its permission across restarts), the old one let go, the daily backup set. */
    private suspend fun link(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val old = settings.current().backupFolder
        runCatching { resolver.takePersistableUriPermission(uri, flags) }
        if (old.isNotBlank() && old != uri.toString()) runCatching { resolver.releasePersistableUriPermission(Uri.parse(old), flags) }
        val place = runCatching { core.place(root(uri.toString())) }.getOrDefault("")
        settings.setBackupFolder(uri.toString(), place)
        BackupWorker.schedule(context, settings.current(), replace = true)
    }

    /**
     * A folder chosen (the first steps, Ajustes). One with a Novelo backup in it is hers: that backup
     * comes back — and what the app has now, if anything, is first kept beside it with its date, so
     * nothing is lost. A new folder (or the same one again): the backups go on there. What came back,
     * or null.
     */
    suspend fun chooseAndBringBack(uri: Uri): Pair<Backup.Kept, Backup.Restored>? {
        val same = settings.current().backupFolder == uri.toString()
        link(uri)
        val root = root(uri.toString())
        val there = runCatching { core.kept(root) }.getOrDefault(emptyList()).firstOrNull { it.name == Backup.FILE }
        if (same || there == null) {
            save()
            return null
        }
        if (!core.empty()) {
            val aside = runCatching { core.saveAside(root) }.getOrNull()
            if (aside == null) {
                // What the app has couldn't be kept beside: nothing is replaced (the backup there
                // is kept beside instead, by the next backup).
                save()
                return null
            }
        }
        // Not brought back (busy, a broken file): she is told, under the folder; nothing was replaced.
        return restore(Backup.FILE).onFailure { settings.setBackupFailed(it.message.orEmpty()) }.getOrNull()?.let { there to it }
    }

    /** Nothing in the app yet: a backup can come back without replacing anything. */
    suspend fun empty(): Boolean = core.empty()

    fun now(): Job = scope.launch {
        save()
        cloudSave()
    }

    /** One backup to the cloud (when it is on). False when it is off or didn't go. */
    suspend fun cloudSave(): Boolean {
        val c = cloud ?: return false
        if (!settings.current().cloud) return false
        _busy.value = SAVING
        return try {
            c.save()
        } finally {
            _busy.value = null
        }
    }

    /**
     * The cloud backup on, and a first backup right away. True when the secret went to her Google
     * account's safe too (when not, only the recovery code brings the backup back).
     */
    suspend fun cloudOn(): Boolean {
        val c = cloud ?: return false
        val safe = c.turnOn()
        runCatching { CloudWorker.soon(context) }
        scope.launch { cloudSave() }
        return safe
    }

    /** The cloud backup off; [erase]: what is there is erased too. A failure to erase is told (nothing is turned back on). */
    suspend fun cloudOff(erase: Boolean): Result<Unit> = runCatching {
        cloud?.turnOff(erase)
        Unit
    }.recoverCatching { throw IOException(cloud?.said(it) ?: it.message.orEmpty()) }

    /** The backup [seed] opens, brought back from the cloud in place of everything. */
    suspend fun cloudRestore(seed: ByteArray): Result<Backup.Restored> {
        val c = cloud ?: return Result.failure(IOException(t(R.string.backup_not_found)))
        if (!mind.idle) return Result.failure(IOException(t(R.string.backup_wait_busy)))
        _busy.value = IMPORTING
        val r = try {
            Result.success(c.restore(seed))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(IOException(c.said(e), e))
        } finally {
            _busy.value = null
        }
        if (r.isSuccess) {
            runCatching { joinDays() }
            mind.restored()
            // The folder's file (when there is a folder) and the cloud follow what came back.
            save()
            scope.launch { cloudSave() }
        }
        return r
    }

    /** One backup, its result kept for Ajustes. False when there is no folder or it went wrong. */
    suspend fun save(): Boolean {
        val folder = settings.current().backupFolder.takeIf { it.isNotBlank() } ?: return false
        _busy.value = SAVING
        return try {
            val root = root(folder)
            val r = core.save(root, key = folder)
            // Where it is now (a folder chosen before the backup became one file showed another place).
            core.place(root).let { if (it != settings.current().backupPlace) settings.setBackupPlace(it) }
            val said = if (r.empty) t(R.string.backup_nothing_yet) else note(r.entries, r.photos, r.sounds, r.files)
            // A backup that was already in the folder: kept beside, and she is told where.
            settings.setBackupDone(System.currentTimeMillis(), r.aside?.let { said + " · " + t(R.string.backup_kept_aside, it.removeSuffix(".zip")) } ?: said)
            true
        } catch (e: Throwable) {
            settings.setBackupFailed(message(e))
            false
        } finally {
            _busy.value = null
        }
    }

    /** The backups in the folder, the newest first. */
    suspend fun kept(): List<Backup.Kept> {
        val folder = settings.current().backupFolder.takeIf { it.isNotBlank() } ?: return emptyList()
        return core.kept(root(folder))
    }

    suspend fun read(name: String): Snapshot = core.read(root(settings.current().backupFolder), name)

    /** Puts the backup [name] back in place of everything. */
    suspend fun restore(name: String): Result<Backup.Restored> {
        val folder = settings.current().backupFolder.takeIf { it.isNotBlank() } ?: return Result.failure(IOException(t(R.string.backup_choose_folder)))
        if (!mind.idle) return Result.failure(IOException(t(R.string.backup_wait_busy)))
        _busy.value = IMPORTING
        val r = try {
            Result.success(core.restore(root(folder), name, key = folder))
        } catch (e: Throwable) {
            Result.failure(IOException(message(e), e))
        } finally {
            _busy.value = null
        }
        if (r.isSuccess) {
            runCatching { joinDays() }
            mind.restored()
            // The file follows what came back.
            save()
        }
        return r
    }

    private fun message(e: Throwable): String = when (e) {
        is SecurityException -> TreeShelf.LOST
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    companion object {
        /** Quiet after a change before the backup goes (a talk changes several things at once). */
        const val QUIET_MS = 4_000L

        /** How often it looks again while something is going through. */
        const val BUSY_MS = 1_500L

        const val SAVING = "Fazendo o backup…"
        const val IMPORTING = "Importando…"

        fun note(entries: Int, photos: Int, sounds: Int = 0, files: Int = 0): String = buildString {
            append(tn(R.plurals.items, entries))
            if (photos > 0) append(" · ").append(tn(R.plurals.photos, photos))
            if (files > 0) append(" · ").append(tn(R.plurals.files, files))
            if (sounds > 0) append(" · ").append(tn(R.plurals.sounds, sounds))
        }
    }
}

/** The automatic backup: after every change (see [Backups.watch]), and once a day besides. */
class BackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val graph = NoveloApplication.graph
        // Something going through: a bit later (the app backs up itself when it ends, if it is open).
        if (graph.mind.working) return Result.retry()
        graph.backups.save()
        return Result.success()
    }

    companion object {
        private const val NAME = "backup"
        private const val SOON = "backup-soon"

        /**
         * After a change, a backup in a while even if the app goes away first (the app does it sooner
         * itself while it is open; this one then finds nothing new and writes nothing).
         */
        fun soon(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<BackupWorker>()
                .setInitialDelay(2, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(SOON, androidx.work.ExistingWorkPolicy.KEEP, request)
        }

        /**
         * In step with Ajustes: on when there is a folder, off when there isn't. [replace] when she
         * changed it (the next one counts from now); otherwise a schedule already there stays.
         */
        fun schedule(context: Context, s: com.villamediana.novelo.settings.NoveloSettings, replace: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (s.backupFolder.isBlank()) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val days = 1L
            val request = PeriodicWorkRequestBuilder<BackupWorker>(days, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
                .setInitialDelay(days, TimeUnit.DAYS)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/**
 * The backup to the cloud when the app isn't there to do it: once there is a network, after a
 * change. What didn't go (no network, the server busy) is tried again later; what went stays there.
 */
class CloudWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val graph = NoveloApplication.graph
        if (!graph.settings.current().cloud) return Result.success()
        if (graph.mind.working) return Result.retry()
        return if (graph.backups.cloudSave() || runAttemptCount >= 5) Result.success() else Result.retry()
    }

    companion object {
        private const val SOON = "cloud-backup-soon"

        fun soon(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<CloudWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .setInitialDelay(2, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(SOON, androidx.work.ExistingWorkPolicy.KEEP, request)
        }
    }
}
