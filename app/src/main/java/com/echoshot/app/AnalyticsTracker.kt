package com.echoshot.app

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

object AnalyticsTracker {
    fun log(context: Context, event: String, vararg parameters: Pair<String, String>) {
        val bundle = Bundle().apply {
            parameters.forEach { (key, value) -> putString(key, value) }
        }
        FirebaseAnalytics.getInstance(context.applicationContext).logEvent(event, bundle)
    }
}
