package com.example.btprint

import android.bluetooth.BluetoothDevice
import android.graphics.Bitmap // Import Bitmap
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme // Added import for MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap // To convert Bitmap to ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.btprint.ui.theme.BtprintTheme

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
    onSendToPrinterClicked: () -> Unit,
    onPreviewImageClicked: () -> Unit // Added new callback
) {
    val context = LocalContext.current
    Column(modifier = modifier.padding(16.dp)) {
        Button(onClick = onScanClicked, modifier = Modifier.fillMaxWidth()) {
            Text("Procurar Dispositivos")
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (devices.isEmpty()) {
            Text("Nenhum dispositivo encontrado ainda. Clique em 'Procurar' para começar.")
        } else {
            Text("Toque em um dispositivo para selecionar:")
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f).padding(vertical = 8.dp)
            ) {
                items(items = devices, key = { it.address }) { device ->
                    val deviceNameString = device.getSafeName(context)
                    val deviceAddressString = device.address ?: "Endereço Desconhecido"

                    Card(
                        modifier = Modifier
                            .padding(4.dp)
                            .fillMaxWidth()
                            .clickable { onDeviceSelected(device) },
                        colors = if (device == selectedDevice) {
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primary, // Changed to MaterialTheme.colorScheme.primary
                                contentColor = Color.White
                            )
                        } else {
                            CardDefaults.cardColors() // Uses default theme colors
                        }
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(text = deviceNameString)
                            Text(text = deviceAddressString)
                            if (device == selectedDevice) {
                                Text(
                                    text = "(Selecionado)",
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
                contentDescription = "Pré-visualização da Imagem Selecionada",
                modifier = Modifier
                    .size(100.dp)
                    .padding(bottom = 8.dp)
                    .align(Alignment.CenterHorizontally)
                    .clickable { onPreviewImageClicked() }, // Made image clickable
                contentScale = ContentScale.Fit
            )
        }

        OutlinedTextField(
            value = textToSend,
            onValueChange = onTextChange,
            label = { Text("Digite o texto para imprimir") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onSendImageClicked,
            enabled = selectedDevice != null, // Still need a device to be selected
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (imageToSend == null) "Selecionar Imagem" else "Enviar Imagem Selecionada")
        }
        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onSendToPrinterClicked,
            enabled = selectedDevice != null && textToSend.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Enviar para Impressora Selecionada")
        }
    }
}


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
            onSendToPrinterClicked = {},
            onPreviewImageClicked = {} // Added for preview
        )
    }
}
