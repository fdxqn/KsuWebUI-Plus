package io.github.a13e300.ksuwebui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import io.github.a13e300.ksuwebui.databinding.ViewWebuiErrorBinding
import io.github.a13e300.ksuwebui.shortcut.ModuleIdValidator
import io.github.a13e300.ksuwebui.shortcut.ModuleLaunchContract

class ModuleEntryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val moduleId = intent.getStringExtra(ModuleLaunchContract.EXTRA_MODULE_ID)
            ?: intent.data?.let { uri ->
                if (uri.scheme == "ksuwebui" && uri.authority == "module") {
                    uri.lastPathSegment
                } else {
                    null
                }
            }
        if (!ModuleIdValidator.isValid(moduleId)) {
            showInvalidRequest()
            return
        }

        startActivity(
            ModuleLaunchContract.webUiIntent(this, moduleId!!, true)
                .setData(ModuleLaunchContract.documentUri(moduleId))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        )
        finish()
    }

    private fun showInvalidRequest() {
        val binding = ViewWebuiErrorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.errorTitle.setText(R.string.invalid_launch_request)
        binding.errorMessage.setText(R.string.invalid_launch_message)
        binding.errorPrimary.setText(R.string.close)
        binding.errorPrimary.setOnClickListener { finish() }
        binding.errorSecondary.visibility = View.GONE
    }
}
