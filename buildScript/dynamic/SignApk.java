import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.cert.*;
import java.util.*;
import java.io.*;

/** Sign without materializing the private key in a file or command argument. */
public final class SignApk {
    public static void main(String[] args) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(System.getenv("DYNAMIC_CORE_SIGNING_KEY").trim());
        PrivateKey key;
        try { key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes)); }
        finally { Arrays.fill(bytes, (byte)0); }
        X509Certificate certificate;
        try (InputStream input = Files.newInputStream(Path.of(args[0]))) {
            certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
        var signer = new ApkSigner.SignerConfig.Builder("dynamic", key, List.of(certificate)).build();
        new ApkSigner.Builder(List.of(signer)).setInputApk(new File(args[1])).setOutputApk(new File(args[2]))
            .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true).setV4SigningEnabled(false)
            .build().sign();
        var result = new ApkVerifier.Builder(new File(args[2])).build().verify();
        if (!result.isVerified()) throw new IllegalStateException("APK signature verification failed");
        System.out.println("Signed and verified: " + new File(args[2]).getName());
    }
}
