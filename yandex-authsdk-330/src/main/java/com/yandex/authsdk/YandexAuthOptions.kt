package com.yandex.authsdk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Parcel
import android.os.Parcelable
import android.text.TextUtils
import com.yandex.authsdk.internal.Constants
import com.yandex.authsdk.internal.getApplicationInfo

class YandexAuthOptions : Parcelable {
    val clientId: String
    val isLoggingEnabled: Boolean
    val oauthHost: String
    /** Skip Chrome Custom Tabs and use WebView for web authorization. Native login keeps its priority. */
    val skipChromeTab: Boolean

    @JvmOverloads
    constructor(context: Context, loggingEnabled: Boolean = false) :
        this(context, loggingEnabled, false)

    constructor(context: Context, loggingEnabled: Boolean = false, skipChromeTab: Boolean) {
        val app: ApplicationInfo = try {
            context.packageManager.getApplicationInfo(context.packageName,
                PackageManager.GET_META_DATA.toLong())
        } catch (e: PackageManager.NameNotFoundException) {
            throw RuntimeException(e)
        }

        val clientId = app.metaData.getString(Constants.META_CLIENT_ID)
        checkNotNull(clientId) {
            "Application should provide client id in gradle.properties"
        }

        this.clientId = clientId
        isLoggingEnabled = loggingEnabled
        oauthHost = app.metaData.getString(Constants.META_OAUTH_HOST)!!
        this.skipChromeTab = skipChromeTab
    }

    internal constructor(
        loggingEnabled: Boolean,
        clientId: String,
        oauthHost: String,
        skipChromeTab: Boolean = false,
    ) {
        this.isLoggingEnabled = loggingEnabled
        this.clientId = clientId
        this.oauthHost = oauthHost
        this.skipChromeTab = skipChromeTab
    }

    val isTesting: Boolean
        get() = !TextUtils.equals(oauthHost, Constants.HOST_PRODUCTION)

    protected constructor(`in`: Parcel) {
        clientId = `in`.readString()!!
        isLoggingEnabled = `in`.readByte().toInt() != 0
        oauthHost = `in`.readString()!!
        skipChromeTab = `in`.readByte().toInt() != 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(clientId)
        dest.writeByte((if (isLoggingEnabled) 1 else 0).toByte())
        dest.writeString(oauthHost)
        dest.writeByte((if (skipChromeTab) 1 else 0).toByte())
    }

    override fun describeContents(): Int {
        return 0
    }


    companion object CREATOR : Parcelable.Creator<YandexAuthOptions> {
        override fun createFromParcel(parcel: Parcel): YandexAuthOptions {
            return YandexAuthOptions(parcel)
        }

        override fun newArray(size: Int): Array<YandexAuthOptions?> {
            return arrayOfNulls(size)
        }
    }
}
