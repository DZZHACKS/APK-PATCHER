package com.dmz.apkpatcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class GameRegistry {

    public static final class Feature {
        public final String name, desc;
        public Feature(String name, String desc) { this.name = name; this.desc = desc; }
    }

    public static final class Variant {
        public final String label;
        public final List<String> sos;
        public final String apk;         // optional: full menu APK filename in library (menus/<apk>)
        public final String bootstrap;   // optional: "Lclass;->Method(args)V" invoked from game onCreate
        public Variant(String label, List<String> sos, String apk, String bootstrap) {
            this.label = label; this.sos = sos; this.apk = apk; this.bootstrap = bootstrap;
        }
    }

    public static final class Game {
        public final String code, name, so, accent, logo;
        public final List<String> packages;
        public final List<Feature> features;
        public final List<Variant> variants;
        public final boolean wildcard;
        Game(String code, String name, List<String> packages, String so, String accent, String logo,
             List<Feature> features, List<Variant> variants) {
            this.code = code; this.name = name; this.packages = packages;
            this.so = so; this.accent = accent; this.logo = logo;
            this.features = features;
            this.variants = variants == null ? new ArrayList<Variant>() : variants;
            this.wildcard = packages.contains("*");
        }
    }

    public final List<Game> games = new ArrayList<>();

    public static GameRegistry load(Context ctx) {
        GameRegistry r = new GameRegistry();
        File override = new File(ctx.getExternalFilesDir(null), "games.json");
        try {
            if (override.isFile()) r.parse(readFile(override));
            else r.parse(readAsset(ctx, "games.json"));
        } catch (Exception e) {
            try { r.parse(readAsset(ctx, "games.json")); } catch (Exception ignored) {}
        }
        return r;
    }

    private void parse(String json) throws Exception {
        games.clear();
        JSONObject root = new JSONObject(json);
        JSONArray arr = root.optJSONArray("games");
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject g = arr.getJSONObject(i);
            List<String> pkgs = new ArrayList<>();
            JSONArray pa = g.optJSONArray("packages");
            if (pa != null) for (int j = 0; j < pa.length(); j++) pkgs.add(pa.getString(j));
            List<Feature> feats = new ArrayList<>();
            JSONArray fa = g.optJSONArray("features");
            if (fa != null) for (int j = 0; j < fa.length(); j++) {
                JSONObject f = fa.getJSONObject(j);
                feats.add(new Feature(f.optString("name"), f.optString("desc")));
            }
            List<Variant> vars = new ArrayList<>();
            JSONArray va = g.optJSONArray("variants");
            if (va != null) for (int j = 0; j < va.length(); j++) {
                JSONObject v = va.getJSONObject(j);
                List<String> sos = new ArrayList<>();
                JSONArray sa = v.optJSONArray("sos");
                if (sa != null) for (int k = 0; k < sa.length(); k++) sos.add(sa.getString(k));
                vars.add(new Variant(
                        v.optString("label", "v" + j), sos,
                        v.optString("apk", null),
                        v.optString("bootstrap", null)));
            }
            games.add(new Game(
                    g.optString("code", "GAME" + i),
                    g.optString("name", "Unknown"),
                    pkgs,
                    g.optString("so", ""),
                    g.optString("accent", "#FF4A6B"),
                    g.optString("logo", ""),
                    feats,
                    vars
            ));
        }
    }

    public Game match(String pkg) {
        if (pkg == null) return firstWildcard();
        String p = pkg.toLowerCase(Locale.ROOT);
        for (Game g : games) for (String s : g.packages)
            if (!"*".equals(s) && s.toLowerCase(Locale.ROOT).equals(p)) return g;
        for (Game g : games) for (String s : g.packages)
            if (!"*".equals(s) && p.startsWith(s.toLowerCase(Locale.ROOT))) return g;
        return firstWildcard();
    }

    private Game firstWildcard() {
        for (Game g : games) if (g.wildcard) return g;
        return null;
    }

    public static File menusDir(Context ctx) {
        File d = new File(ctx.getExternalFilesDir(null), "menus");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /**
     * Resolve the menu file for a game: first check externalFilesDir/menus/<so>,
     * then fall back to a bundled asset at assets/menus/<so> (copied to cache on
     * first use). Returns null when neither is available.
     */
    public static File resolveMenuFile(Context ctx, String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;
        File override = new File(menusDir(ctx), fileName);
        if (override.isFile()) return override;
        File cached = new File(ctx.getCacheDir(), "bundled-menus/" + fileName);
        if (cached.isFile()) return cached;
        try (InputStream in = ctx.getAssets().open("menus/" + fileName)) {
            if (cached.getParentFile() != null) cached.getParentFile().mkdirs();
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(cached)) {
                byte[] buf = new byte[1 << 15];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return cached;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readFile(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) { return readAll(in); }
    }
    private static String readAsset(Context ctx, String name) throws Exception {
        try (InputStream in = ctx.getAssets().open(name)) { return readAll(in); }
    }
    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        return sb.toString();
    }
}
