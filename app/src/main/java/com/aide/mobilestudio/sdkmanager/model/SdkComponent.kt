package com.aide.mobilestudio.sdkmanager.model

/**
 * SDK Component model - Package metadata.
 */
data class SdkComponent(
    val id: String,
    val name: String,
    val type: String = "Tool",
    val version: String = "",
    val size: Long = 0,
    val isInstalled: Boolean = false,
    val description: String = "",
    val dependencies: List<String> = emptyList(),
    val channel: String = "Stable",
    val licenseId: String? = null,
    val archiveName: String? = null,
    val installPath: String? = null
)