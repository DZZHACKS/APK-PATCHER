package dmz.engine;

import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Inject a built menu (class files under com.android.support.* + native payload) into an existing
 * APK and re-sign it. Mirrors the Portable WinForms Patch flow, but done on-device with dexlib2
 * instead of apktool + smali tools.
 *
 * <p>The target APK is treated as a ZIP: menu classes from every {@code classes*.dex} of the menu
 * APK are merged into the target's main {@code classes.dex}; every {@code lib/arm64-v8a/*.so} of
 * the menu APK plus an optional sidecar payload are copied over; the target's launcher activity
 * {@code onCreate(Landroid/os/Bundle;)V} gets an {@code invoke-static} to
 * {@code Lcom/android/support/Launcher;->start()V} inserted before the first instruction. The
 * result is re-packed as a ZIP and signed with the caller-provided {@link Signer.Key}.
 *
 * <p>Smali text editing (like the Portable "Custom Smali edits" box) is NOT supported here:
 * the dex rewriter works at the IR level, so user-supplied smali snippets cannot be pasted. The
 * hook injection covers the common case. APKS / XAPK containers are not supported: extract the
 * base APK first.
 */
public final class ApkPatcher {
    private static final String LAUNCHER_START = "Lcom/android/support/Launcher;";
    private static final String SUPPORT_PREFIX = "Lcom/android/support/";
    private static final int ARG_API = 28;

    private final BuildLog log;

    private String bootstrapClassDesc;
    private String bootstrapMethodName;
    private java.util.List<String> bootstrapParamTypes;

    public ApkPatcher(BuildLog log) { this.log = log; }

    public void setBootstrap(String sig) {
        if (sig == null || sig.isEmpty()) {
            bootstrapClassDesc = null; bootstrapMethodName = null; bootstrapParamTypes = null;
            return;
        }
        int arrow = sig.indexOf("->");
        bootstrapClassDesc = sig.substring(0, arrow);
        String rest = sig.substring(arrow + 2);
        int paren = rest.indexOf('(');
        int endParen = rest.indexOf(')');
        bootstrapMethodName = rest.substring(0, paren);
        bootstrapParamTypes = parseParams(rest.substring(paren + 1, endParen));
    }

    private static java.util.List<String> parseParams(String raw) {
        java.util.List<String> out = new ArrayList<String>();
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == 'L') { int s = raw.indexOf(';', i); out.add(raw.substring(i, s + 1)); i = s + 1; }
            else if (c == '[') { int j = i; while (j < raw.length() && raw.charAt(j) == '[') j++;
                if (raw.charAt(j) == 'L') { int s = raw.indexOf(';', j); out.add(raw.substring(i, s + 1)); i = s + 1; }
                else { out.add(raw.substring(i, j + 1)); i = j + 1; } }
            else { out.add(String.valueOf(c)); i++; }
        }
        return out;
    }

    /**
     * Entry point used by the Bridge. Dispatches on extension: plain {@code .apk} goes through
     * {@link #patch}, {@code .apks} or {@code .xapk} containers are extracted, their base APK is
     * patched, every split APK inside the container is re-signed with the same key, and the
     * container is re-packed as a ZIP with the same extension. Anything else is refused.
     */
    public Result patchAny(File input, File menuApk, File payloadSo, String launcherActivity,
                           Signer.Key key, File outDir, File workDir) throws Exception {
        String ext = extOf(input.getName());
        if (".apk".equals(ext)) return patch(input, menuApk, payloadSo, launcherActivity, key, outDir, workDir);
        if (!".apks".equals(ext) && !".xapk".equals(ext))
            throw new BuildException("Only .apk, .apks and .xapk files are supported (got " + ext + ").");
        return patchContainer(input, menuApk, payloadSo, launcherActivity, key, outDir, workDir, ext);
    }

    private Result patchContainer(File input, File menuApk, File payloadSo, String launcherActivity,
                                  Signer.Key key, File outDir, File workDir, String ext) throws Exception {
        log.log("INFO", "[patch] opening " + ext.toUpperCase() + " container " + input.getName());
        Files2.deleteTree(workDir);
        Files2.mkdirs(workDir);
        File extracted = new File(workDir, "package");
        Files2.mkdirs(extracted);
        extractZipFlat(input, extracted);
        File base = findBaseApk(extracted);
        log.log("INFO", "[patch] base APK inside container: " + Files2.rel(extracted, base));

        File innerOut = new File(workDir, "inner-out");
        Files2.mkdirs(innerOut);
        Result r = patch(base, menuApk, payloadSo, launcherActivity, key, innerOut, new File(workDir, "inner-work"));

        // Overwrite base.apk with the patched one and re-sign every split APK in place.
        Files2.copy(r.outApk, base);
        List<File> splits = findAllApks(extracted);
        int signedSplits = 0;
        int droppedSplits = 0;
        for (File split : splits) {
            if (split.getAbsolutePath().equals(base.getAbsolutePath())) continue;
            // 64-bit only: discard arch splits for armeabi-v7a / x86 / x86_64 / mips.
            String sn = split.getName().toLowerCase();
            if (sn.contains("armeabi_v7a") || sn.contains("armeabi-v7a")
                    || sn.contains("x86_64") || sn.matches(".*(^|[_\\-\\.])x86([_\\-\\.].*|$)")
                    || sn.contains("mips")) {
                split.delete();
                droppedSplits++;
                continue;
            }
            File tmp = new File(split.getParentFile(), split.getName() + ".dmz-signing");
            Signer.sign(split, tmp, key, 21, log);
            Files2.copy(tmp, split);
            tmp.delete();
            signedSplits++;
        }
        if (droppedSplits > 0) log.log("INFO", "[patch] stripped " + droppedSplits + " non-arm64 split APK(s)");
        log.log("OK", "[patch] re-signed " + signedSplits + " split APK(s)");

        File dest = new File(outDir, baseName(input) + "-patched" + ext);
        if (dest.isFile()) dest.delete();
        zipFlat(extracted, dest);
        r.outApk = dest;
        return r;
    }

    public Result patch(File targetApk, File menuApk, File payloadSo, String launcherActivity,
                        Signer.Key key, File outDir, File workDir) throws Exception {
        if (!targetApk.isFile()) throw new BuildException("Target APK not found: " + targetApk);
        if (!menuApk.isFile()) throw new BuildException("Menu APK not found: " + menuApk);
        log.progress(2, "Reading target APK");
        Files2.deleteTree(workDir);
        Files2.mkdirs(workDir);
        // Only the menu is loaded in full (small). Target is streamed to avoid OOM on 500 MB+ APKs.
        Map<String, byte[]> menu = readZip(menuApk);

        // 1. Collect menu classes. Default = only com.android.support.*. With a custom bootstrap,
        //    merge EVERY non-Android menu class so the mod's full MainActivity + helpers come in.
        log.progress(10, "Collecting menu classes");
        Opcodes op = Opcodes.forApi(ARG_API);
        Map<String, ClassDef> support = new LinkedHashMap<String, ClassDef>();
        boolean customBootstrap = bootstrapClassDesc != null;
        for (Map.Entry<String, byte[]> e : menu.entrySet()) {
            if (!e.getKey().matches("classes(\\d*)\\.dex")) continue;
            File tmp = new File(workDir, "menu-" + e.getKey());
            Files2.write(tmp, e.getValue());
            DexFile df = DexFileFactory.loadDexFile(tmp, op);
            for (ClassDef cd : df.getClasses()) {
                String t = cd.getType();
                if (customBootstrap) {
                    if (!t.startsWith("Landroid/") && !t.startsWith("Ljava/") && !t.startsWith("Lkotlin/")
                            && !t.startsWith("Ldalvik/")) support.put(t, cd);
                } else if (t.startsWith(SUPPORT_PREFIX)) support.put(t, cd);
            }
        }
        if (customBootstrap) {
            if (support.get(bootstrapClassDesc) == null)
                throw new BuildException("Menu APK does not contain bootstrap class " + bootstrapClassDesc);
            log.log("INFO", "[patch] bootstrap " + bootstrapClassDesc + "->" + bootstrapMethodName + " · menu classes to merge: " + support.size());
        } else {
            if (support.get(LAUNCHER_START) == null) throw new BuildException("Menu APK does not contain Lcom/android/support/Launcher;");
            log.log("INFO", "[patch] menu classes to merge: " + support.size());
        }

        // 2. Extract ALL classes*.dex from the target (Unity + multidex apps put the launcher
        //    in classes2.dex / classes3.dex / etc.). Scan them for the launcher activity.
        log.progress(25, "Scanning target dex files");
        Map<String, File> targetDexFiles = extractAllDex(targetApk, workDir);
        if (!targetDexFiles.containsKey("classes.dex"))
            throw new BuildException("Target APK has no classes.dex");

        String activityType = classToDescriptor(launcherActivity);
        String launcherDexName = null;
        Set<String> targetTypes = new HashSet<String>();
        for (Map.Entry<String, File> e : targetDexFiles.entrySet()) {
            DexFile df = DexFileFactory.loadDexFile(e.getValue(), op);
            for (ClassDef cd : df.getClasses()) {
                targetTypes.add(cd.getType());
                if (launcherDexName == null && cd.getType().equals(activityType)) launcherDexName = e.getKey();
            }
        }
        if (launcherDexName == null)
            throw new BuildException("Launcher activity not found in any classes*.dex: " + launcherActivity);
        log.log("INFO", "[patch] launcher activity found in " + launcherDexName);

        // Patch the dex that contains the launcher: rewrite its onCreate.
        DexFile launcherDf = DexFileFactory.loadDexFile(targetDexFiles.get(launcherDexName), op);
        List<ClassDef> launcherClasses = new ArrayList<ClassDef>();
        for (ClassDef cd : launcherDf.getClasses()) {
            if (cd.getType().equals(activityType)) launcherClasses.add(withHookedOnCreate(cd));
            else launcherClasses.add(cd);
        }
        launcherDf = null;

        // Merge menu classes into classes.dex (cross-dex refs work at runtime).
        boolean launcherIsMain = "classes.dex".equals(launcherDexName);
        List<ClassDef> mainClasses;
        if (launcherIsMain) {
            mainClasses = launcherClasses;
        } else {
            DexFile mainDf = DexFileFactory.loadDexFile(targetDexFiles.get("classes.dex"), op);
            mainClasses = new ArrayList<ClassDef>();
            for (ClassDef cd : mainDf.getClasses()) mainClasses.add(cd);
            mainDf = null;
        }
        for (Map.Entry<String, ClassDef> e : support.entrySet()) {
            if (targetTypes.contains(e.getKey())) continue;
            ClassDef mc = e.getValue();
            mainClasses.add(new ImmutableClassDef(mc.getType(), mc.getAccessFlags(), mc.getSuperclass(),
                    mc.getInterfaces(), mc.getSourceFile(), mc.getAnnotations(), mc.getStaticFields(),
                    mc.getInstanceFields(), mc.getDirectMethods(), mc.getVirtualMethods()));
        }

        // 3. Write the patched dex files. Map zip-entry-name → local patched file.
        log.progress(55, "Writing patched dex files");
        Map<String, File> patchedDex = new LinkedHashMap<String, File>();
        File patchedMain = new File(workDir, "out-classes.dex");
        DexPool.writeTo(patchedMain.getAbsolutePath(), new ImmutableDexFile(op, mainClasses));
        patchedDex.put("classes.dex", patchedMain);
        mainClasses = null;
        if (!launcherIsMain) {
            File patchedLauncher = new File(workDir, "out-" + launcherDexName);
            DexPool.writeTo(patchedLauncher.getAbsolutePath(), new ImmutableDexFile(op, launcherClasses));
            patchedDex.put(launcherDexName, patchedLauncher);
        }
        launcherClasses = null;
        for (File f : targetDexFiles.values()) f.delete();

        // 4. Stream-repack: copy each entry of target to output on the fly, replacing classes.dex.
        log.progress(75, "Repacking APK");
        File unsigned = new File(workDir, "unsigned.apk");
        Set<String> wroteLib = new HashSet<String>();
        ZipOutputStream out = new ZipOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(unsigned)));
        int droppedAbi = 0;
        try {
            ZipInputStream in = new ZipInputStream(new java.io.BufferedInputStream(new FileInputStream(targetApk)));
            try {
                ZipEntry ze;
                byte[] buf = new byte[1 << 16];
                while ((ze = in.getNextEntry()) != null) {
                    if (ze.isDirectory()) continue;
                    String name = ze.getName();
                    if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA")
                            || name.endsWith(".DSA") || name.endsWith(".EC") || name.equalsIgnoreCase("META-INF/MANIFEST.MF")))
                        continue;
                    // Force 64-bit only: drop lib/armeabi-v7a/, lib/x86/, lib/x86_64/, lib/mips*/ ...
                    if (name.startsWith("lib/") && !name.startsWith("lib/arm64-v8a/")) { droppedAbi++; continue; }
                    File patched = patchedDex.get(name);
                    if (patched != null) {
                        java.io.InputStream dexIn = new java.io.FileInputStream(patched);
                        try { pipeEntry(out, name, dexIn, buf); } finally { dexIn.close(); }
                    } else {
                        pipeEntry(out, name, in, buf);
                        if (name.startsWith("lib/arm64-v8a/") && name.endsWith(".so")) wroteLib.add(name);
                    }
                }
            } finally { in.close(); }
            if (droppedAbi > 0) log.log("INFO", "[patch] stripped " + droppedAbi + " non-arm64 native lib entry(s)");

            for (Map.Entry<String, byte[]> e : menu.entrySet()) {
                String name = e.getKey();
                if (!name.startsWith("lib/arm64-v8a/") || !name.endsWith(".so")) continue;
                if (wroteLib.contains(name)) continue;
                writeEntry(out, name, e.getValue());
                wroteLib.add(name);
            }
            if (payloadSo != null && payloadSo.isFile()) {
                String name = "lib/arm64-v8a/" + payloadSo.getName();
                if (!wroteLib.contains(name)) {
                    java.io.InputStream payIn = new java.io.FileInputStream(payloadSo);
                    try { pipeEntry(out, name, payIn, new byte[1 << 16]); } finally { payIn.close(); }
                    wroteLib.add(name);
                }
            }
        } finally { out.close(); }
        for (File f : patchedDex.values()) f.delete();

        // 5. Sign.
        log.progress(90, "Signing patched APK");
        File dest = new File(outDir, baseName(targetApk) + "-patched.apk");
        Files2.mkdirs(outDir);
        if (key == null) throw new BuildException("No signing key available: import one in Settings or pull a menu with keystore.properties.");
        Signer.sign(unsigned, dest, key, 21, log);
        log.progress(100, "Done");
        Result r = new Result();
        r.outApk = dest;
        r.hookedActivity = launcherActivity;
        r.libsAdded = wroteLib.size();
        r.menuClasses = support.size();
        return r;
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private static Map<String, byte[]> readZip(File apk) throws IOException {
        Map<String, byte[]> m = new LinkedHashMap<String, byte[]>();
        ZipInputStream in = new ZipInputStream(new FileInputStream(apk));
        try {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                m.put(e.getName(), Files2.readAll(in));
            }
        } finally { in.close(); }
        return m;
    }

    private static void writeEntry(ZipOutputStream out, String name, byte[] data) throws IOException {
        ZipEntry z = new ZipEntry(name);
        out.putNextEntry(z);
        out.write(data);
        out.closeEntry();
    }

    private static void pipeEntry(ZipOutputStream out, String name, java.io.InputStream data, byte[] buf) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        int n;
        while ((n = data.read(buf)) > 0) out.write(buf, 0, n);
        out.closeEntry();
    }

    /** Extract every classes*.dex from {@code apk} to {@code workDir}, returning name→localFile. */
    private static Map<String, File> extractAllDex(File apk, File workDir) throws IOException {
        Map<String, File> out = new LinkedHashMap<String, File>();
        ZipInputStream in = new ZipInputStream(new java.io.BufferedInputStream(new FileInputStream(apk)));
        try {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory() || !name.matches("classes\\d*\\.dex")) continue;
                File dest = new File(workDir, "in-" + name);
                Files2.mkdirs(dest.getParentFile());
                java.io.OutputStream o = new FileOutputStream(dest);
                try { Files2.copy(in, o); } finally { o.close(); }
                out.put(name, dest);
            }
        } finally { in.close(); }
        return out;
    }

    static String classToDescriptor(String className) {
        if (className == null || className.isEmpty()) throw new IllegalArgumentException("Empty launcher activity");
        String c = className.trim();
        if (c.startsWith("L") && c.endsWith(";")) return c.replace('.', '/');
        return "L" + c.replace('.', '/') + ";";
    }

    static String baseName(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase();
    }

    /** Extract a ZIP (apks / xapk) to {@code dest} without allowing zip-slip. */
    private static void extractZipFlat(File zip, File dest) throws IOException {
        String root = dest.getCanonicalPath() + File.separator;
        ZipInputStream in = new ZipInputStream(new FileInputStream(zip));
        try {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                File out = new File(dest, e.getName().replace('/', File.separatorChar));
                if (!out.getCanonicalPath().startsWith(root))
                    throw new IOException("Unsafe entry in container: " + e.getName());
                if (e.isDirectory()) { Files2.mkdirs(out); continue; }
                Files2.mkdirs(out.getParentFile());
                FileOutputStream fo = new FileOutputStream(out);
                try { Files2.copy(in, fo); } finally { fo.close(); }
            }
        } finally { in.close(); }
    }

    /** Re-pack {@code dir} into a ZIP container at {@code dest} (used for .apks / .xapk output). */
    private static void zipFlat(File dir, File dest) throws IOException {
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(dest));
        try {
            String root = dir.getCanonicalPath();
            for (File f : Files2.list(dir, "")) {                  // "" matches every file (String.endsWith(""))
                String rel = f.getCanonicalPath().substring(root.length() + 1).replace(File.separatorChar, '/');
                ZipEntry z = new ZipEntry(rel);
                out.putNextEntry(z);
                FileInputStream in = new FileInputStream(f);
                try { Files2.copy(in, out); } finally { in.close(); }
                out.closeEntry();
            }
        } finally { out.close(); }
    }

    /** base.apk if present, otherwise the largest .apk the container ships. */
    static File findBaseApk(File dir) throws IOException {
        List<File> apks = findAllApks(dir);
        if (apks.isEmpty()) throw new IOException("No .apk inside the container.");
        for (File f : apks) if (f.getName().equalsIgnoreCase("base.apk")) return f;
        File biggest = apks.get(0);
        for (File f : apks) if (f.length() > biggest.length()) biggest = f;
        return biggest;
    }

    static List<File> findAllApks(File dir) {
        List<File> out = new ArrayList<File>();
        collectApks(dir, out);
        return out;
    }

    private static void collectApks(File d, List<File> out) {
        File[] kids = d.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) collectApks(k, out);
            else if (k.getName().toLowerCase().endsWith(".apk")) out.add(k);
        }
    }

    /**
     * Return a rewritten class with {@code Launcher.start()} injected at the top of every
     * {@code onCreate} overload (Bundle or Bundle + PersistableBundle). When the launcher
     * activity doesn't define its own {@code onCreate} (typical of UE4 / UE5 games whose
     * GameActivity inherits from {@code android.app.NativeActivity}) a synthetic
     * {@code onCreate(Bundle)} is generated that calls {@code super.onCreate} and then the
     * launcher hook, matching what AddStartHook() does in the Portable tool.
     */
    private ClassDef withHookedOnCreate(ClassDef cd) {
        List<Method> direct = new ArrayList<Method>();
        List<Method> virtual = new ArrayList<Method>();
        for (Method m : cd.getDirectMethods()) direct.add(m);
        boolean touched = false;
        for (Method m : cd.getVirtualMethods()) {
            if (!"onCreate".equals(m.getName())) { virtual.add(m); continue; }
            List<? extends CharSequence> params = m.getParameterTypes();
            boolean bundle1 = params.size() == 1 && "Landroid/os/Bundle;".contentEquals(params.get(0));
            boolean bundle2 = params.size() == 2
                    && "Landroid/os/Bundle;".contentEquals(params.get(0))
                    && "Landroid/os/PersistableBundle;".contentEquals(params.get(1));
            if (!bundle1 && !bundle2) { virtual.add(m); continue; }
            MethodImplementation impl = m.getImplementation();
            if (impl == null) { virtual.add(m); continue; }
            MutableMethodImplementation mut = new MutableMethodImplementation(impl);
            mut.addInstruction(0, bootstrapCall(thisRegOf(m)));
            virtual.add(new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(),
                    m.getReturnType(), m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(),
                    new ImmutableMethodImplementation(mut.getRegisterCount(), mut.getInstructions(),
                            mut.getTryBlocks(), mut.getDebugItems())));
            log.log("OK", "[patch] hooked existing onCreate(" + (bundle2 ? "Bundle,PersistableBundle" : "Bundle") + ") of " + humanType(cd.getType()));
            touched = true;
        }
        if (!touched) {
            virtual.add(syntheticOnCreateWithHook(cd.getType(), cd.getSuperclass()));
            log.log("OK", "[patch] synthesized onCreate(Bundle) on " + humanType(cd.getType()) + " (inherits from " + humanType(cd.getSuperclass()) + ")");
        }
        return new ImmutableClassDef(cd.getType(), cd.getAccessFlags(), cd.getSuperclass(),
                cd.getInterfaces(), cd.getSourceFile(), cd.getAnnotations(),
                cd.getStaticFields(), cd.getInstanceFields(), direct, virtual);
    }

    private static BuilderInstruction35c launcherStartCall() {
        MethodReference startRef = new ImmutableMethodReference(LAUNCHER_START, "start",
                Collections.<CharSequence>emptyList(), "V");
        return new BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, startRef);
    }

    /** Build the right bootstrap call instruction for the current patcher config. */
    private com.android.tools.smali.dexlib2.builder.BuilderInstruction bootstrapCall(int thisReg) {
        if (bootstrapClassDesc == null) return launcherStartCall();
        MethodReference ref = new ImmutableMethodReference(bootstrapClassDesc, bootstrapMethodName,
                (java.util.List) bootstrapParamTypes, "V");
        if (bootstrapParamTypes.isEmpty()) {
            return new BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, ref);
        }
        // Single arg = Context: pass the activity's `this` register via invoke-static/range (works for any reg).
        return new com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc(
                Opcode.INVOKE_STATIC_RANGE, thisReg, bootstrapParamTypes.size(), ref);
    }

    private static int thisRegOf(Method m) {
        // Count parameter registers (this + each param, long/double = 2 regs).
        int paramRegs = (m.getAccessFlags() & 0x8) != 0 ? 0 : 1;
        for (CharSequence t : m.getParameterTypes()) {
            char c = t.length() > 0 ? t.charAt(0) : 'V';
            paramRegs += (c == 'J' || c == 'D') ? 2 : 1;
        }
        MethodImplementation impl = m.getImplementation();
        return impl == null ? 0 : impl.getRegisterCount() - paramRegs;
    }

    private ImmutableMethod syntheticOnCreateWithHook(String definingClass, String superType) {
        String parent = superType == null ? "Landroid/app/Activity;" : superType;
        MethodReference superRef = new ImmutableMethodReference(parent, "onCreate",
                Collections.<CharSequence>singletonList("Landroid/os/Bundle;"), "V");
        MutableMethodImplementation impl = new MutableMethodImplementation(2);          // p0 = this (v0), p1 = Bundle (v1)
        impl.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_SUPER, 2, 0, 1, 0, 0, 0, superRef));
        impl.addInstruction(bootstrapCall(0));                                           // thisReg = v0 in the synthetic frame
        impl.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        return new ImmutableMethod(definingClass, "onCreate",
                Collections.singletonList(new ImmutableMethodParameter("Landroid/os/Bundle;", null, null)),
                "V", 0x1,
                null, null,
                new ImmutableMethodImplementation(impl.getRegisterCount(), impl.getInstructions(),
                        impl.getTryBlocks(), impl.getDebugItems()));
    }

    private static String humanType(String desc) {
        if (desc == null) return "<null>";
        if (desc.startsWith("L") && desc.endsWith(";")) return desc.substring(1, desc.length() - 1).replace('/', '.');
        return desc;
    }

    public static final class Result {
        public File outApk;
        public String hookedActivity;
        public int libsAdded;
        public int menuClasses;
    }
}
