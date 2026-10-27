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
            if(arguments.getString("phase","").startsWith("delivery-") || arguments.getString("phase","").startsWith("exact-timing-")) {
                verifyDeliveryFixture(arguments.getString("phase"));
            } else {
                MinifiedPlanRegressionTest.verify(this,arguments);
            }
            result.putString("minifiedPersistence","PASS");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable failure) {
            Log.e("MinifiedPlanInstrumentation","Actual persistence verification failed",failure);
            result.putString("minifiedPersistence","FAIL");
            result.putString("stack",Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED,result);
        }
    }

    /** SDK/JSON only: operates on ordinary delivery without the kept test ABI.
     * Production reads/writes between phases are driven through the real UI.
     */
    private void verifyDeliveryFixture(String phase) throws Exception {
        android.content.Context context=getTargetContext();
        boolean debuggable=(context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0;
        boolean timing=phase.startsWith("exact-timing-");
        if(timing) {
            if(!java.util.Arrays.asList("exact-timing-seed-15","exact-timing-check-15","exact-timing-check-16").contains(phase))
                throw new AssertionError("Unknown exact timing phase");
            boolean before=phase.endsWith("-15");
            long version=context.getPackageManager().getPackageInfo(context.getPackageName(),0).getLongVersionCode();
            if(version!=(before?51L:52L) || debuggable!=before)
                throw new AssertionError("Frozen exact timing binary identity/flags mismatch");
        } else if(debuggable) throw new AssertionError("Delivery target must be non-debuggable");
        android.content.SharedPreferences prefs=context.getSharedPreferences("madre_prefs",0);
        String legacy="[{\"recipeId\":\"ciabatta\",\"portions\":2,\"startAtMillis\":2000000000000},{\"recipeId\":\"home_bread\",\"portions\":3,\"startAtMillis\":2000000060000}]";
        if("delivery-seed".equals(phase) || "exact-timing-seed-15".equals(phase)) {
            if(!prefs.edit().putString("planned_bakes",legacy).putString("qa_persistence_fixture","legacy-15-to-delivery-16").commit())
                throw new AssertionError("Synthetic CI fixture commit failed");
        } else if(timing || "delivery-check".equals(phase)) {
            org.json.JSONArray plans=new org.json.JSONArray(prefs.getString("planned_bakes","[]"));
            if(plans.length()!=(timing?2:3))throw new AssertionError("Unexpected legacy fixture plan count");
            java.util.Set<String> ids=new java.util.HashSet<>();
            java.util.Set<Integer> numbers=new java.util.HashSet<>();
            for(int i=0;i<plans.length();i++) {
                org.json.JSONObject plan=plans.getJSONObject(i);String id=plan.getString("recipeId");ids.add(id);
                if(!timing) {
                    int number=plan.getInt("number");
                    if(number<1 || number>9 || !numbers.add(number))throw new AssertionError("Real plan numbers must be unique in 1..9");
                }
                if("ciabatta".equals(id) && (plan.getInt("portions")!=2 || plan.getLong("startAtMillis")!=2000000000000L))throw new AssertionError("Legacy ciabatta changed");
                if("home_bread".equals(id) && (plan.getInt("portions")!=3 || plan.getLong("startAtMillis")!=2000000060000L))throw new AssertionError("Legacy home bread changed");
            }
            java.util.Set<String> expected=new java.util.HashSet<>(java.util.Arrays.asList("ciabatta","home_bread"));
            if(!timing)expected.add("family_bread");
            if(!ids.equals(expected))throw new AssertionError("Unexpected actual plans");
            if(!"legacy-15-to-delivery-16".equals(prefs.getString("qa_persistence_fixture",null)))throw new AssertionError("Unrelated pref changed");
        } else throw new AssertionError("Unknown delivery phase");
        java.io.File out=new java.io.File(context.getExternalFilesDir(null),"minified-delivery");
        if(!out.isDirectory() && !out.mkdirs())throw new AssertionError("Missing raw output directory");
        try(java.io.FileOutputStream stream=new java.io.FileOutputStream(new java.io.File(out,phase+"-prefs.json"))) {
            stream.write(prefs.getString("planned_bakes","[]").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        try(java.io.FileOutputStream stream=new java.io.FileOutputStream(new java.io.File(out,phase+"-result.txt"))) {
            stream.write(("phase="+phase+"; pid="+Process.myPid()+"; debuggable="+debuggable+"; app-ABI-called=false; assertions=PASS\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
