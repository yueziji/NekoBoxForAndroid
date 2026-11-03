package moe.matsuri.nb4a.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.zip.*;

/** Strict, platform-independent validation. Untrusted metadata never selects a path. */
public final class CoreBundle {
    public static final long MAX_LIBRARY = 128L * 1024 * 1024;
    public final String version, sha256;

    private CoreBundle(String version, String sha256) {
        this.version = version;
        this.sha256 = sha256;
    }

    public static CoreBundle verify(File archive, byte[] publicKey, String binding, String abi, int sdk) throws Exception {
        if (archive.length() > MAX_LIBRARY + 65536) throw new IOException("内核包过大");
        try (ZipFile zip = new ZipFile(archive)) {
            Set<String> names = new HashSet<>();
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                if (!names.add(entry.getName())) throw new IOException("内核包包含重复文件");
            }
            if (!names.equals(new HashSet<>(Arrays.asList("core.json", "core.sig", "libgojni.so"))))
                throw new IOException("内核包结构不正确");
            byte[] manifest = read(zip.getInputStream(zip.getEntry("core.json")), 16384);
            byte[] signature = read(zip.getInputStream(zip.getEntry("core.sig")), 1024);
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicKey)));
            verifier.update(manifest);
            if (!verifier.verify(signature)) throw new SecurityException("内核签名不可信");
            JsonObject info = JsonParser.parseString(new String(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
            if (info.get("schema").getAsInt() != 1 || !binding.equals(info.get("binding_sha256").getAsString()))
                throw new IOException("内核接口与此 App 不兼容，请先更新 App");
            if (!abi.equals(info.get("abi").getAsString())) throw new IOException("内核 CPU 架构不匹配");
            if (info.get("min_sdk").getAsInt() > sdk) throw new IOException("Android 版本过低");
            String version = info.get("version").getAsString();
            String expected = info.get("sha256").getAsString();
            if (!version.matches("[A-Za-z0-9._+-]{1,80}") || !expected.matches("[a-f0-9]{64}"))
                throw new IOException("内核版本信息无效");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = zip.getInputStream(zip.getEntry("libgojni.so"))) {
                byte[] header = new byte[20];
                new DataInputStream(input).readFully(header);
                int machine = (header[18] & 255) | ((header[19] & 255) << 8);
                if (header[0] != 127 || header[1] != 'E' || header[2] != 'L' || header[3] != 'F'
                        || header[4] != 2 || header[5] != 1 || header[16] != 3 || header[17] != 0
                        || machine != (abi.equals("arm64-v8a") ? 183 : 62))
                    throw new IOException("内核不是支持的 Android 共享库");
                digest.update(header);
                byte[] buffer = new byte[32768];
                long total = header.length;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_LIBRARY) throw new IOException("内核解压大小超限");
                    digest.update(buffer, 0, count);
                }
            }
            if (!expected.equals(hex(digest.digest()))) throw new SecurityException("内核文件校验失败");
            return new CoreBundle(version, expected);
        }
    }

    static byte[] read(InputStream source, int limit) throws IOException {
        try (InputStream input = source; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > limit) throw new IOException("数据大小超限");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    public static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
