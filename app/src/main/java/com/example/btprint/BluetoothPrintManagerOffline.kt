package com.example.btprint

import com.example.btprint.BluetoothPrintManager.Companion.NAME
import com.genexus.android.core.actions.ExternalObjectEvent

class BluetoothPrintManagerOffline {
    companion object {
        // Event names based on the Genexus External Object definition
        const val EVENT_ON_SCAN_COMPLETED = "OnScanCompleted"
        const val EVENT_ON_PRINT_COMPLETED = "OnPrintCompleted"
        const val EVENT_ON_DEVICE_CONNECTED = "OnDeviceConnected"
        // Custom event for offline simulation of initializePermissions call
        const val EVENT_INITIALIZE_PERMISSIONS_TRIGGERED = "InitializePermissionsTriggered"

        @JvmStatic
        fun initializePermissions() {
            val event = ExternalObjectEvent(NAME, EVENT_INITIALIZE_PERMISSIONS_TRIGGERED)
            event.fire(emptyList())
        }

        @JvmStatic
        fun startBluetoothScan() {
            val event = ExternalObjectEvent(NAME, EVENT_ON_SCAN_COMPLETED)
            val params = listOf(0) // devicesFound = 0
            event.fire(params)
        }

        @JvmStatic
        fun getDiscoveredDevices(): String {
            return "[]" // Simulate empty list of devices
        }

        @JvmStatic
        fun selectDevice(deviceAddress: String): Boolean {
            val event = ExternalObjectEvent(NAME, EVENT_ON_DEVICE_CONNECTED)
            // Params: deviceName, deviceAddress
            val params = listOf("Offline Selected Device", deviceAddress)
            event.fire(params)
            return true // Indicate success as per API definition
        }

        @JvmStatic
        fun printText(textToPrint: String): Boolean {
            val event = ExternalObjectEvent(NAME, EVENT_ON_PRINT_COMPLETED)
            // Params: success, message
            val params = listOf(true, "Offline: Text print for '$textToPrint' requested.")
            event.fire(params)
            return true // Indicate success
        }

        @JvmStatic
        fun printImage(imagePath: String): Boolean {
            val event = ExternalObjectEvent(NAME, EVENT_ON_PRINT_COMPLETED)
            // Params: success, message
            val params = listOf(true, "Offline: Image print for '$imagePath' requested.")
            event.fire(params)
            return true // Indicate success
        }

        @JvmStatic
        fun isPrinting(): Boolean {
            return false // Simulate not printing in offline mode
        }

        @JvmStatic
        fun isBluetoothEnabled(): Boolean {
            return true // Simulate Bluetooth is enabled in offline mode
        }

        @JvmStatic
        fun getSelectedDevice(): String {
            return "" // Simulate no device selected in offline mode
        }
    }
}
