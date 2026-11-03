package moe.matsuri.nb4a.core;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import libcore.Libcore;
import libcore.BoxInstance;

/** Shell-only debug smoke tests; runs on a fresh CI emulator, never user devices. */
public class CoreSmokeInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args; start(); }
    @Override public void onStart() {
        Bundle report = new Bundle();
        try {
            // Instrumentation starts before Application.onCreate has finished on the main thread.
            waitForIdleSync();
            String action = arguments.getString("action", "probe");
            switch (action) {
                case "config":
                    report.putInt("configurations", CoreConfigProbe.verify());
                    break;
                case "ui":
                    for (Class<?> screen : new Class<?>[] {io.nekohasekai.sagernet.ui.MainActivity.class, CoreUpdateActivity.class}) {
                        Activity activity = startActivitySync(new Intent(getTargetContext(), screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        waitForIdleSync();
                        runOnMainSync(activity::finish);
                    }
                    break;
                case "stage":
                    try (InputStream input = new FileInputStream(new File(getTargetContext().getFilesDir(), "core-test.zip"))) {
                        report.putString("version", CoreLoader.install(input));
                    }
                    break;
                case "builtin": CoreLoader.useBuiltin(); break;
                case "interrupt":
                    // Reproduce the persisted state left by a process dying during native initialization.
                    AtomicFile store = new AtomicFile(new File(getTargetContext().getNoBackupFilesDir(), "dynamic-core/state.json"));
                    JSONObject state;
                    try (InputStream input = store.openRead()) {
                        state = new JSONObject(new String(CoreBundle.read(input, 65536), StandardCharsets.UTF_8));
                    }
                    state.put("pending_main", state.getString("selected"));
                    FileOutputStream output = store.startWrite();
                    output.write(state.toString().getBytes(StandardCharsets.UTF_8));
                    store.finishWrite(output);
                    break;
                case "probe":
                    String expected = arguments.getString("expected", "内置");
                    if (!CoreLoader.status().contains(expected)) throw new AssertionError("Unexpected core selection: " + CoreLoader.status());
                    if (!Libcore.versionBox().equals(arguments.getString("version"))) throw new AssertionError("Unexpected core version");
                    probeSocks();
                    break;
                default: throw new IllegalArgumentException("Unknown smoke action");
            }
            report.putString("result", "PASS");
            report.putString("status", CoreLoader.status());
            finish(Activity.RESULT_OK, report);
        } catch (Throwable error) {
            report.putString("result", "FAIL");
            report.putString("error", error.getClass().getSimpleName() + ": " + error.getMessage());
            finish(Activity.RESULT_CANCELED, report);
        }
    }

    private void probeSocks() throws Exception {
        try (ServerSocket witness = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            witness.setSoTimeout(15000);
            int proxyPort;
            try (ServerSocket reservation = new ServerSocket(0)) { proxyPort = reservation.getLocalPort(); }
            BoxInstance instance = Libcore.newSingBoxInstance("{\"inbounds\":[{\"type\":\"mixed\",\"listen\":\"127.0.0.1\",\"listen_port\":" + proxyPort + "}],\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\"}],\"route\":{\"final\":\"direct\"}}", null);
            try {
                instance.start();
                Thread responder = new Thread(() -> {
                    try (Socket socket = witness.accept()) {
                        socket.setSoTimeout(10000);
                        byte[] request = new byte[4];
                        new DataInputStream(socket.getInputStream()).readFully(request);
                        if (!new String(request, StandardCharsets.US_ASCII).equals("PING")) return;
                        socket.getOutputStream().write("PONG".getBytes(StandardCharsets.US_ASCII));
                    } catch (IOException ignored) { }
                });
                responder.start();
                try (Socket client = new Socket(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", proxyPort)))) {
                    client.connect(new InetSocketAddress("127.0.0.1", witness.getLocalPort()), 10000);
                    client.setSoTimeout(10000);
                    client.getOutputStream().write("PING".getBytes(StandardCharsets.US_ASCII));
                    byte[] reply = new byte[4];
                    new DataInputStream(client.getInputStream()).readFully(reply);
                    if (!new String(reply, StandardCharsets.US_ASCII).equals("PONG")) throw new AssertionError("SOCKS data plane failed");
                }
                responder.join(10000);
                if (responder.isAlive()) throw new AssertionError("Witness did not finish");
            } finally { instance.close(); }
        }
    }
}
