package com.mydream.calllogger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mydream.calllogger.ui.AppViewModel
import com.mydream.calllogger.ui.HomeScreen
import com.mydream.calllogger.ui.OnboardingScreen
import com.mydream.calllogger.ui.theme.CallLoggerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CallLoggerTheme {
                val vm: AppViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()

                if (!state.onboardingComplete) {
                    OnboardingScreen(onSubmit = { vm.saveEmail(it) })
                } else {
                    HomeScreen(vm)
                }
            }
        }
    }
}
