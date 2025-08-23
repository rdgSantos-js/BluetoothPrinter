package com.example.btprint

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
// import android.bluetooth.BluetoothSocket // No longer used directly for text
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
// import java.io.IOException // No longer directly used here
// import java.io.OutputStream // No longer used here
// import java.util.UUID // SPP_UUID No longer used here
// import java.nio.charset.Charset // No longer used here

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
            return this.address ?: "Desconhecido (Sem Permissão)"
        }
    }
    return try {
        @SuppressLint("MissingPermission")
        val name = this.name
        name ?: this.address ?: "Dispositivo Desconhecido"
    } catch (_: SecurityException) {
        this.address ?: "Desconhecido (Exceção de Segurança)"
    }
}

class MainActivity : ComponentActivity() {

    private val bluetoothManager by lazy { getSystemService(BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter: BluetoothAdapter? by lazy { bluetoothManager.adapter }
    private val discoveredDevices = mutableStateListOf<BluetoothDevice>()
    private var selectedDevice by mutableStateOf<BluetoothDevice?>(null)
    private var textToSend by mutableStateOf("")
    private var imageToSend by mutableStateOf<Bitmap?>(null)

    companion object {
        private const val TAG = "BluetoothAppTag"
        // private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // No longer needed
        private const val REQUEST_ENABLE_BT = 101
    }

    private lateinit var imagePickerLauncher: ActivityResultLauncher<String>

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
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
                        val deviceAddress = it.address ?: "Endereço Desconhecido"
                        Log.d(TAG, "discoveryReceiver: ACTION_FOUND - Device: $deviceNameForLog ($deviceAddress)")
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
                        Toast.makeText(context, "Procura finalizada. Nenhum novo dispositivo encontrado.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Procura finalizada.", Toast.LENGTH_SHORT).show()
                    }
                }
                else -> {
                    Log.w(TAG, "discoveryReceiver: Received unhandled action: $action")
                }
            }
        }
    }

    @SuppressLint("InlinedApi")
    private val requestBluetoothPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            Log.d(TAG, "requestBluetoothPermissionsLauncher: Received permission results:")
            permissions.entries.forEach { Log.d(TAG, "  ${it.key} = ${it.value}") }

            val scanGranted = permissions[Manifest.permission.BLUETOOTH_SCAN] ?: false
            val connectGranted = permissions[Manifest.permission.BLUETOOTH_CONNECT] ?: false
            val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (scanGranted && connectGranted) {
                    Log.i(TAG, "requestBluetoothPermissionsLauncher: BLUETOOTH_SCAN and BLUETOOTH_CONNECT permissions granted. Starting discovery.")
                    startBluetoothDiscovery()
                } else {
                    Log.e(TAG, "requestBluetoothPermissionsLauncher: BLUETOOTH_SCAN ($scanGranted) or BLUETOOTH_CONNECT ($connectGranted) permission denied.")
                    Toast.makeText(this, "Permissões de Scan e Conexão Bluetooth são necessárias para encontrar e conectar a dispositivos.", Toast.LENGTH_LONG).show()
                }
            } else { 
                if (fineLocationGranted) {
                     Log.i(TAG, "requestBluetoothPermissionsLauncher: ACCESS_FINE_LOCATION permission granted on pre-S device. Starting discovery.")
                    startBluetoothDiscovery()
                } else {
                    Log.e(TAG, "requestBluetoothPermissionsLauncher: ACCESS_FINE_LOCATION permission denied on pre-S device.")
                    Toast.makeText(this, "Permissão de localização é necessária para procurar dispositivos em versões mais antigas do Android.", Toast.LENGTH_LONG).show()
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
                    imageToSend = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    Log.i(TAG, "imagePickerLauncher: Bitmap created successfully. Width: ${imageToSend?.width}, Height: ${imageToSend?.height}")
                } catch (e: Exception) {
                    Log.e(TAG, "imagePickerLauncher: Error creating Bitmap from URI: ${e.message}", e)
                    Toast.makeText(this, "Erro ao carregar imagem: ${e.message}", Toast.LENGTH_LONG).show()
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
                                    Toast.makeText(this, "Nenhum dispositivo selecionado para envio de imagem", Toast.LENGTH_SHORT).show()
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
                                    Toast.makeText(this, "Digite o texto para a impressora", Toast.LENGTH_SHORT).show()
                                }
                            } ?: run {
                                Log.w(TAG, "onSendToPrinterClicked: No device selected for printer.")
                                Toast.makeText(this, "Nenhum dispositivo selecionado", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onPreviewImageClicked = { 
                            Log.d(TAG, "onPreviewImageClicked: Preview image clicked. Launching image picker.")
                            imagePickerLauncher.launch("image/*")
                        }
                    )
                }
            }
        }
    }

    @SuppressLint("InlinedApi") 
    private fun checkAndRequestPermissions() {
        Log.d(TAG, "checkAndRequestPermissions: Checking permissions.")
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.e(TAG, "checkAndRequestPermissions: Bluetooth not supported on this device.")
            Toast.makeText(this, "Bluetooth não suportado", Toast.LENGTH_SHORT).show()
            return
        }
        if (!adapter.isEnabled) {
            Log.i(TAG, "checkAndRequestPermissions: Bluetooth not enabled. Requesting...")
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION") 
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT) 
            } else {
                 Log.w(TAG, "checkAndRequestPermissions: BLUETOOTH_CONNECT permission needed to request enabling Bluetooth.")
                 Toast.makeText(this, "Por favor, ative o Bluetooth nas Configurações do Sistema ou conceda a permissão de Conexão.", Toast.LENGTH_LONG).show()
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
                Toast.makeText(this, "Bluetooth é necessário para procurar dispositivos.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothDiscovery() {
        Log.d(TAG, "startBluetoothDiscovery: Attempting to start discovery.")
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "startBluetoothDiscovery: Bluetooth adapter not available or not enabled.")
            Toast.makeText(this, "Por favor, ative o Bluetooth.", Toast.LENGTH_SHORT).show()
            return
        }

        val canScan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED && 
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
        }

        if (!canScan) {
             Log.e(TAG, "startBluetoothDiscovery: Missing necessary permissions to start discovery.")
             Toast.makeText(this, "Permissões necessárias para iniciar a procura. Verifique as configurações.", Toast.LENGTH_LONG).show()
             checkAndRequestPermissions() 
             return
        }

        if (adapter.isDiscovering) {
            Log.d(TAG, "startBluetoothDiscovery: Already discovering. Stopping previous discovery.")
            adapter.cancelDiscovery() 
        }
        
        discoveredDevices.clear() 
        addBondedDevicesToList() 

        Log.i(TAG, "startBluetoothDiscovery: Starting Bluetooth discovery...")
        val discoveryStarted = adapter.startDiscovery() 
        
        if (discoveryStarted) {
            Toast.makeText(this, "Procurando dispositivos...", Toast.LENGTH_SHORT).show()
        } else {
            Log.e(TAG, "startBluetoothDiscovery: Failed to start Bluetooth discovery despite passing permission checks. System error?")
            Toast.makeText(this, "Falha ao iniciar a procura. Por favor, tente novamente.", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("MissingPermission")
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
            return
        }

        try {
            val bondedBtDevices: Set<BluetoothDevice>? = adapter.bondedDevices
            bondedBtDevices?.forEach { device ->
                 val deviceName = device.getSafeName(this)
                 val deviceAddress = device.address ?: "Endereço Desconhecido"
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

    @SuppressLint("MissingPermission")
    private fun sendTextToDevice(device: BluetoothDevice, text: String) {
        val deviceName = device.getSafeName(this)
        val deviceAddress = device.address
        Log.i(TAG, "sendTextToDevice: Initiating text send to $deviceName ($deviceAddress)")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "sendTextToDevice: BLUETOOTH_CONNECT permission not granted for $deviceName.")
            Toast.makeText(this, "Permissão BLUETOOTH_CONNECT necessária para enviar texto para $deviceName", Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            var printerConnection: BluetoothConnection? = null
            try {
                Log.d(TAG, "sendTextToDevice: Searching for Bluetooth printer connection for address: $deviceAddress")
                // Try to get from the library's list first for potential reuse
                printerConnection = BluetoothPrintersConnections().list?.firstOrNull { it.device.address == deviceAddress }

                if (printerConnection == null) {
                    Log.w(TAG, "sendTextToDevice: Device $deviceAddress not found in library's list. Attempting direct connection.")
                    printerConnection = BluetoothConnection(device)
                    Log.i(TAG, "sendTextToDevice: Direct BluetoothConnection object created for $deviceAddress.")
                } else {
                    Log.d(TAG, "sendTextToDevice: Reusing existing BluetoothConnection from library for $deviceName ($deviceAddress).")
                }

                // Attempt to connect. EscPosPrinter might also try to connect,
                // but explicit connection here can help diagnose issues earlier.
                if (!printerConnection.isConnected) {
                    Log.d(TAG, "sendTextToDevice: Explicitly connecting printerConnection for $deviceName")
                    printerConnection.connect()
                }
                 Log.i(TAG, "sendTextToDevice: Printer connection active for $deviceName. Creating EscPosPrinter.")
                // Use the same printer parameters as for image printing
                val printer = EscPosPrinter(printerConnection, 203, 48f, 32)
                
                // The library handles encoding; plain text with newlines should work.
                // Adding [L] for left alignment, and ensuring some feed with 

                val textToPrint = "[L]$text\n\n\n" 
                Log.i(TAG, "sendTextToDevice: Attempting to print text: $textToPrint to $deviceName.")
                printer.printFormattedTextAndCut(textToPrint)

                Log.i(TAG, "sendTextToDevice: Text sent to EscPosPrinter for $deviceName.")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Texto enviado para $deviceName", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "sendTextToDevice: Error sending text to $deviceName: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Erro ao enviar texto para $deviceName: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            } finally {
                Log.d(TAG, "sendTextToDevice: Cleaning up EscPosPrinter connection for $deviceName.")
                try {
                    printerConnection?.disconnect()
                    Log.i(TAG, "sendTextToDevice: EscPosPrinter connection disconnected for $deviceName.")
                } catch (e: Exception) {
                    Log.e(TAG, "sendTextToDevice: Error disconnecting EscPosPrinter connection for $deviceName: ${e.message}", e)
                }
            }
        }
    }


    @SuppressLint("MissingPermission") 
    private fun sendImageToDevice(device: BluetoothDevice, bitmapToPrint: Bitmap) {
        val deviceName = device.getSafeName(this)
        val deviceAddress = device.address
        Log.i(TAG, "sendImageToDevice: Initiating image send to $deviceName ($deviceAddress)")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "sendImageToDevice: BLUETOOTH_CONNECT permission not granted for $deviceName.")
            Toast.makeText(this, "Permissão BLUETOOTH_CONNECT necessária para imprimir em $deviceName", Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            var printerConnection: BluetoothConnection? = null
            try {
                Log.d(TAG, "sendImageToDevice: Searching for Bluetooth printer connection for address: $deviceAddress")
                
                // Try to get from the library's list first for potential reuse
                printerConnection = BluetoothPrintersConnections().list?.firstOrNull { it.device.address == deviceAddress }

                if (printerConnection == null) {
                    Log.w(TAG, "sendImageToDevice: Device $deviceAddress not found in library's list. Attempting direct connection.")
                    printerConnection = BluetoothConnection(device) 
                    Log.i(TAG, "sendImageToDevice: Direct BluetoothConnection object created for $deviceAddress.")
                }
                
                if (!printerConnection.isConnected) { 
                         Log.d(TAG, "sendImageToDevice: Explicitly connecting printerConnection for $deviceName")
                         printerConnection.connect()
                }
                Log.i(TAG, "sendImageToDevice: Printer connection active for $deviceName. Creating EscPosPrinter.")
                val printer = EscPosPrinter(printerConnection, 203, 48f, 32)
                Log.i(TAG, "sendImageToDevice: EscPosPrinter created. Attempting to print image to $deviceName.")
                
                val formattedText = "[C]<img>${PrinterTextParserImg.bitmapToHexadecimalString(printer, bitmapToPrint)}</img>\n"
                printer.printFormattedTextAndCut(formattedText)

                Log.i(TAG, "sendImageToDevice: Image sent to EscPosPrinter for $deviceName.")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Imagem enviada para $deviceName", Toast.LENGTH_SHORT).show()
                    imageToSend = null
                }

            } catch (e: Exception) { 
                Log.e(TAG, "sendImageToDevice: Error sending image to $deviceName: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Erro ao imprimir imagem para $deviceName: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
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

    @SuppressLint("MissingPermission") 
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
