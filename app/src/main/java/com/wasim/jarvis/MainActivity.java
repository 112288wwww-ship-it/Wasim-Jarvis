package com.wasim.jarvis;

import android.app.Activity;
import android.os.Bundle;
import android.Manifest;
import android.content.pm.PackageManager;
import android.speech.SpeechRecognizer;
import android.speech.RecognizerIntent;
import android.speech.RecognitionListener;
import android.os.Build;
import android.widget.Toast;
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
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class MainActivity extends Activity {
    private static final int MIC_PERMISSION_REQUEST = 701;
    private SpeechRecognizer speechRecognizer;
    private String pendingRecognitionLanguage = "ur-PK";
    private boolean pendingRecognitionStart = false;
    WebView web;
    private TextToSpeech textToSpeech;
    private boolean ttsReady = false;
    private String pendingSpeech = "";
    private String pendingLanguage = "ur-PK";
    private String pendingVoice = "default";
    private float pendingSpeed = 1.0f;
    private float pendingPitch = 1.0f;
    private String pendingVoiceName = "";
    SharedPreferences prefs;
    private final ExecutorService chatExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                if (!pendingSpeech.isEmpty()) {
                    speakTextWithVoice(pendingSpeech, pendingLanguage, pendingVoice, pendingSpeed, pendingPitch, pendingVoiceName);
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


    private void startNativeSpeech(String language) {
        runOnUiThread(() -> {
            pendingRecognitionLanguage =
                (language == null || language.trim().isEmpty()) ? "ur-PK" : language;

            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingRecognitionStart = true;
                requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    MIC_PERMISSION_REQUEST
                );
                return;
            }

            beginNativeSpeech();
        });
    }

    private void beginNativeSpeech() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendSpeechStatus("इस फोन में Speech Recognition उपलब्ध नहीं है।");
            return;
        }

        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                sendSpeechStatus("JARVIS सुन रही है...");
            }

            @Override public void onBeginningOfSpeech() {}

            @Override public void onRmsChanged(float rmsdB) {}

            @Override public void onBufferReceived(byte[] buffer) {}

            @Override public void onEndOfSpeech() {
                sendSpeechStatus("आवाज़ समझी जा रही है...");
            }

            @Override public void onError(int error) {
                String message = "माइक्रोफोन की गलती: " + error;
                sendSpeechStatus(message);
                if (speechRecognizer != null) {
                    speechRecognizer.destroy();
                    speechRecognizer = null;
                }
            }

            @Override public void onResults(Bundle results) {
                ArrayList<String> matches =
                    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

                if (matches != null && !matches.isEmpty()) {
                    String spokenText = JSONObject.quote(matches.get(0));
                    web.evaluateJavascript(
                        "window.onNativeSpeechResult(" + spokenText + ")",
                        null
                    );
                } else {
                    sendSpeechStatus("आवाज़ समझ नहीं आई। दोबारा कोशिश करें।");
                }

                if (speechRecognizer != null) {
                    speechRecognizer.destroy();
                    speechRecognizer = null;
                }
            }

            @Override public void onPartialResults(Bundle partialResults) {}

            @Override public void onEvent(int eventType, Bundle params) {}
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        );
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, pendingRecognitionLanguage);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);

        speechRecognizer.startListening(intent);
    }

    private void sendSpeechStatus(String message) {
        if (web != null) {
            web.evaluateJavascript(
                "window.onNativeSpeechStatus(" + JSONObject.quote(message) + ")",
                null
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == MIC_PERMISSION_REQUEST) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingRecognitionStart) {
                    pendingRecognitionStart = false;
                    beginNativeSpeech();
                }
            } else {
                pendingRecognitionStart = false;
                sendSpeechStatus("माइक्रोफोन की अनुमति नहीं मिली।");
            }
        }
    }

    private void speakText(String text, String language, String choice) {
        speakTextAdvanced(text, language, choice, 1.0f, 1.0f);
    }

    private void speakTextAdvanced(String text, String language, String choice, float speed, float pitch) {
        speakTextWithVoice(text, language, choice, speed, pitch, "");
    }

    private void speakTextWithVoice(String text, String language, String choice, float speed, float pitch, String selectedVoiceName) {
        if (text == null || text.trim().isEmpty()) return;

        pendingSpeech = text;
        pendingLanguage = language;
        pendingVoice = choice;
        pendingSpeed = speed;
        pendingPitch = pitch;
        pendingVoiceName = selectedVoiceName == null ? "" : selectedVoiceName;

        runOnUiThread(() -> {
            if (!ttsReady || textToSpeech == null) {
                Toast.makeText(this, "आवाज़ का इंजन अभी तैयार नहीं है।", Toast.LENGTH_LONG).show();
                return;
            }

            textToSpeech.setSpeechRate(Math.max(0.5f, Math.min(2.0f, speed)));
            textToSpeech.setPitch(Math.max(0.5f, Math.min(2.0f, pitch)));

            Locale locale = Locale.forLanguageTag(language == null ? "en-US" : language);
            int result = textToSpeech.setLanguage(locale);

            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "यह भाषा उपलब्ध नहीं है, English आवाज़ आज़मा रहे हैं।", Toast.LENGTH_LONG).show();
                locale = Locale.US;
                result = textToSpeech.setLanguage(locale);
            }

            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "मोबाइल में आवाज़ का डेटा उपलब्ध नहीं है।", Toast.LENGTH_LONG).show();
                return;
            }

            if (selectedVoiceName != null && !selectedVoiceName.isEmpty()
                    && textToSpeech.getVoices() != null) {
                for (Voice voice : textToSpeech.getVoices()) {
                    if (voice.getName().equals(selectedVoiceName)
                            && voice.getLocale().getLanguage().equals(locale.getLanguage())) {
                        textToSpeech.setVoice(voice);
                        break;
                    }
                }
            } else if (!"default".equals(choice) && textToSpeech.getVoices() != null) {
                for (Voice voice : textToSpeech.getVoices()) {
                    if (voice.getLocale().getLanguage().equals(locale.getLanguage())) {
                        String name = voice.getName().toLowerCase(Locale.ROOT);
                        boolean match = "female".equals(choice)
                            ? name.contains("female") || name.contains("woman")
                            : (name.contains("male") && !name.contains("female"))
                                || (name.contains("man") && !name.contains("woman"));
                        if (match) {
                            textToSpeech.setVoice(voice);
                            break;
                        }
                    }
                }
            }

            int spoken = textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-voice");
            if (spoken == TextToSpeech.ERROR) {
                Toast.makeText(this, "आवाज़ चलाने में गलती हुई।", Toast.LENGTH_LONG).show();
            }
        });
    }

    public class JarvisBridge {

        @JavascriptInterface
        public void chatAsync(String message, String language) {
            chatExecutor.execute(() -> {
                String result = chat(message, language);
                runOnUiThread(() -> {
                    if (web != null) {
                        web.evaluateJavascript(
                            "window.onJarvisReply(" + JSONObject.quote(result) + ")",
                            null
                        );
                    }
                });
            });
        }

        @JavascriptInterface
        public String getAvailableVoices() {
            JSONArray result = new JSONArray();
            if (!ttsReady || textToSpeech == null) return result.toString();

            try {
                if (textToSpeech.getVoices() != null) {
                    for (Voice voice : textToSpeech.getVoices()) {
                        JSONObject item = new JSONObject();
                        item.put("name", voice.getName());
                        item.put("language", voice.getLocale().toLanguageTag());
                        item.put("networkRequired", voice.isNetworkConnectionRequired());
                        item.put("quality", voice.getQuality());
                        result.put(item);
                    }
                }
            } catch (Exception e) {
                android.util.Log.e("JARVIS", "Voice list error", e);
            }
            return result.toString();
        }

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
            if (message == null) message = "";
            String question = message.toLowerCase(Locale.ROOT).trim();

            if (question.contains("वसीम बॉस") ||
                question.contains("وسیم باس") ||
                question.contains("wasim boss")) {
                String reply = "जी वसीम बॉस।";
                saveHistory(message, reply);
                try {
                    return new JSONObject().put("reply", reply).toString();
                } catch (Exception e) {
                    return "{\"reply\":\"जी वसीम बॉस।\"}";
                }
            }

            if (question.contains("तुझे किसने बनाया") ||
                question.contains("तुम्हें किसने बनाया") ||
                question.contains("आपको किसने बनाया") ||
                question.contains("کس نے بنایا") ||
                question.contains("تمہیں کس نے بنایا") ||
                question.contains("who created you") ||
                question.contains("who made you") ||
                question.contains("who built you")) {
                String reply = "मुझे वसीम बॉस ने बनाया है।";
                saveHistory(message, reply);
                try {
                    return new JSONObject().put("reply", reply).toString();
                } catch (Exception e) {
                    return "{\"reply\":\"मुझे वसीम बॉस ने बनाया है।\"}";
                }
            }

            String key = prefs.getString("groq_key", "");

            if (key.isEmpty()) {
                return "{\"reply\":\"पहले अपनी Groq API Key दर्ज करें।\",\"needsKey\":true}";
            }

            try {
                JSONArray messages = new JSONArray();

                String lang = Locale.forLanguageTag(
                    language == null ? "ur-PK" : language
                ).getDisplayLanguage(Locale.ENGLISH);
                if (lang == null || lang.isEmpty()) lang = "the user's language";

                JSONObject system = new JSONObject();
                system.put("role", "system");
                system.put("content",
                    "You are JARVIS, a friendly, intelligent conversational assistant. " +
                    "Reply naturally in " + lang + ". Understand the user's question and give a useful conversational answer. " +
                    "If the user asks who created you, always say exactly: मुझे वसीम बॉस ने बनाया है। " +
                    "Do not claim to control phone features unless the Android app provides that function.");
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
        public String openApp(String appName) {
            if (appName == null) return "ऐप का नाम नहीं मिला।";
            String name = appName.toLowerCase(Locale.ROOT).trim();
            String packageName;

            if (name.contains("whatsapp")) packageName = "com.whatsapp";
            else if (name.contains("youtube")) packageName = "com.google.android.youtube";
            else if (name.contains("chrome")) packageName = "com.android.chrome";
            else if (name.contains("camera")) {
                Intent cameraIntent = new Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
                try {
                    startActivity(cameraIntent);
                    return "कैमरा खोल रहा हूँ।";
                } catch (Exception e) {
                    return "कैमरा नहीं खुल सका।";
                }
            }
            else if (name.contains("setting")) {
                Intent settingsIntent = new Intent(android.provider.Settings.ACTION_SETTINGS);
                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(settingsIntent);
                    return "सेटिंग्स खोल रहा हूँ।";
                } catch (Exception e) {
                    return "सेटिंग्स नहीं खुल सकीं।";
                }
            } else return "यह ऐप अभी कमांड सूची में नहीं है।";

            try {
                Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
                if (launch == null) return "यह ऐप मोबाइल में नहीं मिला।";
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                return "ऐप खोल रहा हूँ।";
            } catch (Exception e) {
                return "ऐप नहीं खुल सका।";
            }
        }

        @JavascriptInterface
        public void startNativeSpeech(String language) {
            MainActivity.this.startNativeSpeech(language);
        }

        @JavascriptInterface
        public void speak(String text, String language, String choice) {
            speakText(text, language, choice);
        }

        @JavascriptInterface
        public void speakAdvanced(String text, String language, String choice, float speed, float pitch) {
            speakTextAdvanced(text, language, choice, speed, pitch);
        }

        @JavascriptInterface
        public void speakWithVoice(String text, String language, String choice, float speed, float pitch, String voiceName) {
            speakTextWithVoice(text, language, choice, speed, pitch, voiceName);
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
        if (speechRecognizer != null) {
            speechRecognizer.cancel();
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        chatExecutor.shutdownNow();
        super.onDestroy();
    }

}
