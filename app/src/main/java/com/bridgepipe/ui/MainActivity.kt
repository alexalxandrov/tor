package com.bridgepipe.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bridgepipe.R
import com.bridgepipe.bridges.BridgeManager
import com.bridgepipe.bridges.BridgeTester
import com.bridgepipe.data.BridgeDb
import com.bridgepipe.data.Prefs
import com.bridgepipe.tor.TorService
import com.bridgepipe.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: Prefs
    private lateinit var db: BridgeDb
    private lateinit var bridgesAdapter: BridgesAdapter
    private lateinit var logAdapter: LogAdapter
    private var unlog: (() -> Unit)? = null
    private var unstat: (() -> Unit)? = null

    private val logFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // --- вьюхи ---
    private lateinit var viewStatus: View
    private lateinit var viewBridgesV: View
    private lateinit var viewLogV: View
    private lateinit var viewSettingsV: View

    private lateinit var statusState: TextView
    private lateinit var statusProgress: TextView
    private lateinit var statusBw: TextView
    private lateinit var statusSocks: TextView
    private lateinit var statusBridgesInfo: TextView
    private lateinit var btnStart: Button

    private lateinit var bridgesCount: TextView
    private lateinit var listBridges: RecyclerView
    private lateinit var listLog: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        db = BridgeDb(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        viewStatus = findViewById(R.id.viewStatus)
        viewBridgesV = findViewById(R.id.viewBridges)
        viewLogV = findViewById(R.id.viewLog)
        viewSettingsV = findViewById(R.id.viewSettings)

        statusState = findViewById(R.id.statusState)
        statusProgress = findViewById(R.id.statusProgress)
        statusBw = findViewById(R.id.statusBw)
        statusSocks = findViewById(R.id.statusSocks)
        statusBridgesInfo = findViewById(R.id.statusBridgesInfo)
        btnStart = findViewById(R.id.btnStart)

        bridgesCount = findViewById(R.id.bridgesCount)
        listBridges = findViewById(R.id.listBridges)
        listLog = findViewById(R.id.listLog)

        setupTabs()
        setupStatusTab()
        setupBridgesTab()
        setupLogTab()
        setupSettingsTab()

        unstat = TorService.listen { s -> runOnUiThread { renderStatus(s) } }
        refreshBridges()
    }

    override fun onDestroy() {
        unlog?.invoke()
        unstat?.invoke()
        scope.cancel()
        super.onDestroy()
    }

    // ---------- табы ----------

    private fun setupTabs() {
        val tabs = listOf(
            findViewById<Button>(R.id.tabStatus),
            findViewById(R.id.tabBridges),
            findViewById(R.id.tabLog),
            findViewById(R.id.tabSettings)
        )
        tabs.forEachIndexed { i, b ->
            b.setOnClickListener { showTab(i) }
        }
        showTab(0)
    }

    private fun showTab(idx: Int) {
        viewStatus.visibility = if (idx == 0) View.VISIBLE else View.GONE
        viewBridgesV.visibility = if (idx == 1) View.VISIBLE else View.GONE
        viewLogV.visibility = if (idx == 2) View.VISIBLE else View.GONE
        viewSettingsV.visibility = if (idx == 3) View.VISIBLE else View.GONE
    }

    // ---------- статус ----------

    private fun setupStatusTab() {
        btnStart.setOnClickListener {
            val inst = TorService.instance
            if (inst != null && inst.isTunnelWanted()) {
                sendAction(TorService.Actions.STOP)
            } else {
                sendAction(TorService.Actions.START)
            }
        }
        findViewById<Button>(R.id.btnRefreshSources).setOnClickListener {
            scope.launch(Dispatchers.IO) {
                val torUp = TorService.status().state == TorService.State.CONNECTED
                val m = BridgeManager(this@MainActivity, db, prefs)
                val added = m.refreshSources(torUp)
                withContext(Dispatchers.Main) {
                    toast("Новых мостов: $added")
                    refreshBridges()
                }
            }
        }
        findViewById<Button>(R.id.btnProbe).setOnClickListener { probe(false) }
        findViewById<Button>(R.id.btnApplyBest).setOnClickListener {
            val inst = TorService.instance
            if (inst != null && inst.isTunnelWanted()) {
                inst.applyBest()
            } else {
                toast("Сначала запустите туннель")
            }
        }
        statusSocks.text = "SOCKS5: 127.0.0.1:${prefs.socksPort}"
    }

    private fun renderStatus(s: TorService.Status) {
        statusState.text = when (s.state) {
            TorService.State.OFF -> "Остановлено"
            TorService.State.STARTING -> "Запуск…"
            TorService.State.BOOTSTRAPPING -> "Подключение… ${s.progress}%"
            TorService.State.CONNECTED -> "Подключено ✓"
            TorService.State.STOPPING -> "Остановка…"
            TorService.State.FAILED -> "Ошибка"
        }
        statusProgress.text = if (s.state == TorService.State.BOOTSTRAPPING) {
            "bootstrap: ${s.progress}%"
        } else {
            ""
        }
        statusBw.text = "↓ ${s.readRate / 1024} КБ/с  ↑ ${s.writeRate / 1024} КБ/с"
        val inst = TorService.instance
        btnStart.text = if (inst != null && inst.isTunnelWanted()) "Остановить туннель" else "Запустить туннель"
        statusBridgesInfo.text = "Мостов в базе: ${db.count()} (рабочих: ${db.countOk()})"
    }

    private fun sendAction(action: String) {
        val i = Intent(this, TorService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i)
        } else {
            startService(i)
        }
    }

    // ---------- мосты ----------

    private fun setupBridgesTab() {
        bridgesAdapter = BridgesAdapter()
        listBridges.layoutManager = LinearLayoutManager(this)
        listBridges.adapter = bridgesAdapter

        findViewById<Button>(R.id.btnImport).setOnClickListener { showImportDialog() }
        findViewById<Button>(R.id.btnProbeAll).setOnClickListener { probe(true) }
        findViewById<Button>(R.id.btnDeleteAll).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Удалить все мосты?")
                .setPositiveButton("Удалить") { _, _ ->
                    scope.launch(Dispatchers.IO) {
                        db.deleteAll()
                        withContext(Dispatchers.Main) { refreshBridges() }
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun refreshBridges() {
        scope.launch(Dispatchers.IO) {
            val all = db.all()
            val ok = db.countOk()
            val fail = db.countFail()
            val new = db.countNew()
            withContext(Dispatchers.Main) {
                bridgesAdapter.submit(all)
                bridgesCount.text = "Всего ${all.size} · ✓ $ok · ✗ $fail · новых $new"
            }
        }
    }

    private fun showImportDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_import, null)
        AlertDialog.Builder(this)
            .setTitle("Импорт мостов (до 2000 строк)")
            .setView(v)
            .setPositiveButton("Импортировать") { _, _ ->
                val txt = v.findViewById<EditText>(R.id.importText).text.toString()
                scope.launch(Dispatchers.IO) {
                    val m = BridgeManager(this@MainActivity, db, prefs)
                    val n = m.importLines(txt, "manual")
                    withContext(Dispatchers.Main) {
                        toast("Добавлено: $n")
                        refreshBridges()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun probe(force: Boolean) {
        scope.launch(Dispatchers.IO) {
            val inst = TorService.instance
            val r = if (inst != null) {
                inst.probeBridges(force)
            } else {
                BridgeTester(db, prefs).testAll(force)
            }
            withContext(Dispatchers.Main) {
                toast("Живых: ${r.ok} · мёртвых: ${r.fail} · пропущено: ${r.skipped}")
                refreshBridges()
            }
        }
    }

    // ---------- лог ----------

    private fun setupLogTab() {
        logAdapter = LogAdapter()
        listLog.layoutManager = LinearLayoutManager(this)
        listLog.adapter = logAdapter
        for (e in AppLog.snapshot()) {
            logAdapter.add(fmt(e))
        }
        listLog.scrollToPosition(logAdapter.itemCount - 1)

        unlog = AppLog.listen { e ->
            runOnUiThread {
                logAdapter.add(fmt(e))
                listLog.scrollToPosition(logAdapter.itemCount - 1)
            }
        }

        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("bridgepipe-log", logAdapter.dump()))
            toast("Лог скопирован")
        }
        findViewById<Button>(R.id.btnShareLog).setOnClickListener {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, logAdapter.dump(2000))
            }
            startActivity(Intent.createChooser(i, "Экспорт лога"))
        }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            AppLog.clear()
            logAdapter.clear()
        }
    }

    private fun fmt(e: AppLog.Entry): String =
        logFmt.format(Date(e.ts)) + " ${e.level}/${e.tag}: ${e.msg}"

    // ---------- настройки ----------

    private fun setupSettingsTab() {
        findViewById<EditText>(R.id.setSocksPort).setText(prefs.socksPort.toString())
        findViewById<EditText>(R.id.setControlPort).setText(prefs.controlPort.toString())
        findViewById<CheckBox>(R.id.setSocksBindAll).isChecked = prefs.socksBindAll
        findViewById<EditText>(R.id.setTopN).setText(prefs.topN.toString())
        findViewById<CheckBox>(R.id.setUseBridges).isChecked = prefs.useBridges
        findViewById<CheckBox>(R.id.setFetchViaTor).isChecked = prefs.fetchViaTor
        findViewById<CheckBox>(R.id.setBridgesDb).isChecked = prefs.bridgesDbEnabled
        findViewById<EditText>(R.id.setBridgesDbUrl).setText(prefs.bridgesDbUrl)
        findViewById<EditText>(R.id.setCustomSources).setText(prefs.customSources)
        findViewById<CheckBox>(R.id.setReducedPadding).isChecked = prefs.reducedPadding
        findViewById<CheckBox>(R.id.setAllowIpv6).isChecked = prefs.allowIpv6
        findViewById<CheckBox>(R.id.setSnowflake).isChecked = prefs.snowflakeEnabled
        findViewById<EditText>(R.id.setBandwidthRate).setText(prefs.bandwidthRateKB.toString())
        findViewById<EditText>(R.id.setSocksTimeout).setText(prefs.socksTimeoutSec.toString())
        findViewById<EditText>(R.id.setProbeTimeout).setText(prefs.probeTimeoutSec.toString())
        findViewById<EditText>(R.id.setProbeConcurrency).setText(prefs.probeConcurrency.toString())
        findViewById<CheckBox>(R.id.setSniEnabled).isChecked = prefs.sniEnabled
        findViewById<EditText>(R.id.setSniHost).setText(prefs.sniHost)
        findViewById<CheckBox>(R.id.setAutoApply).isChecked = prefs.autoApply
        findViewById<EditText>(R.id.setTorrcOverride).setText(prefs.torrcOverride)

        val levels = listOf("notice", "info", "debug")
        val spinner = findViewById<Spinner>(R.id.setTorLogLevel)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, levels)
        spinner.setSelection(levels.indexOf(prefs.torLogLevel).coerceAtLeast(0))

        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener { saveSettings() }
    }

    private fun saveSettings() {
        fun int(id: Int, fallback: Int): Int =
            findViewById<EditText>(id).text.toString().toIntOrNull() ?: fallback

        prefs.socksPort = int(R.id.setSocksPort, 9050).coerceIn(1024, 65535)
        prefs.controlPort = int(R.id.setControlPort, 9051).coerceIn(1024, 65535)
        prefs.socksBindAll = findViewById<CheckBox>(R.id.setSocksBindAll).isChecked
        prefs.topN = int(R.id.setTopN, 12).coerceIn(1, 200)
        prefs.useBridges = findViewById<CheckBox>(R.id.setUseBridges).isChecked
        prefs.fetchViaTor = findViewById<CheckBox>(R.id.setFetchViaTor).isChecked
        prefs.bridgesDbEnabled = findViewById<CheckBox>(R.id.setBridgesDb).isChecked
        prefs.bridgesDbUrl =
            findViewById<EditText>(R.id.setBridgesDbUrl).text.toString().ifBlank { "https://bridges.torproject.org/bridges?transport=" }
        prefs.customSources = findViewById<EditText>(R.id.setCustomSources).text.toString()
        prefs.reducedPadding = findViewById<CheckBox>(R.id.setReducedPadding).isChecked
        prefs.allowIpv6 = findViewById<CheckBox>(R.id.setAllowIpv6).isChecked
        prefs.snowflakeEnabled = findViewById<CheckBox>(R.id.setSnowflake).isChecked
        prefs.bandwidthRateKB = int(R.id.setBandwidthRate, 0).coerceAtLeast(0)
        prefs.socksTimeoutSec = int(R.id.setSocksTimeout, 120).coerceIn(10, 600)
        prefs.probeTimeoutSec = int(R.id.setProbeTimeout, 7).coerceIn(1, 60)
        prefs.probeConcurrency = int(R.id.setProbeConcurrency, 12).coerceIn(1, 64)
        prefs.sniEnabled = findViewById<CheckBox>(R.id.setSniEnabled).isChecked
        prefs.sniHost = findViewById<EditText>(R.id.setSniHost).text.toString().trim()
        prefs.autoApply = findViewById<CheckBox>(R.id.setAutoApply).isChecked
        prefs.torrcOverride = findViewById<EditText>(R.id.setTorrcOverride).text.toString()
        prefs.torLogLevel = (findViewById<Spinner>(R.id.setTorLogLevel).selectedItem as? String) ?: "notice"

        statusSocks.text = "SOCKS5: 127.0.0.1:${prefs.socksPort}"
        toast("Сохранено. Рестарт туннеля применит новые настройки.")
        AppLog.i("Settings", "настройки сохранены")
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }
}
