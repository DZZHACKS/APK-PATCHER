package dmz.engine;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Date;

public final class KeystoreCreator {
    private KeystoreCreator() {}

    public static void create(File out, String password, String alias, String commonName) throws Exception {
        if (out == null) throw new BuildException("Keystore path required");
        if (password == null || password.length() < 6)
            throw new BuildException("Password must be at least 6 characters");
        if (alias == null || alias.isEmpty()) alias = "zli";
        if (commonName == null || commonName.isEmpty()) commonName = "ZLI APK Patcher";
        Files2.mkdirs(out.getParentFile());

        BouncyCastleProvider bc = new BouncyCastleProvider();
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        Date notBefore = new Date();
        Date notAfter = new Date(notBefore.getTime() + 25L * 365L * 24L * 60L * 60L * 1000L);
        X500Name subject = new X500Name("CN=" + escape(commonName));
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(bc).build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter()
                .setProvider(bc).getCertificate(builder.build(signer));

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(alias, kp.getPrivate(), password.toCharArray(), new X509Certificate[]{cert});
        try (FileOutputStream fos = new FileOutputStream(out)) {
            ks.store(fos, password.toCharArray());
        }
    }

    private static String escape(String s) {
        return s.replace(",", "\\,").replace("=", "\\=").replace("\"", "\\\"");
    }
}
