package com.aide.mobilestudio.sdkmanager.license

import android.content.Context
import android.util.Log
import java.io.File

/**
 * SDK License Manager - Auto-accept licenses, manage licenses/ directory
 */
class SdkLicenseManager {
    companion object {
        // Known license hashes (SHA-1)
        private val LICENSES = mapOf(
            "android-sdk-license" to listOf(
                "24333f8a63b6825ea9c5514f83c2829b004d1fee",
                "d56f5187479451eabf01fb78af6dfcb131a6481e",
                "8933bad161af4178b1185d1a37fbf41ea5269c55"
            ),
            "android-sdk-preview-license" to listOf(
                "84831b9409646a918e30573bab4c9c91346d8abd"
            ),
            "google-gdk-license" to listOf(
                "72fa1b8c4f874d4bc659c1f1e4f3b2d4e5f6a7b8"
            ),
            "intel-android-extra-license" to listOf(
                "d975f751698a77b2d2f6a4e8c9d1b2e3f4a5b6c7"
            ),
            "android-sdk-license-arm-dbt" to listOf(
                "e6a9f8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1"
            )
        )

        fun acceptAllLicenses(sdkDir: File): Boolean {
            val licensesDir = File(sdkDir, "licenses")
            licensesDir.mkdirs()
            
            var allSuccess = true
            LICENSES.forEach { (licenseFile, hashes) ->
                val file = File(licensesDir, licenseFile)
                try {
                    file.writeText(hashes.joinToString("\n", postfix = "\n"))
                    file.setReadable(true, false)
                } catch (e: Exception) {
                    Log.e("SdkLicenseManager", "Failed to write license $licenseFile", e)
                    allSuccess = false
                }
            }
            
            return allSuccess
        }

        fun acceptSpecificLicenses(sdkDir: File, licenseIds: List<String>): Boolean {
            val licensesDir = File(sdkDir, "licenses")
            licensesDir.mkdirs()
            
            var allSuccess = true
            licenseIds.forEach { licenseId ->
                val hashes = LICENSES[licenseId] ?: return@forEach
                val file = File(licensesDir, licenseId)
                try {
                    file.writeText(hashes.joinToString("\n", postfix = "\n"))
                    file.setReadable(true, false)
                } catch (e: Exception) {
                    Log.e("SdkLicenseManager", "Failed to write license $licenseId", e)
                    allSuccess = false
                }
            }
            
            return allSuccess
        }

        fun hasLicense(sdkDir: File, licenseId: String): Boolean {
            val file = File(sdkDir, "licenses/$licenseId")
            return file.exists() && file.length() > 0
        }

        fun getAcceptedLicenses(sdkDir: File): List<String> {
            val licensesDir = File(sdkDir, "licenses")
            if (!licensesDir.exists()) return emptyList()
            return licensesDir.listFiles()
                ?.filter { it.isFile && it.length() > 0 }
                ?.map { it.name }
                ?: emptyList()
        }

        fun removeLicense(sdkDir: File, licenseId: String): Boolean {
            val file = File(sdkDir, "licenses/$licenseId")
            return file.delete()
        }

        fun clearAllLicenses(sdkDir: File): Boolean {
            val licensesDir = File(sdkDir, "licenses")
            if (!licensesDir.exists()) return true
            var success = true
            licensesDir.listFiles()?.forEach { file ->
                if (!file.delete()) success = false
            }
            return success
        }

        fun exportLicenses(sdkDir: File, targetDir: File): Boolean {
            val sourceDir = File(sdkDir, "licenses")
            if (!sourceDir.exists()) return false
            targetDir.mkdirs()
            var success = true
            sourceDir.listFiles()?.forEach { file ->
                val target = File(targetDir, file.name)
                try {
                    file.copyTo(target, overwrite = true)
                } catch (e: Exception) {
                    success = false
                }
            }
            return success
        }

        fun importLicenses(sdkDir: File, sourceDir: File): Boolean {
            val targetDir = File(sdkDir, "licenses")
            targetDir.mkdirs()
            var success = true
            sourceDir.listFiles()?.forEach { file ->
                val target = File(targetDir, file.name)
                try {
                    file.copyTo(target, overwrite = true)
                    target.setReadable(true, false)
                } catch (e: Exception) {
                    success = false
                }
            }
            return success
        }
    }
}