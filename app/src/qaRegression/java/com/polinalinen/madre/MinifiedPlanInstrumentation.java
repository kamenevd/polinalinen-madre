package com.polinalinen.madre;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

/** Public Android instrumentation API only: no test runner dependency on app libraries
 * that R8 legitimately removed. Same certificate is required by Android for this target.
 * A PASS marker is emitted only after all actual persistence assertions complete.
 */
public final class MinifiedPlanInstrumentation extends Instrumentation {
    private Bundle arguments;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        arguments=args==null?new Bundle():new Bundle(args);
        start();
    }

    @Override public void onStart() {
        super.onStart();
        Bundle result=new Bundle();
        result.putString("phase",arguments.getString("phase"));
        result.putInt("pid",Process.myPid());
        try {
            MinifiedPlanRegressionTest.verify(this,arguments);
            result.putString("minifiedPersistence","PASS");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable failure) {
            Log.e("MinifiedPlanInstrumentation","Actual persistence verification failed",failure);
            result.putString("minifiedPersistence","FAIL");
            result.putString("stack",Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED,result);
        }
    }
}
