package com.dougkeen.bart.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.dougkeen.bart.BuildConfig
import com.dougkeen.bart.R
import com.dougkeen.bart.ui.AboutScreen
import com.dougkeen.bart.ui.BartRunnerTheme
import com.google.android.gms.oss.licenses.v2.OssLicensesMenuActivity

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BartRunnerTheme {
                AboutScreen(
                    versionName = BuildConfig.VERSION_NAME,
                    gitBuildHash = BuildConfig.GIT_BUILD_HASH,
                    onBack = { finish() },
                    onOpenGithub = {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(getString(R.string.github_url)),
                            )
                        )
                    },
                    onOpenApacheLicense = {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(getString(R.string.apache_license_url)),
                            )
                        )
                    },
                    onOpenLicenses = {
                        OssLicensesMenuActivity.setActivityTitle(
                            getString(R.string.open_source_licenses),
                        )
                        startActivity(Intent(this, OssLicensesMenuActivity::class.java))
                    },
                    onFeedback = {
                        val subject = getString(R.string.feedback_subject)
                        val body = getString(
                            R.string.feedback_body,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.GIT_BUILD_HASH,
                            Build.VERSION.RELEASE,
                            Build.VERSION.SDK_INT,
                            Build.MODEL,
                        )
                        startActivity(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_EMAIL,
                                    arrayOf(getString(R.string.feedback_email)),
                                )
                                putExtra(
                                    Intent.EXTRA_SUBJECT,
                                    subject,
                                )
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    body,
                                )
                            },
                        )
                    },
                )
            }
        }
    }
}
