package com.yandex.authsdk.internal.domesticroots.webview;

import android.net.http.SslError;
import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;

@AnyThread
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
public interface WebViewSslErrorHandler {

    boolean handleSslError(@NonNull SslError error,
                           @NonNull Callback callback);

    @MainThread
    interface Callback {
        void onProceeded();
        void onCanceled();
    }

}
