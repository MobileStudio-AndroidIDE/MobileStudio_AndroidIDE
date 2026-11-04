package com.aide.mobilestudio

import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object AdbDeviceManager {

    private const val TAG = "AdbDeviceManager"

    data class AdbDevice(
        val serial: String,
        val model: String = "",
        val state: DeviceState = DeviceState.UNKNOWN,
        val apiLevel: Int = 0,
        val isEmulator: Boolean = false,
        val abi: String = "",
        val product: String = ""
    )

    enum class DeviceState(val label: String) {
        DEVICE("Connected"),
        OFFLINE("Offline"),
        UNAUTHORIZED("Unauthorized"),
        UNKNOWN("Unknown")
    }

    data class AdbInfo(
        val adbPath: String,
        val hostAbi: String,
        val isArm64Host: Boolean,
        val version: String = "",
        val isAvailable: Boolean = false
    )

    private val devices = ConcurrentHashMap<String, AdbDevice>()
    private var currentAdbInfo: AdbInfo? = null
    private var pollingJob: Job? = null
    private val listeners = mutableListOf<DeviceListener>()

    interface DeviceListener {
        fun onDeviceListChanged(devices: List<AdbDevice>)
        fun onConnectionStateChanged(state: String)
    }

    fun addListener(listener: DeviceListener) { listeners.add(listener) }
    fun removeListener(listener: DeviceListener) { listeners.remove(listener) }

    private fun notifyDeviceListChanged() {
        val list = devices.values.toList()
        listeners.forEach { try { it.onDeviceListChanged(list) } catch (_: Exception) {} }
    }

    private fun notifyConnectionState(state: String) {
        listeners.forEach { try { it.onConnectionStateChanged(state) } catch (_: Exception) {} }
    }

    fun isArm64Host(): Boolean {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        return abi.contains("arm64") || abi.contains("aarch64")
    }

    fun resolveAdbBinary(context: android.content.Context): AdbInfo {
        if (currentAdbInfo?.isAvailable == true) return currentAdbInfo!!

        val hostAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val isArm64 = isArm64Host()

        // Search paths for ADB binary
        val searchPaths = mutableListOf<String>()

        // SDK platform-tools
        val sdkDir = try {
            val toolchain = ToolchainManager(context)
            toolchain.activeDir(ToolchainManager.Kind.SDK)
        } catch (_: Exception) { null }
        if (sdkDir != null) {
            searchPaths.add(File(sdkDir, "platform-tools/adb").absolutePath)
        }

        // App internal storage
        val filesDir = context.filesDir
        searchPaths.add(File(filesDir, "toolchain/sdk/platform-tools/adb").absolutePath)
        searchPaths.add(File(filesDir, "platform-tools/adb").absolutePath)

        // System ADB
        searchPaths.add("/system/bin/adb")
        searchPaths.add("/system/xbin/adb")

        // Termux-style paths (for reference, but we prefer our own)
        searchPaths.add("/data/data/com.termux/files/usr/bin/adb")

        // LD_LIBRARY_PATH accessible
        searchPaths.add("adb")

        // Find first existing ADB binary
        var adbPath = ""
        for (path in searchPaths) {
            val file = File(path)
            if (file.exists() && file.canExecute()) {
                adbPath = file.absolutePath
                break
            }
        }

        // If no ADB found, try which command
        if (adbPath.isEmpty()) {
            try {
                val process = ProcessBuilder("/system/bin/sh", "-c", "which adb 2>/dev/null || echo ''")
                    .redirectErrorStream(true)
                    .start()
                val result = process.inputStream.bufferedReader().readText().trim()
                process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                if (result.isNotEmpty() && File(result).exists()) {
                    adbPath = result
                }
            } catch (_: Exception) {}
        }

        val version = if (adbPath.isNotEmpty()) getAdbVersion(adbPath) else ""

        currentAdbInfo = AdbInfo(
            adbPath = adbPath,
            hostAbi = hostAbi,
            isArm64Host = isArm64,
            version = version,
            isAvailable = adbPath.isNotEmpty()
        )

        Log.i(TAG, "ADB resolved: path=$adbPath, abi=$hostAbi, arm64=$isArm64, version=$version")
        return currentAdbInfo!!
    }

    fun getAdbInfo(): AdbInfo? = currentAdbInfo

    fun listDevices(): List<AdbDevice> {
        val info = currentAdbInfo ?: return emptyList()
        if (!info.isAvailable) return emptyList()

        return try {
            val output = execAdb(info.adbPath, listOf("devices", "-l"))
            parseDevicesOutput(output)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list devices", e)
            emptyList()
        }
    }

    fun getDeviceInfo(serial: String): AdbDevice? {
        val info = currentAdbInfo ?: return null
        if (!info.isAvailable) return null

        return try {
            val props = mutableMapOf<String, String>()
            val output = execAdb(info.adbPath, listOf("-s", serial, "shell", "getprop"))
            output.lines().forEach { line ->
                val match = Regex("""\[(.+?)]:\s*\[(.+?)]""").find(line)
                if (match != null) {
                    props[match.groupValues[1]] = match.groupValues[2]
                }
            }

            AdbDevice(
                serial = serial,
                model = props["ro.product.model"] ?: props["ro.product.name"] ?: "",
                state = DeviceState.DEVICE,
                apiLevel = props["ro.build.version.sdk"]?.toIntOrNull() ?: 0,
                isEmulator = serial.startsWith("emulator") || serial.contains("-5554"),
                abi = props["ro.product.cpu.abi"] ?: "",
                product = props["ro.product.device"] ?: ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get device info for $serial", e)
            null
        }
    }

    fun getDeviceAbi(serial: String): String {
        val info = currentAdbInfo ?: return ""
        if (!info.isAvailable) return ""

        return try {
            val output = execAdb(info.adbPath, listOf("-s", serial, "shell", "getprop", "ro.product.cpu.abi"))
            output.trim()
        } catch (_: Exception) { "" }
    }

    fun connectWireless(host: String, port: Int = 5555): Boolean {
        val info = currentAdbInfo ?: return false
        if (!info.isAvailable) return false

        return try {
            val output = execAdb(info.adbPath, listOf("connect", "$host:$port"))
            output.contains("connected") || output.contains("already connected")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to $host:$port", e)
            false
        }
    }

    fun disconnectDevice(serial: String): Boolean {
        val info = currentAdbInfo ?: return false
        if (!info.isAvailable) return false

        return try {
            execAdb(info.adbPath, listOf("disconnect", serial))
            devices.remove(serial)
            notifyDeviceListChanged()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect $serial", e)
            false
        }
    }

    fun execAdbCommand(args: List<String>, timeoutMs: Long = 30_000): AdbCommandResult {
        val info = currentAdbInfo ?: return AdbCommandResult(false, "", "ADB not available", -1)
        if (!info.isAvailable) return AdbCommandResult(false, "", "ADB binary not found", -1)

        return try {
            val output = execAdb(info.adbPath, args, timeoutMs)
            AdbCommandResult(true, output, "", 0)
        } catch (e: Exception) {
            AdbCommandResult(false, "", e.message ?: "Unknown error", -1)
        }
    }

    fun execAdbCommandWithDevice(
        serial: String,
        args: List<String>,
        timeoutMs: Long = 30_000
    ): AdbCommandResult {
        val fullArgs = mutableListOf("-s", serial)
        fullArgs.addAll(args)
        return execAdbCommand(fullArgs, timeoutMs)
    }

    data class AdbCommandResult(
        val success: Boolean,
        val output: String,
        val error: String,
        val exitCode: Int
    )

    fun startPolling(intervalMs: Long = 5000) {
        stopPolling()
        pollingJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            while (isActive) {
                try {
                    refreshDeviceList()
                } catch (e: Exception) {
                    Log.w(TAG, "Polling error", e)
                }
                delay(intervalMs)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun refreshDeviceList() {
        val info = currentAdbInfo ?: return
        if (!info.isAvailable) return

        try {
            val deviceList = listDevices()
            val newSerials = deviceList.map { it.serial }.toSet()
            val oldSerials = devices.keys.toSet()

            // Remove disconnected devices
            (oldSerials - newSerials).forEach { devices.remove(it) }

            // Add/update connected devices
            deviceList.forEach { device ->
                val existing = devices[device.serial]
                if (existing == null || existing.state != device.state) {
                    devices[device.serial] = device
                }
            }

            notifyDeviceListChanged()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh device list", e)
        }
    }

    fun isDeviceConnected(serial: String): Boolean {
        return devices[serial]?.state == DeviceState.DEVICE
    }

    fun getFirstConnectedDevice(): AdbDevice? {
        return devices.values.firstOrNull { it.state == DeviceState.DEVICE }
    }

    // ── Internal helpers ───────────────────────────────────────────────

    private fun execAdb(adbPath: String, args: List<String>, timeoutMs: Long = 30_000): String {
        val command = mutableListOf(adbPath)
        command.addAll(args)

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val output = StringBuilder()
        val reader = process.inputStream.bufferedReader()

        val readJob = CoroutineScope(Dispatchers.IO).launch {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
            }
        }

        val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        readJob.cancel()

        if (!finished) {
            process.destroyForcibly()
            throw RuntimeException("ADB command timed out after ${timeoutMs}ms")
        }

        val exitCode = process.exitValue()
        if (exitCode != 0 && output.isEmpty()) {
            val errOutput = process.errorStream?.bufferedReader()?.readText() ?: ""
            if (errOutput.isNotEmpty()) {
                throw RuntimeException("ADB command failed (exit $exitCode): $errOutput")
            }
        }

        return output.toString()
    }

    private fun getAdbVersion(adbPath: String): String {
        return try {
            val output = execAdb(adbPath, listOf("version"), timeoutMs = 5000)
            output.lines().firstOrNull()?.trim() ?: ""
        } catch (_: Exception) { "" }
    }

    private fun parseDevicesOutput(output: String): List<AdbDevice> {
        val devices = mutableListOf<AdbDevice>()
        val lines = output.lines()

        for (line in lines) {
            if (line.startsWith("List of devices") || line.isBlank()) continue
            if (line.startsWith("* daemon")) continue

            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) continue

            val serial = parts[0]
            val stateStr = parts[1]
            val state = when (stateStr) {
                "device" -> DeviceState.DEVICE
                "offline" -> DeviceState.OFFLINE
                "unauthorized" -> DeviceState.UNAUTHORIZED
                else -> DeviceState.UNKNOWN
            }

            // Parse additional info from the line (e.g., "model:Nexus_5 transport_id:1")
            val modelMatch = Regex("""model:(\S+)""").find(line)
            val productMatch = Regex("""product:(\S+)""").find(line)

            devices.add(AdbDevice(
                serial = serial,
                model = modelMatch?.groupValues?.get(1) ?: "",
                state = state,
                isEmulator = serial.startsWith("emulator") || serial.contains("-5554"),
                product = productMatch?.groupValues?.get(1) ?: ""
            ))
        }

        return devices
    }
}
