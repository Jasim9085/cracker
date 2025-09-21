package com.mytool;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
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
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.IOException;
import java.util.UUID;

public class CrackerService extends Service {

    private static final String TAG = "CrackerService";
    private static final String APP_NAME = "MyTool";
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); // Standard SPP UUID
    private volatile boolean isRunning = true;
    private BluetoothServerThread serverThread;
    private Handler mainThreadHandler;
    private BluetoothAdapter bluetoothAdapter;
    private String scannerExecutablePath;

    @Override
    public void onCreate() {
        super.onCreate();
        mainThreadHandler = new Handler(Looper.getMainLooper());
        
        try {
            prepareScannerExecutable(); // Prepare C tool first
            
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
        } catch (Throwable t) {
            showToast("FATAL in onCreate: " + t.getMessage(), true);
            stopSelf();
        }
    }

    private void prepareScannerExecutable() throws IOException {
        String abi = Build.CPU_ABI;
        Log.d(TAG, "Device ABI: " + abi);
        
        String assetPath;
        if (abi.startsWith("arm64")) {
            assetPath = "arm64-v8a/wifi_scanner";
        } else {
            assetPath = "armeabi-v7a/wifi_scanner";
        }
        
        File outFile = new File(getFilesDir(), "wifi_scanner");
        scannerExecutablePath = outFile.getAbsolutePath();

        InputStream in = getAssets().open(assetPath);
        OutputStream out = new FileOutputStream(outFile);
        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        in.close();
        out.flush();
        out.close();

        outFile.setExecutable(true, false);
    }

    private class BluetoothServerThread extends Thread {
        private BluetoothServerSocket serverSocket;
        public void run() {
            try {
                serverSocket = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(APP_NAME, MY_UUID);
                showToast("Bluetooth server listening...", true);
                while (isRunning) {
                    try {
                        BluetoothSocket clientSocket = serverSocket.accept();
                        new Thread(new ClientHandler(clientSocket)).start();
                    } catch (IOException e) {
                        if (!isRunning) break;
                    }
                }
            } catch (IOException e) {
                showToast("FATAL: Bluetooth listen() failed: " + e.getMessage(), true);
            }
        }
        public void cancel() {
            try {
                if (serverSocket != null) serverSocket.close();
            } catch (IOException e) { /* ignore */ }
        }
    }

    private class ClientHandler implements Runnable {
        private BluetoothSocket clientSocket;
        public ClientHandler(BluetoothSocket socket) { this.clientSocket = socket; }

        @Override
        public void run() {
            PrintWriter output = null;
            BufferedReader input = null;
            try {
                output = new PrintWriter(clientSocket.getOutputStream(), true);
                input = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                output.println("WELCOME");

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
                Log.e(TAG, "ClientHandler CRASHED", t);
            } finally {
                try {
                    if (clientSocket != null) clientSocket.close();
                } catch (IOException e) { /* ignore */ }
            }
        }
    }

    private void handleScanCommand(PrintWriter clientOutput) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            // Get BOTH streams
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            
            os.writeBytes(scannerExecutablePath + "\n");
            os.flush();
            os.writeBytes("exit\n");
            os.flush();

            String line;
            // Read standard output (the results) from the C program
            while ((line = reader.readLine()) != null) {
                clientOutput.println(line); // Send to client
            }
            // Read standard error (the debug/error messages)
            while ((line = errorReader.readLine()) != null) {
                clientOutput.println("DEBUG: " + line); // Send to client, prefixed with DEBUG
            }

            process.waitFor();
            clientOutput.println("--- SCAN FINISHED ---");

        } catch (Exception e) {
            clientOutput.println("FATAL JAVA ERROR: " + e.getMessage());
        } finally {
            if (process != null) {
                process.destroy();
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
