package com.example.btprint

import android.content.Context
import com.genexus.android.core.externalapi.ExternalApiDefinition
import com.genexus.android.core.externalapi.ExternalApiFactory
import com.genexus.android.core.framework.GenexusModule

class BluetoothPrintModule : GenexusModule {
    override fun initialize(context: Context) {
        val basicExternalObject = ExternalApiDefinition(
            BluetoothPrintManager.NAME,
            BluetoothPrintManager::class.java
        )
        ExternalApiFactory.addApi(basicExternalObject)
    }
}