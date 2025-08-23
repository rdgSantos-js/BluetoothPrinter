package com.example.btprint

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.graphics.Bitmap // Import Bitmap
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image // Import Image Composable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size // For image preview size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap // To convert Bitmap to ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.example.btprint.ui.theme.BtprintTheme

@RequiresApi(Build.VERSION_CODES.S)
@Composable
fun BluetoothControlScreen(
    modifier: Modifier = Modifier,
    devices: List<BluetoothDevice>,
    selectedDevice: BluetoothDevice?,
    textToSend: String,
    imageToSend: Bitmap?, // Added imageToSend parameter
    onTextChange: (String) -> Unit,
    onScanClicked: () -> Unit,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onSendImageClicked: () -> Unit,
    onSendToPrinterClicked: () -> Unit
) {
    val context = LocalContext.current
    Column(modifier = modifier.padding(16.dp)) {
        Button(onClick = onScanClicked, modifier = Modifier.fillMaxWidth()) {
            Text("Scan for Devices")
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (devices.isEmpty()) {
            Text("No devices found yet. Click 'Scan' to start.")
        } else {
            Text("Tap a device to select:")
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f).padding(vertical = 8.dp)
            ) {
                items(items = devices, key = { it.address }) { device ->
                    val deviceNameString = device.getSafeName(context)
                    val deviceAddressString = device.address ?: "Unknown Address"

                    Card(
                        modifier = Modifier
                            .padding(4.dp)
                            .fillMaxWidth()
                            .clickable { onDeviceSelected(device) }
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(text = deviceNameString)
                            Text(text = deviceAddressString)
                            if (device == selectedDevice) {
                                Text(
                                    text = "(Selected)",
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Optional: Display the selected image preview
        imageToSend?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Selected Image Preview",
                modifier = Modifier
                    .size(100.dp)
                    .padding(bottom = 8.dp)
                    .align(Alignment.CenterHorizontally),
                contentScale = ContentScale.Fit
            )
        }

        OutlinedTextField(
            value = textToSend,
            onValueChange = onTextChange,
            label = { Text("Enter text to print") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onSendImageClicked,
            enabled = selectedDevice != null, // Still need a device to be selected
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (imageToSend == null) "Select Image" else "Send Selected Image")
        }
        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onSendToPrinterClicked,
            enabled = selectedDevice != null && textToSend.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Send to Selected Printer")
        }
    }
}


@RequiresApi(Build.VERSION_CODES.S)
@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    BtprintTheme {
        BluetoothControlScreen(
            devices = emptyList(),
            selectedDevice = null,
            textToSend = "Test Text",
            imageToSend = null, // Pass null for preview
            onTextChange = {},
            onScanClicked = {},
            onDeviceSelected = {},
            onSendImageClicked = {},
            onSendToPrinterClicked = {}
        )
    }
}
