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
import com.romangarms.autoxlive.data.ResponseCache
import com.romangarms.autoxlive.ui.AppScaffold
import com.romangarms.autoxlive.ui.AutoXTheme
import com.romangarms.autoxlive.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

class MainActivity : ComponentActivity() {
    private val responseCache by lazy { ResponseCache(File(cacheDir, "responses")) }

    private val client by lazy {
        val token = runBlocking { DeviceIdentity.token(applicationContext) }
        ApiClient(BuildConfig.BASE_URL, token, responseCache)
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
        lifecycleScope.launch(Dispatchers.IO) {
            responseCache.prune(olderThan = System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000)
        }
        setContent {
            AutoXTheme {
                AppScaffold(viewModel, updater)
            }
        }
    }
}
