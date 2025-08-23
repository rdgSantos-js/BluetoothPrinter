package com.example.btprint

import android.Manifest
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
import androidx.compose.material3.Text
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
import kotlinx.coroutines.delay

// Helper function to get device name safely considering permissions
fun BluetoothDevice.getSafeName(context: Context): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return this.address ?: "Unknown (No Permission)"
        }
    }
    return try {
        this.name ?: "Unknown Device"
    } catch (e: SecurityException) {
        this.address ?: "Unknown (Security Exception)"
    }
}

class MainActivity : ComponentActivity() {

    private val bluetoothManager by lazy { getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter: BluetoothAdapter? by lazy { bluetoothManager.adapter }
    private val discoveredDevices = mutableStateListOf<BluetoothDevice>()
    private var selectedDevice by mutableStateOf<BluetoothDevice?>(null)
    private var textToSend by mutableStateOf("")
    private var imageToSend by mutableStateOf<Bitmap?>(null)

    companion object {
        private const val tag = "BluetoothApp"
        private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private lateinit var imagePickerLauncher: ActivityResultLauncher<String>

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action: String? = intent.action
            Log.d(tag, "discoveryReceiver: Received action: $action")

            when (action) {
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    Log.i(tag, "discoveryReceiver: ACTION_DISCOVERY_STARTED - Discovery process has started.")
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
                        val deviceAddress = it.address ?: "Endereço_Desconhecido"
                        Log.d(tag, "discoveryReceiver: ACTION_FOUND - Device: $deviceNameForLog ($deviceAddress)")
                        if (it !in discoveredDevices) {
                            discoveredDevices.add(it)
                            Log.i(tag, "discoveryReceiver: Device $deviceNameForLog ($deviceAddress) added to discoveredDevices list.")
                        } else {
                            Log.d(tag, "discoveryReceiver: Device $deviceNameForLog ($deviceAddress) already in list.")
                        }
                    } ?: Log.w(tag, "discoveryReceiver: ACTION_FOUND - Device is null.")
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.i(tag, "discoveryReceiver: ACTION_DISCOVERY_FINISHED - Discovery process has finished.")
                    Toast.makeText(context, "Scan finished.", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    Log.w(tag, "discoveryReceiver: Received unhandled action: $action")
                }
            }
        }
    }

    private val requestBluetoothPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            Log.d(tag, "requestBluetoothPermissionsLauncher: Received permission results:")
            permissions.entries.forEach { Log.d(tag, "  ${it.key} = ${it.value}") }

            val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val scanGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissions[Manifest.permission.BLUETOOTH_SCAN] ?: false
            } else true

            if (scanGranted && fineLocationGranted) {
                Log.i(tag, "requestBluetoothPermissionsLauncher: All critical permissions granted. Starting discovery.")
                startBluetoothDiscovery()
            } else {
                Log.e(tag, "requestBluetoothPermissionsLauncher: Not all critical permissions granted. Scan: $scanGranted, Location: $fineLocationGranted")
                Toast.makeText(this, "Permissions are required to scan for devices.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(tag, "onCreate: Activity creating.")

        imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                Log.d(tag, "imagePickerLauncher: Image URI selected: $it")
                try {
                    val bitmap = if (Build.VERSION.SDK_INT < 28) {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Media.getBitmap(this.contentResolver, it)
                    } else {
                        val source = ImageDecoder.createSource(this.contentResolver, it)
                        ImageDecoder.decodeBitmap(source)
                    }
                    imageToSend = bitmap.copy(Bitmap.Config.ARGB_8888, true) // Ensure mutable bitmap
                    Log.i(tag, "imagePickerLauncher: Bitmap created successfully.")
                } catch (e: Exception) {
                    Log.e(tag, "imagePickerLauncher: Error creating Bitmap from URI: ${e.message}", e)
                    Toast.makeText(this, "Error loading image: ${e.message}", Toast.LENGTH_LONG).show()
                    imageToSend = null
                }
            } ?: Log.d(tag, "imagePickerLauncher: No image URI selected (uri is null).")
        }


        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(discoveryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            Log.d(tag, "onCreate: discoveryReceiver registered with RECEIVER_NOT_EXPORTED.")
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(discoveryReceiver, filter)
            Log.d(tag, "onCreate: discoveryReceiver registered (pre-Tiramisu).")
        }
        
        addBondedDevicesToList()

        setContent {
            BtprintTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        BluetoothControlScreen(
                            modifier = Modifier.padding(innerPadding),
                            devices = discoveredDevices,
                            selectedDevice = selectedDevice,
                            textToSend = textToSend,
                            imageToSend = imageToSend, // Pass imageToSend to composable
                            onTextChange = { textToSend = it },
                            onScanClicked = {
                                Log.d(tag, "onScanClicked: Scan button clicked.")
                                checkAndRequestPermissions()
                            },
                            onDeviceSelected = { device ->
                                Log.d(tag, "onDeviceSelected: Device ${device.getSafeName(this@MainActivity)} (${device.address ?: "N/A"}) selected.")
                                selectedDevice = device
                            },
                            onSendImageClicked = {
                                Log.d(tag, "onSendImageClicked: Send Image button logic initiated.")
                                if (imageToSend == null) {
                                    Log.i(tag, "onSendImageClicked: No image selected. Launching image picker.")
                                    imagePickerLauncher.launch("image/*")
                                } else {
                                    selectedDevice?.let { device ->
                                        Log.i(tag, "onSendImageClicked: Image selected. Attempting to send to ${device.getSafeName(this@MainActivity)}.")
                                        sendImageToDevice(device, imageToSend)
                                        // imageToSend = null // Clear after attempting to send
                                    } ?: run {
                                        Log.w(tag, "onSendImageClicked: No device selected for image sending.")
                                        Toast.makeText(this, "No device selected for image sending", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onSendToPrinterClicked = {
                                Log.d(tag, "onSendToPrinterClicked: Send to Printer button clicked.")
                                selectedDevice?.let { device ->
                                    if (textToSend.isNotBlank()) {
                                        sendTextToDevice(device, textToSend, true)
                                    } else {
                                        Log.w(tag, "onSendToPrinterClicked: Text to send is blank.")
                                        Toast.makeText(this, "Enter text for printer", Toast.LENGTH_SHORT).show()
                                    }
                                } ?: run {
                                    Log.w(tag, "onSendToPrinterClicked: No device selected for printer.")
                                    Toast.makeText(this, "No device selected", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    } else {
                        Text(
                            text = "This feature requires Android 12 (API 31) or higher.",
                            modifier = Modifier.padding(innerPadding).fillMaxSize()
                        )
                        Log.w(tag, "setContent: BluetoothControlScreen requires API 31+. Device is running API ${Build.VERSION.SDK_INT}.")
                    }
                }
            }
        }
        Log.d(tag, "onCreate: Activity created and content set.")
    }

    private fun isBluetoothAdapterReady(): Boolean {
        if (bluetoothAdapter == null) {
            Log.e(tag, "isBluetoothAdapterReady: BluetoothAdapter is null.")
            Toast.makeText(this, "Bluetooth not supported on this device.", Toast.LENGTH_SHORT).show()
            return false
        }
        if (bluetoothAdapter?.isEnabled != true) { 
            Log.w(tag, "isBluetoothAdapterReady: Bluetooth is not enabled.")
            Toast.makeText(this, "Bluetooth is not enabled. Please enable it.", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun addBondedDevicesToList() {
        Log.d(tag, "addBondedDevicesToList: Attempting to add bonded devices.")
        if (bluetoothAdapter == null) {
            Log.w(tag, "addBondedDevicesToList: BluetoothAdapter is null. Cannot get bonded devices.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.w(tag, "addBondedDevicesToList: BLUETOOTH_CONNECT permission not granted for Android S+. Cannot get bonded devices.")
            return
        }

        try {
            val bondedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices 
            if (bondedDevices.isNullOrEmpty()) {
                Log.i(tag, "addBondedDevicesToList: No bonded devices found.")
            } else {
                Log.i(tag, "addBondedDevicesToList: Found ${bondedDevices.size} bonded devices.")
                bondedDevices.forEach { device ->
                    val deviceName = device.getSafeName(this@MainActivity)
                    val deviceAddress = device.address
                    if (device !in discoveredDevices) {
                        discoveredDevices.add(device)
                        Log.d(tag, "addBondedDevicesToList: Added bonded device: $deviceName ($deviceAddress)")
                    } else {
                        Log.d(tag, "addBondedDevicesToList: Bonded device $deviceName ($deviceAddress) already in list.")
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(tag, "addBondedDevicesToList: SecurityException while getting bonded devices. ${e.message}", e)
        }
    }

    private fun checkAndRequestPermissions() {
        Log.d(tag, "checkAndRequestPermissions: Checking Bluetooth adapter and permissions.")
        if (!isBluetoothAdapterReady()) return

        val requiredPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Log.i(tag, "checkAndRequestPermissions: BLUETOOTH_SCAN permission needed.")
                requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.i(tag, "checkAndRequestPermissions: BLUETOOTH_CONNECT permission needed.")
                requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        // Permissions for image picker (add if not present)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                 Log.i(tag, "checkAndRequestPermissions: READ_MEDIA_IMAGES permission needed for Android 13+.")
                requiredPermissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) { // READ_EXTERNAL_STORAGE for API 28 and below
             if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                Log.i(tag, "checkAndRequestPermissions: READ_EXTERNAL_STORAGE permission needed for older APIs.")
                requiredPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        // For API 29-32, READ_EXTERNAL_STORAGE is usually sufficient if declared, but scoped storage applies.
        // For simplicity here, we ensure at least one form of read permission is requested if needed.
        // Consider more granular checks based on specific Android version nuances if issues arise.


        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.i(tag, "checkAndRequestPermissions: ACCESS_FINE_LOCATION permission needed.")
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (requiredPermissions.isNotEmpty()) {
            Log.i(tag, "checkAndRequestPermissions: Requesting permissions: ${requiredPermissions.joinToString()}")
            requestBluetoothPermissionsLauncher.launch(requiredPermissions.toTypedArray())
        } else {
            Log.i(tag, "checkAndRequestPermissions: All required permissions already granted. Starting discovery.")
            startBluetoothDiscovery()
        }
    }

    private fun startBluetoothDiscovery() {
        Log.d(tag, "startBluetoothDiscovery: Attempting to start.")
        if (!isBluetoothAdapterReady()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e(tag, "startBluetoothDiscovery: BLUETOOTH_SCAN permission not granted. Cannot start/manage discovery.")
            Toast.makeText(this, "BLUETOOTH_SCAN permission needed to discover devices.", Toast.LENGTH_SHORT).show()
            return
        }

        if (bluetoothAdapter?.isDiscovering == true) {
            Log.i(tag, "startBluetoothDiscovery: Discovery already in progress. Cancelling existing.")
            val cancelled = bluetoothAdapter?.cancelDiscovery() 
            Log.d(tag, "startBluetoothDiscovery: Existing discovery cancellation attempted. Success: $cancelled")
        }

        Log.d(tag, "startBluetoothDiscovery: Clearing discoveredDevices list and selectedDevice.")
        discoveredDevices.clear()
        selectedDevice = null

        Log.i(tag, "startBluetoothDiscovery: Adding bonded devices before starting new discovery.")
        addBondedDevicesToList()

        Log.d(tag, "startBluetoothDiscovery: Attempting to call bluetoothAdapter.startDiscovery().")
        val discoveryStarted = bluetoothAdapter?.startDiscovery() 

        if (discoveryStarted == true) {
            Log.i(tag, "startBluetoothDiscovery: Discovery process started successfully.")
            Toast.makeText(this, "Scanning for devices...", Toast.LENGTH_SHORT).show()
        } else {
            Log.e(tag, "startBluetoothDiscovery: bluetoothAdapter.startDiscovery() returned false or adapter is null.")
            Toast.makeText(this, "Failed to start discovery. Adapter might be busy or check logs.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendTextToDevice(device: BluetoothDevice, text: String, appendNewline: Boolean) {
        val deviceNameForLog = device.getSafeName(this@MainActivity)
        val deviceAddressForLog = device.address ?: "Endereco_Desconhecido"
        val textToSendProcessed = if (appendNewline) "\n\n${text}\n\n" else text
        val logMessageSuffix = if (appendNewline) " (printer formatted: \\n\\nTEXT\\n\\n)" else ""

        Log.d(tag, "sendTextToDevice: Iniciando envio para $deviceNameForLog ($deviceAddressForLog). Texto processado: '$textToSendProcessed'$logMessageSuffix")

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(tag, "sendTextToDevice: Permissão BLUETOOTH_CONNECT não concedida.")
            Toast.makeText(this, "Permissão BLUETOOTH_CONNECT necessária para enviar dados.", Toast.LENGTH_LONG).show()
            return
        }

        if (bluetoothAdapter?.isDiscovering == true) {
            Log.i(tag, "sendTextToDevice: Descoberta em progresso. Tentando cancelar.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Log.w(tag, "sendTextToDevice: Permissão BLUETOOTH_SCAN não concedida. Não é possível cancelar a descoberta em andamento.")
            } else {
                val cancelled = bluetoothAdapter?.cancelDiscovery()
                Log.d(tag, "sendTextToDevice: Tentativa de cancelamento da descoberta. Sucesso: $cancelled")
            }
        }

        lifecycleScope.launch {
            val maxRetries = 3
            var currentRetry = 0
            var operationSuccessful = false

            while (currentRetry < maxRetries && !operationSuccessful) {
                var localSocket: BluetoothSocket? = null
                var localOutputStream: OutputStream? = null
                currentRetry++ 

                Log.d(tag, "sendTextToDevice Coroutine: Tentativa ${currentRetry}/${maxRetries} para $deviceNameForLog.")

                try {
                    Log.d(tag, "sendTextToDevice Coroutine: Criando RfcommSocket para UUID: $sppUuid.")
                    localSocket = device.createRfcommSocketToServiceRecord(sppUuid)
                    
                    val escByte: Byte = 0x1B
                    val atByte: Byte = 0x40
                    val initPrinterCommand = byteArrayOf(escByte, atByte)                    
                    val textBytes = textToSendProcessed.toByteArray(Charset.forName("CP437")) // Or other relevant charset for your printer
                    val dataToSend = initPrinterCommand + textBytes

                    withContext(Dispatchers.IO) {
                        Log.i(tag, "sendTextToDevice Coroutine (IO): Tentando conectar socket local.")
                        localSocket?.connect()
                        Log.i(tag, "sendTextToDevice Coroutine (IO): Socket local conectado.")
                        localOutputStream = localSocket?.outputStream
                        if (localOutputStream == null) {
                            Log.e(tag, "sendTextToDevice Coroutine (IO): Stream de saída local é nulo.")
                            throw IOException("Stream de saída local é nulo.")
                        }
                        Log.d(tag, "sendTextToDevice Coroutine (IO): Escrevendo ${dataToSend.size} bytes.")
                        localOutputStream?.write(dataToSend)
                        localOutputStream?.flush()
                    }
                    operationSuccessful = true
                    Log.i(tag, "sendTextToDevice Coroutine: Texto enviado com sucesso para $deviceNameForLog$logMessageSuffix na tentativa $currentRetry.")
                    Toast.makeText(this@MainActivity, "Texto enviado para $deviceNameForLog$logMessageSuffix (Tentativa $currentRetry)", Toast.LENGTH_SHORT).show()

                } catch (e: IOException) {
                    Log.e(tag, "sendTextToDevice Coroutine: IOException na tentativa ${currentRetry}/${maxRetries} para $deviceNameForLog: ${e.message}", e)
                    if (currentRetry >= maxRetries) {
                        Log.e(tag, "sendTextToDevice Coroutine: Número máximo de tentativas atingido para $deviceNameForLog.")
                        Toast.makeText(this@MainActivity, "Erro ao enviar para $deviceNameForLog após $maxRetries tentativas: ${e.message}", Toast.LENGTH_LONG).show()
                    } else {
                        val delayMillis = 1000L * currentRetry 
                        Log.i(tag, "sendTextToDevice Coroutine: Aguardando ${delayMillis}ms antes da próxima tentativa.")
                        Toast.makeText(this@MainActivity, "Falha ao enviar, tentando novamente... (Tentativa $currentRetry)", Toast.LENGTH_SHORT).show()
                        delay(delayMillis)
                    }
                } catch (se: SecurityException) {
                    Log.e(tag, "sendTextToDevice Coroutine: SecurityException para $deviceNameForLog: ${se.message}", se)
                    Toast.makeText(this@MainActivity, "Erro de permissão com $deviceNameForLog: ${se.message}", Toast.LENGTH_LONG).show()
                    break 
                } finally {
                    Log.d(tag, "sendTextToDevice Coroutine: Bloco finally na tentativa $currentRetry.")
                    try {
                        localOutputStream?.close()
                        Log.d(tag, "sendTextToDevice Coroutine: Stream de saída local fechado.")
                    } catch (e: IOException) {
                        Log.e(tag, "sendTextToDevice Coroutine: IOException ao fechar stream de saída local: ${e.message}", e)
                    }
                    try {
                        localSocket?.close()
                        Log.d(tag, "sendTextToDevice Coroutine: Socket local fechado.")
                    } catch (e: IOException) {
                        Log.e(tag, "sendTextToDevice Coroutine: IOException ao fechar socket local: ${e.message}", e)
                    }
                }
            }
            if (!operationSuccessful && currentRetry >= maxRetries) {
                 Log.w(tag, "sendTextToDevice Coroutine: Operação final falhou após $maxRetries tentativas para $deviceNameForLog.")
            }
        }
    }

    private fun sendImageToDevice(device: BluetoothDevice, image: Bitmap?) {
        val deviceNameForLog = device.getSafeName(this@MainActivity)
        Log.d(tag, "sendImageToDevice: Tentando enviar imagem para $deviceNameForLog.")

        if (image == null) {
            Log.w(tag, "sendImageToDevice: Imagem é nula. Nenhuma imagem para enviar.")
            Toast.makeText(this, "Nenhuma imagem selecionada para enviar.", Toast.LENGTH_SHORT).show()
            return
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(tag, "sendImageToDevice: Permissão BLUETOOTH_CONNECT não concedida.")
            Toast.makeText(this, "Permissão BLUETOOTH_CONNECT necessária para enviar imagem.", Toast.LENGTH_LONG).show()
            return
        }
        
        // TODO: Implementar a lógica de conversão da imagem e envio para a impressora
        // Isso envolverá a conversão do Bitmap para um formato de byte array
        // que a impressora entende (ex: monocromático, comandos ESC/POS).
        // A função converterBitmapParaFormatoImpressora(image) será crucial aqui.

        lifecycleScope.launch {
            Log.i(tag, "sendImageToDevice Coroutine: [Placeholder] Iniciando lógica de envio de imagem para $deviceNameForLog.")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "[Placeholder] Preparando para enviar imagem para $deviceNameForLog...", Toast.LENGTH_SHORT).show()
            }
            
            // Simulação de conversão e envio (substituir com lógica real)
            // val imageData: ByteArray? = convertBitmapToPrinterFormat(image) // Função a ser implementada
            // if (imageData == null) {
            //     Log.e(tag, "sendImageToDevice Coroutine: Falha ao converter imagem para formato da impressora.")
            //     Toast.makeText(this@MainActivity, "Erro ao converter imagem.", Toast.LENGTH_LONG).show()
            //     return@launch
            // }

            var localSocket: BluetoothSocket? = null
            var localOutputStream: OutputStream? = null
            try {
                Log.d(tag, "sendImageToDevice Coroutine: Criando RfcommSocket para $deviceNameForLog.")
                localSocket = device.createRfcommSocketToServiceRecord(sppUuid)
                
                withContext(Dispatchers.IO) {
                    Log.i(tag, "sendImageToDevice Coroutine (IO): Tentando conectar socket para $deviceNameForLog.")
                    localSocket?.connect()
                    Log.i(tag, "sendImageToDevice Coroutine (IO): Socket conectado para $deviceNameForLog.")
                    localOutputStream = localSocket?.outputStream
                    if (localOutputStream == null) {
                        Log.e(tag, "sendImageToDevice Coroutine (IO): Stream de saída nulo para $deviceNameForLog.")
                        throw IOException("Output stream is null.")
                    }
                    
                    // AQUI VOCÊ DEVE CHAMAR A FUNÇÃO DE CONVERSÃO E ENVIAR OS DADOS
                    // Exemplo:
                    // val printerData = convertBitmapToEscPos(image) // Esta função precisa ser criada!
                    // localOutputStream?.write(printerData)
                    // localOutputStream?.flush()
                    // Log.i(tag, "sendImageToDevice Coroutine (IO): [Placeholder] Dados da imagem enviados para $deviceNameForLog.")

                    // Por enquanto, apenas um log e Toast de placeholder
                     withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "[Placeholder] Lógica de envio real da imagem aqui.", Toast.LENGTH_LONG).show()
                     }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Imagem (placeholder) 'enviada' para $deviceNameForLog!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                Log.e(tag, "sendImageToDevice Coroutine: IOException para $deviceNameForLog: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Erro de I/O ao enviar imagem para $deviceNameForLog: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } catch (se: SecurityException) {
                 Log.e(tag, "sendImageToDevice Coroutine: SecurityException para $deviceNameForLog: ${se.message}", se)
                 withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Erro de permissão com $deviceNameForLog: ${se.message}", Toast.LENGTH_LONG).show()
                }
            }
            finally {
                Log.d(tag, "sendImageToDevice Coroutine: Bloco finally para $deviceNameForLog.")
                try {
                    localOutputStream?.close()
                    Log.d(tag, "sendImageToDevice Coroutine: Stream de saída fechado para $deviceNameForLog.")
                } catch (e: IOException) {
                    Log.e(tag, "sendImageToDevice Coroutine: IOException ao fechar stream para $deviceNameForLog: ${e.message}", e)
                }
                try {
                    localSocket?.close()
                    Log.d(tag, "sendImageToDevice Coroutine: Socket fechado para $deviceNameForLog.")
                } catch (e: IOException) {
                    Log.e(tag, "sendImageToDevice Coroutine: IOException ao fechar socket para $deviceNameForLog: ${e.message}", e)
                }
            }
             // Limpar a imagem selecionada após a tentativa de envio para que o usuário possa selecionar uma nova
            imageToSend = null
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "onDestroy: Activity destroying.")
        try {
            unregisterReceiver(discoveryReceiver)
            Log.i(tag, "onDestroy: discoveryReceiver unregistered.")
        } catch (e: IllegalArgumentException) {
            Log.w(tag, "onDestroy: discoveryReceiver was not registered or already unregistered. ${e.message}")
        }

        if (bluetoothAdapter?.isDiscovering == true) {
            Log.i(tag, "onDestroy: Discovery in progress. Attempting to cancel.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Log.w(tag, "onDestroy: BLUETOOTH_SCAN permission not granted. Cannot cancel discovery (Android S+).")
            } else {
                val cancelled = bluetoothAdapter?.cancelDiscovery() 
                Log.d(tag, "onDestroy: Discovery cancellation attempted. Success: $cancelled")
            }
        }
        Log.d(tag, "onDestroy: Activity destroyed.")
    }
}
