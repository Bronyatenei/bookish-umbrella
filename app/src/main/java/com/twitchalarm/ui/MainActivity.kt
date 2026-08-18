package com.twitchalarm.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.twitchalarm.R
import com.twitchalarm.api.TwitchApi
import com.twitchalarm.data.AppDatabase
import com.twitchalarm.data.Streamer
import com.twitchalarm.databinding.ActivityMainBinding
import com.twitchalarm.work.AlarmPlaybackService
import com.twitchalarm.work.MonitoringController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: StreamerAdapter
    private lateinit var database: AppDatabase
    private var synchronizingBulkToggle = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestFullScreenPermissionIfNeeded() else showNotificationPermissionDialog()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        database = AppDatabase.getInstance(this)

        setupToolbar()
        setupRecyclerView()
        setupAddButton()
        setupSwipeToDelete()
        setupBulkToggle()
        observeStreamers()
        requestNotificationPermission()
        requestFullScreenPermissionIfNeeded()
        refreshMonitoringState()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_scheduled_alarms -> {
            startActivity(Intent(this, ScheduledAlarmsActivity::class.java))
            true
        }
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
    }

    private fun setupRecyclerView() {
        adapter = StreamerAdapter(
            onToggle = { streamer, enabled -> updateToggle(streamer, enabled) },
            onDelete = ::confirmDelete,
            onTestAlarm = ::testAlarm
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            setHasFixedSize(true)
        }
    }

    private fun updateToggle(streamer: Streamer, enabled: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            database.streamerDao().update(streamer.copy(notifyEnabled = enabled))
            if (enabled) {
                MonitoringController.start(this@MainActivity)
            } else if (database.streamerDao().getEnabled().isEmpty()) {
                MonitoringController.stop(this@MainActivity)
            }
        }
    }


    private fun setupBulkToggle() {
        binding.switchAllNotify.setOnCheckedChangeListener { _, enabled ->
            if (synchronizingBulkToggle) return@setOnCheckedChangeListener
            lifecycleScope.launch(Dispatchers.IO) {
                database.streamerDao().setAllNotifyEnabled(enabled)
                if (enabled) {
                    MonitoringController.start(this@MainActivity)
                } else {
                    MonitoringController.stop(this@MainActivity)
                }
            }
        }
    }

    private fun setupAddButton() {
        binding.btnAdd.setOnClickListener {
            val login = binding.etNickname.text?.toString()?.trim()?.lowercase().orEmpty()
            if (login.isEmpty()) {
                binding.etNickname.error = "Введите ник стримера"
                return@setOnClickListener
            }
            addStreamer(login)
        }
        binding.etNickname.setOnEditorActionListener { _, _, _ ->
            binding.btnAdd.performClick()
            true
        }
    }

    private fun addStreamer(login: String) {
        binding.btnAdd.isEnabled = false
        binding.progressAdd.visibility = View.VISIBLE
        lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) {
                database.streamerDao().getByLogin(login)
            }
            if (existing != null) {
                Toast.makeText(this@MainActivity, "Стример уже добавлен", Toast.LENGTH_SHORT).show()
                resetAddButton()
                return@launch
            }

            val info = withContext(Dispatchers.IO) { TwitchApi.checkStream(login) }
            if (info == null) {
                Toast.makeText(this@MainActivity, "Ошибка сети. Проверьте интернет.", Toast.LENGTH_SHORT).show()
                resetAddButton()
                return@launch
            }

            withContext(Dispatchers.IO) {
                database.streamerDao().insert(
                    Streamer(
                        login = login,
                        displayName = info.displayName.ifBlank { login },
                        isLive = info.isLive,
                        streamTitle = info.title,
                        viewerCount = info.viewerCount,
                        gameName = info.gameName
                    )
                )
            }
            binding.etNickname.setText("")
            MonitoringController.start(this@MainActivity)
            Toast.makeText(
                this@MainActivity,
                if (info.isLive) "${info.displayName} уже в эфире" else "${info.displayName} добавлен",
                Toast.LENGTH_SHORT
            ).show()
            resetAddButton()
        }
    }

    private fun resetAddButton() {
        binding.btnAdd.isEnabled = true
        binding.progressAdd.visibility = View.GONE
    }

    private fun setupSwipeToDelete() {
        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position == RecyclerView.NO_POSITION) return
                val streamer = adapter.currentList[position]
                confirmDelete(streamer)
                adapter.notifyItemChanged(position)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.recyclerView)
    }

    private fun confirmDelete(streamer: Streamer) {
        AlertDialog.Builder(this)
            .setTitle("Удалить стримера?")
            .setMessage("${streamer.displayName.ifBlank { streamer.login }} будет удалён из списка.")
            .setPositiveButton("Удалить") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    database.streamerDao().delete(streamer)
                    if (database.streamerDao().getEnabled().isEmpty()) {
                        MonitoringController.stop(this@MainActivity)
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun testAlarm(streamer: Streamer) {
        AlarmPlaybackService.start(
            context = this,
            displayName = streamer.displayName.ifBlank { streamer.login },
            title = "Тест будильника",
            game = "Тестовый стрим",
            viewers = 12_345
        )
    }

    private fun observeStreamers() {
        lifecycleScope.launch {
            database.streamerDao().getAllFlow().collect { streamers ->
                adapter.submitList(streamers)
                binding.tvEmpty.visibility = if (streamers.isEmpty()) View.VISIBLE else View.GONE
                synchronizeBulkToggle(streamers)
            }
        }
    }

    private fun synchronizeBulkToggle(streamers: List<Streamer>) {
        synchronizingBulkToggle = true
        binding.switchAllNotify.visibility = if (streamers.isEmpty()) View.GONE else View.VISIBLE
        binding.switchAllNotify.isChecked = streamers.isNotEmpty() && streamers.all { it.notifyEnabled }
        synchronizingBulkToggle = false
    }

    private fun refreshMonitoringState() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (database.streamerDao().getEnabled().isEmpty()) {
                MonitoringController.stop(this@MainActivity)
            } else {
                MonitoringController.start(this@MainActivity)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestFullScreenPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.canUseFullScreenIntent()) return

        AlertDialog.Builder(this)
            .setTitle("Разрешите полноэкранную тревогу")
            .setMessage(
                "Чтобы будильник открылся при выключенном или заблокированном экране, " +
                    "разрешите полноэкранные уведомления для приложения."
            )
            .setPositiveButton("Открыть настройки") { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun showNotificationPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Нужно разрешение на уведомления")
            .setMessage("Без уведомлений приложение не сможет показать тревогу при начале стрима.")
            .setPositiveButton("Настройки") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                })
            }
            .setNegativeButton("Позже", null)
            .show()
    }
}
