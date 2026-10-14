package com.romangarms.autoxlive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.romangarms.autoxlive.data.ApiClient
import com.romangarms.autoxlive.data.DataStoreSettings
import com.romangarms.autoxlive.data.DeviceIdentity
import com.romangarms.autoxlive.ui.AppScaffold
import com.romangarms.autoxlive.ui.AutoXTheme
import com.romangarms.autoxlive.update.Updater
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    private val client by lazy {
        val token = runBlocking { DeviceIdentity.token(applicationContext) }
        ApiClient(BuildConfig.BASE_URL, token)
    }

    private val viewModel: AppViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(RemoteApi(client), DataStoreSettings(applicationContext)) as T
        }
    }

    private val updater by lazy { Updater(applicationContext, client, lifecycleScope) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        viewModel.start()
        setContent {
            AutoXTheme {
                AppScaffold(viewModel, updater)
            }
        }
    }
}
