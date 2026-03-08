package com.example.broadcastapp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private Spinner profileSpinner;
    private EditText ipInput, webPortInput, tcpPortInput, pwdInput;
    private WebView webView;
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private Thread recordingThread = null;
    private SharedPreferences prefs;

    private FrameLayout settingsOverlay;

    private ByteArrayOutputStream audioBufferStream;
    private List<ServerProfile> profileList = new ArrayList<>();
    private ArrayAdapter<ServerProfile> spinnerAdapter;
    private boolean isUserSelecting = false;

    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    // 优雅的校园风格断网提示页
    private static final String CUSTOM_ERROR_HTML = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"><title>连接失败</title><style>body{font-family:'Microsoft YaHei',sans-serif;background:#f4f6f8;color:#2c3e50;display:flex;flex-direction:column;align-items:center;justify-content:center;height:90vh;margin:0;text-align:center;} .icon{margin-bottom:20px;} .title{font-size:20px;font-weight:bold;margin-bottom:10px;} .desc{color:#7f8c8d;font-size:14px;padding:0 30px;line-height:1.5;}</style></head><body><div class=\"icon\"><svg width=\"60\" height=\"60\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#e74c3c\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71\"></path><path d=\"M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71\"></path></svg></div><div class=\"title\">无法连接校园播控核心</div><div class=\"desc\">系统拒绝访问或局域网不通。<br>请点击右上角【节点设置】检查 IP 是否正确，<br>并确保目标教学楼/机房的电脑端已开启。</div></body></html>";

    @SuppressLint({"SetJavaScriptEnabled", "WebViewClientOnReceivedSslError", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }

        prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);

        settingsOverlay = findViewById(R.id.settingsOverlay);
        MaterialButton btnSettings = findViewById(R.id.btnSettings);
        ImageButton btnCloseSettings = findViewById(R.id.btnCloseSettings);

        profileSpinner = findViewById(R.id.profileSpinner);
        ipInput = findViewById(R.id.ipInput);
        webPortInput = findViewById(R.id.webPortInput);
        tcpPortInput = findViewById(R.id.tcpPortInput);
        pwdInput = findViewById(R.id.pwdInput);
        MaterialButton connectBtn = findViewById(R.id.connectBtn);
        MaterialButton btnSaveProfile = findViewById(R.id.btnSaveProfile);
        MaterialButton btnDelProfile = findViewById(R.id.btnDelProfile);
        webView = findViewById(R.id.webview);
        MaterialButton btnIntercom = findViewById(R.id.btnIntercom);

        setupWebView();
        loadProfiles();

        // 弹窗开闭逻辑
        btnSettings.setOnClickListener(v -> settingsOverlay.setVisibility(View.VISIBLE));
        btnCloseSettings.setOnClickListener(v -> settingsOverlay.setVisibility(View.GONE));

        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isUserSelecting && position > 0) {
                    ServerProfile p = profileList.get(position);
                    ipInput.setText(p.ip);
                    webPortInput.setText(p.webPort);
                    tcpPortInput.setText(p.tcpPort);
                    pwdInput.setText(p.pwd);
                }
                isUserSelecting = true;
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnSaveProfile.setOnClickListener(v -> promptForProfileName());
        btnDelProfile.setOnClickListener(v -> deleteCurrentProfile());

        connectBtn.setOnClickListener(v -> {
            String ip = ipInput.getText().toString().trim();
            String port = webPortInput.getText().toString().trim();
            if (!ip.isEmpty() && !port.isEmpty()) {
                prefs.edit().putString("last_ip", ip)
                        .putString("last_wport", port)
                        .putString("last_tport", tcpPortInput.getText().toString().trim())
                        .putString("last_pwd", pwdInput.getText().toString().trim()).apply();
                webView.loadUrl("http://" + ip + ":" + port + "/m");
                // 连接后自动收起弹窗
                settingsOverlay.setVisibility(View.GONE);
            }
        });

        // 初始化数据填充
        ipInput.setText(prefs.getString("last_ip", ""));
        webPortInput.setText(prefs.getString("last_wport", "5000"));
        tcpPortInput.setText(prefs.getString("last_tport", "7000"));
        pwdInput.setText(prefs.getString("last_pwd", ""));

        // 如果是首次打开（没有IP），自动弹出设置窗
        if (ipInput.getText().toString().isEmpty()) {
            settingsOverlay.setVisibility(View.VISIBLE);
        } else {
            connectBtn.performClick();
        }

        // 事件：原生军规加密对讲 (MD2 状态色过渡)
        btnIntercom.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "[!] 需开启麦克风权限", Toast.LENGTH_SHORT).show();
                    return true;
                }
                // MD2 红色
                btnIntercom.setBackgroundTintList(ColorStateList.valueOf(0xFFF44336));
                btnIntercom.setText("[录音中] 松开立刻广播");
                startRecordingLocally();
                return true;

            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                // MD2 绿色
                btnIntercom.setBackgroundTintList(ColorStateList.valueOf(0xFF4CAF50));
                btnIntercom.setText("按住录音 (松开广播)");
                stopRecordingAndSend();
                return true;
            }
            return false;
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
                handler.proceed();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    view.loadDataWithBaseURL(null, CUSTOM_ERROR_HTML, "text/html", "utf-8", null);
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                view.loadDataWithBaseURL(null, CUSTOM_ERROR_HTML, "text/html", "utf-8", null);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                if (request.isForMainFrame() && errorResponse.getStatusCode() >= 400 && errorResponse.getStatusCode() != 401) {
                    view.loadDataWithBaseURL(null, CUSTOM_ERROR_HTML, "text/html", "utf-8", null);
                }
            }
        });
    }

    private static class ServerProfile {
        String name, ip, webPort, tcpPort, pwd;
        ServerProfile(String n, String i, String w, String t, String p) { name=n; ip=i; webPort=w; tcpPort=t; pwd=p; }
        @Override public String toString() { return name; }
    }

    private void loadProfiles() {
        profileList.clear();
        profileList.add(new ServerProfile("未选择 (手动临时输入)", "", "", "", ""));
        try {
            JSONArray arr = new JSONArray(prefs.getString("saved_profiles", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                profileList.add(new ServerProfile(obj.getString("name"), obj.getString("ip"), obj.getString("webPort"), obj.getString("tcpPort"), obj.getString("pwd")));
            }
        } catch (Exception e) {}

        spinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, profileList);
        profileSpinner.setAdapter(spinnerAdapter);
    }

    private void promptForProfileName() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("保存校园节点预设");

        final EditText input = new EditText(this);
        // 修正为校园语境
        input.setHint("如：初一教学楼 / 田径场广播室");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        // 稍微拉开边距，适应 MD 风格
        input.setPadding(60, 40, 60, 40);
        builder.setView(input);

        builder.setPositiveButton("保存", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if(!name.isEmpty()) saveProfile(name);
        });
        builder.setNegativeButton("取消", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void saveProfile(String name) {
        try {
            JSONArray arr = new JSONArray(prefs.getString("saved_profiles", "[]"));
            int existingIndex = -1;
            for(int i=0; i<arr.length(); i++) { if(arr.getJSONObject(i).getString("name").equals(name)) { existingIndex = i; break; } }

            JSONObject obj = new JSONObject();
            obj.put("name", name);
            obj.put("ip", ipInput.getText().toString().trim());
            obj.put("webPort", webPortInput.getText().toString().trim());
            obj.put("tcpPort", tcpPortInput.getText().toString().trim());
            obj.put("pwd", pwdInput.getText().toString().trim());

            if (existingIndex >= 0) arr.put(existingIndex, obj); else arr.put(obj);

            prefs.edit().putString("saved_profiles", arr.toString()).apply();
            Toast.makeText(this, "配置 [" + name + "] 已保存", Toast.LENGTH_SHORT).show();

            isUserSelecting = false;
            loadProfiles();
            for(int i=0; i<profileList.size(); i++) { if(profileList.get(i).name.equals(name)) { profileSpinner.setSelection(i); break; } }
        } catch (Exception e) {}
    }

    private void deleteCurrentProfile() {
        int pos = profileSpinner.getSelectedItemPosition();
        if (pos <= 0) return;
        String nameToDelete = profileList.get(pos).name;

        try {
            JSONArray arr = new JSONArray(prefs.getString("saved_profiles", "[]"));
            JSONArray newArr = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                if (!arr.getJSONObject(i).getString("name").equals(nameToDelete)) newArr.put(arr.getJSONObject(i));
            }
            prefs.edit().putString("saved_profiles", newArr.toString()).apply();
            Toast.makeText(this, "预设已删除", Toast.LENGTH_SHORT).show();

            isUserSelecting = false;
            loadProfiles();
            profileSpinner.setSelection(0);
        } catch (Exception e) {}
    }

    @SuppressLint("MissingPermission")
    private void startRecordingLocally() {
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        final int finalBufferSize = (bufferSize <= 0) ? SAMPLE_RATE * 2 : bufferSize;

        audioBufferStream = new ByteArrayOutputStream();

        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, finalBufferSize);
            audioRecord.startRecording();
            isRecording = true;
        } catch (Exception e) {
            Toast.makeText(this, "[!] 录音机启动失败", Toast.LENGTH_SHORT).show();
            return;
        }

        recordingThread = new Thread(() -> {
            byte[] buffer = new byte[finalBufferSize];
            while (isRecording) {
                int readSize = audioRecord.read(buffer, 0, buffer.length);
                if (readSize > 0) {
                    audioBufferStream.write(buffer, 0, readSize);
                }
            }
        });
        recordingThread.start();
    }

    private void stopRecordingAndSend() {
        isRecording = false;
        if (audioRecord != null) {
            try { audioRecord.stop(); audioRecord.release(); } catch (Exception e) {}
            audioRecord = null;
        }

        final byte[] rawAudioData = audioBufferStream.toByteArray();
        if (rawAudioData.length == 0) return;

        final String ip = ipInput.getText().toString().trim();
        final String webPort = webPortInput.getText().toString().trim();
        final String tcpPort = tcpPortInput.getText().toString().trim();
        final String pwd = pwdInput.getText().toString().trim();

        if (ip.isEmpty() || webPort.isEmpty() || tcpPort.isEmpty()) return;

        new Thread(() -> {
            try {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "认证并打包发送中...", Toast.LENGTH_SHORT).show());
                String authToken = "";

                if (!pwd.isEmpty()) {
                    URL loginUrl = new URL("http://" + ip + ":" + webPort + "/api/login");
                    HttpURLConnection loginConn = (HttpURLConnection) loginUrl.openConnection();
                    loginConn.setRequestMethod("POST");
                    loginConn.setConnectTimeout(3000);
                    loginConn.setRequestProperty("Content-Type", "application/json");
                    loginConn.setDoOutput(true);

                    String jsonInputString = "{\"password\": \"" + pwd + "\"}";
                    try(OutputStream os = loginConn.getOutputStream()) {
                        byte[] input = jsonInputString.getBytes("utf-8");
                        os.write(input, 0, input.length);
                    }

                    if (loginConn.getResponseCode() == 200) {
                        java.util.Scanner s = new java.util.Scanner(loginConn.getInputStream()).useDelimiter("\\A");
                        String response = s.hasNext() ? s.next() : "";
                        JSONObject jsonResponse = new JSONObject(response);
                        authToken = jsonResponse.optString("token", "");
                    } else {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "认证失败，请检查密码", Toast.LENGTH_LONG).show());
                        return;
                    }
                }

                URL url = new URL("http://" + ip + ":" + webPort + "/api/public_key");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(3000);
                conn.setRequestProperty("Connection", "close");
                if (!authToken.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + authToken);

                if (conn.getResponseCode() != 200) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "无法获取公钥，可能是密码错误或网络异常", Toast.LENGTH_LONG).show());
                    return;
                }

                InputStream is = conn.getInputStream();
                java.util.Scanner scanner = new java.util.Scanner(is).useDelimiter("\\A");
                String response = scanner.hasNext() ? scanner.next() : "";
                scanner.close();
                conn.disconnect();

                String pem = response.replace("-----BEGIN PUBLIC KEY-----", "")
                        .replace("-----END PUBLIC KEY-----", "")
                        .replace("\"", "")
                        .replaceAll("\\s", "");
                byte[] keyBytes = android.util.Base64.decode(pem, android.util.Base64.DEFAULT);
                java.security.spec.X509EncodedKeySpec spec = new java.security.spec.X509EncodedKeySpec(keyBytes);
                java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
                java.security.PublicKey rsaPublicKey = kf.generatePublic(spec);

                byte[] aesKey = new byte[16];
                byte[] iv = new byte[16];
                new java.security.SecureRandom().nextBytes(aesKey);
                new java.security.SecureRandom().nextBytes(iv);

                javax.crypto.Cipher rsaCipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
                rsaCipher.init(javax.crypto.Cipher.ENCRYPT_MODE, rsaPublicKey);
                byte[] encryptedAesKey = rsaCipher.doFinal(aesKey);

                javax.crypto.Cipher aesCipher = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding");
                aesCipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                        new javax.crypto.spec.SecretKeySpec(aesKey, "AES"),
                        new javax.crypto.spec.IvParameterSpec(iv));
                byte[] cipherAudioData = aesCipher.doFinal(rawAudioData);

                java.net.Socket socket = new java.net.Socket(ip, Integer.parseInt(tcpPort));
                OutputStream os = socket.getOutputStream();

                os.write(encryptedAesKey);
                os.write(iv);
                os.write(cipherAudioData);
                os.flush();

                os.close();
                socket.close();

                runOnUiThread(() -> Toast.makeText(MainActivity.this, "[√] 校园安全广播发送成功", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "发送失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }
}