package com.bridgepipe.tor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.bridgepipe.bridges.BridgeManager
import com.bridgepipe.bridges.BridgeTester
import com.bridgepipe.data.BridgeDb
import com.bridgepipe.data.Prefs
import com.bridgepipe.ui.MainActivity
import com.bridgepipe.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Foreground-сервис: запускает нативный tor (libtor.so) сгенерированным
 * torrc, читает stdout в лог, следит за состоянием через ControlPort,
 * поднимает SOCKS5 127.0.0.1:9050.
 */
class TorService : Service() {

    enum class State { OFF, STARTING, BOOTSTRAPPING, CONNECTED, STOPPING, FAILED }

    class Status(
        val state: State,
        val progress: Int,
        val readRate: Long,
        val writeRate: Long,
        val message: String
    )

    companion object {
        const val CHANNEL = "tor"
        const val NOTIF_ID = 1

        @Volatile var instance: TorService? = null
        @Volatile private var lastStatus = Status(State.OFF, 0, 0, 0, "не запущен")
        private val listeners = CopyOnWriteArrayList<(Status) -> Unit>()

        fun status(): Status = lastStatus

        fun listen(cb: (Status) -> Unit): () -> Unit {
            listeners.add(cb)
            cb(lastStatus)
            return { listeners.remove(cb) }
        }

        private fun publish(s: Status) {
            lastStatus = s
            for (l in listeners) {
                try {
                    l(s)
                } catch (_: Exception) {
                }
            }
        }
    }

    object Actions {
        const val START = "com.bridgepipe.START"
        const val STOP = "com.bridgepipe.STOP"
        const val APPLY = "com.bridgepipe.APPLY"
    }

    private lateinit var prefs: Prefs
    private lateinit var db: BridgeDb
    lateinit var manager: BridgeManager
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var torProcess: Process? = null
    private var control: ControlConnection? = null

    @Volatile private var state = State.OFF
    @Volatile private var progress = 0
    @Volatile private var readRate = 0L
    @Volatile private var writeRate = 0L
    @Volatile private var wantRunning = false
    @Volatile private var lastProgressChange = 0L
    private var watchdogJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        db = BridgeDb(this)
        manager = BridgeManager(this, db, prefs)
        createChannel()
        AppLog.i("TorService", "сервис создан")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Обязательное правило startForegroundService: любые входящие
        // intents должны приводить к startForeground, иначе краш.
        startForegroundCompat()
        when (intent?.action ?: Actions.START) {
            Actions.START -> startTor()
            Actions.STOP -> stopTor(true)
            Actions.APPLY -> applyBest()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wantRunning = false
        stopTorInternal()
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    fun isTunnelWanted(): Boolean = wantRunning

    // ---------- публичные операции ----------

    fun startTor() {
        if (wantRunning && (state == State.STARTING || state == State.BOOTSTRAPPING || state == State.CONNECTED)) {
            return
        }
        wantRunning = true
        startForegroundCompat()
        scope.launch { startTorInternal() }
    }

    fun stopTor(andStopService: Boolean) {
        wantRunning = false
        scope.launch {
            stopTorInternal()
            if (andStopService) stopSelf()
        }
    }

    /** Пересобрать конфиг с лучшими мостами и перезапустить tor. */
    fun applyBest() {
        if (!wantRunning) {
            AppLog.w("Tor", "туннель не запущен — сначала «Запустить»")
            return
        }
        scope.launch {
            AppLog.i("Tor", "применяю лучшие мосты: рестарт tor")
            stopTorInternal()
            startTorInternal()
        }
    }

    suspend fun refreshSources(): Int =
        manager.refreshSources(state == State.CONNECTED)

    suspend fun probeBridges(force: Boolean): BridgeTester.Result =
        BridgeTester(db, prefs).testAll(force)

    // ---------- запуск/остановка ----------

    private suspend fun startTorInternal() {
        try {
            setState(State.STARTING, "подготовка конфигурации")
            val torDir = File(filesDir, "tor")
            torDir.mkdirs()
            File(torDir, "cache").mkdirs()
            copyGeoip(torDir)

            val transports = TorConfigBuilder.availableTransports(this)
            val bridges = if (prefs.useBridges) manager.topForConfig(transports) else emptyList()
            AppLog.i(
                "Tor",
                "транспорты в APK: ${transports.joinToString(", ")}; мостов в конфиге: ${bridges.size}"
            )

            val torrc = File(torDir, "torrc")
            torrc.writeText(TorConfigBuilder.build(this, prefs, bridges))
            AppLog.d("Tor", "--- сгенерированный torrc ---\n${torrc.readText()}-------------------------------")

            val torBin = File(applicationInfo.nativeLibraryDir, "libtor.so")
            if (!torBin.exists()) {
                AppLog.e("Tor", "libtor.so не найден в ${applicationInfo.nativeLibraryDir}! Пересоберите APK (CI-шаг с tor-expert-bundle).")
                setState(State.FAILED, "libtor.so не найден — пересоберите APK")
                return
            }

            setState(State.STARTING, "запуск процесса tor")
            val proc = ProcessBuilder(torBin.absolutePath, "-f", torrc.absolutePath)
                .redirectErrorStream(true)
                .start()
            torProcess = proc

            // stdout tor -> AppLog (verbose по умолчанию)
            scope.launch(Dispatchers.IO) {
                try {
                    BufferedReader(InputStreamReader(proc.inputStream, Charsets.ISO_8859_1)).useLines { lines ->
                        for (l in lines) {
                            AppLog.t("TOR", l)
                        }
                    }
                } catch (_: Exception) {
                }
                AppLog.w("Tor", "процесс tor завершился (код ${proc.exitValue()})")
                if (wantRunning) {
                    setState(State.FAILED, "процесс tor завершился (код ${proc.exitValue()})")
                }
            }

            // ControlPort
            val cookieFile = File(torDir, "control_auth_cookie")
            val cc = ControlConnection(prefs.controlPort, ::onControlEvent, ::onControlReply)
            if (!cc.connect()) {
                setState(State.FAILED, "не удалось подключиться к ControlPort ${prefs.controlPort}")
                return
            }
            var waited = 0L
            while (!cookieFile.exists() && waited < 15000) {
                delay(300)
                waited += 300
            }
            val cookie = if (cookieFile.exists()) cookieFile.readBytes() else ByteArray(0)
            if (!cc.authenticate(cookie)) {
                setState(State.FAILED, "аутентификация на ControlPort не удалась")
                cc.close()
                return
            }
            cc.setEvents("STATUS_CLIENT", "BW", "NOTICE", "WARN", "ERR")
            cc.startPump()
            control = cc

            setState(State.BOOTSTRAPPING, "bootstrap…")
            lastProgressChange = System.currentTimeMillis()
            startWatchdog()
        } catch (e: Exception) {
            AppLog.e("Tor", "ошибка запуска: ${e}")
            setState(State.FAILED, "ошибка запуска: ${e.message}")
        }
    }

    private fun copyGeoip(torDir: File) {
        for (name in listOf("geoip", "geoip6")) {
            val out = File(torDir, name)
            if (out.exists()) continue
            try {
                assets.open("tor/$name").use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                AppLog.d("Tor", "geoip скопирован: $name")
            } catch (_: Exception) {
                AppLog.d("Tor", "geoip-файл $name не найден в assets (не критично)")
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        if (!prefs.autoApply) return
        watchdogJob = scope.launch {
            while (isActive) {
                delay(5000)
                if (state != State.BOOTSTRAPPING) continue
                val stuckMs = System.currentTimeMillis() - lastProgressChange
                if (stuckMs > 90000) {
                    AppLog.w("Watchdog", "bootstrap без прогресса ${stuckMs / 1000}с — переприменяю лучшие мосты")
                    lastProgressChange = System.currentTimeMillis()
                    stopTorInternal()
                    startTorInternal()
                }
            }
        }
    }

    private fun stopTorInternal() {
        watchdogJob?.cancel()
        watchdogJob = null
        try {
            control?.signal("SHUTDOWN")
        } catch (_: Exception) {
        }
        control?.close()
        control = null
        val p = torProcess
        torProcess = null
        if (p != null) {
            try {
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroy()
                }
            } catch (_: Exception) {
            }
        }
        state = State.OFF
        progress = 0
        readRate = 0
        writeRate = 0
        publish(buildStatus("остановлено"))
        updateNotification()
    }

    // ---------- ControlPort ----------

    private fun onControlEvent(ev: String) {
        AppLog.d("CTL", ev)
        if (ev.startsWith("STATUS_CLIENT")) {
            val m = Regex("PROGRESS=(\\d+)").find(ev)
            if (m != null) {
                val p = m.groupValues[1].toInt()
                if (p != progress) {
                    progress = p
                    lastProgressChange = System.currentTimeMillis()
                }
                if (p >= 100) {
                    if (state != State.CONNECTED) {
                        setState(State.CONNECTED, "туннель поднят (bootstrap 100%)")
                    } else {
                        publish(buildStatus("туннель поднят"))
                    }
                } else if (state == State.BOOTSTRAPPING) {
                    publish(buildStatus("bootstrap $p%"))
                }
            }
        } else if (ev.startsWith("BW")) {
            val parts = ev.split(" ")
            if (parts.size >= 3) {
                readRate = parts[1].toLongOrNull() ?: 0
                writeRate = parts[2].toLongOrNull() ?: 0
                publish(buildStatus())
            }
        }
    }

    private fun onControlReply(l: String) {
        AppLog.d("CTL", l)
    }

    // ---------- состояние и уведомление ----------

    private fun setState(s: State, msg: String) {
        state = s
        if (s == State.CONNECTED) progress = 100
        if (s == State.OFF || s == State.FAILED) progress = 0
        AppLog.i("Tor", "СОСТОЯНИЕ: $s — $msg")
        publish(buildStatus(msg))
        updateNotification()
    }

    private fun buildStatus(msg: String = ""): Status {
        val m = if (msg.isNotEmpty()) msg else lastStatus.message
        return Status(state, progress, readRate, writeRate, m)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Tor-туннель", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Состояние SOCKS5-туннеля"
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun startForegroundCompat() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("BridgePipe")
            .setContentText(lastStatus.message)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) {
        }
    }
}
