package com.danmukey.app

import com.danmukey.shared.data.EcdsaP256TargetRuleVerifier
import com.danmukey.shared.data.RejectAllTargetRuleSignatures
import com.danmukey.shared.data.TargetRuleSignatureVerifier

internal fun createTargetRuleVerifier(): TargetRuleSignatureVerifier {
    val keyId = BuildConfig.TARGET_RULE_KEY_ID
    val publicKeyHex = BuildConfig.TARGET_RULE_PUBLIC_KEY_X509_HEX
    if (keyId.isBlank() || publicKeyHex.isBlank()) return RejectAllTargetRuleSignatures
    return EcdsaP256TargetRuleVerifier(mapOf(keyId to publicKeyHex.hexToByteArray()))
}

private fun String.hexToByteArray(): ByteArray = ByteArray(length / 2) { index ->
    substring(index * 2, index * 2 + 2).toInt(16).toByte()
}
