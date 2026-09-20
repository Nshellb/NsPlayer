package com.nshell.nsplayer.ads

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform

class AdsConsentManager(context: Context) {
    private val consentInformation =
        UserMessagingPlatform.getConsentInformation(context.applicationContext)

    val isPrivacyOptionsRequired: Boolean
        get() = consentInformation.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    fun gatherConsent(activity: Activity, onAdsAvailabilityChanged: (Boolean) -> Unit) {
        val parameters = ConsentRequestParameters.Builder().build()
        consentInformation.requestConsentInfoUpdate(
            activity,
            parameters,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    onAdsAvailabilityChanged(consentInformation.canRequestAds())
                }
            },
            {
                // A previous valid choice can still allow ads when the refresh fails.
                onAdsAvailabilityChanged(consentInformation.canRequestAds())
            }
        )
    }

    fun showPrivacyOptions(activity: Activity, onDismissed: (FormError?) -> Unit) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity, onDismissed)
    }
}
