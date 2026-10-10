package com.househunter.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.househunter.app.ui.navigation.HouseHunterNavHost
import com.househunter.app.ui.theme.HouseHunterTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HouseHunterTheme {
                HouseHunterNavHost()
            }
        }
    }
}