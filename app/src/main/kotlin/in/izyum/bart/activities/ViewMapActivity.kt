package `in`.izyum.bart.activities

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import `in`.izyum.bart.ui.BartRunnerTheme
import `in`.izyum.bart.ui.SystemMapScreen

class ViewMapActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BartRunnerTheme { SystemMapScreen(onBack = { finish() }) } }
    }
}
