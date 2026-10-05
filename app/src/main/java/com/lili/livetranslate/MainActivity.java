package com.lili.livetranslate;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int REQ_MIC = 1001;
    private static final int REQ_CALL_ASSIST = 2001;
    private static final String UTT_ID = "lili_translate";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private Translator translator;

    private TextView status;
    private TextView heard;
    private TextView translated;
    private TextView direction;
    private Button start;
    private Button swap;
    private Button repeat;
    private Button callAssist;

    private boolean itToFa = true;
    private boolean modelReady = false;
    private boolean listening = false;
    private boolean speaking = false;
    private String lastTranslation = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(10, 14, 28));
        getWindow().setNavigationBarColor(Color.rgb(10, 14, 28));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

        buildUi();
        initRecognizer();
        tts = new TextToSpeech(this, this);
        prepareTranslator();
    }

    private void buildUi() {
        int bg = Color.rgb(10, 14, 28);
        int card = Color.rgb(20, 28, 48);
        int text = Color.rgb(245, 247, 250);
        int muted = Color.rgb(165, 175, 195);
        int purple = Color.rgb(139, 92, 246);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        sv.addView(root);

        TextView title = tv("Lili Live Translate", 30, text, true);
        root.addView(title);

        TextView sub = tv("ترجمه زنده ایتالیایی ↔ فارسی با هندزفری", 16, muted, false);
        sub.setTextDirection(View.TEXT_DIRECTION_RTL);
        add(root, sub, 8);

        direction = tv("", 18, text, true);
        direction.setGravity(Gravity.CENTER);
        direction.setBackgroundColor(card);
        direction.setPadding(dp(12), dp(16), dp(12), dp(16));
        add(root, direction, 20);

        status = tv("در حال آماده‌سازی مدل ترجمه…", 14, muted, true);
        status.setTextDirection(View.TEXT_DIRECTION_RTL);
        add(root, status, 14);

        heard = tv("وقتی صحبت شروع شود، متن شنیده‌شده اینجا می‌آید.", 18, text, false);
        heard.setTextDirection(View.TEXT_DIRECTION_RTL);
        heard.setBackgroundColor(card);
        heard.setPadding(dp(14), dp(16), dp(14), dp(16));
        add(root, heard, 18);

        translated = tv("ترجمه اینجا نمایش داده می‌شود.", 21, text, true);
        translated.setTextDirection(View.TEXT_DIRECTION_RTL);
        translated.setBackgroundColor(Color.rgb(27, 35, 60));
        translated.setPadding(dp(14), dp(18), dp(14), dp(18));
        add(root, translated, 12);

        start = button("🎙 شروع شنیدن", purple);
        start.setOnClickListener(v -> {
            if (listening) stopByUser();
            else requestStart();
        });
        add(root, start, 18);

        swap = button("⇄ جابه‌جایی زبان‌ها", Color.rgb(40, 48, 72));
        swap.setOnClickListener(v -> swapDirection());
        add(root, swap, 10);

        repeat = button("↻ تکرار ترجمه", Color.rgb(40, 48, 72));
        repeat.setOnClickListener(v -> repeatTranslation());
        add(root, repeat, 10);

        callAssist = button("📞 فعال‌سازی دستیار تماس", Color.rgb(22, 101, 52));
        callAssist.setOnClickListener(v -> enableCallAssistant());
        add(root, callAssist, 18);

        TextView assistInfo = tv("دستیار تماس هنگام زنگ خوردن یا تماس فعال، یک پنل شناور برای ترجمه آزمایشی، یادداشت و افزودن قرار به تقویم نشان می‌دهد. دسترسی مستقیم به صدای تماس سیم‌کارت در Android محدود است؛ ترجمه صوتی بسته به مدل گوشی ممکن است کار نکند.", 13, muted, false);
        assistInfo.setTextDirection(View.TEXT_DIRECTION_RTL);
        add(root, assistInfo, 10);

        TextView tip = tv("روش استفاده عادی: هندزفری را وصل کن، گوشی را نزدیک فرد مقابل بگیر و «شروع شنیدن» را بزن. ترجمه از خروجی صوتی فعال گوشی پخش می‌شود.", 13, muted, false);
        tip.setTextDirection(View.TEXT_DIRECTION_RTL);
        add(root, tip, 20);

        setContentView(sv);
        refreshDirection();
    }

    private void prepareTranslator() {
        modelReady = false;
        status.setText("در حال دانلود/بررسی مدل ترجمه…");

        if (translator != null) translator.close();

        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(itToFa ? TranslateLanguage.ITALIAN : TranslateLanguage.PERSIAN)
                .setTargetLanguage(itToFa ? TranslateLanguage.PERSIAN : TranslateLanguage.ITALIAN)
                .build();

        translator = Translation.getClient(options);

        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(v -> {
                    modelReady = true;
                    status.setText("آماده ✓");
                    if (listening) startRecognizer();
                })
                .addOnFailureListener(e -> {
                    status.setText("دانلود مدل ترجمه انجام نشد. اینترنت را بررسی کن.");
                    modelReady = false;
                });
    }

    private void initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition روی این گوشی در دسترس نیست.", Toast.LENGTH_LONG).show();
            return;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { status.setText("گوش می‌دهم…"); }
            @Override public void onBeginningOfSpeech() { status.setText("در حال شنیدن صحبت…"); }
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() { status.setText("در حال پردازش…"); }

            @Override public void onError(int error) {
                if (listening && !speaking) handler.postDelayed(() -> startRecognizer(), 700);
            }

            @Override public void onResults(Bundle results) {
                ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty()) {
                    String source = list.get(0);
                    heard.setText(source);
                    translateAndSpeak(source);
                } else if (listening && !speaking) {
                    handler.postDelayed(() -> startRecognizer(), 500);
                }
            }

            @Override public void onPartialResults(Bundle partialResults) {
                ArrayList<String> list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty()) heard.setText(list.get(0));
            }

            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void requestStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        listening = true;
        start.setText("■ توقف");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (!modelReady) {
            status.setText("مدل ترجمه هنوز آماده نیست…");
            return;
        }
        startRecognizer();
    }

    private void startRecognizer() {
        if (!listening || speaking || recognizer == null || !modelReady) return;

        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, itToFa ? "it-IT" : "fa-IR");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

        try {
            recognizer.cancel();
            recognizer.startListening(i);
        } catch (Exception e) {
            status.setText("میکروفن شروع نشد؛ دوباره تلاش می‌کنم…");
            handler.postDelayed(() -> startRecognizer(), 1000);
        }
    }

    private void translateAndSpeak(String source) {
        if (translator == null || !modelReady) return;

        translator.translate(source)
                .addOnSuccessListener(out -> {
                    translated.setText(out);
                    lastTranslation = out;
                    speak(out);
                })
                .addOnFailureListener(e -> {
                    status.setText("ترجمه انجام نشد.");
                    if (listening) handler.postDelayed(() -> startRecognizer(), 800);
                });
    }

    private void speak(String text) {
        if (tts == null || text == null || text.trim().isEmpty()) {
            if (listening) handler.postDelayed(() -> startRecognizer(), 500);
            return;
        }

        int result = tts.setLanguage(Locale.forLanguageTag(itToFa ? "fa-IR" : "it-IT"));
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            status.setText("صدای این زبان روی گوشی نصب نیست؛ ترجمه متنی آماده است.");
            if (listening) handler.postDelayed(() -> startRecognizer(), 800);
            return;
        }

        speaking = true;
        if (recognizer != null) recognizer.cancel();
        status.setText("در حال پخش ترجمه…");
        tts.setSpeechRate(0.92f);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTT_ID);
    }

    private void stopByUser() {
        listening = false;
        speaking = false;
        if (recognizer != null) recognizer.cancel();
        if (tts != null) tts.stop();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        start.setText("🎙 شروع شنیدن");
        status.setText(modelReady ? "آماده" : "در حال آماده‌سازی…");
    }

    private void swapDirection() {
        boolean resume = listening;
        stopByUser();
        itToFa = !itToFa;
        heard.setText("وقتی صحبت شروع شود، متن شنیده‌شده اینجا می‌آید.");
        translated.setText("ترجمه اینجا نمایش داده می‌شود.");
        lastTranslation = "";
        refreshDirection();
        prepareTranslator();
        if (resume) {
            listening = true;
            start.setText("■ توقف");
        }
    }

    private void refreshDirection() {
        direction.setText(itToFa ? "🇮🇹 Italiano  →  🇮🇷 فارسی" : "🇮🇷 فارسی  →  🇮🇹 Italiano");
        heard.setTextDirection(itToFa ? View.TEXT_DIRECTION_LTR : View.TEXT_DIRECTION_RTL);
        translated.setTextDirection(itToFa ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
    }

    private void repeatTranslation() {
        if (lastTranslation.isEmpty()) {
            Toast.makeText(this, "هنوز ترجمه‌ای وجود ندارد.", Toast.LENGTH_SHORT).show();
            return;
        }
        speak(lastTranslation);
    }

    @Override
    public void onInit(int statusCode) {
        if (statusCode == TextToSpeech.SUCCESS) {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {}
                @Override public void onDone(String utteranceId) {
                    handler.post(() -> {
                        speaking = false;
                        status.setText(listening ? "دوباره گوش می‌دهم…" : "آماده");
                        if (listening) handler.postDelayed(() -> startRecognizer(), 250);
                    });
                }
                @Override public void onError(String utteranceId) {
                    handler.post(() -> {
                        speaking = false;
                        status.setText("پخش صدا انجام نشد؛ ترجمه متنی آماده است.");
                        if (listening) handler.postDelayed(() -> startRecognizer(), 700);
                    });
                }
            });
        }
    }

    private void enableCallAssistant() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "اول اجازه نمایش روی برنامه‌های دیگر را برای Lili Live Translate فعال کن، بعد برگرد و دوباره این دکمه را بزن.", Toast.LENGTH_LONG).show();
            Intent overlayIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(overlayIntent);
            return;
        }

        ArrayList<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_PHONE_STATE);
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQ_CALL_ASSIST);
            return;
        }

        startCallAssistantService();
    }

    private void startCallAssistantService() {
        Intent service = new Intent(this, CallAssistantService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
        Toast.makeText(this, "دستیار تماس فعال شد ✓", Toast.LENGTH_LONG).show();
        if (callAssist != null) callAssist.setText("📞 دستیار تماس فعال است ✓");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestStart();
        } else if (requestCode == REQ_CALL_ASSIST) {
            boolean ok = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    ok = false;
                    break;
                }
            }
            if (ok) startCallAssistantService();
            else Toast.makeText(this, "برای دستیار تماس باید مجوزهای درخواست‌شده را بدهی.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) recognizer.destroy();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (translator != null) translator.close();
        super.onDestroy();
    }

    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private Button button(String s, int bg) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(bg);
        b.setMinHeight(dp(52));
        return b;
    }

    private void add(LinearLayout parent, View child, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        parent.addView(child, p);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
