package com.mytool;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View.OnClickListener;
import android.bluetooth.BluetoothAdapter;

public class MainActivity extends Activity {

    // --- Native Library Setup ---
    private static boolean nativeLibraryLoaded = false;
    static {
        try {
            // This name must match your library file: "libcracker.so"
            System.loadLibrary("cracker");
            nativeLibraryLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            nativeLibraryLoaded = false;
        }
    }

    // Declare the native method from your libcracker.so
    public native String getNativeString();
    // --- End of Native Library Setup ---

    Button mainButton;
    TextView bluetoothAddressTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        mainButton = (Button) findViewById(R.id.mainButton);
        bluetoothAddressTextView = (TextView) findViewById(R.id.textview_bluetooth_address);

        // --- Critical JNI Check ---
        if (nativeLibraryLoaded) {
            try {
                String nativeMessage = getNativeString();
                // We don't need a toast here, we'll just log it.
                // The main purpose is to confirm the function can be called.
                Log.d("JNI_SUCCESS", "Native string: " + nativeMessage);
            } catch (UnsatisfiedLinkError e) {
                Toast.makeText(this, "FATAL: JNI function not found in library!", Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "FATAL: Native C library 'libcracker.so' could not be loaded!", Toast.LENGTH_LONG).show();
        }

        displayBluetoothAddress();

        mainButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent serviceIntent = new Intent(MainActivity.this, CrackerService.class);
                    startService(serviceIntent);
                    Toast.makeText(MainActivity.this, "Starting Service...", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "ERROR starting service: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void displayBluetoothAddress() {
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter == null) {
                bluetoothAddressTextView.setText("Bluetooth: Not Supported");
                return;
            }

            if (!bluetoothAdapter.isEnabled()) {
                bluetoothAddressTextView.setText("Bluetooth: Turned Off");
            } else {
                String macAddress = bluetoothAdapter.getAddress();
                if (macAddress != null && !macAddress.isEmpty()) {
                    bluetoothAddressTextView.setText("BT Address: " + macAddress);
                } else {
                    bluetoothAddressTextView.setText("BT Address: Unavailable");
                }
            }
        } catch (SecurityException e) {
            bluetoothAddressTextView.setText("Error: BLUETOOTH permission missing!");
            Toast.makeText(this, "Go to App Info -> Permissions and grant Bluetooth permission.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            bluetoothAddressTextView.setText("Error: " + e.getMessage());
        }
    }
}
