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
import android.app.Activity
import androidx.core.app.ActivityCompat
import org.json.JSONArray
import org.json.JSONObject

class BluetoothPrintManager(action: ApiAction) : ExternalApi(action) {

    private val tag = "BluetoothPrintManager" // Renamed TAG to tag
    private var selectedDevice: BluetoothDevice? = null
    private var isPrintingStatus: Boolean = false // Renamed to avoid conflict with method name
    private val discoveredDevicesList: MutableList<BluetoothDevice> = mutableListOf() // Renamed

    // Helper function to safely get device name/address
    private fun getSafeDeviceName(device: BluetoothDevice?): String {
        device ?: return "unknown device"
        // BLUETOOTH_CONNECT is required for device.name since API 31
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this.context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.w(tag, "BLUETOOTH_CONNECT permission not granted, cannot get device name for ${device.address}. Falling back to address.")
                return device.address // Fallback to address if name cannot be accessed
            }
        }
        return try {
            // Permissions for device.name are implicitly handled by BLUETOOTH_CONNECT
            // or through scan results (BLUETOOTH_SCAN)
            device.name ?: device.address
        } catch (se: SecurityException) {
            Log.w(tag, "SecurityException getting device name for ${device.address}. Falling back to address.", se)
            device.address
        }
    }

    // Helper function to convert device list to JSON string
    private fun devicesToJsonString(devices: List<BluetoothDevice>): String {
        val jsonArray = JSONArray()
        for (device in devices) {
            val jsonObject = JSONObject()
            val deviceName = getSafeDeviceName(device)
            jsonObject.put("deviceName", deviceName)
            jsonObject.put("macAddress", device.address)
            jsonArray.put(jsonObject)
        }
        return jsonArray.toString()
    }

    // Internal logic methods, now using this.context and returning ExternalApiResult or data
    private fun doInitializePermissions(): ExternalApiResult {
        Log.i(tag, "doInitializePermissions (ExternalApi): Checking necessary Bluetooth permissions.")

        val requiredPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            requiredPermissions.add(Manifest.permission.BLUETOOTH)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missingPermissions = mutableListOf<String>()
        for (permission in requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this.context, permission) != PackageManager.PERMISSION_GRANTED) {
                Log.e(tag, "Missing Bluetooth permission: $permission")
                missingPermissions.add(permission)
            } else {
                Log.i(tag, "Bluetooth permission granted: $permission")
            }
        }

        return if (missingPermissions.isEmpty()) {
            Log.i(tag, "All required Bluetooth permissions are granted.")
            ExternalApiResult.SUCCESS_CONTINUE
        } else {
            val message = "Missing Bluetooth permissions: ${missingPermissions.joinToString()}"
            Log.e(tag, message)

            // 🎯 Foca só na reflection - remove o cast que nunca funciona
            Log.d(tag, "Context type: ${context.javaClass.name}")

            try {
                // Tenta pegar a Activity do UIContext via reflection
                val uiContextClass = context.javaClass

                // Tenta diferentes nomes de campos possíveis
                val possibleFields = listOf("activity", "mActivity", "_activity", "currentActivity")
                var foundActivity: Activity? = null

                for (fieldName in possibleFields) {
                    try {
                        val field = uiContextClass.getDeclaredField(fieldName)
                        field.isAccessible = true
                        val fieldValue = field.get(context)

                        if (fieldValue is Activity) {
                            foundActivity = fieldValue
                            Log.i(tag, "Found Activity via field: $fieldName")
                            break
                        }
                    } catch (e: NoSuchFieldException) {
                        // Campo não existe, tenta o próximo
                        continue
                    } catch (e: Exception) {
                        Log.w(tag, "Error accessing field $fieldName: ${e.message}")
                    }
                }

                if (foundActivity != null) {
                    Log.i(tag, "Requesting Bluetooth permissions...")
                    ActivityCompat.requestPermissions(
                        foundActivity,
                        missingPermissions.toTypedArray(),
                        1001
                    )
                    Log.i(tag, "Permission dialog should appear now.")
                } else {
                    Log.w(tag, "Could not find Activity field in UIContext")
                    Log.w(tag, "Available fields: ${uiContextClass.declaredFields.map { it.name }}")
                }

            } catch (e: Exception) {
                Log.e(tag, "Reflection failed completely: ${e.message}")
            }

            ExternalApiResult.failure(message)
        }
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
        val devicesJson = devicesToJsonString(discoveredDevicesList)
        Log.i(tag, "Discovered devices JSON: $devicesJson")
        return ExternalApiResult.success(devicesJson)
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

    private fun doPrintText(macAddress: String, text: String): ExternalApiResult {
        val bluetoothManager = this.context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter

        if (adapter == null) {
            Log.w(tag, "BluetoothAdapter not available for printing.")
            return ExternalApiResult.failure("BluetoothAdapter not available.")
        }

        if (
            !adapter.isEnabled) {
            Log.w(tag, "Bluetooth is not enabled. Cannot print.")
            return ExternalApiResult.failure("Bluetooth is not enabled.")
        }

        val deviceToPrint: BluetoothDevice?
        try {
            deviceToPrint = adapter.getRemoteDevice(macAddress)
        } catch (iae: IllegalArgumentException) {
            Log.e(tag, "Invalid MAC address provided for printing: $macAddress", iae)
            return ExternalApiResult.failure("Invalid MAC address for printing: $macAddress")
        } catch (e: Exception) {
            Log.e(tag, "Error getting remote device $macAddress for printing", e)
            return ExternalApiResult.failure("Error getting device for printing: ${e.message}")
        }

        if (deviceToPrint == null) {
            Log.w(tag, "Could not find device with MAC address: $macAddress for printing.")
            return ExternalApiResult.failure("Device not found for MAC address: $macAddress")
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

        isPrintingStatus = true
        var connection: BluetoothConnection? = null
        val deviceNameForLogs = getSafeDeviceName(deviceToPrint)

        try {
            connection = BluetoothConnection(deviceToPrint)
            connection.connect()
            Log.i(tag, "Connected to device: $deviceNameForLogs for printing.")

            val printer = EscPosPrinter(connection, 203, 48f, 32)
            printer.printFormattedTextAndCut("[L]$text\n\n\n")
            Log.i(tag, "Printing successful to $deviceNameForLogs")
            return ExternalApiResult.success(true)
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException during printing to $deviceNameForLogs.", e)
            return ExternalApiResult.failure("SecurityException during printing: ${e.message}")
        } catch (e: Exception) {
            Log.e(tag, "Error during printing to $deviceNameForLogs", e)
            return ExternalApiResult.failure("Error during printing: ${e.message}")
        } finally {
            try {
                connection?.disconnect()
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

    private fun doGetPairedDevices(): ExternalApiResult {
        Log.i(tag, "doGetPairedDevices: Called")
        val bluetoothManager = this.context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter

        if (adapter == null) {
            Log.w(tag, "BluetoothAdapter not available.")
            return ExternalApiResult.failure("BluetoothAdapter not available.")
        }

        if (!adapter.isEnabled) {
            Log.w(tag, "Bluetooth is not enabled. Cannot get paired devices.")
            return ExternalApiResult.failure("Bluetooth is not enabled.")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this.context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.e(tag, "BLUETOOTH_CONNECT permission not granted. Cannot get paired devices.")
                return ExternalApiResult.failure("BLUETOOTH_CONNECT permission not granted.")
            }
        }

        try {
            val pairedDevices: Set<BluetoothDevice>? = adapter.bondedDevices
            if (pairedDevices.isNullOrEmpty()) {
                Log.i(tag, "No paired devices found.")
                return ExternalApiResult.success("[]")
            }

            val devicesJson = devicesToJsonString(pairedDevices.toList())
            Log.i(tag, "Paired devices JSON: $devicesJson")
            return ExternalApiResult.success(devicesJson)
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException while getting paired devices.", e)
            return ExternalApiResult.failure("SecurityException while getting paired devices: ${e.message}")
        } catch (e: Exception) {
            Log.e(tag, "Error getting paired devices", e)
            return ExternalApiResult.failure("Error getting paired devices: ${e.message}")
        }
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
        val macAddress = params[0] as String
        val textToPrint = params[1] as String
        doPrintText(macAddress, textToPrint)
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
    private val methodGetPaired = IMethodInvoker { 
        doGetPairedDevices()
    }

    companion object {
        const val NAME = "BluetoothPrintManager"

        private const val METHOD_INITIALIZE_PERMISSIONS = "InitializePermissions"
        private const val METHOD_START_BLUETOOTH_SCAN = "StartBluetoothScan"
        private const val METHOD_GET_DISCOVERED_DEVICES = "GetDiscoveredDevices"
        private const val METHOD_SELECT_DEVICE = "SelectDevice"
        private const val METHOD_PRINT_TEXT = "PrintText"
        private const val METHOD_IS_PRINTING = "IsPrinting"
        private const val METHOD_IS_BLUETOOTH_ENABLED = "IsBluetoothEnabled"
        private const val METHOD_GET_SELECTED_DEVICE = "GetSelectedDevice"
        private const val METHOD_GET_PAIRED_DEVICES = "GetPairedDevices"
    }

    init {
        addMethodHandler(METHOD_INITIALIZE_PERMISSIONS, 0, methodInitialize)
        addMethodHandler(METHOD_START_BLUETOOTH_SCAN, 0, methodStartScan)
        addMethodHandler(METHOD_GET_DISCOVERED_DEVICES, 0, methodGetDevices)
        addMethodHandler(METHOD_SELECT_DEVICE, 1, methodSelect)
        addMethodHandler(METHOD_PRINT_TEXT, 2, methodPrint) // Now expects 2 params
        addMethodHandler(METHOD_IS_PRINTING, 0, methodIsCurrentlyPrinting)
        addMethodHandler(METHOD_IS_BLUETOOTH_ENABLED, 0, methodCheckBluetoothEnabled)
        addMethodHandler(METHOD_GET_SELECTED_DEVICE, 0, methodGetSelected)
        addMethodHandler(METHOD_GET_PAIRED_DEVICES, 0, methodGetPaired)
    }
}
