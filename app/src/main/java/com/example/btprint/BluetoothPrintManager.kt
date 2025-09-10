package com.example.btprint

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.connection.bluetooth.BluetoothConnection
import com.genexus.android.core.actions.ApiAction
import com.genexus.android.core.externalapi.ExternalApi
import com.genexus.android.core.externalapi.ExternalApiResult

class BluetoothPrintManager(action: ApiAction) : ExternalApi(action) {

    private val tag = "BluetoothPrintManager" // Renamed TAG to tag
    private var selectedDevice: BluetoothDevice? = null
    private var isPrintingStatus: Boolean = false // Renamed to avoid conflict with method name
    private val discoveredDevicesList: MutableList<BluetoothDevice> = mutableListOf() // Renamed

    // Helper function to safely get device name/address
    private fun getSafeDeviceName(device: BluetoothDevice?): String {
        device ?: return "unknown device"
        return try {
            // Permissions for device.name are implicitly handled by BLUETOOTH_CONNECT
            // or through scan results (BLUETOOTH_SCAN)
            device.name ?: device.address
        } catch (se: SecurityException) {
            Log.w(tag, "SecurityException getting device name for ${device.address}. Falling back to address.", se)
            device.address
        }
    }

    // Internal logic methods, now using this.context and returning ExternalApiResult or data
    private fun doInitializePermissions(): ExternalApiResult {
        Log.i(tag, "initializePermissions (ExternalApi): Called")
        // Permissions are typically checked before calling methods that need them.
        // This method can be a placeholder or used for pre-flight checks if necessary from Genexus.
        // For now, it just logs and confirms execution.
        return ExternalApiResult.SUCCESS_CONTINUE
    }

    private fun doStartBluetoothScan(): ExternalApiResult {
        Log.i(tag, "doStartBluetoothScan: Called")
        val bluetoothManager = this.context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            Log.w(tag, "BluetoothAdapter not available.")
            return ExternalApiResult.failure("BluetoothAdapter not available.")
        }

        if (!adapter.isEnabled) {
            Log.w(tag, "Bluetooth is not enabled. Cannot start scan.")
            return ExternalApiResult.failure("Bluetooth is not enabled.")
        }

        val requiredScanPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredScanPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            requiredScanPermissions.add(Manifest.permission.BLUETOOTH)
            requiredScanPermissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
         // BLUETOOTH_CONNECT is also needed from API 31 for bonded devices if names are accessed post-scan without prior scan results.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredScanPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }


        var allScanPermissionsGranted = true
        for (permission in requiredScanPermissions) {
            if (ContextCompat.checkSelfPermission(this.context, permission) != PackageManager.PERMISSION_GRANTED) {
                Log.e(tag, "Scan Permission not granted: $permission")
                allScanPermissionsGranted = false
                break
            }
        }

        if (!allScanPermissionsGranted) {
            Log.e(tag, "Bluetooth scan cannot proceed without necessary scan permissions.")
            return ExternalApiResult.failure("Necessary Bluetooth scan permissions not granted.")
        }

        try {
            discoveredDevicesList.clear()
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery() // Requires BLUETOOTH_SCAN on API 31+ or BLUETOOTH_ADMIN pre-31
            }
            val discoveryStarted = adapter.startDiscovery() // Requires BLUETOOTH_SCAN on API 31+ or BLUETOOTH_ADMIN pre-31
            return if (discoveryStarted) {
                Log.i(tag, "Bluetooth discovery started successfully.")
                ExternalApiResult.SUCCESS_CONTINUE
            } else {
                Log.w(tag, "Failed to start Bluetooth discovery. Adapter state: ${adapter.state}")
                ExternalApiResult.failure("Failed to start Bluetooth discovery.")
            }
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException during Bluetooth scan operations.", e)
            return ExternalApiResult.failure("SecurityException during scan: ${e.message}")
        }
    }

    private fun doGetDiscoveredDevices(): ExternalApiResult {
        val devicesString = discoveredDevicesList.joinToString(separator = ";") { device ->
            val name = getSafeDeviceName(device)
            "$name|${device.address}"
        }
        return ExternalApiResult.success(devicesString)
    }

    private fun doSelectDevice(macAddress: String): ExternalApiResult {
        val bluetoothManager = this.context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            Log.w(tag, "BluetoothAdapter not available in selectDevice.")
            return ExternalApiResult.failure("BluetoothAdapter not available.")
        }
        try {
            val device = adapter.getRemoteDevice(macAddress) // Does not require permissions itself
            selectedDevice = device
            Log.i(tag, "Device selected: ${getSafeDeviceName(device)}")
            return ExternalApiResult.success(true) // Indicate success
        } catch (iae: IllegalArgumentException) {
            Log.e(tag, "Invalid MAC address provided to selectDevice: $macAddress", iae)
            return ExternalApiResult.failure("Invalid MAC address: $macAddress")
        }
    }

    private fun doPrintText(text: String): ExternalApiResult {
        val currentSelectedDevice = selectedDevice
        if (currentSelectedDevice == null) {
            Log.w(tag, "No device selected for printing.")
            return ExternalApiResult.failure("No device selected.")
        }

        val bluetoothManager = this.context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(tag, "Bluetooth is not enabled. Cannot print.")
            return ExternalApiResult.failure("Bluetooth is not enabled.")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val requiredConnectPermissions = listOf(Manifest.permission.BLUETOOTH_CONNECT)
            var allConnectPermissionsGranted = true
            for (permission in requiredConnectPermissions) {
                if (ContextCompat.checkSelfPermission(this.context, permission) != PackageManager.PERMISSION_GRANTED) {
                    Log.e(tag, "Connect Permission not granted: $permission")
                    allConnectPermissionsGranted = false
                    break
                }
            }

            if (!allConnectPermissionsGranted) {
                Log.e(tag, "Cannot print text without BLUETOOTH_CONNECT permission on API 31+.")
                return ExternalApiResult.failure("BLUETOOTH_CONNECT permission not granted.")
            }
        }
        // For pre-API 31, BLUETOOTH permission is implicitly handled by earlier checks or library.

        isPrintingStatus = true
        var connection: BluetoothConnection? = null
        val deviceNameForLogs = getSafeDeviceName(currentSelectedDevice)

        try {
            // BluetoothConnection constructor might require BLUETOOTH_CONNECT on API 31+
            // if it internally tries to fetch device name or other sensitive info without prior scan providing it.
            // The EscPosPrinter library itself handles the connection.
            connection = BluetoothConnection(currentSelectedDevice)
            // connect() method will require BLUETOOTH_CONNECT on API 31+
            connection.connect()
            Log.i(tag, "Connected to device: $deviceNameForLogs")

            val printer = EscPosPrinter(connection, 203, 48f, 32)
            printer.printFormattedTextAndCut("[L]$text\n\n\n")
            Log.i(tag, "Printing successful to $deviceNameForLogs")
            return ExternalApiResult.success(true) // Indicate success
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException during printing to $deviceNameForLogs.", e)
            return ExternalApiResult.failure("SecurityException during printing: ${e.message}")
        } catch (e: Exception) {
            Log.e(tag, "Error during printing to $deviceNameForLogs", e)
            return ExternalApiResult.failure("Error during printing: ${e.message}")
        } finally {
            try {
                connection?.disconnect() // May also require BLUETOOTH_CONNECT on API 31+
            } catch (se: SecurityException) {
                Log.e(tag, "SecurityException during disconnect from $deviceNameForLogs.", se)
            } catch (e: Exception) {
                Log.e(tag, "Exception during disconnect from $deviceNameForLogs.", e)
            }
            isPrintingStatus = false
        }
    }

    private fun doIsPrinting(): ExternalApiResult {
        return ExternalApiResult.success(isPrintingStatus)
    }

    private fun doIsBluetoothEnabled(): ExternalApiResult {
        val bluetoothManager = this.context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        return ExternalApiResult.success(adapter?.isEnabled == true)
    }

    private fun doGetSelectedDevice(): ExternalApiResult {
        val deviceName = selectedDevice?.let { getSafeDeviceName(it) } ?: ""
        return ExternalApiResult.success(deviceName)
    }

    // Method Invokers
    private val methodInitialize = IMethodInvoker {
        doInitializePermissions()
    }
    private val methodStartScan = IMethodInvoker {
        doStartBluetoothScan()
    }
    private val methodGetDevices = IMethodInvoker {
        doGetDiscoveredDevices()
    }
    private val methodSelect = IMethodInvoker { params ->
        val macAddress = params[0] as String
        doSelectDevice(macAddress)
    }
    private val methodPrint = IMethodInvoker { params ->
        val textToPrint = params[0] as String
        doPrintText(textToPrint)
    }
    private val methodIsCurrentlyPrinting = IMethodInvoker {
        doIsPrinting()
    }
    private val methodCheckBluetoothEnabled = IMethodInvoker {
        doIsBluetoothEnabled()
    }
    private val methodGetSelected = IMethodInvoker {
        doGetSelectedDevice()
    }

    companion object {
        const val NAME = "BluetoothPrintManager" // Choose a unique name for Genexus

        // Method names for Genexus
        private const val METHOD_INITIALIZE_PERMISSIONS = "InitializePermissions"
        private const val METHOD_START_BLUETOOTH_SCAN = "StartBluetoothScan"
        private const val METHOD_GET_DISCOVERED_DEVICES = "GetDiscoveredDevices"
        private const val METHOD_SELECT_DEVICE = "SelectDevice"
        private const val METHOD_PRINT_TEXT = "PrintText"
        private const val METHOD_IS_PRINTING = "IsPrinting"
        private const val METHOD_IS_BLUETOOTH_ENABLED = "IsBluetoothEnabled"
        private const val METHOD_GET_SELECTED_DEVICE = "GetSelectedDevice"
    }

    init {
        addMethodHandler(METHOD_INITIALIZE_PERMISSIONS, 0, methodInitialize)
        addMethodHandler(METHOD_START_BLUETOOTH_SCAN, 0, methodStartScan)
        addMethodHandler(METHOD_GET_DISCOVERED_DEVICES, 0, methodGetDevices)
        addMethodHandler(METHOD_SELECT_DEVICE, 1, methodSelect) // Expects 1 param: macAddress
        addMethodHandler(METHOD_PRINT_TEXT, 1, methodPrint)       // Expects 1 param: text
        addMethodHandler(METHOD_IS_PRINTING, 0, methodIsCurrentlyPrinting)
        addMethodHandler(METHOD_IS_BLUETOOTH_ENABLED, 0, methodCheckBluetoothEnabled)
        addMethodHandler(METHOD_GET_SELECTED_DEVICE, 0, methodGetSelected)
    }
}
