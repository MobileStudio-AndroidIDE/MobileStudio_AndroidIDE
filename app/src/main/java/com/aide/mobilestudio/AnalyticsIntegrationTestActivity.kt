package com.aide.mobilestudio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aide.mobilestudio.analytics.AnalyticsCategory
import com.aide.mobilestudio.analytics.AnalyticsManager
import com.aide.mobilestudio.analytics.AnalyticsEvents

// ------------------------------------------------------------------
// App-wide intent launchers used by the integration test suite.
// These deliver a controlled set of PostHog-captured analytics events so the
// backend dashboard can be verified without requiring user interaction.
// ------------------------------------------------------------------
fun Context.launchAnalyticsIntegrationTest() {
    val intent = Intent(this, AnalyticsIntegrationTestActivity::class.java)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}

class AnalyticsIntegrationTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MobileStudioTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Palette.bg) {
                    AnalyticsIntegrationTestBody()
                }
            }
        }
    }
}

@Composable
internal fun AnalyticsIntegrationTestBody() {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Analytics Integration Test",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Text(
            "Sends a controlled event per consent category to verify event delivery " +
                "to the configured PostHog project. All categories are OFF by default; " +
                "enable the ones you want to verify in Settings > Privacy > Analytics.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        AnalyticsCategory.values().forEach { category ->
            Button(
                onClick = {
                    when (category) {
                        AnalyticsCategory.BASIC -> AnalyticsManager.capture(
                            AnalyticsEvents.USER_FIRST_OPENED,
                            AnalyticsCategory.BASIC,
                            mapOf("integration_test" to true),
                        )
                        AnalyticsCategory.USAGE -> AnalyticsManager.capture(
                            AnalyticsEvents.APP_SESSION_STARTED,
                            AnalyticsCategory.USAGE,
                            emptyMap(),
                        )
                        AnalyticsCategory.FEATURE -> AnalyticsManager.capture(
                            AnalyticsEvents.PROJECT_CREATED,
                            AnalyticsCategory.FEATURE,
                            emptyMap(),
                        )
                        AnalyticsCategory.BUILD -> AnalyticsManager.capture(
                            AnalyticsEvents.BUILD_COMPLETED,
                            AnalyticsCategory.BUILD,
                            mapOf(AnalyticsEvents.PROP_DURATION_MS to 1234L,
                                  AnalyticsEvents.PROP_SUCCESS to true),
                        )
                    }
                    AnalyticsManager.flush()
                    Toast.makeText(context,
                        "Sent ${category.label} test event", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Send ${category.label} event")
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = { AnalyticsManager.flush() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Flush PostHog queue") }
    }
}
