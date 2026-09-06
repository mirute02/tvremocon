// Phase 0 probe for a Tapo H110 IR hub over KLAP v2. Single-file, run with:
//   java -cp tools/json.jar tools/KlapProbe.java <command> [options]
//
// Commands:
//   discover [--subnet 192.168.1.]      tcp/80 scan + handshake1 probe -> KLAP candidates
//   info     --host H                    handshake (verifies credentials) + get_device_info
//   remotes  --host H                    ir.remote children + key_list -> tools/fixtures/remotes.json
//   send     --host H --device-id ID --key NAME [--no-batch]
//   vectors                              deterministic KLAP test vectors from fake credentials
//
// Credentials come from TAPO_USER / TAPO_PASS. Username is trimmed, case preserved.
// --try-lowercase additionally lowercases the username (explicit diagnostic only).
// Never prints authHash, password, or session cookie values.

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class KlapProbe {

    static final int CONNECT_MS = 1500;
    static final int READ_MS = 5000;
    static final Path FIXTURES = Path.of("tools", "fixtures");

    // ---------------------------------------------------------------- crypto

    static byte[] digest(String alg, byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance(alg);
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] sha256(byte[]... p) { return digest("SHA-256", p); }
    static byte[] sha1(byte[]... p) { return digest("SHA-1", p); }
    static byte[] md5(byte[]... p) { return digest("MD5", p); }
    static byte[] int32be(int v) { return ByteBuffer.allocate(4).putInt(v).array(); }
    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** KLAP v2: SHA256( SHA1(username) || SHA1(password) ). */
    static byte[] authHash(String user, String pass) {
        return sha256(sha1(utf8(user)), sha1(utf8(pass)));
    }

    /** Derived session keys. Not thread-safe; request/response must stay paired. */
    static final class Session {
        final byte[] key, sigKey, ivPrefix;
        int seq;

        Session(byte[] localSeed, byte[] remoteSeed, byte[] authHash) {
            key = Arrays.copyOf(sha256(utf8("lsk"), localSeed, remoteSeed, authHash), 16);
            sigKey = Arrays.copyOf(sha256(utf8("ldk"), localSeed, remoteSeed, authHash), 28);
            byte[] fullIv = sha256(utf8("iv"), localSeed, remoteSeed, authHash);
            ivPrefix = Arrays.copyOf(fullIv, 12);
            seq = ByteBuffer.wrap(fullIv, 28, 4).getInt();
        }

        byte[] iv() { return ByteBuffer.allocate(16).put(ivPrefix).put(int32be(seq)).array(); }

        /** Increments seq, returns signature||ciphertext. */
        byte[] encrypt(byte[] plain) throws GeneralSecurityException {
            seq += 1;
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv()));
            byte[] ct = c.doFinal(plain);
            byte[] sig = sha256(sigKey, int32be(seq), ct);
            return ByteBuffer.allocate(sig.length + ct.length).put(sig).put(ct).array();
        }

        /** Strips the 32-byte signature and decrypts with the current seq. */
        byte[] decrypt(byte[] payload) throws GeneralSecurityException {
            if (payload.length < 32) throw new IllegalArgumentException("response shorter than signature");
            byte[] ct = Arrays.copyOfRange(payload, 32, payload.length);
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv()));
            return c.doFinal(ct);
        }
    }

    // ---------------------------------------------------------------- http

    record Resp(int status, byte[] body, List<String> setCookies) {}

    /** One POST, no redirects, no retry. */
    static Resp post(String url, byte[] body, String cookie, String contentType) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(CONNECT_MS);
        c.setReadTimeout(READ_MS);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setUseCaches(false);
        if (contentType != null) c.setRequestProperty("Content-Type", contentType);
        if (cookie != null) c.setRequestProperty("Cookie", cookie);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream o = c.getOutputStream()) { o.write(body); }
        int status = c.getResponseCode();
        byte[] data;
        try (InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream()) {
            data = in == null ? new byte[0] : in.readAllBytes();
        }
        List<String> cookies = c.getHeaderFields().getOrDefault("Set-Cookie", List.of());
        c.disconnect();
        return new Resp(status, data, cookies);
    }

    /** Keep only TP_SESSIONID; the device does not want TIMEOUT echoed back. */
    static String sessionCookie(List<String> setCookies) {
        for (String h : setCookies) {
            for (String part : h.split(";")) {
                String p = part.trim();
                if (p.startsWith("TP_SESSIONID=")) return p;
            }
        }
        return null;
    }

    static int timeoutSeconds(List<String> setCookies) {
        for (String h : setCookies)
            for (String part : h.split(";")) {
                String p = part.trim();
                if (p.startsWith("TIMEOUT=")) try { return Integer.parseInt(p.substring(8)); } catch (NumberFormatException ignored) {}
            }
        return -1;
    }

    // ---------------------------------------------------------------- client

    static final class AuthException extends IOException {
        AuthException(String m) { super(m); }
    }

    static final class Client {
        final String base;
        final byte[] authHash;
        final String terminalUuid;
        Session session;
        String cookie;

        Client(String host, byte[] authHash) {
            this.base = "http://" + host + ":80/app";
            this.authHash = authHash;
            UUID u = UUID.randomUUID();
            byte[] raw = ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array();
            this.terminalUuid = Base64.getEncoder().encodeToString(md5(raw));
        }

        void handshake() throws IOException, GeneralSecurityException {
            byte[] localSeed = new byte[16];
            new SecureRandom().nextBytes(localSeed);

            Resp h1 = post(base + "/handshake1", localSeed, null, null);
            if (h1.status != 200) throw new IOException("handshake1 HTTP " + h1.status);
            if (h1.body.length != 48) throw new IOException("handshake1 returned " + h1.body.length + " bytes, expected 48");
            byte[] remoteSeed = Arrays.copyOfRange(h1.body, 0, 16);
            byte[] deviceHash = Arrays.copyOfRange(h1.body, 16, 48);
            byte[] expected = sha256(localSeed, remoteSeed, authHash);
            if (!MessageDigest.isEqual(expected, deviceHash))
                throw new AuthException("device hash mismatch: credentials rejected (or not KLAP v2)");
            cookie = sessionCookie(h1.setCookies);
            if (cookie == null) throw new IOException("handshake1 did not set TP_SESSIONID");
            int t = timeoutSeconds(h1.setCookies);
            System.err.println("  handshake1 ok (session cookie received, TIMEOUT=" + t + "s)");

            Resp h2 = post(base + "/handshake2", sha256(remoteSeed, localSeed, authHash), cookie, null);
            if (h2.status != 200) throw new IOException("handshake2 HTTP " + h2.status);
            System.err.println("  handshake2 ok -> KLAP v2 confirmed");
            session = new Session(localSeed, remoteSeed, authHash);
        }

        /** Sends exactly one request. No retry at any layer; caller decides what a failure means. */
        String request(String json) throws IOException, GeneralSecurityException {
            if (session == null) handshake();
            byte[] payload = session.encrypt(utf8(json));
            Resp r = post(base + "/request?seq=" + session.seq, payload, cookie, "application/octet-stream");
            if (r.status == 403) { session = null; throw new IOException("HTTP 403: session rejected"); }
            if (r.status != 200) throw new IOException("HTTP " + r.status + " for seq " + session.seq);
            return new String(session.decrypt(r.body), StandardCharsets.UTF_8);
        }

        JSONObject envelope(String method, JSONObject params) {
            JSONObject o = new JSONObject()
                    .put("method", method)
                    .put("request_time_milis", System.currentTimeMillis())   // sic: TP-Link's spelling
                    .put("terminal_uuid", terminalUuid);
            if (params != null) o.put("params", params);
            return o;
        }

        /** Envelope + send + top-level and recursive error check. Returns the whole response object. */
        JSONObject call(String method, JSONObject params) throws IOException, GeneralSecurityException {
            String raw = request(envelope(method, params).toString());
            JSONObject resp = new JSONObject(raw);
            validate(resp, method);
            return resp;
        }
    }

    // ---------------------------------------------------------------- protocol errors
    // Recursive check adapted from Loadst0ne/tapo-ir-hub protocol.py (MIT). Success codes: absent, null, 0, "0".

    static final class ProtocolError extends IOException {
        final Object code;
        ProtocolError(String method, Object code) { super(method + " failed with protocol error " + code); this.code = code; }
    }

    static boolean isFailureCode(Object v) {
        if (v == null || v == JSONObject.NULL) return false;
        if (v instanceof Number n) return n.intValue() != 0;
        if (v instanceof String s) return !s.equals("0");
        return true;
    }

    static void validate(Object node, String method) throws ProtocolError {
        if (node instanceof JSONObject o) {
            for (String k : new String[]{"error_code", "errorCode"})
                if (o.has(k) && isFailureCode(o.opt(k))) throw new ProtocolError(method, o.opt(k));
            for (String k : new String[]{method, "multipleRequest", "responses", "responseData", "result"})
                if (o.has(k)) validate(o.opt(k), method);
        } else if (node instanceof JSONArray a) {
            for (int i = 0; i < a.length(); i++) validate(a.opt(i), method);
        }
    }

    // ---------------------------------------------------------------- helpers

    static String b64decode(String s) {
        if (s == null) return null;
        try { return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { return s; }
    }

    static String localIpv4() {
        try (java.net.DatagramSocket s = new java.net.DatagramSocket()) {
            s.connect(java.net.InetAddress.getByName("192.0.2.1"), 9); // TEST-NET, never sent
            return s.getLocalAddress().getHostAddress();
        } catch (Exception e) { return null; }
    }

    static byte[] credentials(Map<String, String> opts) {
        String user = System.getenv("TAPO_USER");
        String pass = System.getenv("TAPO_PASS");
        if (user == null || pass == null) {
            // Fallback: ~/.tvremocon_credentials, line 1 = email, line 2 = password (chmod 600, outside the repo)
            Path f = Path.of(System.getProperty("user.home"), ".tvremocon_credentials");
            try {
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                if (lines.size() >= 2) { user = lines.get(0); pass = lines.get(1); }
            } catch (IOException ignored) {}
        }
        if (user == null || pass == null) die("set TAPO_USER and TAPO_PASS, or create ~/.tvremocon_credentials (line1 email, line2 password)");
        user = user.trim();                     // whitespace stripped, case preserved
        if (opts.containsKey("try-lowercase")) {
            user = user.toLowerCase(java.util.Locale.ROOT);
            System.err.println("  (diagnostic: username lowercased)");
        }
        return authHash(user, pass);            // password used verbatim
    }

    static void die(String msg) { System.err.println("error: " + msg); System.exit(2); }

    static void writeFixture(String name, Object json) throws IOException {
        Files.createDirectories(FIXTURES);
        Path p = FIXTURES.resolve(name);
        String text = json instanceof JSONObject o ? o.toString(2) : ((JSONArray) json).toString(2);
        Files.writeString(p, text + "\n");
        System.err.println("  wrote " + p);
    }

    // ---------------------------------------------------------------- commands

    static void discover(Map<String, String> opts) throws Exception {
        String subnet = opts.get("subnet");
        if (subnet == null) {
            String ip = localIpv4();
            if (ip == null) die("cannot determine local IP; pass --subnet 192.168.x.");
            subnet = ip.substring(0, ip.lastIndexOf('.') + 1);
        }
        System.err.println("scanning " + subnet + "0/24 tcp/80 ...");
        ExecutorService ex = Executors.newFixedThreadPool(64);
        List<Future<String>> fs = new ArrayList<>();
        for (int i = 1; i < 255; i++) {
            final String h = subnet + i;
            fs.add(ex.submit(() -> {
                try (Socket s = new Socket()) { s.connect(new InetSocketAddress(h, 80), 400); }
                catch (IOException e) { return null; }
                try {
                    byte[] seed = new byte[16];
                    new SecureRandom().nextBytes(seed);
                    Resp r = post("http://" + h + ":80/app/handshake1", seed, null, null);
                    boolean klap = r.status == 200 && r.body.length == 48 && sessionCookie(r.setCookies) != null;
                    return h + (klap ? "  KLAP candidate (48-byte handshake1, TIMEOUT=" + timeoutSeconds(r.setCookies) + "s)"
                                     : "  port 80 open, not KLAP (HTTP " + r.status + ", " + r.body.length + " bytes)");
                } catch (IOException e) {
                    return h + "  port 80 open, handshake1 failed: " + e.getMessage();
                }
            }));
        }
        int candidates = 0;
        for (Future<String> f : fs) {
            String r = f.get();
            if (r != null) { System.out.println(r); if (r.contains("KLAP candidate")) candidates++; }
        }
        ex.shutdown();
        System.err.println(candidates + " KLAP candidate(s). Confirm model with: info --host <ip>");
    }

    static Client connect(Map<String, String> opts) throws Exception {
        String host = opts.get("host");
        if (host == null) die("--host is required");
        Client c = new Client(host, credentials(opts));
        System.err.println("connecting to " + host + " ...");
        c.handshake();
        return c;
    }

    static void info(Map<String, String> opts) throws Exception {
        Client c = connect(opts);
        JSONObject resp = c.call("get_device_info", null);
        JSONObject r = resp.getJSONObject("result");
        JSONObject out = new JSONObject()
                .put("model", r.optString("model"))
                .put("device_type", r.optString("type", r.optString("device_type")))
                .put("device_id", r.optString("device_id"))
                .put("fw_ver", r.optString("fw_ver"))
                .put("hw_ver", r.optString("hw_ver"))
                .put("mac", r.optString("mac"))
                .put("ip", r.optString("ip"))
                .put("nickname", b64decode(r.optString("nickname", null)));
        System.out.println(out.toString(2));
        writeFixture("device.json", out);
        writeFixture("device_info_raw.json", r);
        if (!"H110".equalsIgnoreCase(out.optString("model"))) System.err.println("WARNING: model is not H110");
    }

    static JSONArray allChildren(Client c) throws Exception {
        JSONArray all = new JSONArray();
        int start = 0;
        while (true) {
            JSONObject r = c.call("get_child_device_list", new JSONObject().put("start_index", start)).getJSONObject("result");
            JSONArray page = r.optJSONArray("child_device_list");
            if (page == null) break;
            for (int i = 0; i < page.length(); i++) all.put(page.get(i));
            int sum = r.optInt("sum", all.length());
            start = r.optInt("start_index", start) + page.length();
            if (page.length() == 0 || start >= sum) break;
        }
        return all;
    }

    static void remotes(Map<String, String> opts) throws Exception {
        Client c = connect(opts);
        JSONArray children = allChildren(c);
        writeFixture("child_device_list_raw.json", children);
        JSONArray remotes = new JSONArray();
        for (int i = 0; i < children.length(); i++) {
            JSONObject ch = children.getJSONObject(i);
            if (!"ir.remote".equals(ch.optString("category"))) continue;
            JSONObject rem = new JSONObject()
                    .put("device_id", ch.optString("device_id"))
                    .put("nickname", b64decode(ch.optString("nickname", null)))
                    .put("model", ch.optString("model"))
                    .put("key_sum", ch.optInt("key_sum", -1))
                    .put("is_ac", "AC".equals(ch.optString("model")));
            JSONArray keys = new JSONArray();
            JSONArray kl = ch.optJSONArray("key_list");
            if (kl != null) for (int k = 0; k < kl.length(); k++) {
                JSONObject key = kl.getJSONObject(k);
                keys.put(new JSONObject()
                        .put("name", key.optString("name"))                     // raw protocol name: what send uses
                        .put("id", key.opt("id"))
                        .put("display_name", b64decode(key.optString("display_name", null)))
                        .put("order", key.opt("order"))
                        .put("type", key.opt("type")));
            }
            rem.put("keys", keys);
            remotes.put(rem);
        }
        System.out.println(remotes.toString(2));
        writeFixture("remotes.json", remotes);
        System.err.println(remotes.length() + " ir.remote child(ren) of " + children.length() + " total");
    }

    static void send(Map<String, String> opts) throws Exception {
        String deviceId = opts.get("device-id");
        String key = opts.get("key");
        if (deviceId == null || key == null) die("--device-id and --key are required");
        boolean batch = !opts.containsKey("no-batch");

        JSONObject inner = new JSONObject().put("method", "sendIrCmdById").put("params", new JSONObject().put("name", key));
        JSONObject requestData = batch
                ? new JSONObject().put("method", "multipleRequest").put("params", new JSONObject().put("requests", new JSONArray().put(inner)))
                : inner;
        JSONObject params = new JSONObject().put("device_id", deviceId).put("requestData", requestData);

        Client c = connect(opts);
        System.err.println("sending key '" + key + "' (" + (batch ? "multipleRequest" : "unbatched") + ") — exactly once");
        String raw;
        try {
            raw = c.request(c.envelope("control_child", params).toString());
        } catch (IOException e) {
            System.out.println("RESULT: UNKNOWN — no usable response after send (" + e.getMessage() + "). Check whether the TV reacted.");
            return;
        }
        JSONObject resp = new JSONObject(raw);
        System.out.println("raw response: " + resp.toString(2));
        try {
            validate(resp, "sendIrCmdById");
        } catch (ProtocolError pe) {
            System.out.println("RESULT: REJECTED — hub reported error " + pe.code);
            return;
        }
        System.out.println(classify(resp, batch) ? "RESULT: ACCEPTED — hub acknowledged sendIrCmdById" : "RESULT: UNKNOWN — no error, but response shape not as expected");
    }

    /** True only if the response has the expected acknowledgement shape. */
    static boolean classify(JSONObject resp, boolean batch) {
        JSONObject result = resp.optJSONObject("result");
        if (result == null) return false;
        JSONObject rd = result.optJSONObject("responseData");
        if (rd == null) return false;
        if (!batch) return rd.has("error_code") && !isFailureCode(rd.opt("error_code"));
        JSONArray responses = findResponses(rd);
        if (responses == null || responses.length() == 0) return false;
        JSONObject first = responses.optJSONObject(0);
        return first != null && "sendIrCmdById".equals(first.optString("method")) && first.has("error_code") && !isFailureCode(first.opt("error_code"));
    }

    static JSONArray findResponses(JSONObject o) {
        if (o.has("responses")) return o.optJSONArray("responses");
        JSONObject r = o.optJSONObject("result");
        return r == null ? null : findResponses(r);
    }

    static void vectors() throws Exception {
        byte[] localSeed = new byte[16], remoteSeed = new byte[16];
        for (int i = 0; i < 16; i++) { localSeed[i] = (byte) i; remoteSeed[i] = (byte) (0xA0 + i); }
        byte[] ah = authHash("user@example.com", "pw");
        Session s = new Session(localSeed, remoteSeed, ah);
        int seq0 = s.seq;
        String plain = "{\"method\":\"get_device_info\"}";
        byte[] payload = s.encrypt(utf8(plain));
        JSONObject v = new JSONObject()
                .put("note", "fake credentials, fixed seeds; safe to commit")
                .put("username", "user@example.com").put("password", "pw")
                .put("local_seed", hex(localSeed)).put("remote_seed", hex(remoteSeed))
                .put("auth_hash", hex(ah))
                .put("key", hex(s.key)).put("sig_key", hex(s.sigKey)).put("iv_prefix", hex(s.ivPrefix))
                .put("seq_initial", seq0).put("seq_first_request", s.seq)
                .put("handshake2_body", hex(sha256(remoteSeed, localSeed, ah)))
                .put("plaintext", plain)
                .put("signature", hex(Arrays.copyOf(payload, 32)))
                .put("ciphertext", hex(Arrays.copyOfRange(payload, 32, payload.length)));
        System.out.println(v.toString(2));
        byte[] back = s.decrypt(payload);
        if (!plain.equals(new String(back, StandardCharsets.UTF_8))) die("roundtrip mismatch");
        System.err.println("roundtrip ok");
    }

    // ---------------------------------------------------------------- main

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); return; }
        Map<String, String> opts = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) die("unexpected argument " + a);
            String k = a.substring(2);
            boolean flag = k.equals("no-batch") || k.equals("try-lowercase");
            if (flag) opts.put(k, "true");
            else if (i + 1 < args.length) opts.put(k, args[++i]);
            else die("missing value for --" + k);
        }
        try {
            switch (args[0]) {
                case "discover" -> discover(opts);
                case "info" -> info(opts);
                case "remotes" -> remotes(opts);
                case "send" -> send(opts);
                case "vectors" -> vectors();
                default -> usage();
            }
        } catch (AuthException e) {
            die("AUTH: " + e.getMessage() + " — check TAPO_USER/TAPO_PASS exactly as typed in the Tapo app; --try-lowercase to test the lowercase variant");
        } catch (ProtocolError e) {
            die("PROTOCOL: " + e.getMessage());
        } catch (IOException e) {
            die("NETWORK: " + e.getMessage());
        }
    }

    static void usage() {
        System.out.println("""
            usage: java -cp tools/json.jar tools/KlapProbe.java <command> [options]
              discover [--subnet 192.168.1.]
              info     --host H [--try-lowercase]
              remotes  --host H
              send     --host H --device-id ID --key NAME [--no-batch]
              vectors
            env: TAPO_USER, TAPO_PASS""");
    }
}
