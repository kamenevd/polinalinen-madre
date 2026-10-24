package com.polinalinen.madre;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.polinalinen.madre.navigation.BookPreferences;
import com.polinalinen.madre.navigation.PlannedBake;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/** Actual non-debuggable R8 QA; only the isolated synthetic preference namespace.
 * Java/JDK/Android assertions deliberately do not call optimized-away app Kotlin/Gson APIs.
 * Write and restart execute in separate target app processes controlled by the host.
 */
@RunWith(AndroidJUnit4.class)
public class MinifiedPlanRegressionTest {
    private static final String NAMESPACE="qa_minified_plan_regression";

    @Test public void legacyPlansAndFailedReads() throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Context context=instrumentation.getTargetContext();
        Bundle args=InstrumentationRegistry.getArguments();
        boolean negative="true".equals(args.getString("negative"));
        String phase=args.getString("phase");
        assertNotNull(phase);
        assertEquals("com.polinalinen.madre.qa",context.getPackageName());
        assertEquals(0,context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE);
        SharedPreferences raw=context.getSharedPreferences(NAMESPACE,Context.MODE_PRIVATE);
        PlannedBake old=new PlannedBake("ciabatta",2,2_000_000_000_000L,0);
        PlannedBake second=new PlannedBake("home_bread",3,2_000_000_060_000L,0);
        PlannedBake added=new PlannedBake("waffles",4,2_000_000_120_000L,3);
        List<PlannedBake> empty=Collections.emptyList();
        List<PlannedBake> expected=Arrays.asList(old,second,added);
        String legacy="[{\"recipeId\":\"ciabatta\",\"portions\":2,\"startAtMillis\":2000000000000},{\"recipeId\":\"home_bread\",\"portions\":3,\"startAtMillis\":2000000060000}]";
        if("write".equals(phase)) {
            assertTrue(raw.edit().clear().putString("planned_bakes",legacy).putString("unrelated","keep").commit());
            try(BookPreferences book=new BookPreferences(context,NAMESPACE)) {
                assertEquals(negative?empty:Arrays.asList(old,second),book.snapshotPlans());
                assertTrue(book.putPlan(added,null));
                assertEquals(negative?empty:expected,book.snapshotPlans());
            }
            String valid=raw.getString("planned_bakes",null);
            assertNotNull(valid);
            JSONArray persisted=new JSONArray(valid);
            List<String> persistedIds=new ArrayList<>();
            for(int i=0;i<persisted.length();i++) persistedIds.add(persisted.getJSONObject(i).getString("recipeId"));
            assertEquals(negative?Collections.singletonList("waffles"):Arrays.asList("ciabatta","home_bread","waffles"),persistedIds);
            try(BookPreferences reread=new BookPreferences(context,NAMESPACE)) {
                assertEquals(negative?empty:expected,reread.snapshotPlans());
            }
            if(!negative) {
                for(String broken:Arrays.asList("[{","null","[null]","[{\"recipeId\":\"ciabatta\",\"portions\":0,\"startAtMillis\":2000000000000}]")) {
                    assertTrue(raw.edit().putString("planned_bakes",broken).putString("planned_action_nonce_"+old.getId(),"synthetic-capability").commit());
                    Map<String,?> before=new HashMap<>(raw.getAll());
                    try(BookPreferences book=new BookPreferences(context,NAMESPACE)) {
                        boolean failed=false;
                        try { book.snapshotPlans(); } catch(IllegalStateException expectedError) { failed=true; }
                        assertTrue("Malformed original must fail a strict read",failed);
                        assertFalse(book.putPlan(added,null));
                        assertFalse(book.putPlan(added,old.getId()));
                        assertFalse(book.removePlan(old.getId()));
                        assertNull(book.notificationNonce(old.getId(),null));
                        assertFalse(book.consumeNotificationAction(old.getId(),"synthetic-capability"));
                    }
                    assertEquals("Read errors must not alter any original pref or capability",before,raw.getAll());
                }
                assertTrue(raw.edit().putString("planned_bakes",valid).commit());
            }
        } else {
            assertEquals("restart",phase);
            try(BookPreferences restored=new BookPreferences(context,NAMESPACE)) {
                assertEquals(negative?empty:expected,restored.snapshotPlans());
            }
        }
        assertEquals("keep",raw.getString("unrelated",null));
        File external=context.getExternalFilesDir(null);
        assertNotNull(external);
        File evidence=new File(external,"minified-plan-regression");
        if(!evidence.exists()) assertTrue(evidence.mkdirs());
        String saved=raw.getString("planned_bakes",null);
        assertNotNull(saved);
        write(new File(evidence,phase+"-prefs.json"),saved);
        write(new File(evidence,phase+"-result.txt"),"negative-before="+negative+"; phase="+phase+"; target="+context.getPackageName()+"; debuggable=false; pid="+android.os.Process.myPid()+"; assertions=PASS\n");
    }

    private static void write(File file,String text) throws Exception {
        try(FileOutputStream stream=new FileOutputStream(file)) {
            stream.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
