package com.example.btprint

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
// import androidx.compose.material3.Text // Unused import
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.btprint.ui.theme.BtprintTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.nio.charset.Charset
// import kotlinx.coroutines.delay // Unused import

// Imports for escpos-android library
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.connection.bluetooth.BluetoothConnection
import com.dantsu.escposprinter.connection.bluetooth.BluetoothPrintersConnections
import com.dantsu.escposprinter.textparser.PrinterTextParserImg

// Helper function to get device name safely considering permissions
fun BluetoothDevice.getSafeName(context: Context): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        @SuppressLint("MissingPermission") // Checked by higher level functions or contextually appropriate
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Before Android S, we might not have BLUETOOTH_CONNECT, but name is accessible.
            // For S and above, if permission is missing, return address.
            return this.address ?: "Unknown (No Permission)"
        }
    }
    return try {
        @SuppressLint("MissingPermission") // Checked by higher level functions or contextually appropriate
        val name = this.name
        name ?: this.address ?: "Unknown Device"
    } catch (_: SecurityException) { // Parameter e is never used
        // Log.e(MainActivity.TAG, "SecurityException while getting device name for ${this.address}", e)
        this.address ?: "Unknown (Security Exception)"
    }
}

class MainActivity : ComponentActivity() {

    private val bluetoothManager by lazy { getSystemService(BLUETOOTH_SERVICE) as BluetoothManager } // Removed redundant Context qualifier
    private val bluetoothAdapter: BluetoothAdapter? by lazy { bluetoothManager.adapter }
    private val discoveredDevices = mutableStateListOf<BluetoothDevice>()
    private var selectedDevice by mutableStateOf<BluetoothDevice?>(null)
    private var textToSend by mutableStateOf("")
    private var imageToSend by mutableStateOf<Bitmap?>(null)

    companion object {
        private const val TAG = "BluetoothAppTag" // Consistent Log Tag
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val REQUEST_ENABLE_BT = 101
        // private const val PERMISSION_REQUEST_CODE = 102 // Property is never used
    }

    private lateinit var imagePickerLauncher: ActivityResultLauncher<String>

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission") // Permissions are checked before starting discovery or accessing device details
        override fun onReceive(context: Context, intent: Intent) {
            val action: String? = intent.action
            Log.d(TAG, "discoveryReceiver: Received action: $action")

            when (action) {
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    Log.i(TAG, "discoveryReceiver: ACTION_DISCOVERY_STARTED - Discovery process has started.")
                }
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let {
                        val deviceNameForLog = it.getSafeName(this@MainActivity)
                        val deviceAddress = it.address ?: "Unknown Address"
                        Log.d(TAG, "discoveryReceiver: ACTION_FOUND - Device: $deviceNameForLog ($deviceAddress)")
                        // BLUETOOTH_CONNECT is checked in getSafeName for S+
                        // For adding to list, name is the primary concern here.
                        if (it.name != null && it !in discoveredDevices) {
                            discoveredDevices.add(it)
                            Log.i(TAG, "discoveryReceiver: Device $deviceNameForLog ($deviceAddress) added to discoveredDevices list.")
                        } else if (it.name == null) {
                            Log.d(TAG, "discoveryReceiver: Device with null name not added: $deviceAddress")
                        } else {
                            Log.d(TAG, "discoveryReceiver: Device $deviceNameForLog ($deviceAddress) already in list.")
                        }
                    } ?: Log.w(TAG, "discoveryReceiver: ACTION_FOUND - Device is null.")
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.i(TAG, "discoveryReceiver: ACTION_DISCOVERY_FINISHED - Discovery process has finished.")
                    if (discoveredDevices.isEmpty()) {
                        Toast.makeText(context, "Scan finished. No new devices found.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Scan finished.", Toast.LENGTH_SHORT).show()
                    }
                }
                else -> {
                    Log.w(TAG, "discoveryReceiver: Received unhandled action: $action")
                }
            }
        }
    }

    @SuppressLint("InlinedApi") // BLUETOOTH_SCAN and BLUETOOTH_CONNECT are SDK S+
    private val requestBluetoothPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            Log.d(TAG, "requestBluetoothPermissionsLauncher: Received permission results:")
            permissions.entries.forEach { Log.d(TAG, "  ${it.key} = ${it.value}") }

            val scanGranted = permissions[Manifest.permission.BLUETOOTH_SCAN] ?: false
            val connectGranted = permissions[Manifest.permission.BLUETOOTH_CONNECT] ?: false
            val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false // Still good to have for discovery robustness

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (scanGranted && connectGranted) {
                    Log.i(TAG, "requestBluetoothPermissionsLauncher: BLUETOOTH_SCAN and BLUETOOTH_CONNECT permissions granted. Starting discovery.")
                    startBluetoothDiscovery()
                } else {
                    Log.e(TAG, "requestBluetoothPermissionsLauncher: BLUETOOTH_SCAN ($scanGranted) or BLUETOOTH_CONNECT ($connectGranted) permission denied.")
                    Toast.makeText(this, "Bluetooth Scan and Connect permissions are required to find and connect to devices.", Toast.LENGTH_LONG).show()
                }
            } else { // Pre-Android S
                if (fineLocationGranted) { // Primarily ACCESS_FINE_LOCATION was key for discovery
                     Log.i(TAG, "requestBluetoothPermissionsLauncher: ACCESS_FINE_LOCATION permission granted on pre-S device. Starting discovery.")
                    startBluetoothDiscovery()
                } else {
                    Log.e(TAG, "requestBluetoothPermissionsLauncher: ACCESS_FINE_LOCATION permission denied on pre-S device.")
                    Toast.makeText(this, "Location permission is required to scan for devices on older Android versions.", Toast.LENGTH_LONG).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: Activity creating.")

        imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                Log.d(TAG, "imagePickerLauncher: Image URI selected: $it")
                try {
                    val bitmap = if (Build.VERSION.SDK_INT < 28) {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Media.getBitmap(this.contentResolver, it)
                    } else {
                        val source = ImageDecoder.createSource(this.contentResolver, it)
                        ImageDecoder.decodeBitmap(source)
                    }
                    imageToSend = bitmap.copy(Bitmap.Config.ARGB_8888, true) // Ensure mutable bitmap
                    Log.i(TAG, "imagePickerLauncher: Bitmap created successfully. Width: ${imageToSend?.width}, Height: ${imageToSend?.height}")
                } catch (e: Exception) {
                    Log.e(TAG, "imagePickerLauncher: Error creating Bitmap from URI: ${e.message}", e)
                    Toast.makeText(this, "Error loading image: ${e.message}", Toast.LENGTH_LONG).show()
                    imageToSend = null
                }
            } ?: Log.d(TAG, "imagePickerLauncher: No image URI selected (uri is null).")
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(discoveryReceiver, filter, RECEIVER_NOT_EXPORTED)
            Log.d(TAG, "onCreate: discoveryReceiver registered with RECEIVER_NOT_EXPORTED.")
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(discoveryReceiver, filter)
            Log.d(TAG, "onCreate: discoveryReceiver registered (pre-Tiramisu).")
        }
        
        addBondedDevicesToList()

        setContent {
            BtprintTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BluetoothControlScreen(
                        modifier = Modifier.padding(innerPadding),
                        devices = discoveredDevices,
                        selectedDevice = selectedDevice,
                        textToSend = textToSend,
                        imageToSend = imageToSend,
                        onTextChange = { textToSend = it },
                        onScanClicked = {
                            Log.d(TAG, "onScanClicked: Scan button clicked.")
                            checkAndRequestPermissions()
                        },
                        onDeviceSelected = { device ->
                            Log.d(TAG, "onDeviceSelected: Device ${device.getSafeName(this@MainActivity)} (${device.address ?: "N/A"}) selected.")
                            selectedDevice = device
                        },
                        onSendImageClicked = {
                            Log.d(TAG, "onSendImageClicked: Send Image button logic initiated.")
                            if (imageToSend == null) {
                                Log.i(TAG, "onSendImageClicked: No image selected. Launching image picker.")
                                imagePickerLauncher.launch("image/*")
                            } else {
                                selectedDevice?.let { device ->
                                    imageToSend?.let { bitmap ->
                                        Log.i(TAG, "onSendImageClicked: Image and device selected. Attempting to send to ${device.getSafeName(this@MainActivity)}.")
                                        sendImageToDevice(device, bitmap)
                                    } ?: Log.w(TAG, "onSendImageClicked: Image is null even after check, should not happen.")
                                } ?: run {
                                    Log.w(TAG, "onSendImageClicked: No device selected for image sending.")
                                    Toast.makeText(this, "No device selected for image sending", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onSendToPrinterClicked = {
                            Log.d(TAG, "onSendToPrinterClicked: Send to Printer button clicked.")
                            selectedDevice?.let { device ->
                                if (textToSend.isNotBlank()) {
                                    sendTextToDevice(device, textToSend)
                                } else {
                                    Log.w(TAG, "onSendToPrinterClicked: Text to send is blank.")
                                    Toast.makeText(this, "Enter text for printer", Toast.LENGTH_SHORT).show()
                                }
                            } ?: run {
                                Log.w(TAG, "onSendToPrinterClicked: No device selected for printer.")
                                Toast.makeText(this, "No device selected", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }
    }

    @SuppressLint("InlinedApi") // For BLUETOOTH_SCAN, BLUETOOTH_CONNECT
    private fun checkAndRequestPermissions() {
        Log.d(TAG, "checkAndRequestPermissions: Checking permissions.")
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.e(TAG, "checkAndRequestPermissions: Bluetooth not supported on this device.")
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show()
            return
        }
        if (!adapter.isEnabled) {
            Log.i(TAG, "checkAndRequestPermissions: Bluetooth not enabled. Requesting...")
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            // BLUETOOTH_CONNECT is needed for ACTION_REQUEST_ENABLE on S+
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION") 
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT) 
            } else {
                 Log.w(TAG, "checkAndRequestPermissions: BLUETOOTH_CONNECT permission needed to request enabling Bluetooth.")
                 Toast.makeText(this, "Please enable Bluetooth via System Settings or grant Connect permission.", Toast.LENGTH_LONG).show()
            }
            return
        }

        val requiredPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }


        if (requiredPermissions.isNotEmpty()) {
            Log.i(TAG, "checkAndRequestPermissions: Requesting permissions: ${requiredPermissions.joinToString()}")
            requestBluetoothPermissionsLauncher.launch(requiredPermissions.toTypedArray())
        } else {
            Log.i(TAG, "checkAndRequestPermissions: All necessary permissions already granted. Starting discovery.")
            startBluetoothDiscovery()
        }
    }
    
    @Deprecated("onActivityResult is deprecated but used here for startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") 
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                Log.i(TAG, "onActivityResult: Bluetooth enabled by user.")
                checkAndRequestPermissions() 
            } else {
                Log.w(TAG, "onActivityResult: Bluetooth enabling was cancelled or failed.")
                Toast.makeText(this, "Bluetooth is required to scan for devices.", Toast.LENGTH_SHORT).show()
            }
        }
    }


    @SuppressLint("MissingPermission") // Permissions (SCAN, CONNECT/ADMIN, FINE_LOCATION) are checked by canScan
    private fun startBluetoothDiscovery() {
        Log.d(TAG, "startBluetoothDiscovery: Attempting to start discovery.")
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "startBluetoothDiscovery: Bluetooth adapter not available or not enabled.")
            Toast.makeText(this, "Please enable Bluetooth.", Toast.LENGTH_SHORT).show()
            return
        }

        val canScan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED // Connect also needed for discovery results
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED && // For discovery
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED    // For starting/stopping discovery
        }

        if (!canScan) {
             Log.e(TAG, "startBluetoothDiscovery: Missing necessary permissions to start discovery.")
             Toast.makeText(this, "Permissions required to start scan. Check settings.", Toast.LENGTH_LONG).show()
             // Optionally, call checkAndRequestPermissions() again or guide user to settings
             checkAndRequestPermissions() // Re-trigger permission flow if scan is attempted without sufficient permissions
             return
        }

        if (adapter.isDiscovering) {
            Log.d(TAG, "startBluetoothDiscovery: Already discovering. Stopping previous discovery.")
            adapter.cancelDiscovery() // BLUETOOTH_SCAN on S+, BLUETOOTH_ADMIN on pre-S. Assumed granted by canScan check.
        }
        
        discoveredDevices.clear() 
        addBondedDevicesToList() 

        Log.i(TAG, "startBluetoothDiscovery: Starting Bluetooth discovery...")
        val discoveryStarted = adapter.startDiscovery() // BLUETOOTH_SCAN on S+, BLUETOOTH_ADMIN on pre-S. Assumed granted by canScan check.
        
        if (discoveryStarted) {
            Toast.makeText(this, "Scanning for devices...", Toast.LENGTH_SHORT).show()
        } else {
            Log.e(TAG, "startBluetoothDiscovery: Failed to start Bluetooth discovery despite passing permission checks. System error?")
            Toast.makeText(this, "Failed to start scan. Please try again.", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT checked for S+, legacy BLUETOOTH for older
    private fun addBondedDevicesToList() {
        Log.d(TAG, "addBondedDevicesToList: Adding bonded devices.")
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.w(TAG, "addBondedDevicesToList: BluetoothAdapter is null.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "addBondedDevicesToList: BLUETOOTH_CONNECT permission not granted. Cannot get bonded devices on Android S+.")
            // Toast.makeText(this, "Connect permission needed for bonded devices", Toast.LENGTH_SHORT).show() // Optional user feedback
            return
        }
        // Pre-S, BLUETOOTH permission would have been implicitly required for adapter.bondedDevices if it's a system check

        try {
            val bondedBtDevices: Set<BluetoothDevice>? = adapter.bondedDevices // Name changed to avoid conflict
            bondedBtDevices?.forEach { device ->
                 val deviceName = device.getSafeName(this)
                 val deviceAddress = device.address ?: "Unknown Address"
                Log.d(TAG, "addBondedDevicesToList: Found bonded device: $deviceName ($deviceAddress)")
                if (device !in discoveredDevices) {
                    discoveredDevices.add(device)
                    Log.i(TAG, "addBondedDevicesToList: Bonded device $deviceName ($deviceAddress) added to list.")
                }
            }
        } catch (se: SecurityException) {
            Log.e(TAG, "addBondedDevicesToList: SecurityException while getting bonded devices.", se)
        }
    }

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT checked for S+
    private fun sendTextToDevice(device: BluetoothDevice, text: String) {
        val deviceName = device.getSafeName(this)
        Log.d(TAG, "sendTextToDevice: Attempting to send text to $deviceName")
        lifecycleScope.launch(Dispatchers.IO) {
            var socket: BluetoothSocket? = null
            var outputStream: OutputStream? = null
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "sendTextToDevice: BLUETOOTH_CONNECT permission not granted for $deviceName.")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "BLUETOOTH_CONNECT permission needed for $deviceName", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                Log.d(TAG, "sendTextToDevice: Creating RfcommSocket for $deviceName.")
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect() 
                Log.i(TAG, "sendTextToDevice: Socket connected to $deviceName.")
                outputStream = socket.outputStream
                
                val textForPrinter = text + "\n\n\n" 
                val bytesToSend = textForPrinter.toByteArray(Charset.forName("CP437")) 

                outputStream.write(bytesToSend)
                outputStream.flush()
                Log.i(TAG, "sendTextToDevice: Text sent successfully to $deviceName.")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Text sent to $deviceName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "sendTextToDevice: SecurityException for $deviceName: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Permission error connecting to $deviceName", Toast.LENGTH_LONG).show()
                }
            } catch (e: IOException) {
                Log.e(TAG, "sendTextToDevice: IOException for $deviceName: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error sending to $deviceName: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            } finally {
                Log.d(TAG, "sendTextToDevice: Cleaning up for $deviceName.")
                try {
                    outputStream?.close()
                    Log.d(TAG, "sendTextToDevice: Output stream closed for $deviceName.")
                } catch (ioe: IOException) {
                    Log.e(TAG, "sendTextToDevice: Error closing output stream for $deviceName.", ioe)
                }
                try {
                    socket?.close()
                    Log.d(TAG, "sendTextToDevice: Socket closed for $deviceName.")
                } catch (ioe: IOException) {
                    Log.e(TAG, "sendTextToDevice: Error closing socket for $deviceName.", ioe)
                }
            }
        }
    }


    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT checked for S+
    private fun sendImageToDevice(device: BluetoothDevice, bitmapToPrint: Bitmap) {
        val deviceName = device.getSafeName(this)
        val deviceAddress = device.address
        Log.i(TAG, "sendImageToDevice: Initiating image send to $deviceName ($deviceAddress)")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "sendImageToDevice: BLUETOOTH_CONNECT permission not granted for $deviceName.")
            Toast.makeText(this, "BLUETOOTH_CONNECT permission needed to print to $deviceName", Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            var printerConnection: BluetoothConnection? = null
            try {
                Log.d(TAG, "sendImageToDevice: Searching for Bluetooth printer connection for address: $deviceAddress")
                
                val connectionsList = BluetoothPrintersConnections().list
                
                Log.i(TAG, "sendImageToDevice: Found ${connectionsList?.size ?: 0} connections from library.")
                connectionsList?.forEachIndexed { index, conn -> 
                    Log.d(TAG, "sendImageToDevice: Library conn [$index]: Name=${conn.device.name}, Address=${conn.device.address}")
                }
                printerConnection = connectionsList?.firstOrNull { it.device.address == deviceAddress }


                if (printerConnection == null) {
                    Log.w(TAG, "sendImageToDevice: Device $deviceAddress not found in library's list or list was null. Attempting direct connection.")
                    try {
                        printerConnection = BluetoothConnection(device) 
                        Log.i(TAG, "sendImageToDevice: Direct BluetoothConnection object created for $deviceAddress.")
                         // If direct creation is successful, printerConnection is no longer null here.
                    } catch (e: Exception) {
                        Log.e(TAG, "sendImageToDevice: Error creating direct BluetoothConnection for $deviceAddress: ${e.message}", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Failed to create direct connection for $deviceName.", Toast.LENGTH_LONG).show()
                        }
                        return@launch 
                    }
                    // If we reach here, printerConnection was successfully assigned in the try block above.
                    // The redundant 'if (printerConnection == null)' check has been removed.
                }
                
                // If we reach here, printerConnection should be non-null.

                Log.i(TAG, "sendImageToDevice: Printer connection object obtained for $deviceName. Attempting to connect.")
                try {
                    // Since printerConnection is confirmed non-null by the logic above (either found in list or created directly, or function returned)
                    if (!printerConnection.isConnected) { 
                         Log.d(TAG, "sendImageToDevice: Explicitly connecting printerConnection for $deviceName")
                         printerConnection.connect()
                    }
                } catch (e: Exception) {
                     Log.e(TAG, "sendImageToDevice: Error explicitly connecting BluetoothConnection for $deviceName: ${e.message}", e)
                     withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Failed to connect to $deviceName (library): ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    try { printerConnection.disconnect() } catch (de: Exception) { Log.e(TAG, "Disconnect exception",de) } 
                    return@launch
                }


                Log.i(TAG, "sendImageToDevice: Creating EscPosPrinter for $deviceName.")
                // printerConnection is non-null here
                val printer = EscPosPrinter(printerConnection, 203, 48f, 32)
                Log.i(TAG, "sendImageToDevice: EscPosPrinter created. Attempting to print image to $deviceName.")
                
                val formattedText = "[C]<img>${PrinterTextParserImg.bitmapToHexadecimalString(printer, bitmapToPrint)}</img>\n"
                printer.printFormattedTextAndCut(formattedText)

                Log.i(TAG, "sendImageToDevice: Image sent to EscPosPrinter for $deviceName.")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Image sent to $deviceName", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) { 
                Log.e(TAG, "sendImageToDevice: Error sending image to $deviceName: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error printing image to $deviceName: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            } finally {
                Log.d(TAG, "sendImageToDevice: Cleaning up EscPosPrinter connection for $deviceName.")
                try {
                    printerConnection?.disconnect()
                    Log.i(TAG, "sendImageToDevice: EscPosPrinter connection disconnected for $deviceName.")
                } catch (e: Exception) {
                    Log.e(TAG, "sendImageToDevice: Error disconnecting EscPosPrinter connection for $deviceName: ${e.message}", e)
                }
            }
        }
    }


    @SuppressLint("MissingPermission") // Permissions checked before calling cancelDiscovery
    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Activity being destroyed.")
        super.onDestroy()
        val adapter = bluetoothAdapter
        if (adapter != null) {
            val canStopDiscovery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            } else {
                 ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
            }

            if (canStopDiscovery && adapter.isDiscovering) {
                 Log.d(TAG, "onDestroy: Stopping Bluetooth discovery.")
                 adapter.cancelDiscovery()
            } else if (adapter.isDiscovering) {
                 Log.w(TAG, "onDestroy: Was discovering but required permission for cancelDiscovery not granted (SCAN for S+, ADMIN for pre-S).")
            }
        }
        unregisterReceiver(discoveryReceiver)
        Log.d(TAG, "onDestroy: discoveryReceiver unregistered.")
    }
}
