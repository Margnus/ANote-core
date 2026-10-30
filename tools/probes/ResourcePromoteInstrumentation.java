package dev.notecore.probes;

import android.app.Instrumentation;
import android.app.Activity;
import android.os.Bundle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** A disposable self-targeted APK observes a real App execution domain without restarting NoteCore. */
public final class ResourcePromoteInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String scope = "resource-promote-" + UUID.randomUUID();
            Path folder = getTargetContext().getCacheDir().toPath().resolve(scope);
            Files.createDirectory(folder);
            Files.write(folder.resolve("scope.txt"), scope.getBytes("UTF-8"));
            result.putString("notecore_promote", ResourcePromoteProbe.execute(new String[]{folder.toString(), "cases"}).toString());
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("notecore_failure", failure.toString()); finish(Activity.RESULT_CANCELED, result);
        }
    }
}
