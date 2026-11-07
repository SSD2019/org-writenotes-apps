@file:Suppress("ktlint")

package com.journeyapps.barcodescanner

import androidx.activity.result.contract.ActivityResultContract
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

class ScanOptions {
    fun setDesiredBarcodeFormats(vararg formats: String): ScanOptions = this
    fun setPrompt(prompt: String): ScanOptions = this
    fun setBeepEnabled(enabled: Boolean): ScanOptions = this
    fun setOrientationLocked(locked: Boolean): ScanOptions = this

    companion object {
        const val QR_CODE = "QR_CODE"
    }
}

class ScanIntentResult(val contents: String?)

class ScanContract : ActivityResultContract<ScanOptions, ScanIntentResult>() {
    override fun executeDesktop(input: ScanOptions, callback: (ScanIntentResult) -> Unit) {
        SwingUtilities.invokeLater {
            val entered = JOptionPane.showInputDialog(
                null,
                "Paste org-writenotes:// pairing URI or ws:// URL from Emacs:",
                "Pair with Emacs",
                JOptionPane.PLAIN_MESSAGE
            )
            callback(ScanIntentResult(entered?.trim()?.takeIf { it.isNotEmpty() }))
        }
    }
}

