package dmz.engine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Small file helpers (java.nio.file is limited on older Android). */
public final class Files2 {
    private Files2() {}

    public static String read(File f) throws IOException {
        return new String(readBytes(f), "UTF-8");
    }

    public static byte[] readBytes(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try { return readAll(in); } finally { in.close(); }
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        copy(in, bo);
        return bo.toByteArray();
    }

    public static void write(File f, String s) throws IOException { write(f, s.getBytes("UTF-8")); }

    public static void write(File f, byte[] b) throws IOException {
        mkdirs(f.getParentFile());
        OutputStream o = new FileOutputStream(f);
        try { o.write(b); } finally { o.close(); }
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    public static void copy(File from, File to) throws IOException {
        mkdirs(to.getParentFile());
        InputStream in = new FileInputStream(from);
        try {
            OutputStream o = new FileOutputStream(to);
            try { copy(in, o); } finally { o.close(); }
        } finally { in.close(); }
    }

    public static void mkdirsSilently(File d) {
        try { mkdirs(d); } catch (IOException ignored) {}
    }

    public static void mkdirs(File d) throws IOException {
        // mkdirs() is false when another build thread created it first: only fail if still missing.
        if (d != null && !d.isDirectory() && !d.mkdirs() && !d.isDirectory()) throw new IOException("Cannot create " + d);
    }

    public static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null && !isSymlink(f)) for (File k : kids) deleteTree(k);
        f.delete();
    }

    static boolean isSymlink(File f) {
        try {
            File parent = f.getParentFile() == null ? null : f.getParentFile().getCanonicalFile();
            File self = parent == null ? f.getAbsoluteFile() : new File(parent, f.getName());
            return !self.getCanonicalPath().equals(self.getAbsolutePath());
        } catch (IOException e) { return false; }
    }

    /** All files under dir with one of the extensions (".java"), sorted. */
    public static List<File> list(File dir, String... exts) {
        List<File> out = new ArrayList<File>();
        walk(dir, exts, out);
        java.util.Collections.sort(out);
        return out;
    }

    private static void walk(File d, String[] exts, List<File> out) {
        File[] kids = d.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) walk(k, exts, out);
            else for (String e : exts) if (k.getName().endsWith(e)) { out.add(k); break; }
        }
    }

    /** dir-relative path with forward slashes. */
    public static String rel(File base, File f) {
        String b = base.getAbsolutePath(), p = f.getAbsolutePath();
        String r = p.startsWith(b) ? p.substring(b.length()) : p;
        r = r.replace(File.separatorChar, '/');
        return r.startsWith("/") ? r.substring(1) : r;
    }
}
