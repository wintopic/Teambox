package com.danmukey.app

import com.danmukey.shared.data.DevelopmentTargetRuleTrust
import com.danmukey.shared.data.EcdsaP256TargetRuleVerifier
import com.danmukey.shared.data.TargetRuleSignatureVerifier

internal fun createTargetRuleVerifier(): TargetRuleSignatureVerifier =
    EcdsaP256TargetRuleVerifier(DevelopmentTargetRuleTrust.publicKeys)
