package com.arthatis.wallet

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import com.arthatis.wallet.ui.ArthatisApp

/** AppCompatActivity (a FragmentActivity) so BiometricPrompt works for approving web sessions. */
class MainActivity : AppCompatActivity() {
    private val vm: AppViewModel by viewModels()

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val name = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "backup"
        val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes == null) vm.say(vm.t("File tidak bisa dibaca.", "Couldn't read that file.")) else vm.stageFile(name, bytes)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ArthatisApp(vm) { pick.launch(arrayOf("*/*")) } }
    }

    override fun onStart() {
        super.onStart()
        vm.runSync()
    }
}
