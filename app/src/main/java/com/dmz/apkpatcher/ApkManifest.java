package com.dmz.apkpatcher;

import android.content.res.AssetManager;
import android.content.res.XmlResourceParser;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import dmz.engine.Files2;

final class ApkManifest {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    static final class Info {
        String pkg;
        String launcherActivity;
    }

    static Info read(File input, File workDir) {
        Info info = new Info();
        File apk = input;
        try {
            String ext = extOf(input.getName());
            if (".apks".equals(ext) || ".xapk".equals(ext)) {
                Files2.mkdirs(workDir);
                apk = extractBaseApk(input, workDir);
                if (apk == null) return info;
            }
            parseManifest(apk, info);
        } catch (Exception ignored) {}
        return info;
    }

    static String launcherActivity(File input, File workDir) {
        return read(input, workDir).launcherActivity;
    }

    private static void parseManifest(File apk, Info info) {
        try {
            AssetManager am = AssetManager.class.newInstance();
            AssetManager.class.getMethod("addAssetPath", String.class).invoke(am, apk.getAbsolutePath());
            XmlResourceParser p = am.openXmlResourceParser("AndroidManifest.xml");
            try {
                String pkg = "";
                String currentActivity = null;
                boolean inActivity = false, inIntent = false, hasMain = false, hasLauncher = false;
                int event;
                while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        String tag = p.getName();
                        if ("manifest".equals(tag)) {
                            String a = p.getAttributeValue(null, "package");
                            if (a != null) { pkg = a; info.pkg = a; }
                        } else if ("activity".equals(tag) || "activity-alias".equals(tag)) {
                            currentActivity = p.getAttributeValue(ANDROID_NS, "name");
                            inActivity = true; hasMain = false; hasLauncher = false;
                        } else if (inActivity && "intent-filter".equals(tag)) {
                            inIntent = true; hasMain = false; hasLauncher = false;
                        } else if (inIntent && ("action".equals(tag) || "category".equals(tag))) {
                            String n = p.getAttributeValue(ANDROID_NS, "name");
                            if ("action".equals(tag) && "android.intent.action.MAIN".equals(n)) hasMain = true;
                            if ("category".equals(tag) && "android.intent.category.LAUNCHER".equals(n)) hasLauncher = true;
                        }
                    } else if (event == XmlPullParser.END_TAG) {
                        String tag = p.getName();
                        if ("intent-filter".equals(tag)) {
                            if (hasMain && hasLauncher && currentActivity != null && info.launcherActivity == null)
                                info.launcherActivity = absolute(pkg, currentActivity);
                            inIntent = false;
                        } else if ("activity".equals(tag) || "activity-alias".equals(tag)) {
                            inActivity = false; currentActivity = null;
                        }
                    }
                }
            } finally { p.close(); }
        } catch (Exception ignored) {}
    }

    private static String absolute(String pkg, String name) {
        if (name == null) return null;
        if (name.startsWith(".")) return pkg + name;
        if (!name.contains(".")) return pkg + "." + name;
        return name;
    }

    private static File extractBaseApk(File container, File workDir) throws Exception {
        // Fast path: scan for "base.apk" only, stop as soon as we extract it. Avoids extracting
        // every split APK (which can be hundreds of MB) just to read the base manifest.
        File base = null;
        ZipInputStream in = new ZipInputStream(new FileInputStream(container));
        try {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory() || !e.getName().toLowerCase().endsWith(".apk")) continue;
                String name = new File(e.getName()).getName();
                if (!"base.apk".equalsIgnoreCase(name)) continue;
                File out = new File(workDir, name);
                OutputStream o = new FileOutputStream(out);
                try { Files2.copy(in, o); } finally { o.close(); }
                base = out;
                break;
            }
        } finally { in.close(); }
        if (base != null) return base;
        // Fallback: no base.apk entry — extract the first .apk we find.
        in = new ZipInputStream(new FileInputStream(container));
        try {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory() || !e.getName().toLowerCase().endsWith(".apk")) continue;
                File out = new File(workDir, new File(e.getName()).getName());
                OutputStream o = new FileOutputStream(out);
                try { Files2.copy(in, o); } finally { o.close(); }
                return out;
            }
        } finally { in.close(); }
        return null;
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase();
    }
}
