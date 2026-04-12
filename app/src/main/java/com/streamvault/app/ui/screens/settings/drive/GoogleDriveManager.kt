package com.streamvault.app.ui.screens.settings.drive

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

class GoogleDriveManager(private val context: Context) {

    private val httpClient = OkHttpClient.Builder().build()
    private val scopeAppData = "https://www.googleapis.com/auth/drive.appdata"
    
    fun getSignInOptions(): GoogleSignInOptions {
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(scopeAppData))
            .build()
    }
    
    suspend fun getAuthToken(account: GoogleSignInAccount): String? = withContext(Dispatchers.IO) {
        try {
            val androidAccount = account.account ?: return@withContext null
            com.google.android.gms.auth.GoogleAuthUtil.getToken(context, androidAccount, "oauth2:$scopeAppData")
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun findBackupFileId(authToken: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=name='streamvault_backup.json'")
                .addHeader("Authorization", "Bearer $authToken")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val json = JSONObject(response.body?.string() ?: "")
                val files = json.optJSONArray("files")
                if (files != null && files.length() > 0) {
                    return@withContext files.getJSONObject(0).getString("id")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    suspend fun uploadBackup(authToken: String, file: File): Boolean = withContext(Dispatchers.IO) {
        val fileId = findBackupFileId(authToken)
        val url = if (fileId != null) {
            "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=multipart"
        } else {
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
        }
        
        val boundary = "BackupBoundary12345"
        val metadata = JSONObject().apply {
            put("name", "streamvault_backup.json")
            if (fileId == null) {
                put("parents", org.json.JSONArray().put("appDataFolder"))
            }
        }.toString()
        
        val bodyBuilder = StringBuilder()
        bodyBuilder.append("--$boundary\r\n")
        bodyBuilder.append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
        bodyBuilder.append(metadata)
        bodyBuilder.append("\r\n--$boundary\r\n")
        bodyBuilder.append("Content-Type: application/json\r\n\r\n")
        bodyBuilder.append(file.readText())
        bodyBuilder.append("\r\n--$boundary--")

        val requestBody = bodyBuilder.toString().toRequestBody("multipart/related; boundary=$boundary".toMediaType())

        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $authToken")
            
        if (fileId != null) {
            requestBuilder.patch(requestBody)
        } else {
            requestBuilder.post(requestBody)
        }

        try {
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun downloadBackup(authToken: String, destFile: File): Boolean = withContext(Dispatchers.IO) {
        val fileId = findBackupFileId(authToken) ?: return@withContext false
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .addHeader("Authorization", "Bearer $authToken")
            .get()
            .build()
            
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                response.body?.byteStream()?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
