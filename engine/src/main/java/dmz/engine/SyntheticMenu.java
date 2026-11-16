package dmz.engine;

import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Build a minimal "menu APK" zip from just a native .so. The generated zip contains one
 * synthesized class {@code com.android.support.Launcher} whose static {@code start()} method
 * calls {@code System.loadLibrary(stem)}, plus the .so itself under {@code lib/arm64-v8a/}.
 * The result is a valid {@link ApkPatcher#patch} input, so the user can drop just a built
 * .so without having to ship a separate menu APK.
 */
public final class SyntheticMenu {
    private static final String LAUNCHER = "Lcom/android/support/Launcher;";
    private static final int API = 28;

    private SyntheticMenu() {}

    public static File build(File soFile, File outMenuApk) throws Exception {
        return build(Collections.singletonList(soFile), outMenuApk);
    }

    /** Multi-so variant: Launcher.start() calls System.loadLibrary(stem) for each .so in order,
     *  and all .so files are bundled under lib/arm64-v8a/ in the synthetic menu apk. */
    public static File build(List<File> soFiles, File outMenuApk) throws Exception {
        if (soFiles == null || soFiles.isEmpty()) throw new BuildException("No .so files given");
        List<String> libStems = new ArrayList<String>();
        for (File f : soFiles) libStems.add(stripLibPrefixAndExt(f.getName()));

        Opcodes op = Opcodes.forApi(API);
        ClassDef launcher = makeLauncherClass(libStems);
        File dex = new File(outMenuApk.getParentFile(), "synth-classes.dex");
        Files2.mkdirs(dex.getParentFile());
        DexPool.writeTo(dex.getAbsolutePath(), new ImmutableDexFile(op,
                Collections.singletonList(launcher)));
        byte[] dexBytes = Files2.readBytes(dex);
        dex.delete();

        ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(outMenuApk));
        try {
            zo.putNextEntry(new ZipEntry("classes.dex"));
            zo.write(dexBytes);
            zo.closeEntry();
            for (File so : soFiles) {
                zo.putNextEntry(new ZipEntry("lib/arm64-v8a/" + so.getName()));
                zo.write(Files2.readBytes(so));
                zo.closeEntry();
            }
        } finally { zo.close(); }
        return outMenuApk;
    }

    private static ClassDef makeLauncherClass(List<String> libStems) {
        MethodReference loadLib = new ImmutableMethodReference(
                "Ljava/lang/System;", "loadLibrary",
                Collections.<CharSequence>singletonList("Ljava/lang/String;"), "V");
        MutableMethodImplementation impl = new MutableMethodImplementation(1);
        for (String stem : libStems) {
            impl.addInstruction(new BuilderInstruction21c(Opcode.CONST_STRING, 0,
                    new ImmutableStringReference(stem)));
            impl.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 1,
                    0, 0, 0, 0, 0, loadLib));
        }
        impl.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));

        ImmutableMethod start = new ImmutableMethod(LAUNCHER, "start",
                Collections.<com.android.tools.smali.dexlib2.iface.MethodParameter>emptyList(),
                "V", 0x9,
                null, null,
                new ImmutableMethodImplementation(impl.getRegisterCount(), impl.getInstructions(),
                        impl.getTryBlocks(), impl.getDebugItems()));

        List<com.android.tools.smali.dexlib2.iface.Method> direct = new ArrayList<com.android.tools.smali.dexlib2.iface.Method>();
        direct.add(start);
        return new ImmutableClassDef(LAUNCHER, 0x1, "Ljava/lang/Object;",
                null, null, null,
                Collections.<com.android.tools.smali.dexlib2.iface.Field>emptyList(),
                Collections.<com.android.tools.smali.dexlib2.iface.Field>emptyList(),
                direct,
                Collections.<com.android.tools.smali.dexlib2.iface.Method>emptyList());
    }

    private static String stripLibPrefixAndExt(String name) {
        String n = name;
        if (n.endsWith(".so")) n = n.substring(0, n.length() - 3);
        if (n.startsWith("lib")) n = n.substring(3);
        return n;
    }
}
