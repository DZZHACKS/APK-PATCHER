package dmz.engine;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import com.android.apksig.ApkSigner;

/** v1 + v2 signing (and zip alignment) with apksig, keystore PKCS12 / BKS / JKS. */
public class Signer {

    public static class Key {
        public File store;
        public String storePass, alias, keyPass;
    }

    public static void sign(File unsigned, File signed, Key k, int minSdk, BuildLog log) throws BuildException {
        try {
            KeyStore ks = load(k.store, k.storePass);
            String alias = k.alias;
            if (alias == null || alias.isEmpty() || !ks.containsAlias(alias)) {
                Enumeration<String> al = ks.aliases();
                if (!al.hasMoreElements()) throw new BuildException("Keystore is empty");
                String first = al.nextElement();
                if (alias != null && !alias.isEmpty()) log.log("WARN", "Alias '" + alias + "' not found, using '" + first + "'");
                alias = first;
            }
            String kp = k.keyPass == null || k.keyPass.isEmpty() ? k.storePass : k.keyPass;
            PrivateKey pk = (PrivateKey) ks.getKey(alias, kp.toCharArray());
            Certificate[] chain = ks.getCertificateChain(alias);
            if (pk == null || chain == null) throw new BuildException("No private key for alias '" + alias + "'");
            List<X509Certificate> certs = new ArrayList<X509Certificate>();
            for (Certificate c : chain) certs.add((X509Certificate) c);
            ApkSigner.SignerConfig cfg = new ApkSigner.SignerConfig.Builder(alias, pk, certs).build();
            new ApkSigner.Builder(Collections.singletonList(cfg))
                    .setInputApk(unsigned)
                    .setOutputApk(signed)
                    .setMinSdkVersion(minSdk)
                    .setV1SigningEnabled(true)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(false)
                    .setV4SigningEnabled(false)
                    .setAlignmentPreserved(false)
                    .build()
                    .sign();
        } catch (BuildException e) {
            throw e;
        } catch (java.io.IOException e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("password") || e.getCause() instanceof java.security.UnrecoverableKeyException)
                throw new BuildException("Wrong keystore password", e);
            throw new BuildException("Signing failed: " + e, e);
        } catch (Exception e) {
            throw new BuildException("Signing failed: " + e, e);
        }
    }

    static KeyStore load(File f, String pass) throws Exception {
        if (f == null || !f.isFile()) throw new BuildException("Keystore not found: " + f);
        byte[] head = new byte[4];
        InputStream hs = new FileInputStream(f);
        try { hs.read(head); } finally { hs.close(); }
        String type;
        if ((head[0] & 0xff) == 0x30) type = "PKCS12";                         // DER sequence
        else if ((head[0] & 0xff) == 0xFE && (head[1] & 0xff) == 0xED) type = "JKS";
        else type = "BKS";
        // Platform provider first, then the bundled BouncyCastle: keystores from a recent
        // keytool use PBES2/AES + HmacSHA256, which Android's PKCS12 cannot always decrypt.
        Exception first = null;
        java.util.List<java.security.Provider> providers = new java.util.ArrayList<java.security.Provider>();
        providers.add(null);
        providers.add(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        for (java.security.Provider pr : providers) {
            try {
                KeyStore ks = pr == null ? KeyStore.getInstance(type) : KeyStore.getInstance(type.equals("JKS") ? "PKCS12" : type, pr);
                InputStream in = new FileInputStream(f);
                try { ks.load(in, pass == null ? null : pass.toCharArray()); } finally { in.close(); }
                return ks;
            } catch (Exception e) {
                if (first == null) first = e;
            }
        }
        if (type.equals("JKS"))
            throw new BuildException("This phone cannot read JKS keystores. Convert it once on a PC: "
                    + "keytool -importkeystore -srckeystore old.jks -destkeystore new.p12 -deststoretype PKCS12");
        Throwable root = first;
        while (root.getCause() != null) root = root.getCause();
        boolean badPass = root instanceof java.security.UnrecoverableKeyException
                || String.valueOf(root.getMessage()).toLowerCase().contains("password")
                || String.valueOf(root.getMessage()).toLowerCase().contains("mac");
        throw new BuildException((badPass ? "Wrong keystore password" : "Cannot read the keystore") + " (" + root + ")", first);
    }
}
