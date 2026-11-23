package es.al3x.leonsailing;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Red del barco: lee NMEA 0183 de la pasarela (TCP o UDP) y sincroniza
 * los dispositivos de a bordo por difusión UDP en la WiFi.
 * Los sockets se atan a la red WiFi aunque Android use los datos móviles
 * para Internet (la WiFi de la W2K-1 no tiene Internet).
 */
@CapacitorPlugin(name = "BoatNet")
public class BoatNet extends Plugin {
    private volatile boolean nmeaRun = false;
    private Thread nmeaThread;
    private Socket tcp;
    private DatagramSocket nmeaUdp;
    private volatile boolean syncRun = false;
    private DatagramSocket syncSock;
    private int syncPort = 47474;
    private WifiManager.MulticastLock mlock;

    private Network wifi() {
        ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
        }
        return null;
    }

    private void status(String state, String msg) {
        JSObject o = new JSObject();
        o.put("state", state);
        o.put("msg", msg);
        notifyListeners("status", o);
    }

    @PluginMethod
    public void connect(PluginCall call) {
        final String host = call.getString("host", "192.168.4.1");
        final int port = call.getInt("port", 60003);
        final String proto = call.getString("protocol", "tcp");
        stopNmea();
        nmeaRun = true;
        nmeaThread = new Thread(() -> {
            while (nmeaRun) {
                try {
                    if ("udp".equals(proto)) readUdp(port); else readTcp(host, port);
                } catch (Exception e) {
                    status("error", String.valueOf(e.getMessage()));
                }
                closeNmeaSockets();
                if (nmeaRun) { try { Thread.sleep(2000); } catch (InterruptedException ie) { break; } }
            }
        }, "nmea");
        nmeaThread.start();
        call.resolve();
    }

    private void emitLines(StringBuilder sb) {
        if (sb.length() == 0) return;
        JSObject o = new JSObject();
        o.put("text", sb.toString());
        notifyListeners("nmea", o);
        sb.setLength(0);
    }

    private void readTcp(String host, int port) throws Exception {
        Network w = wifi();
        tcp = w != null ? w.getSocketFactory().createSocket() : new Socket();
        status("connecting", host + ":" + port);
        tcp.connect(new InetSocketAddress(host, port), 5000);
        tcp.setSoTimeout(10000);
        status("connected", host + ":" + port + " (TCP)");
        BufferedReader in = new BufferedReader(new InputStreamReader(tcp.getInputStream(), StandardCharsets.US_ASCII));
        StringBuilder sb = new StringBuilder();
        long last = System.currentTimeMillis();
        String line;
        while (nmeaRun && (line = in.readLine()) != null) {
            sb.append(line).append('\n');
            long now = System.currentTimeMillis();
            if (now - last > 150) { emitLines(sb); last = now; }
        }
        emitLines(sb);
        if (nmeaRun) status("error", "conexión cerrada");
    }

    private void readUdp(int port) throws Exception {
        nmeaUdp = new DatagramSocket(null);
        nmeaUdp.setReuseAddress(true);
        nmeaUdp.bind(new InetSocketAddress(port));
        Network w = wifi();
        if (w != null) w.bindSocket(nmeaUdp);
        nmeaUdp.setSoTimeout(10000);
        status("connected", "UDP " + port);
        byte[] buf = new byte[4096];
        StringBuilder sb = new StringBuilder();
        long last = System.currentTimeMillis();
        while (nmeaRun) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            nmeaUdp.receive(p);
            sb.append(new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII));
            if (sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
            long now = System.currentTimeMillis();
            if (now - last > 150) { emitLines(sb); last = now; }
        }
    }

    private void closeNmeaSockets() {
        try { if (tcp != null) tcp.close(); } catch (Exception ignored) {}
        try { if (nmeaUdp != null) nmeaUdp.close(); } catch (Exception ignored) {}
        tcp = null; nmeaUdp = null;
    }

    private void stopNmea() {
        nmeaRun = false;
        closeNmeaSockets();
        if (nmeaThread != null) nmeaThread.interrupt();
        nmeaThread = null;
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        stopNmea();
        status("off", "");
        call.resolve();
    }

    @PluginMethod
    public void syncStart(PluginCall call) {
        syncPort = call.getInt("port", 47474);
        if (syncRun) { call.resolve(); return; }
        syncRun = true;
        try {
            WifiManager wm = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) { mlock = wm.createMulticastLock("leon-sync"); mlock.setReferenceCounted(false); mlock.acquire(); }
        } catch (Exception ignored) {}
        new Thread(() -> {
            while (syncRun) {
                try {
                    syncSock = new DatagramSocket(null);
                    syncSock.setReuseAddress(true);
                    syncSock.setBroadcast(true);
                    syncSock.bind(new InetSocketAddress(syncPort));
                    Network w = wifi();
                    if (w != null) w.bindSocket(syncSock);
                    byte[] buf = new byte[65000];
                    while (syncRun) {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        syncSock.receive(p);
                        JSObject o = new JSObject();
                        o.put("msg", new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                        o.put("from", p.getAddress().getHostAddress());
                        notifyListeners("sync", o);
                    }
                } catch (Exception e) {
                    try { if (syncSock != null) syncSock.close(); } catch (Exception ignored) {}
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
                }
            }
        }, "sync").start();
        call.resolve();
    }

    @PluginMethod
    public void syncSend(PluginCall call) {
        final String msg = call.getString("msg", "");
        new Thread(() -> {
            try {
                byte[] data = msg.getBytes(StandardCharsets.UTF_8);
                DatagramSocket s = syncSock != null && !syncSock.isClosed() ? syncSock : new DatagramSocket();
                s.setBroadcast(true);
                s.send(new DatagramPacket(data, data.length, InetAddress.getByName("255.255.255.255"), syncPort));
                InetAddress b = subnetBroadcast();
                if (b != null) s.send(new DatagramPacket(data, data.length, b, syncPort));
            } catch (Exception ignored) {}
        }).start();
        call.resolve();
    }

    private InetAddress subnetBroadcast() {
        try {
            Network w = wifi();
            if (w == null) return null;
            ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            LinkProperties lp = cm.getLinkProperties(w);
            if (lp == null) return null;
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() instanceof Inet4Address) {
                    byte[] a = la.getAddress().getAddress();
                    int pre = la.getPrefixLength();
                    int ip = ((a[0] & 255) << 24) | ((a[1] & 255) << 16) | ((a[2] & 255) << 8) | (a[3] & 255);
                    int mask = pre == 0 ? 0 : (~0 << (32 - pre));
                    int bc = ip | ~mask;
                    return InetAddress.getByAddress(new byte[]{(byte) (bc >>> 24), (byte) (bc >>> 16), (byte) (bc >>> 8), (byte) bc});
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    protected void handleOnDestroy() {
        stopNmea();
        syncRun = false;
        try { if (syncSock != null) syncSock.close(); } catch (Exception ignored) {}
        try { if (mlock != null) mlock.release(); } catch (Exception ignored) {}
    }
}
