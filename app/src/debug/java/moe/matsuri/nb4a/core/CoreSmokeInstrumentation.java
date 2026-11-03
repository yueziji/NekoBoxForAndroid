package moe.matsuri.nb4a.core;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import libcore.Libcore;
import libcore.BoxInstance;

/** Shell-only debug smoke tests; runs on a fresh CI emulator, never user devices. */
public class CoreSmokeInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args; start(); }
    @Override public void onStart() {
        Bundle report = new Bundle();
        CountDownLatch backgroundReady = new CountDownLatch(1);
        ServiceConnection background = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) { backgroundReady.countDown(); }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        boolean bound = false;
        try {
            // Instrumentation starts before Application.onCreate has finished on the main thread.
            waitForIdleSync();
            // Room auto-binds its invalidation service in :bg. Hold an explicit binding
            // until its Application.onCreate completes before instrumentation can kill it.
            bound = getTargetContext().bindService(new Intent(getTargetContext(), io.nekohasekai.sagernet.bg.VpnService.class)
                    .setAction("io.nekohasekai.sagernet.SERVICE"), background, Context.BIND_AUTO_CREATE);
            if (!bound || !backgroundReady.await(20, TimeUnit.SECONDS)) throw new AssertionError("Background core did not initialize");
            String action = arguments.getString("action", "probe");
            switch (action) {
                case "config":
                    report.putInt("configurations", CoreConfigProbe.verify());
                    break;
                case "ui":
                    for (Class<?> screen : new Class<?>[] {io.nekohasekai.sagernet.ui.MainActivity.class, CoreUpdateActivity.class}) {
                        Activity activity = startActivitySync(new Intent(getTargetContext(), screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        waitForIdleSync();
                        if (activity instanceof io.nekohasekai.sagernet.ui.MainActivity) {
                            boolean[] connected = {false};
                            long deadline = SystemClock.elapsedRealtime() + 15000;
                            do {
                                runOnMainSync(() -> connected[0] = ((io.nekohasekai.sagernet.ui.MainActivity) activity).getConnection().getService() != null);
                                if (!connected[0]) SystemClock.sleep(100);
                            } while (!connected[0] && SystemClock.elapsedRealtime() < deadline);
                            if (!connected[0]) throw new AssertionError("Background service did not initialize");
                        }
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
                    if (!Libcore.versionBox().split("\n", 2)[0].equals("sing-box: " + arguments.getString("version"))) throw new AssertionError("Unexpected core version");
                    probeSocks();
                    break;
                default: throw new IllegalArgumentException("Unknown smoke action");
            }
            if (action.equals("probe") || action.equals("config") || action.equals("ui")) {
                if (!CoreLoader.status().contains(arguments.getString("expected", "内置")))
                    throw new AssertionError("Unexpected core selection: " + CoreLoader.status());
                try (InputStream input = new FileInputStream(new File(getTargetContext().getNoBackupFilesDir(), "dynamic-core/state.json"))) {
                    JSONObject state = new JSONObject(new String(CoreBundle.read(input, 65536), StandardCharsets.UTF_8));
                    if (arguments.getString("expected", "内置").equals("动态") && state.optString("selected").isEmpty())
                        throw new AssertionError("Candidate rolled back during the test: " + CoreLoader.status());
                }
            }
            report.putString("result", "PASS");
            report.putString("status", CoreLoader.status());
            getTargetContext().unbindService(background);
            bound = false;
            finish(Activity.RESULT_OK, report);
        } catch (Throwable error) {
            if (bound) getTargetContext().unbindService(background);
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
