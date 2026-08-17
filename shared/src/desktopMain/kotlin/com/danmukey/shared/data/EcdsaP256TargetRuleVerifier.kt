package com.danmukey.shared.data

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

class EcdsaP256TargetRuleVerifier(
    private val publicKeys: Map<String, ByteArray>,
) : TargetRuleSignatureVerifier {
    override fun verify(keyId: String, message: ByteArray, signature: ByteArray): Boolean = runCatching {
        val encodedKey = publicKeys[keyId] ?: return false
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encodedKey))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(message)
            verify(signature)
        }
    }.getOrDefault(false)
}
