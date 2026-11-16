package com.dmz.apkpatcher;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class InstalledAppsAdapter extends RecyclerView.Adapter<InstalledAppsAdapter.VH> {

    public static final class Entry {
        public final String pkg, label;
        public final Drawable icon;
        public final File apkFile;
        public final String[] splitSourceDirs;
        public final long size;
        Entry(String pkg, String label, Drawable icon, File apkFile, String[] splitSourceDirs, long size) {
            this.pkg = pkg; this.label = label; this.icon = icon;
            this.apkFile = apkFile;
            this.splitSourceDirs = splitSourceDirs == null ? new String[0] : splitSourceDirs;
            this.size = size;
        }
    }

    public interface OnPick { void onPick(Entry e); }

    private final List<Entry> items;
    private int selected = -1;
    private final OnPick cb;

    public InstalledAppsAdapter(List<Entry> items, OnPick cb) { this.items = items; this.cb = cb; }

    public static List<Entry> listLaunchable(Context ctx) { return listLaunchable(ctx, null); }

    /** When {@code allowedPackages} is non-null, only installed apps whose package
     *  is in that set are returned (used to restrict the list to the games we support). */
    public static List<Entry> listLaunchable(Context ctx, Set<String> allowedPackages) {
        PackageManager pm = ctx.getPackageManager();
        List<Entry> out = new ArrayList<>();
        String self = ctx.getPackageName();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (ai.packageName.equals(self)) continue;
            if (allowedPackages != null && !allowedPackages.contains(ai.packageName)) continue;
            if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue;
            String label;
            try { label = pm.getApplicationLabel(ai).toString(); }
            catch (Exception e) { label = ai.packageName; }
            Drawable icon;
            try { icon = pm.getApplicationIcon(ai); } catch (Exception e) { icon = null; }
            File apk = ai.sourceDir == null ? null : new File(ai.sourceDir);
            long size = apk != null && apk.isFile() ? apk.length() : 0;
            String[] splits = ai.splitSourceDirs == null ? new String[0] : ai.splitSourceDirs;
            for (String s : splits) { File f = new File(s); if (f.isFile()) size += f.length(); }
            out.add(new Entry(ai.packageName, label, icon, apk, splits, size));
        }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.row_installed_app, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        Entry e = items.get(pos);
        h.name.setText(e.label);
        h.pkg.setText(e.pkg);
        h.size.setText(human(e.size));
        if (e.icon != null) h.icon.setImageDrawable(e.icon);
        h.radio.setImageResource(pos == selected ? R.drawable.radio_on : R.drawable.radio_off);
        h.itemView.setOnClickListener(v -> {
            int prev = selected;
            selected = h.getBindingAdapterPosition();
            if (prev >= 0) notifyItemChanged(prev);
            notifyItemChanged(selected);
            if (cb != null) cb.onPick(e);
        });
    }

    @Override public int getItemCount() { return items.size(); }

    static final class VH extends RecyclerView.ViewHolder {
        ImageView icon, radio;
        TextView name, pkg, size;
        VH(View v) {
            super(v);
            icon = v.findViewById(R.id.appIcon);
            radio = v.findViewById(R.id.appRadio);
            name = v.findViewById(R.id.appName);
            pkg = v.findViewById(R.id.appPkg);
            size = v.findViewById(R.id.appSize);
        }
    }

    private static String human(long b) {
        if (b <= 0) return "";
        if (b < 1024 * 1024) return String.format(Locale.ROOT, "%.0f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", b / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.2f GB", b / (1024.0 * 1024 * 1024));
    }
}
