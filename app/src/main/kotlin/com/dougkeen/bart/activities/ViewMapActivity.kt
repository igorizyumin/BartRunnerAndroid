package com.dougkeen.bart.activities

import android.os.Bundle
import androidx.activity.compose.setContent
import com.dougkeen.bart.ui.BartRunnerTheme
import com.dougkeen.bart.ui.SystemMapScreen

class ViewMapActivity : AbstractViewActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BartRunnerTheme { SystemMapScreen(onBack = { finish() }) } }
    }
}
