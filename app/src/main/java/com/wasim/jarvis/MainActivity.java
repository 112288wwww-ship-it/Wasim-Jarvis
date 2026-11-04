package com.wasim.jarvis;

import android.app.Activity;
import android.widget.Toast;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.JavascriptInterface;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import android.os.StrictMode;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.util.Locale;

public class MainActivity extends Activity {
    WebView web;
    private TextToSpeech textToSpeech;
    private boolean ttsReady = false;
    private String pendingSpeech = "";
    private String pendingLanguage = "ur-PK";
    private String pendingVoice = "default";
    SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                if (!pendingSpeech.isEmpty()) {
                    speakText(pendingSpeech, pendingLanguage, pendingVoice);
                }
            }
        });

        StrictMode.setThreadPolicy(
            new StrictMode.ThreadPolicy.Builder().permitAll().build()
        );

        prefs = getSharedPreferences("JarvisData", MODE_PRIVATE);

        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new JarvisBridge(), "Android");

        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    private void speakText(String text, String language, String choice) {
        if (text == null || text.trim().isEmpty()) return;
        pendingSpeech = text;
        pendingLanguage = language;
        pendingVoice = choice;

        runOnUiThread(() -> {
            if (!ttsReady || textToSpeech == null) {
                Toast.makeText(this, "आवाज़ का इंजन अभी तैयार नहीं है।", Toast.LENGTH_LONG).show();
                return;
            }

            Locale locale = Locale.forLanguageTag(
                language == null ? "en-US" : language
            );
            int result = textToSpeech.setLanguage(locale);

            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "यह भाषा उपलब्ध नहीं है, English आवाज़ आज़मा रहे हैं।", Toast.LENGTH_LONG).show();
                result = textToSpeech.setLanguage(Locale.US);
            }

            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "मोबाइल में आवाज़ का डेटा उपलब्ध नहीं है।", Toast.LENGTH_LONG).show();
                return;
            }

            if (!"default".equals(choice) && textToSpeech.getVoices() != null) {
                for (Voice voice : textToSpeech.getVoices()) {
                    if (voice.getLocale().getLanguage().equals(locale.getLanguage())) {
                        String name = voice.getName().toLowerCase(Locale.ROOT);
                        boolean match = "female".equals(choice)
                            ? name.contains("female") || name.contains("woman")
                            : name.contains("male") || name.contains("man");
                        if (match) {
                            textToSpeech.setVoice(voice);
                            break;
                        }
                    }
                }
            }

            int spoken = textToSpeech.speak(
                text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-voice"
            );
            if (spoken == TextToSpeech.ERROR) {
                Toast.makeText(this, "आवाज़ चलाने में गलती हुई।", Toast.LENGTH_LONG).show();
            }
        });
    }

    public class JarvisBridge {
        @JavascriptInterface
        public boolean saveApiKey(String key) {
            if (key == null || key.trim().isEmpty()) return false;
            prefs.edit().putString("groq_key", key.trim()).apply();
            return true;
        }

        @JavascriptInterface
        public boolean hasApiKey() {
            String key = prefs.getString("groq_key", "");
            return !key.isEmpty();
        }

        @JavascriptInterface
        public String chat(String message, String language) {
            String key = prefs.getString("groq_key", "");

            if (key.isEmpty()) {
                return "{\"reply\":\"पहले अपनी Groq API Key दर्ज करें।\",\"needsKey\":true}";
            }

            try {
                JSONArray messages = new JSONArray();

                String lang = "Urdu";
                if ("hi-IN".equals(language)) lang = "Hindi";
                if ("en-US".equals(language)) lang = "English";

                JSONObject system = new JSONObject();
                system.put("role", "system");
                system.put("content",
                    "You are JARVIS, a friendly and helpful assistant created by Wasim. Reply naturally in " + lang + ".");
                messages.put(system);

                JSONArray history = new JSONArray(
                    prefs.getString("history", "[]")
                );

                for (int i = Math.max(0, history.length() - 10);
                     i < history.length(); i++) {
                    JSONObject item = history.getJSONObject(i);

                    JSONObject user = new JSONObject();
                    user.put("role", "user");
                    user.put("content", item.optString("question"));
                    messages.put(user);

                    JSONObject assistant = new JSONObject();
                    assistant.put("role", "assistant");
                    assistant.put("content", item.optString("answer"));
                    messages.put(assistant);
                }

                JSONObject current = new JSONObject();
                current.put("role", "user");
                current.put("content", message);
                messages.put(current);

                JSONObject data = new JSONObject();
                data.put("model", "openai/gpt-oss-120b");
                data.put("messages", messages);
                data.put("temperature", 0.8);

                URL url = new URL("https://api.groq.com/openai/v1/chat/completions");
                HttpURLConnection connection =
                    (HttpURLConnection) url.openConnection();

                connection.setRequestMethod("POST");
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(45000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Authorization", "Bearer " + key);
                connection.setRequestProperty("Content-Type", "application/json");

                OutputStream output = connection.getOutputStream();
                output.write(data.toString().getBytes("UTF-8"));
                output.close();

                int code = connection.getResponseCode();

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                        code >= 200 && code < 300
                            ? connection.getInputStream()
                            : connection.getErrorStream(),
                        "UTF-8"
                    )
                );

                StringBuilder result = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line);
                }
                reader.close();
                connection.disconnect();

                if (code < 200 || code >= 300) {
                    return new JSONObject()
                        .put("reply", "AI server error: " + code)
                        .toString();
                }

                JSONObject response = new JSONObject(result.toString());
                String reply = response.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");

                saveHistory(message, reply);

                return new JSONObject().put("reply", reply).toString();

            } catch (Exception e) {
                try {
                    return new JSONObject()
                        .put("reply", "AI connection nahi hua. Internet check karein.")
                        .toString();
                } catch (Exception ignored) {
                    return "{\"reply\":\"Connection error\"}";
                }
            }
        }

        private void saveHistory(String question, String answer) {
            try {
                JSONArray history = new JSONArray(
                    prefs.getString("history", "[]")
                );

                JSONObject item = new JSONObject();
                item.put("question", question);
                item.put("answer", answer);
                item.put("time",
                    new SimpleDateFormat("dd-MM-yyyy hh:mm a",
                        Locale.getDefault()).format(new Date()));

                history.put(item);

                JSONArray limited = new JSONArray();
                for (int i = Math.max(0, history.length() - 100);
                     i < history.length(); i++) {
                    limited.put(history.get(i));
                }

                prefs.edit().putString("history", limited.toString()).apply();
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void speak(String text, String language, String choice) {
            speakText(text, language, choice);
        }

        @JavascriptInterface
        public String history() {
            return prefs.getString("history", "[]");
        }

        @JavascriptInterface
        public void openUrl(String address) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(address));
                startActivity(intent);
            } catch (Exception ignored) {}
        }
    }
    @Override
    protected void onDestroy() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        super.onDestroy();
    }

}
