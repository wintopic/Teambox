package com.danmukey.shared.data

import com.danmukey.shared.model.LocatorSpec
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DTargetCodecTest {
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }
    private val verifier = EcdsaP256TargetRuleVerifier(
        mapOf(KEY_ID to keyPair.public.encoded),
    )

    @Test
    fun signedRuleVerifiesAndAlwaysStartsInObservationMode() {
        val encoded = signed(payload())
        val imported = DTargetCodec.decodeAndVerify(encoded, verifier, now = 2_000L)

        assertEquals(TargetRuleSignatureState.Verified, imported.signatureState)
        assertEquals(TargetRuleState.Observation, imported.initialState)
        assertEquals("builtin-test-host", imported.envelope.payload.ruleId)
    }

    @Test
    fun tamperingWithSignedPayloadFailsVerification() {
        val encoded = signed(payload())
        val tampered = encoded.replace("怪团建测试宿主", "被篡改的目标")

        assertFailsWith<IllegalArgumentException> {
            DTargetCodec.decodeAndVerify(tampered, verifier, now = 2_000L)
        }
    }

    @Test
    fun unknownOrScriptFieldsAreRejectedBeforeImport() {
        val encoded = DTargetCodec.encodeUnsigned(payload())
        val withScript = encoded.replace(
            "\"ruleId\":",
            "\"script\":\"click(1,1)\",\"ruleId\":",
        )

        assertFailsWith<Exception> {
            DTargetCodec.decodeAndVerify(withScript, RejectAllTargetRuleSignatures, now = 2_000L)
        }
    }

    @Test
    fun unsignedRuleCanOnlyEnterObservationOnlyState() {
        val encoded = DTargetCodec.encodeUnsigned(payload())
        val imported = DTargetCodec.decodeAndVerify(encoded, RejectAllTargetRuleSignatures, now = 2_000L)

        assertEquals(TargetRuleSignatureState.Unsigned, imported.signatureState)
        assertEquals(TargetRuleState.ObservationOnly, imported.initialState)
    }

    @Test
    fun fixedCalibrationCoordinatesAreRejected() {
        val invalid = payload().let { payload ->
            payload.copy(
                profile = payload.profile.copy(
                    inputLocators = listOf(LocatorSpec.CalibrationPoint(0.5f, 0.5f)),
                ),
            )
        }

        assertFailsWith<IllegalStateException> { DTargetCodec.encodeUnsigned(invalid) }
    }

    @Test
    fun missingActionDeclarationAndExpiredRulesAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            DTargetCodec.encodeUnsigned(
                payload().copy(allowedActions = listOf(TargetRuleAction.ObserveAccessibility)),
            )
        }
        val expired = DTargetCodec.encodeUnsigned(payload().copy(expiresAt = 1_500L))
        assertFailsWith<IllegalArgumentException> {
            DTargetCodec.decodeAndVerify(expired, RejectAllTargetRuleSignatures, now = 2_000L)
        }
    }

    private fun signed(payload: DTargetPayload): String = DTargetCodec.encodeSigned(
        payload = payload,
        keyId = KEY_ID,
        signer = TargetRuleSignatureSigner { message ->
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(message)
                sign()
            }
        },
    )

    private fun payload(): DTargetPayload = DTargetPayload(
        ruleId = SampleTargets.testHost.id,
        revision = SampleTargets.testHost.profileVersion,
        issuedAt = 1_000L,
        expiresAt = 5_000L,
        profile = SampleTargets.testHost,
        allowedActions = listOf(
            TargetRuleAction.ObserveAccessibility,
            TargetRuleAction.OpenComposer,
            TargetRuleAction.FocusInput,
            TargetRuleAction.SetText,
            TargetRuleAction.ClickSubmit,
            TargetRuleAction.ReadEpisodeTitle,
            TargetRuleAction.ReadPlaybackTime,
        ),
    )

    companion object {
        private const val KEY_ID = "test-key-v1"
    }
}
