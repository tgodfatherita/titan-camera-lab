package it.carmine.titancamera;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import java.io.File;
import java.io.IOException;
import java.util.UUID;

/** One recoverable private recording. Only a URI minted here can be retired. */
final class CaptureDraft {
    private static final String KEY = "draftId";
    final File dir, video, samples, calibration;
    final Uri uri;

    private CaptureDraft(Context context, String id) {
        dir = new File(new File(context.getFilesDir(), "recordings"), id);
        video = new File(dir, "source.mp4");
        samples = new File(dir, "gyro.bin");
        calibration = new File(dir, "camera.json");
        uri = new Uri.Builder().scheme("titan-draft").authority("capture").appendPath(id).build();
    }

    static CaptureDraft current(Context context) {
        String id = prefs(context).getString(KEY, null);
        return id == null ? null : new CaptureDraft(context, id);
    }

    static CaptureDraft resolve(Context context, Uri uri) {
        CaptureDraft draft = current(context);
        return draft != null && draft.uri.equals(uri) ? draft : null;
    }

    static synchronized CaptureDraft create(Context context) throws IOException {
        if (current(context) != null) throw new IOException("Completa o scarta prima la registrazione in sospeso.");
        String id = UUID.randomUUID().toString();
        CaptureDraft draft = new CaptureDraft(context, id);
        if (!draft.dir.mkdirs()) throw new IOException("Spazio temporaneo non disponibile");
        if (!prefs(context).edit().putString(KEY, id).remove("lastVideo").commit()) {
            draft.dir.delete();
            throw new IOException("Impossibile preparare la registrazione");
        }
        return draft;
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("stabilization", Context.MODE_PRIVATE);
    }

    static synchronized void retire(Context context, Uri source) throws IOException {
        CaptureDraft draft = resolve(context, source);
        if (draft == null) return;
        for (File file : new File[]{draft.video, draft.samples, draft.calibration, draft.dir}) {
            if (file.exists() && !file.delete()) throw new IOException("Impossibile liberare lo spazio temporaneo");
        }
        if (!prefs(context).edit().remove(KEY).commit()) throw new IOException("Salvataggio dello stato non riuscito");
    }
}
