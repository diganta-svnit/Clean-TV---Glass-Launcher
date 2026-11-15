package tv.glasshome.launcher;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The launchable apps, and the tray: the fixed dock first, then the most used apps (usage stats). */
final class Apps {

    static final class Entry {
        final String pkg;
        final String label;
        final Intent launch;
        final Drawable banner;
        final Drawable icon;

        Entry(String pkg, String label, Intent launch, Drawable banner, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.launch = launch;
            this.banner = banner;
            this.icon = icon;
        }
    }

    static final class Lists {
        List<Entry> all = Collections.emptyList();
        /** The tray in order: the dock apps that are installed, then the most used of the rest. */
        List<Entry> tray = Collections.emptyList();
        int dockCount;
        boolean usageAccess;
    }

    private Apps() {
    }

    static Lists load(Context c, Set<String> hidden, String[] dock, int n) {
        PackageManager pm = c.getPackageManager();
        Map<String, Entry> byPkg = new LinkedHashMap<>();
        add(pm, byPkg, Intent.CATEGORY_LEANBACK_LAUNCHER, hidden, c.getPackageName());
        add(pm, byPkg, Intent.CATEGORY_LAUNCHER, hidden, c.getPackageName());

        Lists out = new Lists();
        List<Entry> all = new ArrayList<>(byPkg.values());
        all.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        out.all = all;
        out.usageAccess = hasUsageAccess(c);
        Map<String, UsageStats> stats = out.usageAccess ? usage(c) : Collections.emptyMap();

        LinkedHashSet<Entry> tray = new LinkedHashSet<>();
        for (String p : dock) {
            Entry e = byPkg.get(p);
            if (e != null) tray.add(e);
        }
        out.dockCount = tray.size();
        List<Entry> byTime = new ArrayList<>();
        for (Entry e : all) {
            UsageStats s = stats.get(e.pkg);
            if (s != null && s.getTotalTimeInForeground() > 0) byTime.add(e);
        }
        byTime.sort((a, b) -> Long.compare(stats.get(b.pkg).getTotalTimeInForeground(),
                stats.get(a.pkg).getTotalTimeInForeground()));
        tray.addAll(byTime);
        tray.addAll(all);
        List<Entry> list = new ArrayList<>(n);
        for (Entry e : tray) {
            if (list.size() == n) break;
            list.add(e);
        }
        out.tray = list;
        return out;
    }

    private static void add(PackageManager pm, Map<String, Entry> byPkg, String category, Set<String> hidden,
                            String self) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(self) || hidden.contains(pkg) || byPkg.containsKey(pkg)) continue;
            Drawable banner = null;
            if (Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category)) {
                banner = ri.activityInfo.loadBanner(pm);
                if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            }
            Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(category)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            byPkg.put(pkg, new Entry(pkg, String.valueOf(ri.loadLabel(pm)), launch, banner, ri.loadIcon(pm)));
        }
    }

    static boolean hasUsageAccess(Context c) {
        AppOpsManager ops = c.getSystemService(AppOpsManager.class);
        @SuppressWarnings("deprecation")
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private static Map<String, UsageStats> usage(Context c) {
        UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        try {
            Map<String, UsageStats> m = usm.queryAndAggregateUsageStats(now - 30L * 24 * 3600 * 1000, now);
            return m != null ? m : Collections.emptyMap();
        } catch (RuntimeException e) {
            return Collections.emptyMap();
        }
    }
}
