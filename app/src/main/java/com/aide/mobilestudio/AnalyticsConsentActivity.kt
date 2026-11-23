package com.aide.mobilestudio

import android.os.Bundle
import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aide.mobilestudio.analytics.AnalyticsCategory
import com.aide.mobilestudio.analytics.AnalyticsConsent

/**
 * First-run English analytics consent screen.
 *
 * Shows the four MobileStudio analytics categories, each individually
 * allow/deny-able, all defaulting to OFF. Users can either toggle categories
 * and press "Save Preferences & Continue", or press "Decline All".
 *
 * Declining never blocks app functionality - all features remain usable.
 */
class AnalyticsConsentActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val langCode = AppLanguage.current(newBase).code
        val locale = java.util.Locale(langCode)
        val res = newBase.resources
        val config = Configuration(res.configuration)
        config.setLocale(locale)
        val newContext = newBase.createConfigurationContext(config)
        super.attachBaseContext(newContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val consent = AnalyticsConsent(this)
        setContent {
            MobileStudioTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Palette.bg) {
                    ConsentScreen(consent, onFinish = { finish() })
                }
            }
        }
    }
}

@Composable
private fun ConsentScreen(
    consent: AnalyticsConsent,
    onFinish: () -> Unit,
) {
    var enabled by remember { mutableStateOf<Map<AnalyticsCategory, Boolean>>(consent.snapshot()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "MobileStudio Analytics",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            text = "Help us understand how MobileStudio is used. You can allow or decline each category - all are OFF by default, and declining never disables any feature.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 24.dp),
        )

        Text(
            text = "Data we collect",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        AnalyticsCategory.values().forEach { category ->
            ConsentCard(
                category = category,
                checked = enabled[category] ?: false,
                onToggle = { value ->
                    enabled = enabled + (category to value)
                    consent.setEnabled(category, value)
                },
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                AnalyticsCategory.values().forEach { category ->
                    consent.setEnabled(category, false)
                }
                consent.markAccepted()
                onFinish()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Decline All")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                AnalyticsCategory.values().forEach { category ->
                    consent.setEnabled(category, enabled[category] ?: false)
                }
                consent.markAccepted()
                onFinish()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save Preferences & Continue")
        }
    }
}

@Composable
private fun ConsentCard(
    category: AnalyticsCategory,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = category.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Checkbox(
                checked = checked,
                onCheckedChange = onToggle,
            )
        }
    }
}
