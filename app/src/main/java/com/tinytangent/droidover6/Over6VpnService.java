package com.tinytangent.droidover6;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.CountDownTimer;
import android.os.ParcelFileDescriptor;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Created by tansinan on 5/4/17.
 */

public class Over6VpnService extends VpnService {

    protected static final byte BACKEND_IPC_COMMAND_STATUS = (byte) 0x00;
    protected static final byte BACKEND_IPC_COMMAND_STATISTICS = (byte) 0x01;
    protected static final byte BACKEND_IPC_COMMAND_CONFIGURATION = (byte) 0x02;
    protected static final byte BACKEND_IPC_COMMAND_SET_TUNNEL_FD = (byte) 0x03;
    protected static final byte BACKEND_IPC_COMMAND_TERMINATE = (byte) 0xFF;

    protected static final String IPV6_NONE = "2001:db8:ffff:ffff:ffff:ffff:ffff:ffff";

    public static final String BROADCAST_VPN_STATE = "com.tinytangent.droidover6.STATUS_CHANGED";
    public static final String BROADCAST_VPN_TRAFFIC = "com.tinytangent.droidover6.TRAFFIC_STATISTICS";

    ParcelFileDescriptor commandReadFd;
    ParcelFileDescriptor commandWriteFd;
    ParcelFileDescriptor responseReadFd;
    ParcelFileDescriptor responseWriteFd;

    Pipe communicationPipe = null;
    Pipe.SourceChannel commandChannel = null;
    Pipe.SinkChannel responseChannel = null;
    FileOutputStream commandStream = null;
    FileInputStream responseStream = null;
    Thread backendThread = null;

    protected ParcelFileDescriptor vpnInterface = null;
    protected PendingIntent pendingIntent;
    protected Timer timer = null;

    static protected Over6VpnService instance = null;

    static Over6VpnService getInstance() {
        return instance;
    }

    static byte[] readExactBytes(FileInputStream stream, int bytes) throws IOException {
        byte[] ret = new byte[bytes];
        int bytesRead = 0;
        while (bytesRead < ret.length) {
            int temp = stream.read(ret, bytesRead, ret.length - bytesRead);
            if (temp > 0) bytesRead += temp;
        }
        return ret;
    }

    @Override
    public void onCreate() {
        instance = this;
        try {
            ParcelFileDescriptor[] pipeFds = ParcelFileDescriptor.createPipe();
            commandWriteFd = pipeFds[1];
            commandReadFd = pipeFds[0];
            pipeFds = ParcelFileDescriptor.createPipe();
            responseWriteFd = pipeFds[1];
            responseReadFd = pipeFds[0];
            communicationPipe = Pipe.open();
            commandChannel = communicationPipe.source();
            responseChannel = communicationPipe.sink();
        } catch (IOException e) {
            return;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onCreate();
        //LocalBroadcastManager.getInstance(this).sendBroadcast(
        //  new Intent(BROADCAST_VPN_STATE).putExtra("running", true));
        commandStream = new FileOutputStream(commandWriteFd.getFileDescriptor());
        responseStream = new FileInputStream(responseReadFd.getFileDescriptor());
        String hostName = intent.getStringExtra("host_name");
        int port = intent.getIntExtra("port", 0);
        backendThread = new Thread(new BackendWrapperThread(
                hostName, port,
                commandReadFd.getFd(),
                responseWriteFd.getFd()
        ));

        backendThread.start();
        Log.d("Backend", "Backend started!");

        TimerTask timer_task = new TimerTask() {
            int i = 0;

            public void run() {
                if (!backendThread.isAlive()) {
                    Intent intent = new Intent(BROADCAST_VPN_STATE);
                    intent.putExtra("status_code", BackendIPC.BACKEND_STATE_DISCONNECTED);
                    LocalBroadcastManager.getInstance(Over6VpnService.this).sendBroadcast(intent);
                    Over6VpnService.this.reliableStop();
                    return;
                } else if (vpnInterface == null) {
                    try {
                        commandStream.write(BACKEND_IPC_COMMAND_CONFIGURATION);
                        byte[] data = readExactBytes(responseStream, 20);
                        InetAddress address = InetAddress.getByAddress(Arrays.copyOfRange(data, 0, 4));
                        InetAddress dns = InetAddress.getByAddress(Arrays.copyOfRange(data, 8, 12));
                        InetAddress dns2 = InetAddress.getByAddress(Arrays.copyOfRange(data, 12, 16));
                        InetAddress dns3 = InetAddress.getByAddress(Arrays.copyOfRange(data, 16, 20));
                        byte[] addr = address.getAddress();
                        if (addr[0] == -1 && addr[1] == -1 && addr[2] == -1 && addr[3] == -1) {
                            Log.d("DroidOver6 VPN", "Waiting address from server");
                            return;
                        }
                        vpnInterface = new Builder()
                                .addAddress(address, 24)
                                .addDnsServer(dns)
                                .addDnsServer(dns2)
                                .addDnsServer(dns3)
                                .addRoute("0.0.0.0", 0)
                                .addRoute(IPV6_NONE, 128)
                                .setSession(getString(R.string.app_name))
                                //.setConfigureIntent(pendingIntent)
                                .establish();
                        commandStream.write(BACKEND_IPC_COMMAND_SET_TUNNEL_FD);
                        commandStream.write(ByteBuffer.allocate(4).putInt(vpnInterface.getFd()).array());
                    } catch (IOException e) {
                        Log.d("DroidOver6 VPN", "IO error");
                        return;
                    } catch (IllegalArgumentException e) {
                        // illegal IP/DNS Configuration.
                        Log.d("DroidOver6 VPN", "Illegal IP/DNS Configuration");
                        return;
                    }
                } else {
                    try {
                        commandStream.write(BACKEND_IPC_COMMAND_STATUS);
                        commandStream.flush();
                        int response = responseStream.read();
                        Intent intent = new Intent(BROADCAST_VPN_STATE);
                        intent.putExtra("status_code", response);
                        if (response == BackendIPC.BACKEND_STATE_DISCONNECTED) {
                            LocalBroadcastManager.getInstance(Over6VpnService.this).sendBroadcast(intent);
                            Over6VpnService.this.reliableStop();
                            return;
                        }
                        commandStream.write(BACKEND_IPC_COMMAND_STATISTICS);
                        commandStream.flush();
                        byte[] inBytes = readExactBytes(responseStream, Long.SIZE / 8);
                        byte[] outBytes = readExactBytes(responseStream, Long.SIZE / 8);
                        ByteBuffer buffer = ByteBuffer.allocate(Long.SIZE / 8).put(inBytes);
                        buffer.flip();
                        intent.putExtra("in_bytes", buffer.getLong());
                        buffer = ByteBuffer.allocate(Long.SIZE / 8).put(outBytes);
                        buffer.flip();
                        intent.putExtra("out_bytes", buffer.getLong());
                        LocalBroadcastManager.getInstance(Over6VpnService.this).sendBroadcast(intent);
                    } catch (Exception e) {
                        return;
                    }
                }
            }

        };

        timer = new Timer();
        timer.scheduleAtFixedRate(timer_task, 1000, 1000);
        return START_REDELIVER_INTENT;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    public void reliableStop() {
        try {
            timer.cancel();
            commandStream.write(BACKEND_IPC_COMMAND_TERMINATE);
            if (vpnInterface != null) {
                Over6VpnService.getInstance().vpnInterface.close();
            }
            Over6VpnService.getInstance().commandStream.close();
            Over6VpnService.getInstance().responseStream.close();
        } catch (Exception e) {

        }
        Over6VpnService.getInstance().stopSelf();
    }
}
