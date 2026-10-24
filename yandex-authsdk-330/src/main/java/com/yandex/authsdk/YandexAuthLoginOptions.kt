package com.yandex.authsdk

import android.os.Parcelable
import com.yandex.authsdk.internal.strategy.LoginType
import kotlinx.parcelize.Parcelize

@Parcelize
data class YandexAuthLoginOptions(
    val loginType: LoginType = LoginType.NATIVE,
    val clientId: String? = null,
) : Parcelable {
    constructor(loginType: LoginType = LoginType.NATIVE) : this(loginType, null)
}
