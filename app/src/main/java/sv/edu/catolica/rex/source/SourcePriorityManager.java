package sv.edu.catolica.rex.source;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SourcePriorityManager {
    private static final String PREF = "source_priority";
    private final SharedPreferences prefs;

    public SourcePriorityManager(Context ctx) {
        this.prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public List<String> getPriorityList() {
        String csv = prefs.getString("order", "allcalidad,pelispedia");
        if (csv == null || csv.trim().isEmpty()) return Collections.singletonList("allcalidad");
        String[] parts = csv.split(",");
        List<String> out = new ArrayList<>();
        for (String p : parts) out.add(p.trim());
        return out;
    }

    public void setPriorityList(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(','); sb.append(list.get(i));
        }
        prefs.edit().putString("order", sb.toString()).apply();
    }
}
