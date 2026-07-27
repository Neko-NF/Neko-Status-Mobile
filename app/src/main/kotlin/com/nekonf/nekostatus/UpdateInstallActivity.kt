package com.nekonf.nekostatus

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

open class UpdateInstallActivity : Activity() {
    private var waitingForPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        waitingForPermission = savedInstanceState?.getBoolean(STATE_WAITING_FOR_PERMISSION) == true
        if (!waitingForPermission) continueInstall(allowPermissionPrompt = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_WAITING_FOR_PERMISSION, waitingForPermission)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_UNKNOWN_SOURCE_PERMISSION) return
        waitingForPermission = false
        continueInstall(allowPermissionPrompt = false)
    }

    private fun continueInstall(allowPermissionPrompt: Boolean) {
        when (installReadyUpdate()) {
            UpdateInstallResult.INSTALLER_STARTED -> finish()
            UpdateInstallResult.PERMISSION_REQUIRED -> {
                if (allowPermissionPrompt && launchUnknownSourceSettings()) {
                    waitingForPermission = true
                } else {
                    finishWithMessage(R.string.update_install_permission_required)
                }
            }
            UpdateInstallResult.NOT_READY -> finishWithMessage(R.string.update_install_not_ready)
            UpdateInstallResult.INSTALLER_UNAVAILABLE ->
                finishWithMessage(R.string.update_install_unavailable)
        }
    }

    internal open fun installReadyUpdate(): UpdateInstallResult = UpdateManager.installReadyUpdate(this)

    @Suppress("DEPRECATION")
    private fun launchUnknownSourceSettings(): Boolean =
        launchFirstSupportedIntent(UpdateManager.unknownSourceSettingsIntents(this)) { intent ->
            startActivityForResult(intent, REQUEST_UNKNOWN_SOURCE_PERMISSION)
        }

    private fun finishWithMessage(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        fun launch(context: Context) {
            val intent =
                Intent(context, UpdateInstallActivity::class.java).apply {
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            if (!launchFirstSupportedIntent(listOf(intent), context::startActivity)) {
                Toast.makeText(context, R.string.update_install_unavailable, Toast.LENGTH_LONG).show()
            }
        }

        private const val REQUEST_UNKNOWN_SOURCE_PERMISSION = 5101
        private const val STATE_WAITING_FOR_PERMISSION = "waiting-for-permission"
    }
}
