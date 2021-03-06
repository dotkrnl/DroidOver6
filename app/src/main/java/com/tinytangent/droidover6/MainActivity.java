package com.tinytangent.droidover6;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.net.VpnService;
import android.support.design.widget.TextInputEditText;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import static android.widget.Toast.LENGTH_SHORT;

public class MainActivity extends AppCompatActivity {

    boolean uiModeForVpnStarted = false;
    protected static final int VPN_REQUEST_CODE = 0x100;
    protected Button buttonChangeConnectionState = null;
    protected TextInputEditText inputHostName = null;
    protected TextInputEditText inputPort = null;
    protected TextView textViewOutBytes = null;
    protected TextView textViewInBytes = null;
    protected LinearLayout layoutNetworkStatistics = null;
    protected long inBytes = 0;
    protected long outBytes = 0;

    protected static String defaultHostName = "2400:8500:1301:736:a133:130:98:2310";
    protected static String defaultPortText = "5678";

    protected BroadcastReceiver vpnStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Over6VpnService.BROADCAST_VPN_STATE)) {
                if (intent.getIntExtra("status_code", -1) == BackendIPC.BACKEND_STATE_DISCONNECTED) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);

                    builder.setMessage("An error occurred. Disconnected from server")
                            .setTitle("Disconnected")
                            .setNeutralButton("OK", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    dialog.dismiss();
                                }
                            });
                    AlertDialog dialog = builder.create();
                    dialog.show();
                } else {
                    MainActivity.this.inBytes = intent.getLongExtra("in_bytes", 0);
                    MainActivity.this.outBytes = intent.getLongExtra("out_bytes", 0);
                }
            }
            updateGUI();
        }
    };

    protected void updateGUI() {
        if (ServiceUtil.isServiceRunning(this, Over6VpnService.class)) {
            uiModeForVpnStarted = true;
            buttonChangeConnectionState.setText("Disconnect");
            inputHostName.setEnabled(false);
            inputPort.setEnabled(false);
            layoutNetworkStatistics.setVisibility(View.VISIBLE);
            textViewOutBytes.setText("In: " +
                    Formatter.formatShortFileSize(MainActivity.this, inBytes));
            textViewInBytes.setText("Out: " +
                    Formatter.formatShortFileSize(MainActivity.this, outBytes));
        } else {
            uiModeForVpnStarted = false;
            buttonChangeConnectionState.setText("Connect");
            inputHostName.setEnabled(true);
            inputPort.setEnabled(true);
            layoutNetworkStatistics.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        buttonChangeConnectionState = (Button) findViewById(R.id.button_change_connection_state);
        inputHostName = (TextInputEditText) findViewById(R.id.edit_text_host_name);
        inputPort = (TextInputEditText) findViewById(R.id.edit_text_port);
        textViewOutBytes = (TextView) findViewById(R.id.text_view_out_bytes);
        textViewInBytes = (TextView) findViewById(R.id.text_view_in_bytes);
        layoutNetworkStatistics = (LinearLayout) findViewById(R.id.linear_layout_network_statistics);

        final SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);

        // Set host name & port from defaults
        String hostName = settings.getString("droidOver6_hostName", defaultHostName);
        inputHostName.setText(hostName);
        String portText = settings.getString("droidOver6_portText", defaultPortText);
        inputPort.setText(portText);

        buttonChangeConnectionState.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Update defaults
                Editor edit = settings.edit();
                edit.putString("droidOver6_hostName", inputHostName.getText().toString());
                edit.apply();
                edit = settings.edit();
                edit.putString("droidOver6_portText", inputPort.getText().toString());
                edit.apply();

                if (uiModeForVpnStarted) {
                    stopVPNService();
                } else {
                    prepareStartVPN();
                }
            }
        });
        LocalBroadcastManager.getInstance(this).registerReceiver(vpnStateReceiver,
                new IntentFilter(Over6VpnService.BROADCAST_VPN_STATE));
    }


    @Override
    public void onResume() {
        super.onResume();
        updateGUI();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startVPNService();
        }
    }

    protected void prepareStartVPN() {
        Toast.makeText(this, stringFromJNI(), LENGTH_SHORT).show();
        Intent vpnIntent = VpnService.prepare(this);
        if (vpnIntent != null)
            startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
        else
            onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null);
    }

    protected void startVPNService() {
        if (ServiceUtil.isServiceRunning(this, Over6VpnService.class)) {
            updateGUI();
            return;
        }
        Intent intent = new Intent(this, Over6VpnService.class);
        intent.putExtra("host_name", inputHostName.getText().toString());
        intent.putExtra("port", Integer.parseInt(inputPort.getText().toString()));
        startService(intent);
        inBytes = 0;
        outBytes = 0;
        updateGUI();
    }

    protected void stopVPNService() {
        if (!ServiceUtil.isServiceRunning(this, Over6VpnService.class)) {
            updateGUI();
            return;
        }
        Over6VpnService.getInstance().reliableStop();
        updateGUI();
    }

    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     */
    public native String stringFromJNI();

    // Used to load the 'native-lib' library on application startup.
    static {
        System.loadLibrary("native-lib");
    }
}
