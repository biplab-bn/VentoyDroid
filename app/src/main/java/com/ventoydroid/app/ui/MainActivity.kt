package com.ventoydroid.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ventoydroid.app.ui.theme.VentoyDroidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VentoyDroidTheme {
                VentoyDroidApp()
            }
        }
    }

    @Composable
    private fun VentoyDroidApp() {
        val vm: AppViewModel = viewModel()
        val state by vm.installState.collectAsState()
        VentoyDroidScreen(vm = vm, state = state)
    }
}
