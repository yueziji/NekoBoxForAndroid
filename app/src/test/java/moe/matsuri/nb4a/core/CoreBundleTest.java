package moe.matsuri.nb4a.core;

import com.google.gson.JsonObject;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class CoreBundleTest {
    private final KeyPair key;
    public CoreBundleTest() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        key = generator.generateKeyPair();
    }
    private File bundle(String abi, String binding, boolean corrupt, boolean extra) throws Exception {
        byte[] library = new byte[64];
        library[0] = 127; library[1] = 'E'; library[2] = 'L'; library[3] = 'F';
        library[4] = 2; library[5] = 1; library[16] = 3; library[18] = (byte)183;
        JsonObject manifest = new JsonObject();
        manifest.addProperty("schema", 1);
        manifest.addProperty("min_sdk", 23);
        manifest.addProperty("abi", abi);
        manifest.addProperty("binding_sha256", binding);
        manifest.addProperty("version", "1.14.4-test");
        manifest.addProperty("sha256", CoreBundle.hex(MessageDigest.getInstance("SHA-256").digest(library)));
        byte[] data = manifest.toString().getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key.getPrivate()); signer.update(data);
        byte[] signature = signer.sign();
        if (corrupt) library[40] = 1;
        File file = File.createTempFile("nekobox-core-test-", ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            entry(zip, "core.json", data); entry(zip, "core.sig", signature); entry(zip, "libgojni.so", library);
            if (extra) entry(zip, "../unexpected", new byte[0]);
        }
        return file;
    }
    private void entry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(data); zip.closeEntry();
    }
    private CoreBundle verify(File file) throws Exception {
        return CoreBundle.verify(file, key.getPublic().getEncoded(), "binding", "arm64-v8a", 33);
    }
    @Test public void acceptsSignedCompatiblePackage() throws Exception {
        assertEquals("1.14.4-test", verify(bundle("arm64-v8a", "binding", false, false)).version);
    }
    @Test public void rejectsModifiedLibrary() throws Exception {
        assertThrows(SecurityException.class, () -> verify(bundle("arm64-v8a", "binding", true, false)));
    }
    @Test public void rejectsBindingMismatch() throws Exception {
        assertThrows(IOException.class, () -> verify(bundle("arm64-v8a", "other", false, false)));
    }
    @Test public void rejectsWrongArchitecture() throws Exception {
        assertThrows(IOException.class, () -> verify(bundle("x86_64", "binding", false, false)));
    }
    @Test public void rejectsUnexpectedPaths() throws Exception {
        assertThrows(IOException.class, () -> verify(bundle("arm64-v8a", "binding", false, true)));
    }
    @Test public void rejectsUntrustedSigner() throws Exception {
        File bundle = bundle("arm64-v8a", "binding", false, false);
        KeyPairGenerator other = KeyPairGenerator.getInstance("RSA"); other.initialize(2048);
        assertThrows(SecurityException.class, () -> CoreBundle.verify(bundle, other.generateKeyPair().getPublic().getEncoded(), "binding", "arm64-v8a", 33));
    }
    @Test public void rejectsUnsupportedAndroid() throws Exception {
        File bundle = bundle("arm64-v8a", "binding", false, false);
        assertThrows(IOException.class, () -> CoreBundle.verify(bundle, key.getPublic().getEncoded(), "binding", "arm64-v8a", 21));
    }
}
