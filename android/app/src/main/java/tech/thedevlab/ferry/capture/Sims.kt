package tech.thedevlab.ferry.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import tech.thedevlab.ferry.granted
import tech.thedevlab.ferry.model.Sim

object Sims {
    @SuppressLint("MissingPermission")
    fun active(context: Context): List<SubscriptionInfo> {
        if (!context.granted(Manifest.permission.READ_PHONE_STATE)) return emptyList()
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return sm.activeSubscriptionInfoList.orEmpty().sortedBy { it.simSlotIndex }
    }

    /** The subscription a telephony broadcast belongs to; extra names differ across Android versions and OEMs. */
    fun subscriptionId(intent: Intent): Int? =
        listOf(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, "subscription", "subscription_id", "android.telephony.extra.SUBSCRIPTION_INDEX")
            .firstNotNullOfOrNull { key -> intent.getIntExtra(key, -1).takeIf { it >= 0 } }

    @SuppressLint("MissingPermission")
    fun resolve(context: Context, subscriptionId: Int?): Sim? {
        val subs = active(context)
        val info = subs.find { it.subscriptionId == subscriptionId } ?: subs.singleOrNull() ?: return null
        return Sim(
            slot = info.simSlotIndex,
            subscriptionId = info.subscriptionId,
            carrier = info.carrierName?.toString()?.takeIf { it.isNotBlank() },
            number = number(context, info),
        )
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun number(context: Context, info: SubscriptionInfo): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            if (!context.granted(Manifest.permission.READ_PHONE_NUMBERS)) return null
            context.getSystemService(SubscriptionManager::class.java).getPhoneNumber(info.subscriptionId)
        } else {
            @Suppress("DEPRECATION")
            info.number
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

object Contacts {
    fun name(context: Context, number: String?): String? {
        if (number.isNullOrBlank() || !context.granted(Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
    }
}
