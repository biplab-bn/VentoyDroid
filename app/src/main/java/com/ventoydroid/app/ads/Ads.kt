package com.ventoydroid.app.ads

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * AdMob wiring for VentoyDroid. Uses Google's official TEST ad IDs — they
 * only deliver demo ads and generate no revenue; swap in real AdMob console
 * IDs (one line each) before a public release. Using real IDs in a debug
 * build risks an AdMob account ban, so don't.
 *
 * Every entry point is fail-silent: the install flow must never break
 * because an ad failed to load or the SDK threw.
 */
object Ads {

    // Single source of truth for ad IDs. Flip useRealIds after creating the
    // real units in the AdMob console AND updating APPLICATION_ID in the
    // manifest — never ship real IDs in debug builds (account-ban risk).
    private const val USE_REAL_IDS = false
    private val BANNER_UNIT_ID =
        if (USE_REAL_IDS) "REPLACE_WITH_REAL_BANNER_UNIT_ID"
        else "ca-app-pub-3940256099942544/6300978111" // Google test banner
    private val INTERSTITIAL_UNIT_ID =
        if (USE_REAL_IDS) "REPLACE_WITH_REAL_INTERSTITIAL_UNIT_ID"
        else "ca-app-pub-3940256099942544/1033173712" // Google test interstitial

    @Volatile private var initialized = false
    private var interstitial: InterstitialAd? = null

    @Volatile private var interstitialLoading = false
    private lateinit var consentInformation: ConsentInformation

    /**
     * Idempotent, safe to call from Application.onCreate. Off-main-thread init.
     * Consent is gathered separately via [gatherConsent] (needs an Activity).
     */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        // Google recommends background-thread initialization.
        Thread {
            runCatching { MobileAds.initialize(context) }
        }.start()
    }

    /**
     * UMP consent: required for EEA/UK users before any ad request; shows
     * nothing where consent isn't needed. Fail-silent — ads simply won't
     * load if consent errors out. Call from the Activity on first launch.
     */
    fun gatherConsent(activity: Activity, onDone: () -> Unit = {}) {
        runCatching {
            consentInformation = UserMessagingPlatform.getConsentInformation(activity)
            consentInformation.requestConsentInfoUpdate(
                activity,
                ConsentRequestParameters.Builder().build(),
                {
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                        onDone()
                    }
                },
                { _ -> onDone() },
            )
        }.onFailure { onDone() }
    }

    // ---- banner ----

    /** Creates a banner inside [container]; no-op on any failure. */
    fun attachBanner(activity: Activity, container: ViewGroup) {
        runCatching {
            val adView = AdView(activity).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = BANNER_UNIT_ID
            }
            container.addView(adView)
            adView.loadAd(AdRequest.Builder().build())
        }
    }

    // ---- interstitial ----

    /** Preloads an interstitial so it's ready by the time an install succeeds. */
    fun preloadInterstitial(activity: Activity) {
        if (interstitial != null || interstitialLoading) return
        interstitialLoading = true
        runCatching {
            InterstitialAd.load(
                activity, INTERSTITIAL_UNIT_ID, AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        interstitial = ad
                        interstitialLoading = false
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        interstitial = null
                        interstitialLoading = false
                    }
                },
            )
        }.onFailure {
            interstitialLoading = false
        }
    }

    /** Shows the interstitial if one is ready; preloads the next one after. */
    fun maybeShowInterstitial(activity: Activity) {
        val ad = interstitial ?: return
        interstitial = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                preloadInterstitial(activity)
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                interstitial = null
            }
        }
        runCatching { ad.show(activity) }
    }
}
