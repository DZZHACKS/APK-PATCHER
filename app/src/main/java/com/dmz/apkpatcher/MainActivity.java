package com.dmz.apkpatcher;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.app.PendingIntent;
import android.content.pm.PackageInstaller;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

import dmz.engine.ApkPatcher;
import dmz.engine.BuildException;
import dmz.engine.BuildLog;
import dmz.engine.Files2;
import dmz.engine.Signer;
import dmz.engine.SyntheticMenu;

public class MainActivity extends AppCompatActivity {

    enum Screen { HOME, SOURCE, MENU, SIGNING, PATCHING, SUCCESS, INSTALLED, SETTINGS, LANGUAGE, LOGS, ABOUT }

    private final StringBuilder logBuffer = new StringBuilder();
    private TextView logTextView;
    private android.widget.ScrollView logScrollView;

    private final View[] screens = new View[Screen.values().length];

    private GameRegistry registry;
    private GameRegistry.Game detectedGame;
    private File fApk, fMenuOrSo, fKs, outApk;
    private boolean menuIsSo;
    private boolean menuAuto = true;
    private android.graphics.drawable.Drawable detectedIcon;
    private ApkManifest.Info cachedManifest;
    private volatile int loadSeq = 0;
    private View srcLoadingOverlay, srcContinueBtn;
    private TextView srcLoadingText;

    private TextInputEditText edKsPass, edAlias, edActivity;
    private TextView lblKs;
    private CircularProgressIndicator circProgress;
    private TextView progressPct, progressLabel;
    private View[] dots = new View[4];
    private String installedPackage;
    private boolean keepAwake;
    private boolean confirmInstall;
    private BroadcastReceiver installReceiver;

    private final Handler main = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String[]> pickApk = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> { if (uri != null) onApkPicked(uri); });
    private final ActivityResultLauncher<String[]> pickSo = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> { if (uri != null) onMenuPicked(uri); });
    private final ActivityResultLauncher<String[]> pickKs = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> { if (uri != null) onKeystorePicked(uri); });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        registry = GameRegistry.load(this);
        android.content.SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        menuAuto = prefs.getBoolean("autoMenu", true);
        keepAwake = prefs.getBoolean("keepAwake", false);
        confirmInstall = prefs.getBoolean("confirmInstall", true);

        screens[Screen.HOME.ordinal()]      = findViewById(R.id.screenHome);
        screens[Screen.SOURCE.ordinal()]    = findViewById(R.id.screenSource);
        screens[Screen.MENU.ordinal()]      = findViewById(R.id.screenMenu);
        screens[Screen.SIGNING.ordinal()]   = findViewById(R.id.screenSigning);
        screens[Screen.PATCHING.ordinal()]  = findViewById(R.id.screenPatching);
        screens[Screen.SUCCESS.ordinal()]   = findViewById(R.id.screenSuccess);
        screens[Screen.INSTALLED.ordinal()] = findViewById(R.id.screenInstalled);
        screens[Screen.SETTINGS.ordinal()] = findViewById(R.id.screenSettings);
        screens[Screen.LANGUAGE.ordinal()] = findViewById(R.id.screenLanguage);
        screens[Screen.LOGS.ordinal()]     = findViewById(R.id.screenLogs);
        screens[Screen.ABOUT.ordinal()]    = findViewById(R.id.screenAbout);

        wireHome();
        wireSource();
        wireMenu();
        wireSigning();
        wirePatching();
        wireSuccess();
        wireInstalled();
        wireSettings();

        registerInstallReceiver();
        wireSettings();
        wireLanguage();
        wireLogs();
        wireAbout();
        show(Screen.HOME);
    }

    private void show(Screen s) {
        for (int i = 0; i < screens.length; i++) screens[i].setVisibility(i == s.ordinal() ? View.VISIBLE : View.GONE);
        if (s == Screen.MENU) refreshMenu();
        if (s == Screen.HOME) refreshHomeSummary();
        if (s == Screen.LOGS) refreshLogs();
        if (s == Screen.LANGUAGE) refreshLanguageChecks();
    }

    @Override public void onBackPressed() {
        for (int i = screens.length - 1; i >= 0; i--) {
            if (screens[i].getVisibility() == View.VISIBLE) {
                if (i == 0) { super.onBackPressed(); return; }
                // SUCCESS and INSTALLED go home
                if (i == Screen.SETTINGS.ordinal()) { show(Screen.HOME); return; }
                if (i >= Screen.SUCCESS.ordinal()) { show(Screen.HOME); return; }
                show(Screen.values()[i - 1]);
                return;
            }
        }
        super.onBackPressed();
    }

    // ───────────────────────── HOME ─────────────────────────
    private void wireHome() {
        View home = screens[Screen.HOME.ordinal()];
        home.findViewById(R.id.btnSettings).setOnClickListener(v -> show(Screen.SETTINGS));
        home.findViewById(R.id.homeInstalledSource).setOnClickListener(v -> { show(Screen.SOURCE); switchTab(true); });
        home.findViewById(R.id.homeFileSource).setOnClickListener(v -> { show(Screen.SOURCE); switchTab(false); });
        home.findViewById(R.id.homeStep2).setOnClickListener(v -> {
            if (fApk == null) show(Screen.SOURCE); else { if (menuAuto) tryAutoPick(); show(Screen.MENU); }
        });
        home.findViewById(R.id.homeStep3).setOnClickListener(v -> {
            if (fApk == null) show(Screen.SOURCE);
            else if (fMenuOrSo == null) { if (menuAuto) tryAutoPick(); show(Screen.MENU); }
            else show(Screen.SIGNING);
        });
        home.findViewById(R.id.homePatchNow).setOnClickListener(v -> {
            if (fApk == null) { show(Screen.SOURCE); return; }
            if (fMenuOrSo == null) { if (menuAuto) tryAutoPick(); show(Screen.MENU); return; }
            if (fKs == null) { show(Screen.SIGNING); return; }
            runPatch();
        });
        home.findViewById(R.id.navHome).setOnClickListener(v -> show(Screen.HOME));
        home.findViewById(R.id.navLogs).setOnClickListener(v -> show(Screen.LOGS));
        home.findViewById(R.id.navSettings).setOnClickListener(v -> show(Screen.SETTINGS));
        home.findViewById(R.id.navAbout).setOnClickListener(v -> show(Screen.ABOUT));
    }

    // ───────────────────────── SOURCE ─────────────────────────
    private RecyclerView listApps;
    private InstalledAppsAdapter installedAdapter;
    private View panelInstalled, panelFile, tabInstalled, tabFile;
    private TextView fileName, fileSize, filePkg, fileDetected, fileChipType;
    private View fileDetails, dropZone;

    private void wireSource() {
        View s = screens[Screen.SOURCE.ordinal()];
        s.findViewById(R.id.srcBack).setOnClickListener(v -> show(Screen.HOME));

        tabInstalled = s.findViewById(R.id.tabInstalled);
        tabFile = s.findViewById(R.id.tabFile);
        panelInstalled = s.findViewById(R.id.panelInstalled);
        panelFile = s.findViewById(R.id.panelFile);
        tabInstalled.setOnClickListener(v -> switchTab(true));
        tabFile.setOnClickListener(v -> switchTab(false));

        listApps = s.findViewById(R.id.listApps);
        listApps.setLayoutManager(new LinearLayoutManager(this));
        java.util.Set<String> allowed = new java.util.HashSet<>();
        for (GameRegistry.Game g : registry.games)
            for (String p : g.packages) if (!"*".equals(p)) allowed.add(p);
        installedAdapter = new InstalledAppsAdapter(
                InstalledAppsAdapter.listLaunchable(this, allowed),
                this::onInstalledPicked);
        listApps.setAdapter(installedAdapter);

        dropZone = s.findViewById(R.id.dropZone);
        dropZone.setOnClickListener(v -> pickApk.launch(new String[]{"*/*"}));
        fileDetails = s.findViewById(R.id.fileDetails);
        fileName = s.findViewById(R.id.fileName);
        fileSize = s.findViewById(R.id.fileSize);
        filePkg = s.findViewById(R.id.filePkg);
        fileDetected = s.findViewById(R.id.fileDetected);
        fileChipType = s.findViewById(R.id.fileChipType);

        srcLoadingOverlay = s.findViewById(R.id.srcLoadingOverlay);
        srcLoadingText = s.findViewById(R.id.srcLoadingText);
        srcContinueBtn = s.findViewById(R.id.srcContinue);
        srcContinueBtn.setOnClickListener(v -> {
            if (fApk == null) return;
            if (menuAuto) tryAutoPick();
            show(Screen.MENU);
        });
    }

    private void srcLoadingShow(final String msg) {
        main.post(() -> {
            if (srcLoadingText != null) srcLoadingText.setText(msg);
            if (srcLoadingOverlay != null) srcLoadingOverlay.setVisibility(View.VISIBLE);
            if (srcContinueBtn != null) { srcContinueBtn.setEnabled(false); srcContinueBtn.setAlpha(0.4f); }
        });
    }

    private void srcLoadingProgress(final String msg) {
        main.post(() -> { if (srcLoadingText != null) srcLoadingText.setText(msg); });
    }

    private void srcLoadingHide() {
        main.post(() -> {
            if (srcLoadingOverlay != null) srcLoadingOverlay.setVisibility(View.GONE);
            if (srcContinueBtn != null) { srcContinueBtn.setEnabled(true); srcContinueBtn.setAlpha(1f); }
        });
    }

    private ApkManifest.Info manifest() {
        if (cachedManifest != null) return cachedManifest;
        if (fApk == null) return new ApkManifest.Info();
        cachedManifest = ApkManifest.read(fApk, new File(getCacheDir(), "manifest-work"));
        return cachedManifest;
    }

    private void switchTab(boolean installed) {
        panelInstalled.setVisibility(installed ? View.VISIBLE : View.GONE);
        panelFile.setVisibility(installed ? View.GONE : View.VISIBLE);
        tabInstalled.setBackgroundResource(installed ? R.drawable.pill_accent : R.drawable.pill_surface);
        tabFile.setBackgroundResource(installed ? R.drawable.pill_surface : R.drawable.pill_accent);
        ((TextView)((android.view.ViewGroup)tabInstalled).getChildAt(0))
                .setTextColor(installed ? Color.WHITE : getColor(R.color.text));
        ((TextView)((android.view.ViewGroup)tabFile).getChildAt(0))
                .setTextColor(installed ? getColor(R.color.text) : Color.WHITE);
    }

    private void onInstalledPicked(InstalledAppsAdapter.Entry e) {
        if (e.apkFile == null) return;
        final int mySeq = ++loadSeq;
        // Reset state + capture icon BEFORE kicking off the background work. This also means
        // that if the user picks a second game before the first copy finishes, the stale thread's
        // completion will see mySeq != loadSeq and discard its results (preventing the "wrong
        // icon" bug where a slow first pick overwrote the current pick's state).
        detectedIcon = e.icon;
        detectedGame = null;
        fApk = null;
        cachedManifest = null;
        fileDetails.setVisibility(View.GONE);
        srcLoadingShow("Loading " + e.label + "…");
        appendLog("INFO", "Pick " + e.pkg + " (" + (e.splitSourceDirs.length + 1) + " APKs)");

        new Thread(() -> {
            try {
                srcLoadingProgress("Copying base APK…");
                File localBase = copyInstalledApk(e.pkg, e.apkFile, "base.apk");
                boolean hasSplits = e.splitSourceDirs != null && e.splitSourceDirs.length > 0;
                File toPatch;
                if (!hasSplits) {
                    toPatch = localBase;
                    appendLog("INFO", "Loaded installed base APK for " + e.pkg);
                } else {
                    java.util.ArrayList<File> splitsLocal = new java.util.ArrayList<>();
                    int droppedAbi = 0;
                    int total = e.splitSourceDirs.length;
                    for (int i = 0; i < total; i++) {
                        if (mySeq != loadSeq) return; // user cancelled / picked another
                        File src = new File(e.splitSourceDirs[i]);
                        String sn = src.getName().toLowerCase();
                        if (sn.contains("armeabi_v7a") || sn.contains("armeabi-v7a")
                                || sn.contains("x86_64")
                                || sn.matches(".*(^|[_\\-\\.])x86([_\\-\\.].*|$)")
                                || sn.contains("mips")) {
                            droppedAbi++;
                            continue;
                        }
                        srcLoadingProgress("Copying split " + (i + 1) + "/" + total + "…");
                        File dst = copyInstalledApk(e.pkg, src, src.getName());
                        if (dst != null) splitsLocal.add(dst);
                    }
                    srcLoadingProgress("Bundling .apks container…");
                    toPatch = packSplitApks(e.pkg, localBase, splitsLocal);
                    appendLog("INFO", "Bundled " + (1 + splitsLocal.size()) + " APKs from " + e.pkg
                            + (droppedAbi > 0 ? " (dropped " + droppedAbi + " non-arm64 split(s))" : "")
                            + " into " + toPatch.getName());
                }
                if (mySeq != loadSeq) return;
                srcLoadingProgress("Reading manifest…");
                File work = new File(getCacheDir(), "manifest-work");
                Files2.deleteTree(work);
                final ApkManifest.Info info = ApkManifest.read(toPatch, work);
                final File finalTarget = toPatch;
                final long sz = finalTarget.length();
                final String labelFinal = e.label + (hasSplits ? " (APKS)" : "");
                main.post(() -> {
                    if (mySeq != loadSeq) return; // stale completion — discard
                    fApk = finalTarget;
                    cachedManifest = info;
                    afterApkLoaded(labelFinal, sz, info);
                    srcLoadingHide();
                });
            } catch (Exception ex) {
                main.post(() -> {
                    if (mySeq != loadSeq) return;
                    srcLoadingHide();
                    android.widget.Toast.makeText(this, "Load install failed: " + ex.getMessage(),
                            android.widget.Toast.LENGTH_LONG).show();
                    appendLog("ERROR", "Load installed APK: " + ex.getMessage());
                });
            }
        }, "load-install").start();
    }

    /** Copy an installed app's APK from /data/app/* to our cacheDir using an InputStream (which
     *  works even when File.isFile() / File.length() lie). Falls back to PackageManager's stream. */
    private File copyInstalledApk(String pkg, File src, String dstName) throws Exception {
        File dir = new File(getCacheDir(), "installed-" + pkg);
        Files2.mkdirs(dir);
        File dst = new File(dir, safeName(dstName));
        if (dst.exists()) dst.delete();
        java.io.InputStream in = null;
        try {
            in = new java.io.FileInputStream(src);
        } catch (Exception ignored) {
            // Fallback: open via AssetFileDescriptor / ApplicationInfo through PackageManager
            try {
                android.content.pm.ApplicationInfo ai =
                        getPackageManager().getApplicationInfo(pkg, 0);
                if (ai != null && src.getAbsolutePath().equals(ai.sourceDir)) {
                    in = new java.io.FileInputStream(ai.sourceDir);
                }
            } catch (Exception ignored2) {}
        }
        if (in == null) throw new java.io.IOException("Cannot read " + src);
        try (java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally { in.close(); }
        return dst;
    }

    /** Zip base + splits (all already copied locally) into an .apks container for ApkPatcher. */
    private File packSplitApks(String pkg, File base, java.util.List<File> splits) throws java.io.IOException {
        File out = new File(getCacheDir(), "input-" + pkg + ".apks");
        if (out.exists()) out.delete();
        try (java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(
                new java.io.BufferedOutputStream(new java.io.FileOutputStream(out)))) {
            byte[] buf = new byte[1 << 16];
            copyIntoZip(zo, "base.apk", base, buf);
            for (File f : splits) copyIntoZip(zo, f.getName(), f, buf);
        }
        return out;
    }

    private static void copyIntoZip(java.util.zip.ZipOutputStream zo, String entry, File src, byte[] buf) throws java.io.IOException {
        zo.putNextEntry(new java.util.zip.ZipEntry(entry));
        try (java.io.InputStream in = new java.io.FileInputStream(src)) {
            int n; while ((n = in.read(buf)) > 0) zo.write(buf, 0, n);
        }
        zo.closeEntry();
    }

    private void onApkPicked(Uri uri) {
        final int mySeq = ++loadSeq;
        detectedIcon = null;
        detectedGame = null;
        fApk = null;
        cachedManifest = null;
        fileDetails.setVisibility(View.GONE);
        srcLoadingShow("Loading file…");
        new Thread(() -> {
            try {
                String name = queryName(uri, "target.apk");
                File dest = new File(getCacheDir(), "input-" + safeName(name));
                srcLoadingProgress("Copying file…");
                copyUriTo(uri, dest);
                if (mySeq != loadSeq) return;
                srcLoadingProgress("Reading manifest…");
                File work = new File(getCacheDir(), "manifest-work");
                Files2.deleteTree(work);
                final ApkManifest.Info info = ApkManifest.read(dest, work);
                final android.graphics.drawable.Drawable icon = readApkIcon(dest);
                final long sz = dest.length();
                final String nameFinal = name;
                main.post(() -> {
                    if (mySeq != loadSeq) return;
                    fApk = dest;
                    cachedManifest = info;
                    detectedIcon = icon;
                    afterApkLoaded(nameFinal, sz, info);
                    srcLoadingHide();
                });
            } catch (Exception ex) {
                main.post(() -> {
                    if (mySeq != loadSeq) return;
                    srcLoadingHide();
                    android.widget.Toast.makeText(this, "Load failed: " + ex.getMessage(),
                            android.widget.Toast.LENGTH_LONG).show();
                    appendLog("ERROR", "Load APK: " + ex.getMessage());
                });
            }
        }, "load-file").start();
    }

    private android.graphics.drawable.Drawable readApkIcon(File apk) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.PackageInfo pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (pi == null || pi.applicationInfo == null) return null;
            pi.applicationInfo.sourceDir = apk.getAbsolutePath();
            pi.applicationInfo.publicSourceDir = apk.getAbsolutePath();
            return pm.getApplicationIcon(pi.applicationInfo);
        } catch (Exception e) { return null; }
    }

    private void afterApkLoaded(String name, long size, ApkManifest.Info info) {
        if (info == null) info = new ApkManifest.Info();
        cachedManifest = info;
        if (info.launcherActivity != null && edActivity != null) edActivity.setText(info.launcherActivity);
        detectedGame = registry.match(info.pkg);

        // populate file-details card
        fileName.setText(name);
        fileSize.setText(human(size));
        filePkg.setText(info.pkg == null ? "—" : "Package: " + info.pkg);
        if (detectedGame != null) {
            fileDetected.setText("Detected: " + detectedGame.name);
            fileDetected.setTextColor(parseColor(detectedGame.accent, R.color.success));
            fileChipType.setText(detectedGame.code);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            bg.setColor(parseColor(detectedGame.accent, R.color.accent));
            fileChipType.setBackground(bg);
        } else {
            fileDetected.setText(R.string.unknown_game);
            fileDetected.setTextColor(getColor(R.color.muted));
        }
        fileDetails.setVisibility(View.VISIBLE);
    }

    private int selectedVariantIndex = 0;

    private String activeBootstrap;   // non-null = ApkPatcher.setBootstrap(sig) before runPatch

    private void tryAutoPick() {
        activeBootstrap = null;
        if (detectedGame == null) return;
        if (detectedGame.variants != null && !detectedGame.variants.isEmpty()) {
            int idx = Math.max(0, Math.min(selectedVariantIndex, detectedGame.variants.size() - 1));
            GameRegistry.Variant v = detectedGame.variants.get(idx);
            // Variant mode A — "bootstrap call":  the variant ships a full menu APK + a bootstrap
            //   class. Merge every menu class into the target, hook the game's onCreate to call
            //   the menu's <bootstrap>(Context). Used by LDOE menus (BlackMods, Zuke BR).
            if (v.apk != null && !v.apk.isEmpty()) {
                File candidate = GameRegistry.resolveMenuFile(this, v.apk);
                if (candidate != null && candidate.isFile()) {
                    fMenuOrSo = candidate;
                    menuIsSo = false;
                    activeBootstrap = v.bootstrap;
                    appendLog("INFO", "Variant " + v.label + " · mode=bootstrap-call · menu="
                            + v.apk + " · boot=" + v.bootstrap);
                    return;
                }
                appendLog("WARN", "Variant APK not found in library: " + v.apk);
            }
            // Variant mode B — "load-library": the variant lists the .so files the mod needs.
            //   Synthesize a tiny Launcher that System.loadLibrary's each in order; the .so itself
            //   hooks into the game via JNI. Used by Project V (libD-M-Z.store.so).
            if (v.sos != null && !v.sos.isEmpty()) {
                java.util.ArrayList<File> sos = new java.util.ArrayList<>();
                for (String name : v.sos) {
                    File f = GameRegistry.resolveMenuFile(this, name);
                    if (f != null && f.isFile()) sos.add(f);
                }
                if (sos.isEmpty()) { fMenuOrSo = null; return; }
                try {
                    File synthDir = new File(getCacheDir(), "synth");
                    Files2.mkdirs(synthDir);
                    File menu = SyntheticMenu.build(sos, new File(synthDir, "menu-" + detectedGame.code + "-" + idx + ".apk"));
                    fMenuOrSo = menu;
                    menuIsSo = false;
                    appendLog("INFO", "Variant " + v.label + " · mode=load-library · sos="
                            + sos.size() + " (" + sos.get(sos.size() - 1).getName() + ")");
                } catch (Exception e) {
                    appendLog("ERROR", "Build variant failed: " + e.getMessage());
                }
            }
            return;
        }
        if (detectedGame.so == null || detectedGame.so.isEmpty()) return;
        File candidate = GameRegistry.resolveMenuFile(this, detectedGame.so);
        if (candidate != null && candidate.isFile()) {
            fMenuOrSo = candidate;
            menuIsSo = candidate.getName().toLowerCase(Locale.ROOT).endsWith(".so");
        }
    }

    // ───────────────────────── MENU ─────────────────────────
    private TextView menuGameCode, menuGameName, menuGamePkg;
    private View menuGameBadge;
    private ImageView menuGameLogo;
    private View menuFoundCard, menuFileRow;
    private TextView menuFoundTitle, menuFoundSub, menuFileName, menuFileSize;

    private void wireMenu() {
        View s = screens[Screen.MENU.ordinal()];
        s.findViewById(R.id.menuBack).setOnClickListener(v -> show(Screen.SOURCE));
        s.findViewById(R.id.menuChangeBtn).setOnClickListener(v -> show(Screen.SOURCE));

        menuGameCode = s.findViewById(R.id.menuGameCode);
        menuGameName = s.findViewById(R.id.menuGameName);
        menuGamePkg = s.findViewById(R.id.menuGamePkg);
        menuGameBadge = s.findViewById(R.id.menuGameBadge);
        menuGameLogo = s.findViewById(R.id.menuGameLogo);
        menuFoundCard = s.findViewById(R.id.menuFoundCard);
        menuFileRow = s.findViewById(R.id.menuFileRow);
        menuFoundTitle = s.findViewById(R.id.menuFoundTitle);
        menuFoundSub = s.findViewById(R.id.menuFoundSub);
        menuFileName = s.findViewById(R.id.menuFileName);
        menuFileSize = s.findViewById(R.id.menuFileSize);

        s.findViewById(R.id.menuContinue).setOnClickListener(v -> {
            if (fMenuOrSo == null) return;
            show(Screen.SIGNING);
        });
    }

    private void refreshMenu() {
        if (detectedGame != null) {
            menuGameCode.setText(detectedGame.code);
            menuGameName.setText(detectedGame.name);
            int c = parseColor(detectedGame.accent, R.color.accent);
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(dp(12));
            d.setColor(c);
            menuGameBadge.setBackground(d);
            loadGameLogo(detectedGame.code);
        } else {
            menuGameCode.setText("??");
            menuGameName.setText(R.string.unknown_game);
            menuGameCode.setVisibility(View.VISIBLE);
            menuGameLogo.setVisibility(View.GONE);
        }
        renderFeatures();
        renderVariantChips();
        ApkManifest.Info info = manifest();
        menuGamePkg.setText(info.pkg == null ? "—" : info.pkg);

        // found / file details — hide implementation (.so filename) from the UI
        menuFileRow.setVisibility(View.GONE);
        if (fMenuOrSo != null && fMenuOrSo.isFile()) {
            menuFoundCard.setVisibility(View.VISIBLE);
            menuFoundTitle.setText(R.string.menu_detected);
            menuFoundSub.setText("");
        } else if (menuAuto && detectedGame != null) {
            menuFoundCard.setVisibility(View.VISIBLE);
            menuFoundTitle.setText(R.string.no_menu_found);
            menuFoundSub.setText(R.string.no_menu_for_game);
        } else {
            menuFoundCard.setVisibility(View.GONE);
        }
    }

    private void loadGameLogo(String code) {
        if (detectedIcon == null) {
            menuGameCode.setVisibility(View.VISIBLE);
            menuGameLogo.setVisibility(View.GONE);
            return;
        }
        menuGameLogo.setImageDrawable(detectedIcon);
        menuGameLogo.setClipToOutline(true);
        menuGameLogo.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
            }
        });
        menuGameLogo.setVisibility(View.VISIBLE);
        menuGameCode.setVisibility(View.GONE);
        menuGameBadge.setBackground(null);
    }

    private void renderVariantChips() {
        View s = screens[Screen.MENU.ordinal()];
        android.view.ViewGroup card = s.findViewById(R.id.menuFeaturesCard);
        if (card == null) return;
        // Remove any previously injected chips row
        View existing = card.findViewWithTag("variant-chips");
        if (existing != null) card.removeView(existing);
        if (detectedGame == null || detectedGame.variants == null || detectedGame.variants.isEmpty()) return;

        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setTag("variant-chips");
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setPadding(0, (int) dp(4), 0, (int) dp(12));
        for (int i = 0; i < detectedGame.variants.size(); i++) {
            final int idx = i;
            GameRegistry.Variant v = detectedGame.variants.get(i);
            TextView chip = new TextView(this);
            chip.setText(v.label);
            chip.setTextSize(13f);
            chip.setPadding((int) dp(16), (int) dp(8), (int) dp(16), (int) dp(8));
            boolean sel = (i == selectedVariantIndex);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(14));
            bg.setColor(sel ? getColor(R.color.accent) : getColor(R.color.surface_high));
            if (!sel) bg.setStroke((int) dp(1), getColor(R.color.outline));
            chip.setBackground(bg);
            chip.setTextColor(sel ? android.graphics.Color.WHITE : getColor(R.color.text));
            chip.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            chip.setClickable(true); chip.setFocusable(true);
            chip.setOnClickListener(x -> {
                selectedVariantIndex = idx;
                tryAutoPick();
                refreshMenu();
                appendLog("INFO", "Variant → " + detectedGame.variants.get(idx).label);
            });
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart((int) dp(8));
            chip.setLayoutParams(lp);
            row.addView(chip);
        }
        // Insert at position 1 (after the "Mod Menu Detected" header row)
        card.addView(row, Math.min(1, card.getChildCount()));
    }

    private void renderFeatures() {
        View s = screens[Screen.MENU.ordinal()];
        View card = s.findViewById(R.id.menuFeaturesCard);
        android.view.ViewGroup grid = s.findViewById(R.id.menuFeaturesGrid);
        if (card == null || grid == null) return;
        grid.removeAllViews();
        if (detectedGame == null || detectedGame.features == null || detectedGame.features.isEmpty()) {
            card.setVisibility(View.GONE);
            return;
        }
        card.setVisibility(View.VISIBLE);
        int gap = (int) dp(8);
        java.util.List<GameRegistry.Feature> fs = detectedGame.features;
        for (int i = 0; i < fs.size(); i += 2) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            android.widget.LinearLayout.LayoutParams rowLp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = i == 0 ? 0 : gap;
            row.setLayoutParams(rowLp);

            row.addView(buildFeatureTile(fs.get(i), gap / 2, 0));
            if (i + 1 < fs.size()) row.addView(buildFeatureTile(fs.get(i + 1), 0, gap / 2));
            else row.addView(spacerTile());
            grid.addView(row);
        }
    }

    private View buildFeatureTile(GameRegistry.Feature f, int marginStart, int marginEnd) {
        int pad = (int) dp(12);
        int accent = detectedGame == null ? getColor(R.color.accent)
                : parseColor(detectedGame.accent, R.color.accent);

        android.widget.LinearLayout tile = new android.widget.LinearLayout(this);
        tile.setOrientation(android.widget.LinearLayout.VERTICAL);
        tile.setPadding(pad, pad, pad, pad);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(getColor(R.color.surface_high));
        bg.setStroke((int) dp(1), getColor(R.color.outline_subtle));
        tile.setBackground(bg);

        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        lp.setMarginStart(marginStart);
        lp.setMarginEnd(marginEnd);
        tile.setLayoutParams(lp);

        // small accent dot
        View dot = new View(this);
        android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
        dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dotBg.setColor(accent);
        dot.setBackground(dotBg);
        android.widget.LinearLayout.LayoutParams dotLp = new android.widget.LinearLayout.LayoutParams(
                (int) dp(8), (int) dp(8));
        dotLp.bottomMargin = (int) dp(8);
        dot.setLayoutParams(dotLp);

        TextView name = new TextView(this);
        name.setText(f.name);
        name.setTextColor(getColor(R.color.text));
        name.setTextSize(14f);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));

        TextView desc = new TextView(this);
        desc.setText(f.desc == null ? "" : f.desc);
        desc.setTextColor(getColor(R.color.muted));
        desc.setTextSize(11f);
        desc.setLineSpacing(dp(2), 1f);
        android.widget.LinearLayout.LayoutParams descLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = (int) dp(3);
        desc.setLayoutParams(descLp);

        tile.addView(dot);
        tile.addView(name);
        tile.addView(desc);
        return tile;
    }

    private View spacerTile() {
        View v = new View(this);
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        return v;
    }

    private void onMenuPicked(Uri uri) {
        try {
            String name = queryName(uri, "menu.so");
            File dest = new File(getCacheDir(), "menu-" + safeName(name));
            copyUriTo(uri, dest);
            fMenuOrSo = dest;
            menuIsSo = name.toLowerCase(Locale.ROOT).endsWith(".so");
            menuAuto = false;
            refreshMenu();
        } catch (Exception ignored) {}
    }

    // ───────────────────────── SIGNING ─────────────────────────
    private void wireSigning() {
        View s = screens[Screen.SIGNING.ordinal()];
        s.findViewById(R.id.signBack).setOnClickListener(v -> show(Screen.MENU));
        lblKs = s.findViewById(R.id.lblKs);
        edKsPass = s.findViewById(R.id.edKsPass);
        edAlias = s.findViewById(R.id.edAlias);
        edActivity = s.findViewById(R.id.edActivity);
        s.findViewById(R.id.btnKs).setOnClickListener(v -> pickKs.launch(new String[]{"*/*"}));
        s.findViewById(R.id.btnKsCreate).setOnClickListener(v -> showCreateKeystoreDialog());
        s.findViewById(R.id.btnPatchNow).setOnClickListener(v -> runPatch());
        restoreLastKeystore();

        if (fApk != null) {
            ApkManifest.Info info = manifest();
            if (info.launcherActivity != null && (edActivity.getText() == null || edActivity.getText().length() == 0))
                edActivity.setText(info.launcherActivity);
        }
    }

    private void showCreateKeystoreDialog() {
        View body = getLayoutInflater().inflate(R.layout.dialog_create_keystore, null);
        TextInputEditText fName = body.findViewById(R.id.ksName);
        TextInputEditText fPass = body.findViewById(R.id.ksPass);
        TextInputEditText fAlias = body.findViewById(R.id.ksAlias);

        String baseName = detectedGame != null
                ? detectedGame.code.toLowerCase(Locale.ROOT) + "-key"
                : "mykey";
        fName.setText(baseName);
        fPass.setText(randomPassword(16));
        fAlias.setText(detectedGame != null ? detectedGame.code.toLowerCase(Locale.ROOT) : "dmz");

        body.findViewById(R.id.ksRegen).setOnClickListener(v -> fPass.setText(randomPassword(16)));

        androidx.appcompat.app.AlertDialog dlg = new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                this, R.style.DarkDialog)
                .setView(body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.create_keystore, null)
                .create();
        dlg.setOnShowListener(di -> dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = fName.getText() == null ? "" : fName.getText().toString().trim();
                    String pass = fPass.getText() == null ? "" : fPass.getText().toString();
                    String alias = fAlias.getText() == null ? "" : fAlias.getText().toString().trim();
                    if (name.isEmpty()) name = "mykey";
                    if (!name.toLowerCase(Locale.ROOT).endsWith(".p12")
                            && !name.toLowerCase(Locale.ROOT).endsWith(".jks")) name = name + ".p12";
                    File out = new File(new File(getExternalFilesDir(null), "keystores"), name);
                    try {
                        dmz.engine.KeystoreCreator.create(out, pass, alias, "DMZ APK Patcher");
                        saveKeystoreCreds(out, pass, alias);
                        useKeystore(out, pass, alias);
                        dlg.dismiss();
                        showKeystoreCreatedDialog(out, pass, alias);
                    } catch (Exception ex) {
                        android.widget.Toast.makeText(this, "Error: " + ex.getMessage(),
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                }));
        dlg.show();
    }

    private void useKeystore(File ks, String pass, String alias) {
        fKs = ks;
        if (edKsPass != null && pass != null) edKsPass.setText(pass);
        if (this.edAlias != null && alias != null) this.edAlias.setText(alias);
        if (lblKs != null) lblKs.setText(ks.getName() + " · prêt");
        getSharedPreferences("dmz", MODE_PRIVATE).edit()
                .putString("lastKs", ks.getAbsolutePath()).apply();
    }

    private void saveKeystoreCreds(File ks, String pass, String alias) {
        // 1) SharedPreferences (in-app, survives reopens)
        android.content.SharedPreferences sp = getSharedPreferences("dmz-keystores", MODE_PRIVATE);
        sp.edit()
                .putString(ks.getAbsolutePath() + ".pass", pass)
                .putString(ks.getAbsolutePath() + ".alias", alias == null ? "" : alias)
                .apply();
        // 2) Sidecar .properties file (exportable, next to the keystore)
        File props = new File(ks.getAbsolutePath() + ".properties");
        try {
            java.util.Properties p = new java.util.Properties();
            p.setProperty("storePassword", pass);
            p.setProperty("keyAlias", alias == null ? "" : alias);
            p.setProperty("keyPassword", pass);
            p.setProperty("created", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new java.util.Date()));
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(props)) {
                p.store(fo, "DMZ APK Patcher — keystore credentials (keep private)");
            }
        } catch (Exception ignored) {}
    }

    private String[] loadKeystoreCreds(File ks) {
        if (ks == null) return null;
        android.content.SharedPreferences sp = getSharedPreferences("dmz-keystores", MODE_PRIVATE);
        String pass = sp.getString(ks.getAbsolutePath() + ".pass", null);
        String alias = sp.getString(ks.getAbsolutePath() + ".alias", null);
        if (pass != null) return new String[]{pass, alias == null ? "" : alias};
        File props = new File(ks.getAbsolutePath() + ".properties");
        if (!props.isFile()) return null;
        try (java.io.FileInputStream fi = new java.io.FileInputStream(props)) {
            java.util.Properties p = new java.util.Properties();
            p.load(fi);
            String pw = p.getProperty("storePassword");
            String al = p.getProperty("keyAlias", "");
            if (pw == null) return null;
            return new String[]{pw, al};
        } catch (Exception e) { return null; }
    }

    private void restoreLastKeystore() {
        String last = getSharedPreferences("dmz", MODE_PRIVATE).getString("lastKs", null);
        if (last == null) return;
        File ks = new File(last);
        if (!ks.isFile()) return;
        String[] creds = loadKeystoreCreds(ks);
        if (creds == null) { fKs = ks; if (lblKs != null) lblKs.setText(ks.getName()); return; }
        useKeystore(ks, creds[0], creds[1]);
    }

    private void showKeystoreCreatedDialog(File out, String pass, String alias) {
        String msg = "File:\n" + out.getAbsolutePath()
                + "\n\nAlias: " + (alias == null || alias.isEmpty() ? "dmz" : alias)
                + "\nPassword:\n" + pass
                + "\n\nThe password is also saved in "
                + out.getName() + ".properties next to the keystore. Keep this file private.";
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DarkDialog)
                .setTitle("Keystore created ✓")
                .setMessage(msg)
                .setNeutralButton("Copy password", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(
                            android.content.ClipData.newPlainText("keystore password", pass));
                    android.widget.Toast.makeText(this, "Copied", android.widget.Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton("OK", null)
                .show();
    }

    private static String randomPassword(int len) {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        return sb.toString();
    }

    private void onKeystorePicked(Uri uri) {
        try {
            String name = queryName(uri, "keystore");
            File dest = new File(getCacheDir(), "ks-" + safeName(name));
            copyUriTo(uri, dest);
            fKs = dest;
            lblKs.setText(name);
            // Try matching sidecar properties file from externalFilesDir/keystores/
            File saved = new File(new File(getExternalFilesDir(null), "keystores"), name);
            String[] creds = loadKeystoreCreds(saved);
            if (creds != null) {
                if (edKsPass != null) edKsPass.setText(creds[0]);
                if (edAlias != null) edAlias.setText(creds[1]);
                lblKs.setText(name + " · creds restaurés");
            }
            getSharedPreferences("dmz", MODE_PRIVATE).edit().putString("lastKs", saved.getAbsolutePath()).apply();
        } catch (Exception ignored) {}
    }

    // ───────────────────────── PATCHING ─────────────────────────
    private View[] rowViews = new View[4];
    private View[] lines = new View[3];
    private long[] stepStartMs = new long[4];
    private long[] stepDurMs = new long[4];
    private int currentStep = -1;
    private TextView patchGameCode, patchGameName, patchGamePkg;
    private ImageView patchGameLogo, waveIcon;
    private View patchGameBadge;

    private void wirePatching() {
        View s = screens[Screen.PATCHING.ordinal()];
        circProgress = s.findViewById(R.id.circProgress);
        progressPct = s.findViewById(R.id.progressPct);
        progressLabel = s.findViewById(R.id.progressLabel);
        waveIcon = s.findViewById(R.id.waveIcon);
        s.findViewById(R.id.patchBack).setOnClickListener(v -> show(Screen.SIGNING));

        dots[0] = s.findViewById(R.id.dot1);
        dots[1] = s.findViewById(R.id.dot2);
        dots[2] = s.findViewById(R.id.dot3);
        dots[3] = s.findViewById(R.id.dot4);
        lines[0] = s.findViewById(R.id.line1);
        lines[1] = s.findViewById(R.id.line2);
        lines[2] = s.findViewById(R.id.line3);

        rowViews[0] = s.findViewById(R.id.row1);
        rowViews[1] = s.findViewById(R.id.row2);
        rowViews[2] = s.findViewById(R.id.row3);
        rowViews[3] = s.findViewById(R.id.row4);
        int[] labelRes = {R.string.row_extract, R.string.row_patch, R.string.row_repack, R.string.row_sign};
        for (int i = 0; i < 4; i++) {
            ((TextView) rowViews[i].findViewById(R.id.rowLabel)).setText(labelRes[i]);
        }

        patchGameCode = s.findViewById(R.id.patchGameCode);
        patchGameName = s.findViewById(R.id.patchGameName);
        patchGamePkg = s.findViewById(R.id.patchGamePkg);
        patchGameLogo = s.findViewById(R.id.patchGameLogo);
        patchGameBadge = s.findViewById(R.id.patchGameBadge);
    }

    private void refreshPatchingGame() {
        if (detectedGame != null) {
            patchGameCode.setText(detectedGame.code);
            patchGameName.setText(detectedGame.name);
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(dp(12));
            d.setColor(parseColor(detectedGame.accent, R.color.accent));
            patchGameBadge.setBackground(d);
        } else {
            patchGameCode.setText("OK");
            patchGameName.setText("Patching");
        }
        ApkManifest.Info info = manifest();
        patchGamePkg.setText(info.pkg == null ? "—" : info.pkg);
        if (detectedIcon != null) {
            patchGameLogo.setImageDrawable(detectedIcon);
            patchGameLogo.setClipToOutline(true);
            patchGameLogo.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12));
                }
            });
            patchGameLogo.setVisibility(View.VISIBLE);
            patchGameCode.setVisibility(View.GONE);
            patchGameBadge.setBackground(null);
        } else {
            patchGameLogo.setVisibility(View.GONE);
            patchGameCode.setVisibility(View.VISIBLE);
        }
    }

    private void resetPatching() {
        circProgress.setProgress(0);
        progressPct.setText("0%");
        progressLabel.setText("");
        waveIcon.setVisibility(View.GONE);
        currentStep = -1;
        for (int i = 0; i < 4; i++) {
            stepStartMs[i] = 0; stepDurMs[i] = 0;
            ((ImageView) dots[i]).setBackgroundResource(R.drawable.step_pending);
            View row = rowViews[i];
            ((ImageView) row.findViewById(R.id.rowIcon)).setImageResource(R.drawable.ic_clock);
            TextView lbl = row.findViewById(R.id.rowLabel);
            lbl.setTextColor(getColor(R.color.muted));
            TextView st = row.findViewById(R.id.rowStatus);
            st.setText(R.string.status_pending);
            st.setTextColor(getColor(R.color.muted));
        }
        for (View l : lines) l.setBackgroundResource(R.drawable.line_pending);
        refreshPatchingGame();
    }

    private void advanceStep(int step, String labelLine) {
        if (step < 0 || step > 3) return;
        long now = System.currentTimeMillis();
        if (currentStep != step) {
            // close previous
            if (currentStep >= 0 && currentStep < 4) {
                stepDurMs[currentStep] = now - stepStartMs[currentStep];
                ((ImageView) dots[currentStep]).setBackgroundResource(R.drawable.step_done);
                if (currentStep < 3) lines[currentStep].setBackgroundResource(R.drawable.line_done);
                View done = rowViews[currentStep];
                ((ImageView) done.findViewById(R.id.rowIcon)).setImageResource(R.drawable.ic_check_filled_green);
                TextView st = done.findViewById(R.id.rowStatus);
                st.setText(String.format(Locale.ROOT, "%.1fs", stepDurMs[currentStep] / 1000f));
                st.setTextColor(getColor(R.color.text_mid));
                TextView lbl = done.findViewById(R.id.rowLabel);
                lbl.setTextColor(getColor(R.color.text));
            }
            currentStep = step;
            stepStartMs[step] = now;
            // start new
            ((ImageView) dots[step]).setBackgroundResource(R.drawable.step_active);
            View active = rowViews[step];
            ((ImageView) active.findViewById(R.id.rowIcon)).setImageResource(R.drawable.ic_refresh);
            TextView st = active.findViewById(R.id.rowStatus);
            st.setText("…");
            st.setTextColor(getColor(R.color.accent));
            TextView lbl = active.findViewById(R.id.rowLabel);
            lbl.setTextColor(getColor(R.color.text));
        }
        if (labelLine != null) progressLabel.setText(labelLine);
        waveIcon.setVisibility(View.VISIBLE);
    }

    private void finishAllSteps() {
        long now = System.currentTimeMillis();
        if (currentStep >= 0 && currentStep < 4) {
            stepDurMs[currentStep] = now - stepStartMs[currentStep];
            ((ImageView) dots[currentStep]).setBackgroundResource(R.drawable.step_done);
            if (currentStep < 3) lines[currentStep].setBackgroundResource(R.drawable.line_done);
            View done = rowViews[currentStep];
            ((ImageView) done.findViewById(R.id.rowIcon)).setImageResource(R.drawable.ic_check_filled_green);
            TextView st = done.findViewById(R.id.rowStatus);
            st.setText(String.format(Locale.ROOT, "%.1fs", stepDurMs[currentStep] / 1000f));
            st.setTextColor(getColor(R.color.text_mid));
        }
        // mark sign step done if we skipped straight through
        for (int i = 0; i < 4; i++) {
            ((ImageView) dots[i]).setBackgroundResource(R.drawable.step_done);
            View row = rowViews[i];
            ImageView icon = row.findViewById(R.id.rowIcon);
            if (icon.getDrawable() == null || stepDurMs[i] == 0) {
                icon.setImageResource(R.drawable.ic_check_filled_green);
            } else {
                icon.setImageResource(R.drawable.ic_check_filled_green);
            }
        }
        for (View l : lines) l.setBackgroundResource(R.drawable.line_done);
        waveIcon.setVisibility(View.GONE);
    }

    private void runPatch() {
        if (fApk == null || fMenuOrSo == null || fKs == null) return;
        String activity = edActivity.getText() == null ? "" : edActivity.getText().toString().trim();
        if (activity.isEmpty()) return;
        String ksPass = edKsPass.getText() == null ? "" : edKsPass.getText().toString();
        if (ksPass.isEmpty()) return;
        String alias = edAlias.getText() == null ? "" : edAlias.getText().toString().trim();

        show(Screen.PATCHING);
        resetPatching();
        if (keepAwake) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        new Thread(() -> {
            BuildLog log = new BuildLog() {
                @Override public void log(String level, String message) { appendLog(level, message); }
                @Override public void progress(int percent, String step) {
                    appendLog("INFO", "[" + percent + "%] " + step);
                    main.post(() -> {
                        circProgress.setProgress(percent);
                        progressPct.setText(percent + "%");
                        int stepIdx = percent < 25 ? 0 : percent < 55 ? 1 : percent < 85 ? 2 : 3;
                        advanceStep(stepIdx, step);
                    });
                }
            };
            try {
                File outDir = new File(getExternalFilesDir(null), "patched");
                Files2.mkdirs(outDir);
                File workDir = new File(getCacheDir(), "patch-work");
                File menuApk = fMenuOrSo;
                if (menuIsSo) {
                    File synthDir = new File(getCacheDir(), "synth");
                    Files2.mkdirs(synthDir);
                    menuApk = SyntheticMenu.build(fMenuOrSo, new File(synthDir, "menu-synth.apk"));
                }
                Signer.Key key = new Signer.Key();
                key.store = fKs; key.storePass = ksPass; key.alias = alias; key.keyPass = ksPass;
                ApkPatcher patcher = new ApkPatcher(log);
                if (activeBootstrap != null) patcher.setBootstrap(activeBootstrap);
                ApkPatcher.Result r = patcher.patchAny(fApk, menuApk, null, activity, key, outDir, workDir);
                outApk = r.outApk;
                main.post(() -> {
                    getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    finishAllSteps(); populateSuccess(); show(Screen.SUCCESS);
                });
            } catch (BuildException e) {
                main.post(() -> { getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); progressLabel.setText(e.getMessage()); progressLabel.setTextColor(getColor(R.color.danger)); });
            } catch (Throwable t) {
                main.post(() -> { getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); progressLabel.setText(String.valueOf(t)); progressLabel.setTextColor(getColor(R.color.danger)); });
            }
        }, "patch").start();
    }

    // ───────────────────────── SUCCESS ─────────────────────────
    private TextView okGameCode, okGameName, okFileName, okFileSize;
    private ImageView okGameLogo;
    private View okGameBadge;
    private void wireSuccess() {
        View s = screens[Screen.SUCCESS.ordinal()];
        okGameCode = s.findViewById(R.id.okGameCode);
        okGameName = s.findViewById(R.id.okGameName);
        okFileName = s.findViewById(R.id.okFileName);
        okFileSize = s.findViewById(R.id.okFileSize);
        okGameLogo = s.findViewById(R.id.okGameLogo);
        okGameBadge = s.findViewById(R.id.okGameBadge);
        s.findViewById(R.id.btnInstall).setOnClickListener(v -> installOutput());
        s.findViewById(R.id.btnOpenFolder).setOnClickListener(v -> openOutputFolder());
        s.findViewById(R.id.btnShare).setOnClickListener(v -> shareOutput());
    }

    private void populateSuccess() {
        if (detectedGame != null) {
            okGameCode.setText(detectedGame.code);
            okGameName.setText(detectedGame.name);
            int c = parseColor(detectedGame.accent, R.color.accent);
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(dp(12));
            d.setColor(c);
            okGameBadge.setBackground(d);
        } else {
            okGameCode.setText("OK");
            okGameName.setText("Patched");
        }
        if (detectedIcon != null) {
            okGameLogo.setImageDrawable(detectedIcon);
            okGameLogo.setClipToOutline(true);
            okGameLogo.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
                }
            });
            okGameLogo.setVisibility(View.VISIBLE);
            okGameCode.setVisibility(View.GONE);
            okGameBadge.setBackground(null);
        } else {
            okGameLogo.setVisibility(View.GONE);
            okGameCode.setVisibility(View.VISIBLE);
        }
        if (outApk != null) {
            okFileName.setText(outApk.getName());
            okFileSize.setText(human(outApk.length()));
        }
    }

    private void installOutput() {
        if (outApk == null || !outApk.isFile()) return;
        if (confirmInstall) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DarkDialog)
                    .setTitle(R.string.install_confirm_title)
                    .setMessage(R.string.install_confirm_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.install, (d, w) -> launchInstaller())
                    .show();
            return;
        }
        launchInstaller();
    }

    private Runnable pendingInstall;

    private final ActivityResultLauncher<Intent> uninstallFlow = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                Runnable r = pendingInstall;
                pendingInstall = null;
                if (r != null) main.post(r);
            });

    private void launchInstaller() {
        String targetPkg = detectedGame == null ? null
                : detectedGame.packages.isEmpty() ? null : detectedGame.packages.get(0);
        installedPackage = targetPkg;
        if (targetPkg != null && isPackageInstalled(targetPkg)) {
            appendLog("INFO", targetPkg + " is installed — uninstalling first (signature mismatch after patch)");
            pendingInstall = this::doRealInstall;
            try {
                Intent uninstall = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
                uninstall.setData(Uri.parse("package:" + targetPkg));
                uninstall.putExtra(Intent.EXTRA_RETURN_RESULT, true);
                uninstallFlow.launch(uninstall);
            } catch (Exception ex) {
                appendLog("ERROR", "Could not launch uninstall: " + ex.getMessage());
                doRealInstall();
            }
        } else {
            doRealInstall();
        }
    }

    private boolean isPackageInstalled(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (Exception e) { return false; }
    }

    private void doRealInstall() {
        String name = outApk == null ? "" : outApk.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".apks") || name.endsWith(".xapk")) {
            installContainer(outApk);
            return;
        }
        try {
            Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", outApk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            main.postDelayed(() -> { populateInstalled(); show(Screen.INSTALLED); }, 900);
        } catch (Exception ignored) {}
    }

    private final java.util.LinkedHashMap<String, File> pendingObbs = new java.util.LinkedHashMap<>();

    private void installContainer(File container) {
        try {
            File dir = new File(getCacheDir(), "install-" + System.currentTimeMillis());
            Files2.mkdirs(dir);
            pendingObbs.clear();
            java.util.ArrayList<File> apks = extractApksAndObbsForInstall(container, dir);
            if (apks.isEmpty()) throw new IllegalStateException("No APKs found in container");
            PackageInstaller installer = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int sessionId = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(sessionId);
            for (File apk : apks) {
                try (java.io.OutputStream out = session.openWrite(apk.getName(), 0, apk.length());
                     java.io.InputStream in = new java.io.FileInputStream(apk)) {
                    byte[] buf = new byte[64 * 1024]; int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    session.fsync(out);
                }
            }
            Intent callback = new Intent("com.dmz.apkpatcher.INSTALL_RESULT").setPackage(getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(this, sessionId, callback, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            installedPackage = detectedGame == null ? null : detectedGame.packages.isEmpty() ? null : detectedGame.packages.get(0);
            if (!pendingObbs.isEmpty())
                appendLog("INFO", "Container has " + pendingObbs.size() + " OBB file(s), will restore after install");
            session.commit(pi.getIntentSender());
            session.close();
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Install failed: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private java.util.ArrayList<File> extractApksAndObbsForInstall(File container, File dest) throws Exception {
        java.util.ArrayList<File> out = new java.util.ArrayList<>();
        try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(new java.io.FileInputStream(container))) {
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".apk")) {
                    File target = new File(dest, safeName(new File(name).getName()));
                    try (java.io.OutputStream o = new java.io.FileOutputStream(target)) {
                        byte[] buf = new byte[64 * 1024]; int n;
                        while ((n = zin.read(buf)) != -1) o.write(buf, 0, n);
                    }
                    out.add(target);
                } else if (lower.endsWith(".obb")) {
                    File target = new File(dest, safeName(new File(name).getName()));
                    try (java.io.OutputStream o = new java.io.FileOutputStream(target)) {
                        byte[] buf = new byte[64 * 1024]; int n;
                        while ((n = zin.read(buf)) != -1) o.write(buf, 0, n);
                    }
                    pendingObbs.put(new File(name).getName(), target);
                }
            }
        }
        return out;
    }

    private void restoreObbsFor(String targetPkg) {
        if (pendingObbs.isEmpty() || targetPkg == null) return;
        File obbDir = new File(android.os.Environment.getExternalStorageDirectory(),
                "Android/obb/" + targetPkg);
        boolean dirOk = obbDir.isDirectory() || obbDir.mkdirs();
        int copied = 0;
        File fallback = new File(getExternalFilesDir(null), "obb-" + targetPkg);
        if (!dirOk) Files2.mkdirsSilently(fallback);
        for (java.util.Map.Entry<String, File> e : pendingObbs.entrySet()) {
            File src = e.getValue();
            File dst = dirOk ? new File(obbDir, e.getKey()) : new File(fallback, e.getKey());
            try {
                Files2.copy(src, dst);
                copied++;
                appendLog("OK", "OBB restored: " + dst.getAbsolutePath());
            } catch (Exception ex) {
                appendLog("ERROR", "Could not write " + dst + " — " + ex.getMessage());
            }
        }
        if (!dirOk && copied > 0) {
            android.widget.Toast.makeText(this,
                    "OBB saved to " + fallback.getAbsolutePath()
                            + "\nCopy them manually to " + obbDir.getAbsolutePath(),
                    android.widget.Toast.LENGTH_LONG).show();
        }
        pendingObbs.clear();
    }

    private void registerInstallReceiver() {
        installReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try { startActivity(confirm); }
                        catch (Exception e) { appendLog("ERROR", "Confirm intent: " + e.getMessage()); }
                    }
                } else if (status == PackageInstaller.STATUS_SUCCESS) {
                    main.post(() -> {
                        restoreObbsFor(installedPackage);
                        populateInstalled();
                        show(Screen.INSTALLED);
                    });
                } else {
                    appendLog("ERROR", "Install status=" + status + " msg=" + message);
                    android.widget.Toast.makeText(MainActivity.this, "Install failed (" + status + "): " + message, android.widget.Toast.LENGTH_LONG).show();
                }
            }
        };
        IntentFilter filter = new IntentFilter("com.dmz.apkpatcher.INSTALL_RESULT");
        androidx.core.content.ContextCompat.registerReceiver(this, installReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override protected void onDestroy() {
        if (installReceiver != null) { try { unregisterReceiver(installReceiver); } catch (Exception ignored) {} }
        super.onDestroy();
    }

    private void shareOutput() {
        if (outApk == null) return;
        Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", outApk);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("application/vnd.android.package-archive");
        i.putExtra(Intent.EXTRA_STREAM, u);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, getString(R.string.share_patched)));
    }

    private void openOutputFolder() {
        if (outApk == null) return;
        File dir = outApk.getParentFile();
        String path = dir == null ? outApk.getAbsolutePath() : dir.getAbsolutePath();

        // Always copy the path to clipboard + toast so the user has a reliable fallback.
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("path", path));
        } catch (Exception ignored) {}
        android.widget.Toast.makeText(this,
                getString(R.string.path_copied) + "\n" + path,
                android.widget.Toast.LENGTH_LONG).show();

        // Best-effort: ask any file manager to reveal the patched APK via FileProvider.
        // Many file managers will "show in folder" when given an APK with ACTION_VIEW.
        try {
            Uri fileU = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", outApk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(fileU, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(Intent.createChooser(i, getString(R.string.open)));
        } catch (Throwable ignored) {
            // Swallow anything — the toast + clipboard already gave the user the location.
        }
    }

    // ───────────────────────── INSTALLED ─────────────────────────
    private TextView instGameCode, instGameName, instVersion;
    private void wireInstalled() {
        View s = screens[Screen.INSTALLED.ordinal()];
        instGameCode = s.findViewById(R.id.instGameCode);
        instGameName = s.findViewById(R.id.instGameName);
        instVersion = s.findViewById(R.id.instVersion);
        s.findViewById(R.id.btnLaunchGame).setOnClickListener(v -> launchInstalled());
        s.findViewById(R.id.btnOpenSettings).setOnClickListener(v -> openAppSettings());
    }

    private void populateInstalled() {
        if (detectedGame != null) {
            instGameCode.setText(detectedGame.code);
            instGameName.setText(detectedGame.name);
        } else { instGameCode.setText("OK"); instGameName.setText("Installed"); }
        instVersion.setText(installedPackage == null ? "Version: —" : installedPackage);
    }

    private void launchInstalled() {
        if (installedPackage == null) return;
        Intent launch = getPackageManager().getLaunchIntentForPackage(installedPackage);
        if (launch != null) { launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(launch); }
    }

    private void openAppSettings() {
        if (installedPackage == null) return;
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.fromParts("package", installedPackage, null));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {}
    }

    // ───────────────────────── SETTINGS ─────────────────────────
    private void wireSettings() {
        View s = screens[Screen.SETTINGS.ordinal()];
        s.findViewById(R.id.settingsBack).setOnClickListener(v -> show(Screen.HOME));
        android.widget.Switch auto = s.findViewById(R.id.settingAutoMenus);
        android.widget.Switch awake = s.findViewById(R.id.settingKeepAwake);
        android.widget.Switch confirm = s.findViewById(R.id.settingInstallConfirm);
        auto.setChecked(menuAuto); awake.setChecked(keepAwake); confirm.setChecked(confirmInstall);
        auto.setOnCheckedChangeListener((b, checked) -> { menuAuto = checked; getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("autoMenu", checked).apply(); });
        awake.setOnCheckedChangeListener((b, checked) -> { keepAwake = checked; getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("keepAwake", checked).apply(); });
        confirm.setOnCheckedChangeListener((b, checked) -> { confirmInstall = checked; getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("confirmInstall", checked).apply(); });
        s.findViewById(R.id.settingsLanguage).setOnClickListener(v -> show(Screen.LANGUAGE));
        updateCurrentLanguage((TextView) s.findViewById(R.id.currentLanguage));
    }

    private void wireLanguage() {
        View s = screens[Screen.LANGUAGE.ordinal()];
        s.findViewById(R.id.languageBack).setOnClickListener(v -> show(Screen.SETTINGS));
        s.findViewById(R.id.langEnglish).setOnClickListener(v -> applyLocale("en"));
        s.findViewById(R.id.langSpanish).setOnClickListener(v -> applyLocale("es"));
        s.findViewById(R.id.langFrench).setOnClickListener(v -> applyLocale("fr"));
        s.findViewById(R.id.langRussian).setOnClickListener(v -> applyLocale("ru"));
        s.findViewById(R.id.langPortuguese).setOnClickListener(v -> applyLocale("pt-BR"));
    }

    private void applyLocale(String tag) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag));
    }

    private void refreshLanguageChecks() {
        View s = screens[Screen.LANGUAGE.ordinal()];
        String tag = AppCompatDelegate.getApplicationLocales().toLanguageTags();
        int[] ids = { R.id.langEnglishCheck, R.id.langSpanishCheck, R.id.langFrenchCheck, R.id.langRussianCheck, R.id.langPortugueseCheck };
        int[] cards = { R.id.langEnglish, R.id.langSpanish, R.id.langFrench, R.id.langRussian, R.id.langPortuguese };
        String[] tags = { "en", "es", "fr", "ru", "pt" };
        int sel = 0;
        for (int i = 0; i < tags.length; i++) if (tag.startsWith(tags[i])) sel = i;
        if (tag.isEmpty()) sel = 0;
        for (int i = 0; i < ids.length; i++) {
            s.findViewById(ids[i]).setVisibility(i == sel ? View.VISIBLE : View.GONE);
            s.findViewById(cards[i]).setBackgroundResource(i == sel ? R.drawable.pill_accent : R.drawable.pill_surface);
        }
    }

    private void wireLogs() {
        View s = screens[Screen.LOGS.ordinal()];
        s.findViewById(R.id.logsBack).setOnClickListener(v -> show(Screen.HOME));
        logTextView = s.findViewById(R.id.logText);
        logScrollView = s.findViewById(R.id.logScroll);
        s.findViewById(R.id.clearLogs).setOnClickListener(v -> {
            logBuffer.setLength(0);
            refreshLogs();
        });
    }

    private void refreshLogs() {
        if (logTextView == null) return;
        if (logBuffer.length() == 0) logTextView.setText(R.string.logs_empty);
        else logTextView.setText(logBuffer.toString());
        if (logScrollView != null) logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void appendLog(String level, String msg) {
        String ts = new java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new java.util.Date());
        synchronized (logBuffer) {
            logBuffer.append('[').append(ts).append("][").append(level).append("] ").append(msg).append('\n');
        }
        main.post(this::refreshLogs);
    }

    private void wireAbout() {
        View s = screens[Screen.ABOUT.ordinal()];
        s.findViewById(R.id.aboutBack).setOnClickListener(v -> show(Screen.HOME));
    }

    private void refreshHomeSummary() {
        View home = screens[Screen.HOME.ordinal()];
        TextView name = home.findViewById(R.id.homeGameName);
        TextView pkg = home.findViewById(R.id.homeGamePkg);
        TextView code = home.findViewById(R.id.homeGameCode);
        ImageView logo = home.findViewById(R.id.homeGameLogo);
        View badge = home.findViewById(R.id.homeGameCode).getParent() instanceof View ?
                (View) home.findViewById(R.id.homeGameCode).getParent() : null;
        View checkBadge = home.findViewById(R.id.homeDetectedBadge);

        if (fApk == null || detectedGame == null) {
            name.setText(detectedGame == null && fApk != null ? R.string.unknown_game_title : R.string.home_no_game);
            pkg.setText(R.string.home_no_game_sub);
            code.setText("?");
            code.setVisibility(View.VISIBLE);
            logo.setVisibility(View.GONE);
            checkBadge.setVisibility(View.GONE);
        } else {
            name.setText(detectedGame.name);
            ApkManifest.Info info = manifest();
            pkg.setText(info.pkg == null ? "" : info.pkg);
            code.setText(detectedGame.code);
            if (detectedIcon != null) {
                logo.setImageDrawable(detectedIcon);
                logo.setClipToOutline(true);
                logo.setOutlineProvider(new android.view.ViewOutlineProvider() {
                    @Override public void getOutline(View v, android.graphics.Outline o) {
                        o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12));
                    }
                });
                logo.setVisibility(View.VISIBLE);
                code.setVisibility(View.GONE);
                if (badge != null) badge.setBackground(null);
            } else {
                logo.setVisibility(View.GONE);
                code.setVisibility(View.VISIBLE);
                if (badge != null) {
                    GradientDrawable d = new GradientDrawable();
                    d.setCornerRadius(dp(12));
                    d.setColor(parseColor(detectedGame.accent, R.color.accent));
                    badge.setBackground(d);
                }
            }
            checkBadge.setVisibility(View.VISIBLE);
        }

        TextView menuStatus = home.findViewById(R.id.homeMenuStatus);
        TextView menuFile = home.findViewById(R.id.homeMenuFile);
        TextView menuFeatures = home.findViewById(R.id.homeMenuFeatures);
        TextView menuTick = home.findViewById(R.id.homeMenuTick);
        if (fMenuOrSo != null && fMenuOrSo.isFile()) {
            menuStatus.setText(R.string.auto_detected);
            menuStatus.setTextColor(getColor(R.color.success));
            menuTick.setText("✓");
            menuTick.setTextColor(getColor(R.color.success));
            menuFile.setText(fMenuOrSo.getName() + " · " + human(fMenuOrSo.length()));
            if (detectedGame != null && detectedGame.features != null && !detectedGame.features.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                int n = Math.min(3, detectedGame.features.size());
                for (int i = 0; i < n; i++) {
                    if (i > 0) sb.append(" · ");
                    sb.append(detectedGame.features.get(i).name);
                }
                if (detectedGame.features.size() > n) sb.append(" · …");
                menuFeatures.setText(sb.toString());
            } else menuFeatures.setText("");
        } else {
            menuStatus.setText(R.string.none);
            menuStatus.setTextColor(getColor(R.color.muted));
            menuTick.setText("•");
            menuTick.setTextColor(getColor(R.color.muted));
            menuFile.setText("—");
            menuFeatures.setText("");
        }

        TextView sig = home.findViewById(R.id.homeSigningValue);
        if (fKs != null && fKs.isFile()) sig.setText(fKs.getName());
        else sig.setText(R.string.none);
    }

    private void updateCurrentLanguage(TextView target) {
        if (target == null) return;
        String tag = AppCompatDelegate.getApplicationLocales().toLanguageTags();
        if (tag.startsWith("es")) target.setText(R.string.language_spanish);
        else if (tag.startsWith("fr")) target.setText(R.string.language_french);
        else if (tag.startsWith("ru")) target.setText(R.string.language_russian);
        else if (tag.startsWith("pt")) target.setText(R.string.language_portuguese);
        else target.setText(R.string.language_english);
    }

    // ───────────────────────── helpers ─────────────────────────
    private int parseColor(String hex, int fallbackRes) {
        try { return Color.parseColor(hex); } catch (Exception e) { return getColor(fallbackRes); }
    }

    private String queryName(Uri uri, String fallback) {
        ContentResolver r = getContentResolver();
        try (Cursor c = r.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) { String n = c.getString(0); if (n != null && !n.isEmpty()) return n; }
        } catch (Exception ignored) {}
        String p = uri.getLastPathSegment();
        return p == null || p.isEmpty() ? fallback : new File(p).getName();
    }

    private void copyUriTo(Uri uri, File out) throws Exception {
        Files2.mkdirs(out.getParentFile());
        if (out.exists()) out.delete();
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream o = new FileOutputStream(out)) {
            if (in == null) throw new java.io.IOException("Cannot open URI: " + uri);
            Files2.copy(in, o);
        }
    }

    private static String safeName(String n) { return n.replaceAll("[^A-Za-z0-9._-]", "_"); }

    private static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", b / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.2f GB", b / (1024.0 * 1024 * 1024));
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
