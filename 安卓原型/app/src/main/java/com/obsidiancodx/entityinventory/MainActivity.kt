package com.obsidiancodx.entityinventory

import android.media.AudioManager
import android.media.ToneGenerator
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.obsidiancodx.entityinventory.scanner.NfcController
import com.obsidiancodx.entityinventory.ui.EntityInventoryApp

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
    private lateinit var nfcController: NfcController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcController = NfcController(
            activity = this,
            onScan = viewModel::acceptNfc,
            onWriteVerified = viewModel::verifyNfcBinding,
            onMessage = viewModel::setMessage
        )
        setContent {
            EntityInventoryApp(
                viewModel = viewModel,
                nfcController = nfcController,
                playSuccessTone = {
                    ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
                        .startTone(ToneGenerator.TONE_PROP_ACK, 90)
                }
            )
        }
        nfcController.handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        nfcController.handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        nfcController.enable()
    }

    override fun onPause() {
        nfcController.disable()
        super.onPause()
    }
}
