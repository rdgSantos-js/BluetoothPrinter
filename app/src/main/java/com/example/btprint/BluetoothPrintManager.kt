package com.example.btprint

// Added imports
import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
// End added imports

import android.content.Context
import android.util.Log
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.connection.bluetooth.BluetoothConnection

object BluetoothPrintManager {

    private const val TAG = "BluetoothPrintManager"
    private var selectedDevice: android.bluetooth.BluetoothDevice? = null
    private var isPrinting: Boolean = false
    private val discoveredDevices: MutableList<android.bluetooth.BluetoothDevice> = mutableListOf()

    // Helper function to safely get device name/address for logging or display
    private fun getSafeDeviceName(device: android.bluetooth.BluetoothDevice?): String {
        device ?: return "unknown device"
        return try {
            // Accessing device.name might require BLUETOOTH_CONNECT on API 31+ 
            // if not bonded or name not obtained via scan (with BLUETOOTH_SCAN).
            device.name ?: device.address
        } catch (se: SecurityException) {
            Log.w(TAG, "SecurityException getting device name for ${device.address}. Falling back to address.", se)
            device.address
        }
    }

    @JvmStatic
    fun initializePermissions(context: Context) {
        Log.i(TAG, "initializePermissions: Called")
        // Permissions should be requested by the Activity. This manager checks them.
    }

    @JvmStatic
    fun startBluetoothScan(context: Context) {
        Log.i(TAG, "startBluetoothScan: Called")

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            Log.w(TAG, "BluetoothAdapter not available.")
            return
        }

        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is not enabled. Cannot start scan.")
            return
        }

        val requiredScanPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // API 31+ for BLUETOOTH_SCAN
            requiredScanPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else { // Below API 31
            requiredScanPermissions.add(Manifest.permission.BLUETOOTH) // For isDiscovering
            requiredScanPermissions.add(Manifest.permission.BLUETOOTH_ADMIN) // For start/cancelDiscovery
        }

        var allScanPermissionsGranted = true
        for (permission in requiredScanPermissions) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Scan Permission not granted: $permission")
                allScanPermissionsGranted = false
                break
            }
        }

        if (!allScanPermissionsGranted) {
            Log.e(TAG, "Bluetooth scan cannot proceed without necessary scan permissions.")
            return
        }

        try {
            discoveredDevices.clear()
            // The calls to isDiscovering, cancelDiscovery, and startDiscovery are now guarded by permission checks.
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            val discoveryStarted = adapter.startDiscovery()
            if (discoveryStarted) {
                Log.i(TAG, "Bluetooth discovery started successfully.")
            } else {
                Log.w(TAG, "Failed to start Bluetooth discovery. Adapter state: ${adapter.state}")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during Bluetooth scan operations. Ensure permissions are correctly declared and granted.", e)
        }
    }

    @JvmStatic
    fun getDiscoveredDevices(): String {
        return discoveredDevices.joinToString(separator = ";") { device ->
            val name = getSafeDeviceName(device) // Use helper for name
            "$name|${device.address}" // Address is generally safe to access
        }
    }

    @JvmStatic
    fun selectDevice(context: Context, macAddress: String): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            Log.w(TAG, "BluetoothAdapter not available in selectDevice.")
            return false
        }
        try {
            // BluetoothAdapter.getRemoteDevice(String address) does not require permissions.
            val device = adapter.getRemoteDevice(macAddress)
            selectedDevice = device
            return true
        } catch (iae: IllegalArgumentException) {
            Log.e(TAG, "Invalid MAC address provided to selectDevice: $macAddress", iae)
            return false
        }
    }

    @JvmStatic
    fun printText(context: Context, text: String): Boolean {
        val currentSelectedDevice = selectedDevice
        if (currentSelectedDevice == null) {
            Log.w(TAG, "No device selected for printing.")
            return false
        }
        
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is not enabled. Cannot print.")
            return false
        }

        // Permissions check for Bluetooth connect
        val requiredConnectPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // API 31+ for BLUETOOTH_CONNECT
            requiredConnectPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else { // Below API 31
            requiredConnectPermissions.add(Manifest.permission.BLUETOOTH)
        }

        var allConnectPermissionsGranted = true
        for (permission in requiredConnectPermissions) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Connect Permission not granted: $permission")
                allConnectPermissionsGranted = false
                break
            }
        }

        if (!allConnectPermissionsGranted) {
            Log.e(TAG, "Cannot print text without necessary Bluetooth connect permissions.")
            return false
        }

        isPrinting = true
        var connection: BluetoothConnection? = null
        val deviceNameForLogs = getSafeDeviceName(currentSelectedDevice)

        return try {
            connection = BluetoothConnection(currentSelectedDevice) // currentSelectedDevice is checked for null
            connection.connect() // This is the line that had the reported warning
            Log.i(TAG, "Connected to device: $deviceNameForLogs")
            
            val printer = EscPosPrinter(connection, 203, 48f, 32)
            printer.printFormattedTextAndCut("[L]$text\n\n\n")
            Log.i(TAG, "Printing successful to $deviceNameForLogs")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during printing to $deviceNameForLogs. Ensure BLUETOOTH_CONNECT (or BLUETOOTH) permission is granted.", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error during printing to $deviceNameForLogs", e)
            false
        } finally {
            try {
                connection?.disconnect()
            } catch (se: SecurityException) {
                Log.e(TAG, "SecurityException during disconnect from $deviceNameForLogs.", se)
            } catch (e: Exception) { // Catch other potential exceptions during disconnect
                Log.e(TAG, "Exception during disconnect from $deviceNameForLogs.", e)
            }
            isPrinting = false
        }
    }

    @JvmStatic
    fun isPrinting(): Boolean = isPrinting

    @JvmStatic
    fun isBluetoothEnabled(context: Context): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        return adapter?.isEnabled == true
    }

    @JvmStatic
    fun getSelectedDevice(): String {
        return selectedDevice?.let { device ->
            getSafeDeviceName(device) // Use helper for consistency
        } ?: ""
    }
}
