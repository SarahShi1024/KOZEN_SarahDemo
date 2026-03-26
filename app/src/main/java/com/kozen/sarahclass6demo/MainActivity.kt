package com.kozen.sarahclass6demo

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.kozen.component.constant.KeyboardConstant
import com.kozen.component.keyboard.IKeyboard
import com.kozen.component.keyboard.InputCallback
import com.kozen.component.secondaryScreen.ISecondaryScreen
import com.kozen.component_client.ComponentEngine
import com.kozen.sarahclass6demo.databinding.ActivityMainBinding
import com.kozen.terminalmanager.TerminalManager
import com.kozen.terminalmanager.device.IDeviceManager
import com.kozen.terminalmanager.resource.IResourceManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val deviceManager: IDeviceManager?
        get() = MyApplication.deviceManager

    private val resourceManager: IResourceManager?
        get() = TerminalManager.getResourceManager()

    private val secondaryScreenManager: ISecondaryScreen?
        get() = MyApplication.secondaryScreenManager

    private val keyboardManager: IKeyboard?
        get() = try {
            ComponentEngine.getKeyboardManager()
        } catch (_: Exception) {
            null
        }

    private val preloadedImagePath: String
        get() = "/storage/emulated/0/Download/secondary_demo.jpg"

    private val selectedApkPublicPath: String
        get() = "/sdcard/Download/selected_install.apk"

    private val REQUEST_PICK_APK = 1001

    private val timeZones = TimeZone.getAvailableIDs()
    private var selectedTimeZone = TimeZone.getDefault().id

    private var selectedApkUri: Uri? = null
    private var selectedApkPath: String? = null

    // game
    private var gameTimer: CountDownTimer? = null
    private var gameRunning = false
    private var currentTargetNumber = -1
    private var score = 0
    private var remainSeconds = 20L
    private var combo = 0
    private var lastKeyTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 开启 Kozen 安装相关权限策略
        try {
            deviceManager?.setSilentInstall(true)
            deviceManager?.forcePermission(true)
        } catch (e: Exception) {
            addStatusLog("Silent install setup failed: ${e.message}")
        }

        copyPreloadedImageToStorage()
        initializeUI()
        initializeGameUI()
        setupListeners()

        binding.root.postDelayed({
            checkSDKStatus()
        }, 1000)
    }

    override fun onDestroy() {
        gameTimer?.cancel()
        stopKeyboardListening()
        super.onDestroy()
    }

    private fun initializeUI() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            timeZones.toList()
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerTimeZone.adapter = adapter

        val currentDeviceTimeZone = try {
            deviceManager?.timeZone
        } catch (_: Exception) {
            null
        }

        val defaultIndex = timeZones.indexOf(currentDeviceTimeZone ?: TimeZone.getDefault().id)
        if (defaultIndex >= 0) {
            binding.spinnerTimeZone.setSelection(defaultIndex)
        }

        binding.etDateTime.setOnClickListener {
            showDateTimePicker()
        }

        updateCurrentDateTime()

        binding.seekBarBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvBrightnessValue.text = "Brightness: $progress"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                setBrightness(seekBar?.progress ?: 128)
            }
        })

        binding.seekBarBrightness.progress = 128
        binding.tvBrightnessValue.text = "Brightness: 128"
        binding.btnInstallApp.isEnabled = false
    }

    private fun setupListeners() {
        binding.spinnerTimeZone.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                selectedTimeZone = timeZones[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.btnSetTime.setOnClickListener { setSystemTime() }
        binding.btnSetTimeZone.setOnClickListener { setTimeZone() }
        binding.btnBrowseApk.setOnClickListener { browseApkFile() }
        binding.btnInstallApp.setOnClickListener { installAppBySdk() }
        binding.btnUninstallApp.setOnClickListener { uninstallApp() }
        binding.btnListApps.setOnClickListener { listInstalledApps() }
        binding.btnPowerOnScreen.setOnClickListener { powerOnScreen() }
        binding.btnPowerOffScreen.setOnClickListener { powerOffScreen() }
        binding.btnShowWallpaper.setOnClickListener { showWallpaper() }
        binding.btnShowPicture.setOnClickListener { showPicture() }
        binding.btnStartGame.setOnClickListener { startWhackNumberGame() }
        binding.btnStopGame.setOnClickListener { stopWhackNumberGame("Game stopped") }
    }

    private fun initializeGameUI() {
        binding.tvGameTarget.text = "-"
        binding.tvGameScore.text = "Score: 0"
        binding.tvGameTimer.text = "Time: 20"
        binding.tvGameStatus.text = "Press Start"
    }

    private fun checkSDKStatus() {
        addStatusLog("DeviceManager = $deviceManager")
        addStatusLog("ResourceManager = $resourceManager")
        addStatusLog("SecondaryScreenManager = $secondaryScreenManager")
        addStatusLog("KeyboardManager = $keyboardManager")

        if (deviceManager == null && resourceManager == null && secondaryScreenManager == null) {
            addStatusLog("Kozen SDK unavailable")
            return
        }

        addStatusLog("Kozen SDK available")
        getDeviceInfo()
    }

    private fun getDeviceInfo() {
        try {
            deviceManager?.let { dm ->
                val currentTime = dm.systemTime
                val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                addStatusLog("System time: ${format.format(Date(currentTime))}")
                addStatusLog("Time zone: ${dm.timeZone}")
            }

            secondaryScreenManager?.let { sm ->
                val resolution = sm.screenResolution
                if (resolution.size >= 2) {
                    addStatusLog("Secondary screen: ${resolution[0]}x${resolution[1]}")
                }
                addStatusLog("Secondary screen power: ${sm.powerOnStatus}")
            }
        } catch (e: Exception) {
            addStatusLog("Get device info failed: ${e.message}")
        }
    }
    private fun prepareApkFromUri(uri: Uri): String? {
        return try {
            val fileName = "selected_install.apk"

            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }

            val resolver = contentResolver

            val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI

            val itemUri = resolver.insert(collection, contentValues) ?: return null

            resolver.openOutputStream(itemUri)?.use { output ->
                resolver.openInputStream(uri)?.use { input ->
                    input.copyTo(output)
                }
            }

            contentValues.clear()
            contentValues.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(itemUri, contentValues, null, null)

            // ⚠️ 关键：拿真实路径
            val filePath = "/storage/emulated/0/Download/$fileName"

            addStatusLog("APK saved to public Download: $filePath")

            filePath
        } catch (e: Exception) {
            addStatusLog("Prepare APK failed: ${e.message}")
            null
        }
    }

    private fun tryGetPathFromUri(uri: Uri): String? {
        return try {
            when (uri.scheme) {
                "file" -> uri.path
                "content" -> {
                    val projection = arrayOf("_data")
                    contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        val index = cursor.getColumnIndexOrThrow("_data")
                        if (cursor.moveToFirst()) {
                            cursor.getString(index)
                        } else {
                            null
                        }
                    }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun copyPreloadedImageToStorage(): Boolean {
        return try {
            val fileName = "secondary_demo.jpg"

            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }

            val resolver = contentResolver
            val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val itemUri = resolver.insert(collection, contentValues) ?: return false

            resolver.openOutputStream(itemUri)?.use { output ->
                assets.open("secondary_demo.jpg").use { input ->
                    input.copyTo(output)
                }
            } ?: return false

            contentValues.clear()
            contentValues.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(itemUri, contentValues, null, null)

            val filePath = "/storage/emulated/0/Download/$fileName"
            addStatusLog("image saved to public Download: $filePath")

            true
        } catch (e: Exception) {
            addStatusLog("image copy failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun showDateTimePicker() {
        val calendar = Calendar.getInstance()

        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                TimePickerDialog(
                    this,
                    { _, hourOfDay, minute ->
                        calendar.set(year, month, dayOfMonth, hourOfDay, minute, 0)
                        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        binding.etDateTime.setText(format.format(calendar.time))
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    true
                ).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun updateCurrentDateTime() {
        try {
            val dm = deviceManager ?: run {
                addStatusLog("DeviceManager unavailable")
                return
            }

            val currentTime = dm.systemTime
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            binding.etDateTime.setText(format.format(Date(currentTime)))
        } catch (e: Exception) {
            addStatusLog("Get system time failed: ${e.message}")
        }
    }

    private fun setSystemTime() {
        try {
            val dm = deviceManager ?: run {
                addStatusLog("DeviceManager unavailable")
                return
            }

            val dateTimeStr = binding.etDateTime.text?.toString().orEmpty()
            if (TextUtils.isEmpty(dateTimeStr)) {
                Snackbar.make(binding.root, "Please select date and time", Snackbar.LENGTH_SHORT).show()
                return
            }

            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val date = format.parse(dateTimeStr)
            val timestamp = date?.time ?: System.currentTimeMillis()

            val result = deviceManager?.setSystemTime(timestamp)
            addStatusLog("setSystemTime($timestamp) result=$result")
            Log.d("test_demo", "setSystemTime: result == "+result)
            if (result == 0) {
                Snackbar.make(binding.root, "Time updated", Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(binding.root, "Set time failed: $result", Snackbar.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            addStatusLog("Set system time error: ${e.message}")
        }
    }

    private fun setTimeZone() {
        try {
            val dm = deviceManager ?: run {
                addStatusLog("DeviceManager unavailable")
                return
            }

            val result = dm.setTimeZone(selectedTimeZone)
            addStatusLog("setTimeZone($selectedTimeZone) result=$result")

            if (result == 0) {
                Snackbar.make(binding.root, "Time zone updated", Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(binding.root, "Set time zone failed: $result", Snackbar.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            addStatusLog("Set time zone error: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun browseApkFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/vnd.android.package-archive"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, REQUEST_PICK_APK)
    }

    private fun copySelectedApkToPublic(sourceUri: Uri): String? {
        return try {
            val targetFile = File(selectedApkPublicPath)
            targetFile.parentFile?.mkdirs()

            contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(targetFile, false).use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            addStatusLog("APK copied: ${targetFile.absolutePath}, len=${targetFile.length()}")
            targetFile.absolutePath
        } catch (e: Exception) {
            addStatusLog("Copy APK failed: ${e.message}")
            null
        }
    }

    private fun installAppBySdk() {
        val path = selectedApkPath ?: binding.etApkPath.text?.toString().orEmpty()

        if (path.isEmpty()) {
            Snackbar.make(binding.root, "Please select APK", Snackbar.LENGTH_SHORT).show()
            return
        }

        val file = File(path)
        addStatusLog("install path=$path")
        addStatusLog("exists=${file.exists()} len=${if (file.exists()) file.length() else -1}")

        if (!file.exists()) {
            Snackbar.make(binding.root, "APK not found", Snackbar.LENGTH_SHORT).show()
            return
        }

        val rm = resourceManager ?: run {
            addStatusLog("ResourceManager unavailable")
            Snackbar.make(binding.root, "ResourceManager unavailable", Snackbar.LENGTH_SHORT).show()
            return
        }

        val result = rm.installOrUpdate(path)
        addStatusLog("installOrUpdate($path) result=$result")

        if (result == 0) {
            Snackbar.make(binding.root, "Install requested", Snackbar.LENGTH_SHORT).show()
            binding.etApkPath.setText("")
            selectedApkPath = null
            selectedApkUri = null
            binding.btnInstallApp.isEnabled = false
        } else {
            Snackbar.make(binding.root, "Install failed: $result", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun uninstallApp() {
        val pkg = binding.etUninstallPackage.text?.toString().orEmpty()

        if (pkg.isEmpty()) {
            Snackbar.make(binding.root, "Please input package", Snackbar.LENGTH_SHORT).show()
            return
        }

        val rm = resourceManager ?: run {
            addStatusLog("ResourceManager unavailable")
            Snackbar.make(binding.root, "ResourceManager unavailable", Snackbar.LENGTH_SHORT).show()
            return
        }

        val result = rm.unInstall(pkg)
        addStatusLog("unInstall($pkg) result=$result")

        if (result == 0) {
            binding.etUninstallPackage.setText("")
            Snackbar.make(binding.root, "Uninstall requested", Snackbar.LENGTH_SHORT).show()
        } else {
            Snackbar.make(binding.root, "Uninstall failed: $result", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun listInstalledApps() {
        try {
            val pm = packageManager

            val packages = pm.getInstalledPackages(0)
                .filter { it.packageName != packageName }
                .sortedByDescending { it.firstInstallTime }
                .take(30)

            if (packages.isEmpty()) {
                Snackbar.make(binding.root, "No apps found", Snackbar.LENGTH_SHORT).show()
                return
            }

            val displayItems = packages.map { pkgInfo ->
                val appName = try {
                    pkgInfo.applicationInfo?.loadLabel(pm)?.toString()
                } catch (_: Exception) {
                    pkgInfo.packageName
                } ?: pkgInfo.packageName

                val installTime = try {
                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                        .format(Date(pkgInfo.firstInstallTime))
                } catch (_: Exception) {
                    ""
                }

                "$appName\n${pkgInfo.packageName}\n$installTime"
            }.toTypedArray()

            AlertDialog.Builder(this)
                .setTitle("Select app to uninstall")
                .setItems(displayItems) { _, which ->
                    val targetPackage = packages[which].packageName
                    binding.etUninstallPackage.setText(targetPackage)
                    addStatusLog("Selected uninstall package: $targetPackage")

                    Snackbar.make(
                        binding.root,
                        "Selected: $targetPackage",
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton("Cancel", null)
                .show()

            addStatusLog("Loaded ${packages.size} installed packages")
        } catch (e: Exception) {
            addStatusLog("List apps failed: ${e.message}")
            Snackbar.make(binding.root, "List apps failed", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun powerOnScreen() {
        try {
            val sm = secondaryScreenManager ?: run {
                addStatusLog("SecondaryScreenManager unavailable")
                return
            }

            val result = sm.power(true)
            addStatusLog("power(true) result=$result")

            if (result == 0) {
                Snackbar.make(binding.root, "Screen ON", Snackbar.LENGTH_SHORT).show()
            } else {
                addStatusLog("Power on failed: $result")
            }
        } catch (e: Exception) {
            addStatusLog("Power on error: ${e.message}")
        }
    }

    private fun powerOffScreen() {
        try {
            val sm = secondaryScreenManager ?: run {
                addStatusLog("SecondaryScreenManager unavailable")
                return
            }

            val result = sm.power(false)
            addStatusLog("power(false) result=$result")

            if (result == 0) {
                Snackbar.make(binding.root, "Screen OFF", Snackbar.LENGTH_SHORT).show()
            } else {
                addStatusLog("Power off failed: $result")
            }
        } catch (e: Exception) {
            addStatusLog("Power off error: ${e.message}")
        }
    }

    private fun showWallpaper() {
        try {
            val sm = secondaryScreenManager ?: run {
                addStatusLog("SecondaryScreenManager unavailable")
                return
            }

            val result = sm.showWallpaper()
            addStatusLog("showWallpaper result=$result")

            if (result == 0) {
                Snackbar.make(binding.root, "Wallpaper shown", Snackbar.LENGTH_SHORT).show()
            } else {
                addStatusLog("Show wallpaper failed: $result")
            }
        } catch (e: Exception) {
            addStatusLog("Show wallpaper error: ${e.message}")
        }
    }

    private fun showPicture() {
        try {
            val sm = secondaryScreenManager ?: run {
                addStatusLog("SecondaryScreenManager unavailable")
                return
            }

            val copied = copyPreloadedImageToStorage()
            if (!copied) {
                Snackbar.make(binding.root, "Prepare image failed", Snackbar.LENGTH_SHORT).show()
                return
            }

            val file = File(preloadedImagePath)
            addStatusLog("showPic path=${file.absolutePath}")
            addStatusLog("exists=${file.exists()}, len=${if (file.exists()) file.length() else -1}")

            val result = sm.showPic(file.absolutePath)
            addStatusLog("showPic result=$result")

            if (result == 0) {
                Snackbar.make(binding.root, "Picture shown", Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(binding.root, "Show failed: $result", Snackbar.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            addStatusLog("Show picture error: ${e.message}")
        }
    }

    private fun setBrightness(value: Int) {
        try {
            val sm = secondaryScreenManager ?: run {
                addStatusLog("SecondaryScreenManager unavailable")
                return
            }

            val result = sm.setBrightness(value)
            addStatusLog("setBrightness($value) result=$result")

            if (result != 0) {
                addStatusLog("Set brightness failed: $result")
            }
        } catch (e: Exception) {
            addStatusLog("Brightness error: ${e.message}")
        }
    }

    private fun startWhackNumberGame() {
        if (gameRunning) {
            addStatusLog("Game is already running")
            return
        }

        val km = keyboardManager ?: run {
            addStatusLog("KeyboardManager unavailable")
            Snackbar.make(binding.root, "KeyboardManager unavailable", Snackbar.LENGTH_SHORT).show()
            return
        }

        score = 0
        combo = 0
        remainSeconds = 20
        gameRunning = true
        lastKeyTime = 0L

        binding.tvGameScore.text = "Score: $score"
        binding.tvGameTimer.text = "Time: $remainSeconds"
        binding.tvGameStatus.text = "Game started"

        generateNextTarget()
        startKeyboardListening(km)
        startGameTimer()
    }

    private fun stopWhackNumberGame(reason: String = "Game over") {
        if (!gameRunning) return

        gameRunning = false
        gameTimer?.cancel()
        gameTimer = null
        stopKeyboardListening()

        binding.tvGameStatus.text = "$reason, final score: $score"
        Snackbar.make(binding.root, "$reason, score=$score", Snackbar.LENGTH_SHORT).show()
    }

    private fun startGameTimer() {
        gameTimer?.cancel()
        gameTimer = object : CountDownTimer(remainSeconds * 1000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                remainSeconds = millisUntilFinished / 1000
                binding.tvGameTimer.text = "Time: $remainSeconds"
            }

            override fun onFinish() {
                binding.tvGameTimer.text = "Time: 0"
                stopWhackNumberGame("Time up")
            }
        }.start()
    }

    private fun generateNextTarget() {
        currentTargetNumber = Random.nextInt(0, 10)
        binding.tvGameTarget.text = currentTargetNumber.toString()
    }

    private fun startKeyboardListening(km: IKeyboard) {
        try {
            val result = km.startPhysicalKeyboard(object : InputCallback {
                override fun onKey(
                    keyCode: KeyboardConstant.KeyCode?,
                    keyAction: KeyboardConstant.KeyAction?
                ) {
                    runOnUiThread {
                        if (!gameRunning) return@runOnUiThread
                        if (!isKeyDownAction(keyAction)) return@runOnUiThread

                        val now = System.currentTimeMillis()
                        if (now - lastKeyTime < 80) return@runOnUiThread
                        lastKeyTime = now

                        val digit = mapKeyCodeToDigit(keyCode) ?: return@runOnUiThread
                        handlePhysicalDigitInput(digit)
                    }
                }
            })
            addStatusLog("startPhysicalKeyboard result=$result")
        } catch (e: Exception) {
            addStatusLog("Keyboard start error: ${e.message}")
        }
    }

    private fun stopKeyboardListening() {
        val km = keyboardManager ?: return
        try {
            val result = km.stopPhysicalKeyboard()
            addStatusLog("stopPhysicalKeyboard result=$result")
        } catch (e: Exception) {
            addStatusLog("Keyboard stop error: ${e.message}")
        }
    }

    private fun handlePhysicalDigitInput(digit: Int) {
        if (!gameRunning) return

        if (digit == currentTargetNumber) {
            score++
            combo++
            remainSeconds += 1

            binding.tvGameScore.text = "Score: $score"
            binding.tvGameTimer.text = "Time: $remainSeconds"
            binding.tvGameStatus.text = "Correct: $digit combo=$combo"
            generateNextTarget()
        } else {
            combo = 0
            binding.tvGameStatus.text = "Wrong: $digit, target=$currentTargetNumber"
        }
    }

    private fun isKeyDownAction(action: KeyboardConstant.KeyAction?): Boolean {
        return action == KeyboardConstant.KeyAction.ACTION_DOWN
    }

    private fun mapKeyCodeToDigit(keyCode: KeyboardConstant.KeyCode?): Int? {
        return when (keyCode) {
            KeyboardConstant.KeyCode.BUTTON_0 -> 0
            KeyboardConstant.KeyCode.BUTTON_1 -> 1
            KeyboardConstant.KeyCode.BUTTON_2 -> 2
            KeyboardConstant.KeyCode.BUTTON_3 -> 3
            KeyboardConstant.KeyCode.BUTTON_4 -> 4
            KeyboardConstant.KeyCode.BUTTON_5 -> 5
            KeyboardConstant.KeyCode.BUTTON_6 -> 6
            KeyboardConstant.KeyCode.BUTTON_7 -> 7
            KeyboardConstant.KeyCode.BUTTON_8 -> 8
            KeyboardConstant.KeyCode.BUTTON_9 -> 9
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_PICK_APK -> {
                if (resultCode == RESULT_OK && data != null) {
                    val uri = data.data ?: return
                    selectedApkUri = uri

                    val localPath = prepareApkFromUri(uri)
                    selectedApkPath = localPath

                    if (!localPath.isNullOrBlank()) {
                        binding.etApkPath.setText(localPath)
                        binding.btnInstallApp.isEnabled = true
                        addStatusLog("APK ready: $localPath")
                    } else {
                        binding.etApkPath.setText("")
                        binding.btnInstallApp.isEnabled = false
                        Snackbar.make(binding.root, "Failed to prepare APK", Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun addStatusLog(message: String) {
        runOnUiThread {
            val currentLog = binding.tvStatusLog.text?.toString().orEmpty()
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val newLog = if (currentLog.isBlank() || currentLog == "Ready") {
                "[$timestamp] $message"
            } else {
                "$currentLog\n[$timestamp] $message"
            }

            val lines = newLog.split("\n")
            binding.tvStatusLog.text = if (lines.size > 10) {
                lines.takeLast(10).joinToString("\n")
            } else {
                newLog
            }
        }
    }
}
