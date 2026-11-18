package com.villamediana.novelo

import com.villamediana.novelo.settings.Lang

import android.app.Application
import com.villamediana.novelo.backup.BackupWorker
import com.villamediana.novelo.update.ExitReport
import com.villamediana.novelo.update.UpdateCheckWorker
import com.villamediana.novelo.update.UpdateIdleWorker
import kotlinx.coroutines.launch

class NoveloApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The app's own words in her language (Portuguese unless she chose another).
        Lang.init(this)
        // If the app ever closes by a crash, its first lines are kept (the update sheet shows why).
        runCatching { ExitReport.catchCrashes(this) }
        graph = AppGraph(this)
        Notifications.createChannels(this)
        graph.scope.launch { runCatching { graph.reminders.syncAll() } }
        // What this phone has, as the Novelo's server says now (the trial, Novelo+).
        graph.scope.launch { if (graph.server.available) runCatching { graph.server.me() } }
        // The medicines in memory from the start (Hoje and Remédios are drawn with them there).
        graph.medsNow; graph.takenNow
        // One diary a day (days left with two from before become one).
        graph.scope.launch { runCatching { graph.library.joinDayDiaries() } }
        // Medicines that began the minute they were written (before 1.64.1): from the start of that day.
        graph.scope.launch { runCatching { graph.library.fixMedStarts() } }
        // Tasks that repeat from before 1.70: each of today's times on its own (the ones ticked today kept).
        graph.scope.launch { runCatching { graph.library.fixRepeats() } }
        // A backup after every change in what she keeps (and the daily one besides).
        graph.scope.launch { runCatching { graph.backups.watch(graph.db) } }
        // The search by meaning: the meanings kept up to date, and its model on the first Wi-Fi (once).
        graph.scope.launch {
            runCatching {
                graph.senses.watch()
                graph.senses.soon()
                if (com.villamediana.novelo.local.LocalEngine.available && !graph.senseModel.ready) graph.senseModel.download(wifiOnly = true)
            }
        }
        // The automatic backup, as Ajustes has it (kept when it is already scheduled).
        graph.scope.launch { runCatching { BackupWorker.schedule(this@NoveloApplication, graph.settings.current(), replace = false) } }
        if (graph.updates.enabled) runCatching { UpdateCheckWorker.schedule(this) }
        runCatching { UpdateIdleWorker.watch(this, graph.updates) }
        // Done while she was away (the app minimized or closed): a notification says so.
        runCatching { WorkDone.watch(this, graph.scope, graph.mind.told) }
        // The network is back (the app open): what waited for it in the line goes, one by one.
        runCatching { graph.net.whenBack { graph.mind.networkBack() } }
        // The Novelo on the computer, when a computer was linked: kept in step.
        runCatching { graph.web.watch() }
    }

    companion object {
        lateinit var graph: AppGraph
            private set
    }
}
