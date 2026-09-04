package com.pierce.canadaip;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.core.content.FileProvider;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private TextView status, details;
    private Button connect, check;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        details = findViewById(R.id.details);
        connect = findViewById(R.id.connect);
        check = findViewById(R.id.check);
        connect.setOnClickListener(v -> findCanadianRelay());
        check.setOnClickListener(v -> checkIp());
    }

    private void setBusy(boolean busy, String msg) {
        runOnUiThread(() -> { connect.setEnabled(!busy); check.setEnabled(!busy); status.setText(msg); });
    }

    private void findCanadianRelay() {
        setBusy(true, "Finding a Canadian relay…");
        executor.execute(() -> {
            try {
                String csv = get("https://www.vpngate.net/api/iphone/", 15000);
                Relay best = parseBestCanada(csv);
                if (best == null) throw new IOException("No Canadian OpenVPN relay is online right now.");
                String ovpn = new String(Base64.getDecoder().decode(best.configB64), StandardCharsets.UTF_8);
                ovpn = makeCredentialsInline(ovpn);
                File dir = new File(getCacheDir(), "vpn");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create cache directory");
                File file = new File(dir, "canada.ovpn");
                try (FileOutputStream out = new FileOutputStream(file)) { out.write(ovpn.getBytes(StandardCharsets.UTF_8)); }
                final Relay chosen = best;
                runOnUiThread(() -> {
                    status.setText("Canadian relay found");
                    details.setText("Relay: " + chosen.host + "\nApprox. line speed: " + humanRate(chosen.speed) + "\n\nImport this profile into OpenVPN for Android and connect. Username/password are prefilled as vpn / vpn.");
                    launchOpenVpnImport(file);
                    connect.setEnabled(true); check.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("Could not connect");
                    details.setText(e.getMessage() + "\n\nVPN Gate is volunteer-operated, so Canadian relays can temporarily disappear.");
                    connect.setEnabled(true); check.setEnabled(true);
                });
            }
        });
    }

    private void launchOpenVpnImport(File file) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/x-openvpn-profile");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(i); }
        catch (ActivityNotFoundException ex) {
            status.setText("Install OpenVPN for Android first");
            details.setText("This app finds the Canadian server, but Android still needs an OpenVPN tunnel engine. Install OpenVPN for Android, then tap CONNECT TO CANADA again.");
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=de.blinkt.openvpn"))); }
            catch (Exception ignored) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=de.blinkt.openvpn"))); }
        }
    }

    private void checkIp() {
        setBusy(true, "Checking public IP…");
        executor.execute(() -> {
            try {
                String json = get("https://ipwho.is/", 10000);
                String ip = jsonField(json, "ip");
                String country = jsonField(json, "country");
                String code = jsonField(json, "country_code");
                runOnUiThread(() -> {
                    status.setText("CA".equalsIgnoreCase(code) ? "Connected through Canada ✓" : "Not currently Canadian");
                    details.setText("Public IP: " + ip + "\nCountry: " + country + " (" + code + ")");
                    connect.setEnabled(true); check.setEnabled(true);
                });
            } catch (Exception e) { setBusy(false, "IP check failed: " + e.getMessage()); }
        });
    }

    private static Relay parseBestCanada(String csv) throws IOException {
        Relay best = null;
        for (String raw : csv.split("\\r?\\n")) {
            if (raw.startsWith("*") || raw.startsWith("#") || raw.trim().isEmpty()) continue;
            List<String> c = parseCsvLine(raw);
            if (c.size() < 15 || !"CA".equalsIgnoreCase(c.get(6)) || c.get(14).isEmpty()) continue;
            long speed = number(c.get(4));
            Relay r = new Relay(c.get(0), speed, c.get(14));
            if (best == null || r.speed > best.speed) best = r;
        }
        return best;
    }

    private static List<String> parseCsvLine(String s) {
        ArrayList<String> out = new ArrayList<>(); StringBuilder cur = new StringBuilder(); boolean q = false;
        for (int i=0;i<s.length();i++) { char ch=s.charAt(i); if(ch=='\"') q=!q; else if(ch==','&&!q){out.add(cur.toString());cur.setLength(0);} else cur.append(ch); }
        out.add(cur.toString()); return out;
    }

    private static String makeCredentialsInline(String s) {
        return s.replaceFirst("(?m)^auth-user-pass\\s*$", "<auth-user-pass>\\nvpn\\nvpn\\n</auth-user-pass>");
    }

    private static String get(String url, int timeout) throws IOException {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection(); c.setConnectTimeout(timeout); c.setReadTimeout(timeout); c.setRequestProperty("User-Agent","CanadaIP/1.0");
        try(InputStream in=c.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString(StandardCharsets.UTF_8.name());}
        finally { c.disconnect(); }
    }

    private static String jsonField(String json, String key) {
        String needle="\""+key+"\""; int p=json.indexOf(needle); if(p<0)return "?"; p=json.indexOf(':',p)+1; while(p<json.length()&&Character.isWhitespace(json.charAt(p)))p++; if(p<json.length()&&json.charAt(p)=='\"'){int e=json.indexOf('\"',p+1); return e>p?json.substring(p+1,e):"?";} int e=p; while(e<json.length()&&",}".indexOf(json.charAt(e))<0)e++; return json.substring(p,e).trim();
    }

    private static long number(String s){try{return Long.parseLong(s);}catch(Exception e){return 0;}}
    private static String humanRate(long bps){ if(bps<=0)return "unknown"; return String.format(Locale.US,"%.1f Mbps",bps/1_000_000.0); }
    private static class Relay { final String host,configB64; final long speed; Relay(String h,long s,String c){host=h;speed=s;configB64=c;} }
}
