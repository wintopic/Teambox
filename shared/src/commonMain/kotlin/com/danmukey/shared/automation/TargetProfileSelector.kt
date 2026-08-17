package com.danmukey.shared.automation

import com.danmukey.shared.model.Orientation
import com.danmukey.shared.model.TargetProfile

data class TargetRuntimeContext(
    val appIdentifier: String,
    val orientation: Orientation,
    val systemApi: Int,
    val appVersionCode: Long? = null,
)

object TargetProfileSelector {
    fun select(
        profiles: List<TargetProfile>,
        context: TargetRuntimeContext,
    ): TargetProfile? = profiles
        .asSequence()
        .filter { profile -> context.appIdentifier in profile.appIdentifiers }
        .filter { profile -> context.orientation in profile.orientations }
        .filter { profile -> profile.minSystemApi?.let { context.systemApi >= it } ?: true }
        .filter { profile -> profile.maxSystemApi?.let { context.systemApi <= it } ?: true }
        .filter { profile ->
            val appVersionCode = context.appVersionCode
            appVersionCode == null ||
                (profile.minAppVersionCode?.let { appVersionCode >= it } ?: true)
        }
        .filter { profile ->
            val appVersionCode = context.appVersionCode
            appVersionCode == null ||
                (profile.maxAppVersionCode?.let { appVersionCode <= it } ?: true)
        }
        .maxWithOrNull(
            compareBy<TargetProfile> { it.capabilityLevel.ordinal }
                .thenBy(TargetProfile::profileVersion)
                .thenBy(TargetProfile::id),
        )
}
