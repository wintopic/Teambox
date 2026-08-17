package com.danmukey.shared.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.danmukey.shared.db.DanmuKeyDatabase
import com.danmukey.shared.model.LocatorSpec
import com.danmukey.shared.visual.LocatorFallbackStage
import com.danmukey.shared.visual.fallbackStage
import com.danmukey.shared.visual.visualLocatorsInFallbackOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DevelopmentDTargetFixtureTest {
    @Test
    fun embeddedDevelopmentKeyVerifiesAndActivatesTheSignedFixture() {
        val text = checkNotNull(
            javaClass.classLoader.getResourceAsStream("targets/development-test-host-v1.dtarget"),
        ).bufferedReader().use { it.readText() }
        val verifier = EcdsaP256TargetRuleVerifier(DevelopmentTargetRuleTrust.publicKeys)
        val imported = DTargetCodec.decodeAndVerify(text, verifier, now = 1_786_766_500_000L)

        assertEquals(TargetRuleSignatureState.Verified, imported.signatureState)
        assertEquals(TargetRuleState.Observation, imported.initialState)

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DanmuKeyDatabase.Schema.create(driver)
        val repository = DanmuKeyRepository(DanmuKeyDatabase(driver))
        repository.importTargetRule(imported, TargetRuleSource.LocalImport, now = 1_786_766_500_000L)
        assertEquals(TargetRuleState.Observation, repository.loadTargetRuleRevisions().single().state)
        repository.activateTargetRule(
            ruleId = imported.envelope.payload.ruleId,
            revision = imported.envelope.payload.revision,
            now = 1_786_766_510_000L,
        )
        assertEquals(
            imported.envelope.payload.profile,
            repository.loadTargetProfiles().single(),
        )
        driver.close()
    }

    @Test
    fun signedV2RequiresObservationThenSupportsActivationAndRollback() {
        val verifier = EcdsaP256TargetRuleVerifier(DevelopmentTargetRuleTrust.publicKeys)
        val v1 = loadFixture("targets/development-test-host-v1.dtarget", verifier)
        val v2 = loadFixture("targets/development-test-host-v2.dtarget", verifier)
        assertEquals(2, v2.envelope.payload.revision)
        assertEquals(TargetRuleState.Observation, v2.initialState)

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DanmuKeyDatabase.Schema.create(driver)
        val repository = DanmuKeyRepository(DanmuKeyDatabase(driver))
        repository.importTargetRule(v1, TargetRuleSource.LocalImport, now = 1_786_766_500_000L)
        repository.activateTargetRule(v1.envelope.payload.ruleId, 1, now = 1_786_766_510_000L)
        repository.importTargetRule(v2, TargetRuleSource.LocalImport, now = 1_786_768_300_000L)

        assertTrue(repository.loadTargetRuleRevisions().any { it.revision == 1 && it.state == TargetRuleState.Active })
        assertTrue(repository.loadTargetRuleRevisions().any { it.revision == 2 && it.state == TargetRuleState.Observation })

        repository.activateTargetRule(v2.envelope.payload.ruleId, 2, now = 1_786_768_310_000L)
        assertEquals(2, repository.loadTargetProfiles().single().profileVersion)
        assertTrue(repository.loadTargetRuleRevisions().any { it.revision == 1 && it.state == TargetRuleState.Superseded })
        assertTrue(repository.loadTargetRuleRevisions().any { it.revision == 2 && it.state == TargetRuleState.Active })

        repository.rollbackTargetRule(v2.envelope.payload.ruleId, now = 1_786_768_320_000L)
        assertEquals(1, repository.loadTargetProfiles().single().profileVersion)
        assertTrue(repository.loadTargetRuleRevisions().any { it.revision == 1 && it.state == TargetRuleState.Active })
        assertTrue(repository.loadTargetRuleRevisions().any { it.revision == 2 && it.state == TargetRuleState.Superseded })
        driver.close()
    }

    @Test
    fun unsignedFixtureRemainsObservationOnly() {
        val imported = loadFixture(
            "targets/development-test-host-unsigned.dtarget",
            EcdsaP256TargetRuleVerifier(DevelopmentTargetRuleTrust.publicKeys),
        )
        assertEquals(TargetRuleSignatureState.Unsigned, imported.signatureState)
        assertEquals(TargetRuleState.ObservationOnly, imported.initialState)
    }

    @Test
    fun signedVisualFixtureContainsOnlyFixedOcrAndLocalTemplateLocators() {
        val imported = loadFixture(
            "targets/development-test-host-visual-v1.dtarget",
            EcdsaP256TargetRuleVerifier(DevelopmentTargetRuleTrust.publicKeys),
        )
        assertEquals(TargetRuleSignatureState.Verified, imported.signatureState)
        assertEquals(TargetRuleState.Observation, imported.initialState)
        assertTrue(TargetRuleAction.CaptureScreenRegion in imported.envelope.payload.allowedActions)

        val profile = imported.envelope.payload.profile
        listOf(
            profile.composerEntryLocators,
            profile.inputLocators,
            profile.submitLocators,
        ).forEach { locators ->
            assertTrue(locators.all { it is LocatorSpec.OcrText || it is LocatorSpec.LocalTemplate })
            assertEquals(
                listOf(LocatorFallbackStage.OcrText, LocatorFallbackStage.LocalTemplate),
                locators.visualLocatorsInFallbackOrder().map { it.fallbackStage },
            )
        }
    }

    private fun loadFixture(
        resource: String,
        verifier: TargetRuleSignatureVerifier,
    ): ImportedDTarget {
        val text = checkNotNull(javaClass.classLoader.getResourceAsStream(resource))
            .bufferedReader()
            .use { it.readText() }
        return DTargetCodec.decodeAndVerify(text, verifier, now = 1_786_768_500_000L)
    }
}
