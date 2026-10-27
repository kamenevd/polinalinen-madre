package com.yandex.authsdk.internal

import okhttp3.OkHttpClient

internal object Components {

    val okHttpClient: OkHttpClient by lazy { OkHttpClient() }
}
