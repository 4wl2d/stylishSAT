package com.tomilov.stylishsat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tomilov.stylishsat.ui.screens.StudyApp
import com.tomilov.stylishsat.ui.theme.StylishSATTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
        setContent { StylishSATTheme { StudyApp(viewModel()) } }
    }
}
