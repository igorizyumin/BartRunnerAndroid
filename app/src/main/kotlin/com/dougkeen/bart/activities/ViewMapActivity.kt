package com.dougkeen.bart.activities

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import com.dougkeen.bart.ui.BartRunnerTheme
import com.dougkeen.bart.ui.SystemMapScreen

class ViewMapActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BartRunnerTheme { SystemMapScreen(onBack = { finish() }) } }
    }
}
