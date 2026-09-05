package com.pierce.canadaip;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.AdapterView;
import android.view.View;
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
    private Spinner location;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static final Set<String> EUROPE_CODES = new HashSet<>(Arrays.asList(
            "AL","AD","AT","BY","BE","BA","BG","HR","CY","CZ","DK","EE","FI","FR","DE","GR",
            "HU","IS","IE","IT","LV","LI","LT","LU","MT","MD","MC","ME","NL","MK","NO","PL",
            "PT","RO","SM","RS","SK","SI","ES","SE","CH","TR","UA","GB","VA"
    ));

    private static final LocationOption[] LOCATIONS = {
            new LocationOption("Canada", "CA", false),
            new LocationOption("Europe — Fastest", "EU", true),
            new LocationOption("United Kingdom", "GB", false),
            new LocationOption("Germany", "DE", false),
            new LocationOption("France", "FR", false),
            new LocationOption("Netherlands", "NL", false),
            new LocationOption("Spain", "ES", false),
            new LocationOption("Italy", "IT", false),
            new LocationOption("Switzerland", "CH", false),
            new LocationOption("Sweden", "SE", false),
            new LocationOption("Norway", "NO", false),
            new LocationOption("Finland", "FI", false),
            new LocationOption("Denmark", "DK", false),
            new LocationOption("Belgium", "BE", false),
            new LocationOption("Austria", "AT", false),
            new LocationOption("Ireland", "IE", false),
            new LocationOption("Portugal", "PT", false),
            new LocationOption("Poland", "PL", false),
            new LocationOption("Czechia", "CZ", false),
            new LocationOption("Romania", "RO", false),
            new LocationOption("Greece", "GR", false),
            new LocationOption("Hungary", "HU", false),
            new LocationOption("Bulgaria", "BG", false),
            new LocationOption("Croatia", "HR", false),
            new LocationOption("Serbia", "RS", false),
            new LocationOption("Slovakia", "SK", false),
            new LocationOption("Slovenia", "SI", false),
            new LocationOption("Lithuania", "LT", false),
            new LocationOption("Latvia", "LV", false),
            new LocationOption("Estonia", "EE", false),
            new LocationOption("Iceland", "IS", false),
            new LocationOption("Luxembourg", "LU", false),
            new LocationOption("Ukraine", "UA", false),
            new LocationOption("Turkey", "TR", false)
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        details = findViewById(R.id.details);
        connect = findViewById(R.id.connect);
        check = findViewById(R.id.check);
        location = findViewById(R.id.location);

        String[] names = new String[LOCATIONS.length];
        for (int i = 0; i < LOCATIONS.length; i++) names[i] = LOCATIONS[i].name;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        location.setAdapter(adapter);
        location.setSelection(0);
        location.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                LocationOption opt = LOCATIONS[position];
                connect.setText("CONNECT TO " + opt.name.toUpperCase(Locale.US));
                status.setText("Ready");
                details.setText(opt.regionEurope
                        ? "Chooses the fastest available European relay."
                        : "Selected location: " + opt.name);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        connect.setOnClickListener(v -> findSelectedRelay());
        check.setOnClickListener(v -> checkIp());
    }

    private LocationOption selectedLocation() {
        int p = location.getSelectedItemPosition();
        if (p < 0 || p >= LOCATIONS.length) p = 0;
        return LOCATIONS[p];
    }

    private void setBusy(boolean busy, String msg) {
        runOnUiThread(() -> {
            connect.setEnabled(!busy);
            check.setEnabled(!busy);
            location.setEnabled(!busy);
            status.setText(msg);
        });
    }

    private void findSelectedRelay() {
        final LocationOption target = selectedLocation();
        setBusy(true, "Finding a server in " + target.name + "…");
        details.setText("Checking live free VPN servers.");

        executor.execute(() -> {
            Exception gateError = null;

            try {
                String csv = getText("https://www.vpngate.net/api/iphone/", 15000);
                Relay best = parseBest(csv, target);
                if (best != null) {
                    String ovpn = new String(Base64.getDecoder().decode(best.configB64), StandardCharsets.UTF_8);
                    ovpn = makeCredentialsInline(ovpn, "vpn", "vpn");
                    File file = writeProfile(normalizeProfile(ovpn), target.code);
                    final Relay chosen = best;
                    runOnUiThread(() -> {
                        status.setText("Server found: " + chosen.countryCode);
                        details.setText("Source: VPN Gate\nLocation: " + chosen.countryName +
                                "\nRelay: " + chosen.host +
                                "\nApprox. line speed: " + humanRate(chosen.speed) +
                                "\n\nOpening the VPN profile now.");
                        launchOpenVpnImport(file, target.name);
                        setControlsEnabled(true);
                    });
                    return;
                }
                gateError = new IOException("No matching VPN Gate OpenVPN relay was listed.");
            } catch (Exception e) {
                gateError = e;
            }

            try {
                runOnUiThread(() -> {
                    status.setText("Trying backup server…");
                    details.setText("VPN Gate had no usable relay. Checking VPNBook where a free OpenVPN fallback exists.");
                });

                VpnBookProfile backup = fetchVpnBook(target);
                File file = writeProfile(backup.ovpn, target.code);
                runOnUiThread(() -> {
                    status.setText("Backup server found");
                    details.setText("Source: VPNBook\nServer: " + backup.server +
                            "\nLocation: " + backup.countryName +
                            "\nProtocol: TCP 443\n\nOpening the VPN profile now.");
                    launchOpenVpnImport(file, target.name);
                    setControlsEnabled(true);
                });
                return;
            } catch (Exception bookError) {
                final String gateMsg = gateError == null ? "unknown" : safeMessage(gateError);
                final String bookMsg = safeMessage(bookError);
                runOnUiThread(() -> {
                    status.setText("No server available");
                    details.setText("Could not find a free OpenVPN server for " + target.name + ".\n\n" +
                            "VPN Gate: " + gateMsg + "\n" +
                            "VPNBook fallback: " + bookMsg +
                            "\n\nTry Europe — Fastest for the best chance of connecting.");
                    setControlsEnabled(true);
                });
            }
        });
    }

    private void setControlsEnabled(boolean enabled) {
        connect.setEnabled(enabled);
        check.setEnabled(enabled);
        location.setEnabled(enabled);
    }

    private VpnBookProfile fetchVpnBook(LocationOption target) throws IOException {
        String page = getText("https://www.vpnbook.com/freevpn/openvpn", 15000);
        String password = extractVpnBookPassword(page);
        if (password == null || password.length() < 5) password = "ytw2awn";

        VpnBookServer[] servers = vpnBookServers(target);
        if (servers.length == 0) throw new IOException("No VPNBook OpenVPN fallback for this location");

        IOException last = null;
        for (VpnBookServer item : servers) {
            String server = item.id;
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
                    return new VpnBookProfile(server + ".vpnbook.com", item.countryName, ovpn);
                } catch (IOException e) {
                    last = e;
                }
            }
        }
        throw last != null ? last : new IOException("VPNBook profile unavailable");
    }

    private static VpnBookServer[] vpnBookServers(LocationOption target) {
        if (target.regionEurope) {
            return new VpnBookServer[] {
                    new VpnBookServer("uk205", "United Kingdom"), new VpnBookServer("uk68", "United Kingdom"),
                    new VpnBookServer("de20", "Germany"), new VpnBookServer("de220", "Germany"),
                    new VpnBookServer("fr200", "France"), new VpnBookServer("fr231", "France"), new VpnBookServer("fr2311", "France")
            };
        }
        switch (target.code) {
            case "CA": return new VpnBookServer[] { new VpnBookServer("ca196", "Canada"), new VpnBookServer("ca149", "Canada") };
            case "GB": return new VpnBookServer[] { new VpnBookServer("uk205", "United Kingdom"), new VpnBookServer("uk68", "United Kingdom") };
            case "DE": return new VpnBookServer[] { new VpnBookServer("de20", "Germany"), new VpnBookServer("de220", "Germany") };
            case "FR": return new VpnBookServer[] { new VpnBookServer("fr200", "France"), new VpnBookServer("fr231", "France"), new VpnBookServer("fr2311", "France") };
            default: return new VpnBookServer[0];
        }
    }

    private static String extractVpnBookPassword(String html) {
        String text = html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ");

        Pattern p = Pattern.compile("(?i)Password\\s*(?:Copy\\s*)?([A-Za-z0-9]{5,32})");
        Matcher m = p.matcher(text);
        if (m.find()) return m.group(1);

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

    private File writeProfile(String ovpn, String code) throws IOException {
        File dir = new File(getCacheDir(), "vpn");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create cache directory");
        String safe = code.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.US);
        File file = new File(dir, "location-" + safe + ".ovpn");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(ovpn.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private void launchOpenVpnImport(File file, String targetName) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/x-openvpn-profile");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException ex) {
            status.setText("Install OpenVPN for Android first");
            details.setText("A " + targetName + " server was found, but Android still needs the free OpenVPN for Android tunnel engine. Install it, then return and connect again.");
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=de.blinkt.openvpn")));
            } catch (Exception ignored) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=de.blinkt.openvpn")));
            }
        }
    }

    private void checkIp() {
        final LocationOption target = selectedLocation();
        setBusy(true, "Checking public IP…");
        executor.execute(() -> {
            try {
                String json = getText("https://ipwho.is/", 10000);
                String ip = jsonField(json, "ip");
                String country = jsonField(json, "country");
                String code = jsonField(json, "country_code");
                boolean match = target.regionEurope ? EUROPE_CODES.contains(code.toUpperCase(Locale.US)) : target.code.equalsIgnoreCase(code);
                runOnUiThread(() -> {
                    status.setText(match ? "Connected through " + target.name + " ✓" : "IP does not match selection");
                    details.setText("Public IP: " + ip + "\nCountry: " + country + " (" + code + ")\nSelected: " + target.name);
                    setControlsEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("IP check failed");
                    details.setText(safeMessage(e));
                    setControlsEnabled(true);
                });
            }
        });
    }

    private static Relay parseBest(String csv, LocationOption target) throws IOException {
        Relay best = null;
        for (String raw : csv.split("\\r?\\n")) {
            if (raw.startsWith("*") || raw.startsWith("#") || raw.trim().isEmpty()) continue;
            List<String> c = parseCsvLine(raw);
            if (c.size() < 15) continue;
            String countryName = c.get(5).trim();
            String countryCode = c.get(6).trim().toUpperCase(Locale.US);
            String config = c.get(14).trim();
            if (config.isEmpty()) continue;
            boolean match = target.regionEurope ? EUROPE_CODES.contains(countryCode) : target.code.equalsIgnoreCase(countryCode);
            if (!match) continue;
            long speed = number(c.get(4));
            Relay r = new Relay(c.get(0), countryName, countryCode, speed, config);
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
        return s.replaceAll("(?m)^\\s*dev\\s+tun\\d+\\s*$", "dev tun");
    }

    private static String getText(String url, int timeout) throws IOException {
        return new String(getBytes(url, timeout, 8 * 1024 * 1024), StandardCharsets.UTF_8);
    }

    private static byte[] getBytes(String url, int timeout, int maxBytes) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 CanadaEuropeIP/1.2");
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

    private static class LocationOption {
        final String name, code;
        final boolean regionEurope;
        LocationOption(String name, String code, boolean regionEurope) {
            this.name = name; this.code = code; this.regionEurope = regionEurope;
        }
    }

    private static class Relay {
        final String host, countryName, countryCode, configB64;
        final long speed;
        Relay(String host, String countryName, String countryCode, long speed, String configB64) {
            this.host = host; this.countryName = countryName; this.countryCode = countryCode; this.speed = speed; this.configB64 = configB64;
        }
    }

    private static class VpnBookServer {
        final String id, countryName;
        VpnBookServer(String id, String countryName) { this.id = id; this.countryName = countryName; }
    }

    private static class VpnBookProfile {
        final String server, countryName, ovpn;
        VpnBookProfile(String server, String countryName, String ovpn) {
            this.server = server; this.countryName = countryName; this.ovpn = ovpn;
        }
    }
}
