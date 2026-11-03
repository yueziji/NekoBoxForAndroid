package moe.matsuri.nb4a.core;

import android.content.Context;
import android.os.Build;
import android.util.AtomicFile;
import android.util.Base64;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipFile;

/** Invoked by the fixed go.Seq binding BEFORE any Go class is initialized. */
public final class CoreLoader {
    private static Context context;
    private static File root;
    private static String role, loadedId = "", loadedVersion = "";
    private static boolean loaded;

    public static void prepare(Context app, String process) {
        context = app.getApplicationContext();
        root = new File(context.getNoBackupFilesDir(), "dynamic-core");
        if (!root.isDirectory() && !root.mkdirs()) throw new IllegalStateException("无法创建内核目录");
        role = process.endsWith(":bg") ? "bg" : "main";
    }

    public static String abi() {
        for (String abi : Build.SUPPORTED_ABIS)
            if (abi.equals("arm64-v8a") || abi.equals("x86_64")) return abi;
        throw new IllegalStateException("此版本只支持 64 位设备");
    }

    private static String asset(String name) throws IOException {
        return new String(CoreBundle.read(context.getAssets().open("dynamic-core/" + name), 32768), StandardCharsets.UTF_8).trim();
    }

    private interface Edit { void apply(JsonObject state) throws Exception; }

    private static synchronized JsonObject state(Edit edit) throws Exception {
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(root, "state.lock"), "rw");
             FileLock lock = lockFile.getChannel().lock()) {
            AtomicFile store = new AtomicFile(new File(root, "state.json"));
            JsonObject state;
            try {
                state = JsonParser.parseString(new String(CoreBundle.read(store.openRead(), 65536), StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (FileNotFoundException missing) {
                state = new JsonObject();
            }
            if (edit != null) {
                edit.apply(state);
                FileOutputStream output = store.startWrite();
                try {
                    output.write(state.toString().getBytes(StandardCharsets.UTF_8));
                    store.finishWrite(output);
                } catch (Exception error) {
                    store.failWrite(output);
                    throw error;
                }
            }
            return state;
        }
    }

    private static String value(JsonObject state, String key) {
        return state.has(key) ? state.get(key).getAsString() : "";
    }

    private static File folder(String id) throws IOException {
        if (!id.matches("[a-f0-9-]{36}")) throw new IOException("无效的内核存储标识");
        return new File(root, id);
    }

    private static CoreBundle verify(File archive) throws Exception {
        return CoreBundle.verify(archive, Base64.decode(asset("public-key.txt"), Base64.DEFAULT), asset("binding.txt"), abi(), Build.VERSION.SDK_INT);
    }

    public static synchronized void load() throws Exception {
        if (loaded) return;
        if (context == null) throw new IllegalStateException("CoreLoader.prepare was not called");
        JsonObject chosen = state(s -> {
            String id = value(s, "selected");
            if (!id.isEmpty() && id.equals(value(s, "pending_" + role))) {
                s.addProperty("selected", value(s, "previous"));
                s.addProperty("previous", "");
                s.addProperty("notice", "上次内核初始化未完成，已回退。请重新启动 App 统一各进程版本。");
            }
            s.addProperty("pending_" + role, value(s, "selected"));
        });
        loadedId = value(chosen, "selected");
        try {
            if (loadedId.isEmpty()) {
                System.loadLibrary("gojni");
                loadedVersion = asset("builtin-version.txt") + "（内置）";
            } else {
                File directory = folder(loadedId);
                CoreBundle bundle = verify(new File(directory, "bundle.zip"));
                File library = new File(directory, "libgojni.so");
                if (!bundle.sha256.equals(CoreBundle.hash(library))) throw new SecurityException("已安装内核校验失败");
                System.load(library.getAbsolutePath());
                loadedVersion = bundle.version + "（动态）";
            }
            loaded = true;
        } catch (Throwable failure) {
            state(s -> {
                s.addProperty("selected", value(s, "previous"));
                s.addProperty("previous", "");
                s.addProperty("pending_" + role, "");
                s.addProperty("notice", "新内核加载失败，已安排回退；重新启动 App 后生效。");
            });
            throw new IllegalStateException("内核加载失败，请重启以回退", failure);
        }
    }

    public static void ready() {
        try { state(s -> s.addProperty("pending_" + role, "")); }
        catch (Exception error) { throw new IllegalStateException("无法保存内核状态", error); }
    }

    public static String install(InputStream source) throws Exception {
        String id = UUID.randomUUID().toString();
        File directory = folder(id);
        if (!directory.mkdir()) throw new IOException("无法创建内核版本目录");
        File archive = new File(directory, "bundle.zip");
        try (InputStream input = source; FileOutputStream output = new FileOutputStream(archive)) {
            byte[] buffer = new byte[32768];
            int count;
            long total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > CoreBundle.MAX_LIBRARY + 65536) throw new IOException("内核包过大");
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        }
        CoreBundle bundle = verify(archive);
        File library = new File(directory, "libgojni.so");
        try (ZipFile zip = new ZipFile(archive);
             InputStream input = zip.getInputStream(zip.getEntry("libgojni.so"));
             FileOutputStream output = new FileOutputStream(library)) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
        if (!library.setReadOnly() || !archive.setReadOnly()) throw new IOException("无法保护内核文件");
        if (!bundle.sha256.equals(CoreBundle.hash(library))) throw new SecurityException("内核落盘校验失败");
        state(s -> {
            // Multiple downloads before restarting must not make an untested package the fallback.
            s.addProperty("previous", loadedId);
            s.addProperty("selected", id);
            s.addProperty("pending_main", "");
            s.addProperty("pending_bg", "");
            s.addProperty("notice", "已安装 " + bundle.version + "，重启后生效。");
        });
        return bundle.version;
    }

    public static void useBuiltin() throws Exception {
        state(s -> {
            s.addProperty("selected", "");
            s.addProperty("previous", "");
            s.addProperty("pending_main", "");
            s.addProperty("pending_bg", "");
            s.addProperty("notice", "已选择内置内核，重启后生效。");
        });
    }

    public static String status() throws Exception {
        JsonObject s = state(null);
        return "当前进程：" + loadedVersion + "\n架构：" + abi() + "\n" + value(s, "notice");
    }
}
