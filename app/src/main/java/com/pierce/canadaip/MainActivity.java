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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
        runOnUiThread(() -> {
            connect.setEnabled(!busy);
            check.setEnabled(!busy);
            status.setText(msg);
        });
    }

    private void findCanadianRelay() {
        setBusy(true, "Finding a Canadian server…");
        details.setText("Trying live Canadian VPN servers.");

        executor.execute(() -> {
            Exception gateError = null;

            // First choice: VPN Gate. It is dynamic and does not require a rotating password.
            try {
                String csv = getText("https://www.vpngate.net/api/iphone/", 15000);
                Relay best = parseBestCanada(csv);
                if (best != null) {
                    String ovpn = new String(Base64.getDecoder().decode(best.configB64), StandardCharsets.UTF_8);
                    ovpn = makeCredentialsInline(ovpn, "vpn", "vpn");
                    File file = writeProfile(normalizeProfile(ovpn));
                    final Relay chosen = best;
                    runOnUiThread(() -> {
                        status.setText("Canadian server found");
                        details.setText("Source: VPN Gate\nRelay: " + chosen.host +
                                "\nApprox. line speed: " + humanRate(chosen.speed) +
                                "\n\nOpening the VPN profile now.");
                        launchOpenVpnImport(file);
                        connect.setEnabled(true);
                        check.setEnabled(true);
                    });
                    return;
                }
                gateError = new IOException("No Canadian VPN Gate relay was listed.");
            } catch (Exception e) {
                gateError = e;
            }

            // Fallback: VPNBook maintains fixed Canadian OpenVPN servers.
            try {
                runOnUiThread(() -> {
                    status.setText("Trying backup Canadian server…");
                    details.setText("VPN Gate had no usable Canadian relay. Trying VPNBook Canada.");
                });

                VpnBookProfile backup = fetchVpnBookCanada();
                File file = writeProfile(backup.ovpn);
                runOnUiThread(() -> {
                    status.setText("Canadian backup found");
                    details.setText("Source: VPNBook\nServer: " + backup.server +
                            "\nProtocol: TCP 443\n\nOpening the VPN profile now.");
                    launchOpenVpnImport(file);
                    connect.setEnabled(true);
                    check.setEnabled(true);
                });
                return;
            } catch (Exception bookError) {
                final String gateMsg = gateError == null ? "unknown" : safeMessage(gateError);
                final String bookMsg = safeMessage(bookError);
                runOnUiThread(() -> {
                    status.setText("Could not connect");
                    details.setText("Both free Canadian sources failed.\n\n" +
                            "VPN Gate: " + gateMsg + "\n" +
                            "VPNBook: " + bookMsg +
                            "\n\nTap CONNECT TO CANADA to retry.");
                    connect.setEnabled(true);
                    check.setEnabled(true);
                });
            }
        });
    }

    private VpnBookProfile fetchVpnBookCanada() throws IOException {
        String page = getText("https://www.vpnbook.com/freevpn/openvpn", 15000);
        String password = extractVpnBookPassword(page);
        if (password == null || password.length() < 5) {
            // Current credential at build time; normally the live page parser above supplies it.
            // Keeping this only as a short-term fallback if VPNBook changes its page markup.
            password = "ytw2awn";
        }

        String[] servers = {"ca196", "ca149"};
        IOException last = null;
        for (String server : servers) {
            String[] urls = {
                    "https://www.vpnbook.com/free-openvpn-account/vpnbook-openvpn-" + server + ".zip",
                    "https://www.vpnbook.com/free-openvpn-account/VPNBook.com-OpenVPN-" + server.toUpperCase(Locale.US) + ".zip"
            };
            for (String url : urls) {
                try {
                    byte[] zip = getBytes(url, 20000, 4 * 1024 * 1024);
                    String ovpn = extractTcp443Ovpn(zip);
                    if (ovpn == null) throw new IOException("TCP 443 profile missing in VPNBook bundle");
                    ovpn = normalizeProfile(ovpn);
                    ovpn = makeCredentialsInline(ovpn, "vpnbook", password);
                    return new VpnBookProfile(server + ".vpnbook.com", ovpn);
                } catch (IOException e) {
                    last = e;
                }
            }
        }
        throw last != null ? last : new IOException("VPNBook Canada profile unavailable");
    }

    private static String extractVpnBookPassword(String html) {
        // Convert page markup to rough visible text first so minor HTML changes do not matter.
        String text = html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ");

        Pattern p = Pattern.compile("(?i)Password\\s*(?:Copy\\s*)?([A-Za-z0-9]{5,32})");
        Matcher m = p.matcher(text);
        if (m.find()) return m.group(1);

        // Some layouts put the credential into a value/data attribute near the word Password.
        p = Pattern.compile("(?is)Password.{0,500}?(?:value|data-[A-Za-z-]+)=[\\\"']([A-Za-z0-9]{5,32})[\\\"']");
        m = p.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private static String extractTcp443Ovpn(byte[] zipBytes) throws IOException {
        String firstOvpn = null;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().toLowerCase(Locale.US);
                if (!name.endsWith(".ovpn")) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zin.read(buf)) != -1) out.write(buf, 0, n);
                String ovpn = out.toString(StandardCharsets.UTF_8.name());
                if (firstOvpn == null) firstOvpn = ovpn;
                if (name.contains("tcp443") || name.contains("tcp-443")) return ovpn;
            }
        }
        return firstOvpn;
    }

    private File writeProfile(String ovpn) throws IOException {
        File dir = new File(getCacheDir(), "vpn");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create cache directory");
        File file = new File(dir, "canada.ovpn");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(ovpn.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private void launchOpenVpnImport(File file) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/x-openvpn-profile");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException ex) {
            status.setText("Install OpenVPN for Android first");
            details.setText("Canada IP found a Canadian server, but Android still needs the free OpenVPN for Android tunnel engine. Install it, then return here and tap CONNECT TO CANADA again.");
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=de.blinkt.openvpn")));
            } catch (Exception ignored) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=de.blinkt.openvpn")));
            }
        }
    }

    private void checkIp() {
        setBusy(true, "Checking public IP…");
        executor.execute(() -> {
            try {
                String json = getText("https://ipwho.is/", 10000);
                String ip = jsonField(json, "ip");
                String country = jsonField(json, "country");
                String code = jsonField(json, "country_code");
                runOnUiThread(() -> {
                    status.setText("CA".equalsIgnoreCase(code) ? "Connected through Canada ✓" : "Not currently Canadian");
                    details.setText("Public IP: " + ip + "\nCountry: " + country + " (" + code + ")");
                    connect.setEnabled(true);
                    check.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("IP check failed");
                    details.setText(safeMessage(e));
                    connect.setEnabled(true);
                    check.setEnabled(true);
                });
            }
        });
    }

    private static Relay parseBestCanada(String csv) throws IOException {
        Relay best = null;
        for (String raw : csv.split("\\r?\\n")) {
            if (raw.startsWith("*") || raw.startsWith("#") || raw.trim().isEmpty()) continue;
            List<String> c = parseCsvLine(raw);
            if (c.size() < 15) continue;
            String countryCode = c.get(6).trim();
            String config = c.get(14).trim();
            if (!"CA".equalsIgnoreCase(countryCode) || config.isEmpty()) continue;
            long speed = number(c.get(4));
            Relay r = new Relay(c.get(0), speed, config);
            if (best == null || r.speed > best.speed) best = r;
        }
        return best;
    }

    private static List<String> parseCsvLine(String s) {
        ArrayList<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\"') {
                if (q && i + 1 < s.length() && s.charAt(i + 1) == '\"') {
                    cur.append('\"');
                    i++;
                } else {
                    q = !q;
                }
            } else if (ch == ',' && !q) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static String makeCredentialsInline(String s, String username, String password) {
        String inline = "<auth-user-pass>\n" + username + "\n" + password + "\n</auth-user-pass>";
        String replaced = s.replaceFirst("(?m)^\\s*auth-user-pass(?:\\s+\\S+)?\\s*$", Matcher.quoteReplacement(inline));
        if (replaced.equals(s)) replaced = s + "\n" + inline + "\n";
        return replaced;
    }

    private static String normalizeProfile(String s) {
        // Android's OpenVPN engine owns the tunnel device name.
        s = s.replaceAll("(?m)^\\s*dev\\s+tun\\d+\\s*$", "dev tun");
        return s;
    }

    private static String getText(String url, int timeout) throws IOException {
        return new String(getBytes(url, timeout, 8 * 1024 * 1024), StandardCharsets.UTF_8);
    }

    private static byte[] getBytes(String url, int timeout, int maxBytes) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 CanadaIP/1.1");
        c.setRequestProperty("Accept", "*/*");
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code + " from " + new URL(url).getHost());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] b = new byte[8192];
                int n;
                int total = 0;
                while ((n = in.read(b)) != -1) {
                    total += n;
                    if (total > maxBytes) throw new IOException("Download was unexpectedly large");
                    out.write(b, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static String jsonField(String json, String key) {
        String needle = "\"" + key + "\"";
        int p = json.indexOf(needle);
        if (p < 0) return "?";
        p = json.indexOf(':', p) + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        if (p < json.length() && json.charAt(p) == '\"') {
            int e = json.indexOf('\"', p + 1);
            return e > p ? json.substring(p + 1, e) : "?";
        }
        int e = p;
        while (e < json.length() && ",}".indexOf(json.charAt(e)) < 0) e++;
        return json.substring(p, e).trim();
    }

    private static long number(String s) {
        try { return Long.parseLong(s.trim()); }
        catch (Exception e) { return 0; }
    }

    private static String humanRate(long bps) {
        if (bps <= 0) return "unknown";
        return String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0);
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    private static class Relay {
        final String host, configB64;
        final long speed;
        Relay(String h, long s, String c) { host = h; speed = s; configB64 = c; }
    }

    private static class VpnBookProfile {
        final String server, ovpn;
        VpnBookProfile(String server, String ovpn) { this.server = server; this.ovpn = ovpn; }
    }
}
