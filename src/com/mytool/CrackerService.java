package com.mytool;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class CrackerService extends Service {

    private static final String TAG = "CrackerService";
    private static final String APP_NAME = "MyTool";

    // --- THIS IS THE FIX ---
    // We are now using the standard, universal Serial Port Profile (SPP) UUID.
    // All Bluetooth terminal apps are designed to connect to this UUID by default.
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private volatile boolean isRunning = true;
    private BluetoothServerThread serverThread; 
    private Handler mainThreadHandler;
    private BluetoothAdapter bluetoothAdapter;

    @Override
    public void onCreate() {
        super.onCreate();
        mainThreadHandler = new Handler(Looper.getMainLooper());
        showToast("Service Created.", false);

        try {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter == null) {
                showToast("FATAL: Bluetooth not supported.", true);
                stopSelf();
                return;
            }
            if (!bluetoothAdapter.isEnabled()) {
                showToast("ERROR: Bluetooth is not enabled.", true);
            }

            serverThread = new BluetoothServerThread();
            serverThread.start();
        } catch (SecurityException e) {
            showToast("FATAL: Service needs BLUETOOTH permission! Grant it in App Info.", true);
            stopSelf();
        } catch (Exception e) {
            showToast("FATAL in onCreate: " + e.getMessage(), true);
            stopSelf();
        }
    }

    private class BluetoothServerThread extends Thread {
        private BluetoothServerSocket bluetoothServerSocket;

        @Override
        public void run() {
            try {
                bluetoothServerSocket = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(APP_NAME, MY_UUID);
                showToast("Bluetooth server listening...", true);

                while (isRunning) {
                    try {
                        BluetoothSocket clientSocket = bluetoothServerSocket.accept();
                        showToast("Bluetooth client accepted!", false);
                        new Thread(new RobustClientHandler(clientSocket)).start();
                    } catch (IOException e) {
                        if (!isRunning) break;
                        showToast("Accept() failed. Ready for next client.", true);
                    }
                }
            } catch (IOException e) {
                showToast("FATAL: Bluetooth listen() failed: " + e.getMessage(), true);
            }
        }

        public void cancel() {
            try {
                if (bluetoothServerSocket != null) {
                    bluetoothServerSocket.close();
                }
            } catch (IOException e) { 
                Log.e(TAG, "Could not close Bluetooth server socket", e);
            }
        }
    }

    private class RobustClientHandler implements Runnable {
        private BluetoothSocket clientSocket;

        public RobustClientHandler(BluetoothSocket socket) {
            this.clientSocket = socket;
        }

        @Override
        public void run() {
            PrintWriter output = null;
            BufferedReader input = null;
            try {
                try {
                    output = new PrintWriter(clientSocket.getOutputStream(), true);
                    output.println("WELCOME"); // Acknowledge connection
                } catch (Throwable t) {
                    showToast("CRASH: GetOutputStream failed: " + t.getMessage(), true);
                    return;
                }

                try {
                    input = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                } catch (Throwable t) {
                    showToast("CRASH: GetInputStream failed: " + t.getMessage(), true);
                    return;
                }

                String command;
                while (isRunning && (command = input.readLine()) != null) {
                    if ("SCAN".equals(command)) {
                        handleScanCommand(output);
                    } else if ("PING".equals(command)) {
                        output.println("PONG");
                    } else {
                        output.println("Unknown command.");
                    }
                }
            } catch (Throwable t) {
                showToast("CRASH in command loop: " + t.getMessage(), true);
            } finally {
                try {
                    if (clientSocket != null) clientSocket.close();
                } catch (IOException e) { /* ignore */ }
                showToast("Client disconnected.", false);
            }
        }
    }

    private void handleScanCommand(PrintWriter clientOutput) {
        clientOutput.println("Attempting scan with 'iw' tool first...");
        List<String> foundSsids = new ArrayList<String>();
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            os.writeBytes("iw wlan0 scan\n");
            os.flush();
            os.writeBytes("exit\n");
            os.flush();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().startsWith("SSID:")) {
                    foundSsids.add(line.trim().substring(5).trim());
                }
            }
            process.waitFor();
            process.destroy();
        } catch (Exception e) {
            clientOutput.println("'iw' command failed: " + e.getMessage());
        }

        if (foundSsids.isEmpty()) {
            clientOutput.println("Fallback: Attempting scan with 'wpa_cli' tool...");
            try {
                Process p1 = Runtime.getRuntime().exec("su");
                DataOutputStream os1 = new DataOutputStream(p1.getOutputStream());
                os1.writeBytes("wpa_cli -i wlan0 scan\n");
                os1.flush();
                os1.writeBytes("exit\n");
                os1.flush();
                p1.waitFor();
                p1.destroy();
                Thread.sleep(3000);
                Process p2 = Runtime.getRuntime().exec("su");
                DataOutputStream os2 = new DataOutputStream(p2.getOutputStream());
                BufferedReader reader2 = new BufferedReader(new InputStreamReader(p2.getInputStream()));
                os2.writeBytes("wpa_cli -i wlan0 scan_results\n");
                os2.flush();
                os2.writeBytes("exit\n");
                os2.flush();
                String line;
                reader2.readLine();
                while ((line = reader2.readLine()) != null)
                {
                    String[] parts = line.split("\t");
                    if (parts.length > 3) {
                        foundSsids.add(parts[parts.length - 1]);
                    }
                }
                p2.waitFor();
                p2.destroy();
            } catch (Exception e) {
                clientOutput.println("FATAL: Both 'iw' and 'wpa_cli' failed: " + e.getMessage());
                showToast("Scan Error: " + e.getMessage(), true);
                return;
            }
        }

        if (foundSsids.isEmpty()) {
            clientOutput.println("Scan finished. No networks found.");
        } else {
            clientOutput.println("Scan finished. Found Networks:");
            for (String ssid : foundSsids) {
                clientOutput.println("- " + ssid);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (serverThread != null) {
            serverThread.cancel();
            serverThread.interrupt();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void showToast(final String message, final boolean isLong) {
        mainThreadHandler.post(new Runnable() {
				@Override
				public void run() {
					Toast.makeText(CrackerService.this, message, isLong ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
				}
			});
    }
}
