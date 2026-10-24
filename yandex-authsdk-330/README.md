# Yandex Auth SDK 3.3.0 — narrowly corrected local source build

This is not an unmodified upstream binary and is not a newer SDK. It builds the
published `com.yandex.android:authsdk:3.3.0` sources. `provenance.json` records the
Maven sources/AAR SHA-256, original per-file hashes and intentional corrections.

## Security correction

`AuthSdkActivity` registers its **actual existing strategy contract** directly
with its own `ActivityResultRegistry`, without a LifecycleOwner. That callback
stays active at STOP instead of allowing a raw bearer Intent into pending saved
state. `loginType` is initialized before registration because a restored result
may be delivered synchronously. SDK strategy selection, package signature checks,
SSL handlers, public APIs and result parsing are unchanged.

`super.onSaveInstanceState` is deliberately preserved. There is no stripping of
saved-state keys, suppression of a registry check or fake verification result.
`YandexSdkActivityCustodyTest` exercises this real internal Activity, an actual
SDK WebView strategy contract (without launching WebView/network), late STOP
result, Parcel serialization and restored request registration. The outer
`YandexSdkResultBridgeTest` separately covers retained-owner/process-death fences.
Neither suite is live SDK/server/Huawei/Station acceptance.

## Build adaptation and license

The manifest package attribute becomes the AGP9 library namespace; all component,
permission and redirect declarations and resources stay upstream. The generated
upstream BuildConfig.java is retained with generation disabled. The local Gradle
project replaces only the pinned3.3.0 dependency and cannot publish anything.
Original sources/AAR are public Maven artifacts, not credentials.

Published3.3.0 POM and upstream copyright notice identify Apache License2.0.
LICENSE.txt, UPSTREAM-NOTICE.txt and NOTICE.txt retain attribution; copies are
packaged as application assets. The correction is explicitly marked in source.
`python3 scripts/verify_yandex_sdk_source.py` checks inventory/hash preservation;
that integrity check alone does not prove runtime token custody.
