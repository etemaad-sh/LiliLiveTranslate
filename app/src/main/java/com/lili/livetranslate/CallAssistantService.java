package com.lili.livetranslate;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class CallAssistantService extends Service {
    private static final String CHANNEL_ID = "lili_call_assistant";
    private static final int NOTIFICATION_ID = 4101;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private View overlayView;
    private TextView callStatus;
    private TextView heard;
    private TextView translated;
    private TextView routeHint;
    private EditText notes;
    private Button listenButton;

    private TelephonyManager telephonyManager;
    private PhoneStateListener phoneStateListener;
    private CallStateCallback telephonyCallback;
    private AudioManager audioManager;

    private SpeechRecognizer recognizer;
    private Translator translator;

    private boolean modelReady = false;
    private boolean listening = false;
    private boolean callActive = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        prepareTranslator();
        initRecognizer();
        monitorCalls();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "com.lili.livetranslate.TEST_OVERLAY".equals(intent.getAction())) {
            showOverlay("🧪 پنل تست — Overlay درست کار می‌کند");
        } else if (callActive) {
            showOverlay("تماس فعال");
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Lili Call Assistant")
                .setContentText("دستیار تماس فعال است")
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Lili Call Assistant",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps the call assistant ready for incoming and active calls.");
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private class CallStateCallback extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            handleCallState(state);
        }
    }

    private void monitorCalls() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "مجوز Phone State داده نشده است.", Toast.LENGTH_LONG).show();
            return;
        }

        telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback = new CallStateCallback();
            telephonyManager.registerTelephonyCallback(getMainExecutor(), telephonyCallback);
        } else {
            phoneStateListener = new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String phoneNumber) {
                    super.onCallStateChanged(state, phoneNumber);
                    handleCallState(state);
                }
            };
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
        }
    }

    private void handleCallState(int state) {
        if (state == TelephonyManager.CALL_STATE_RINGING) {
            callActive = true;
            showOverlay("📞 تماس ورودی — دستیار آماده است");
        } else if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
            callActive = true;
            showOverlay("📞 تماس در حال انجام");
            updateAudioRouteHint();
        } else if (state == TelephonyManager.CALL_STATE_IDLE) {
            callActive = false;
            stopListening();
            hideOverlay();
        }
    }

    private boolean hasBluetoothAudioOutput() {
        if (audioManager == null) return false;
        AudioDeviceInfo[] outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo d : outputs) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    t == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    t == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                return true;
            }
        }
        return false;
    }

    private void updateAudioRouteHint() {
        if (routeHint == null) return;
        if (hasBluetoothAudioOutput()) {
            routeHint.setText("🎧 هندزفری/بلوتوث شناسایی شد. اگر ترجمه صوتی چیزی نشنید، محدودیت Android روی صدای تماس علت احتمالی است.");
        } else {
            routeHint.setText("🔊 هندزفری شناسایی نشد. تماس را روی Speaker بگذار تا صدای طرف مقابل برای میکروفن گوشی قابل شنیدن باشد.");
        }
    }

    private void prepareTranslator() {
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ITALIAN)
                .setTargetLanguage(TranslateLanguage.PERSIAN)
                .build();

        translator = Translation.getClient(options);
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(v -> {
                    modelReady = true;
                    if (callStatus != null) callStatus.setText("آماده برای ترجمه ایتالیایی → فارسی");
                })
                .addOnFailureListener(e -> {
                    modelReady = false;
                    if (callStatus != null) callStatus.setText("مدل ترجمه آماده نشد؛ اینترنت را بررسی کن");
                });
    }

    private void initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return;

        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                if (callStatus != null) callStatus.setText("دارم گوش می‌دهم…");
            }

            @Override public void onBeginningOfSpeech() {
                if (callStatus != null) callStatus.setText("در حال شنیدن ایتالیایی…");
            }

            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}

            @Override public void onEndOfSpeech() {
                if (callStatus != null) callStatus.setText("در حال فهمیدن جمله…");
            }

            @Override public void onError(int error) {
                if (listening && callActive) {
                    if (callStatus != null) {
                        callStatus.setText("صدای تماس در این گوشی در دسترس نیست یا تشخیص صدا متوقف شد.");
                    }
                    handler.postDelayed(() -> startListeningCycle(), 1200);
                }
            }

            @Override public void onResults(Bundle results) {
                ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty()) {
                    String source = list.get(0);
                    if (heard != null) heard.setText(source);
                    translateSentence(source);
                } else if (listening && callActive) {
                    handler.postDelayed(() -> startListeningCycle(), 500);
                }
            }

            @Override public void onPartialResults(Bundle partialResults) {
                ArrayList<String> list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty() && heard != null) {
                    heard.setText(list.get(0));
                }
            }

            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void translateSentence(String source) {
        if (!modelReady || translator == null) {
            if (translated != null) translated.setText("مدل ترجمه هنوز آماده نیست.");
            return;
        }

        translator.translate(source)
                .addOnSuccessListener(out -> {
                    if (translated != null) translated.setText(out);
                    if (callStatus != null) callStatus.setText("ترجمه آماده ✓");
                    if (listening && callActive) handler.postDelayed(() -> startListeningCycle(), 350);
                })
                .addOnFailureListener(e -> {
                    if (callStatus != null) callStatus.setText("ترجمه انجام نشد.");
                    if (listening && callActive) handler.postDelayed(() -> startListeningCycle(), 800);
                });
    }

    private void startListeningCycle() {
        if (!listening || !callActive || recognizer == null || !modelReady) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;

        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

        try {
            recognizer.cancel();
            recognizer.startListening(i);
        } catch (Exception e) {
            if (callStatus != null) callStatus.setText("امکان دسترسی هم‌زمان به میکروفن وجود ندارد.");
        }
    }

    private void toggleListening() {
        if (listening) {
            stopListening();
        } else {
            listening = true;
            if (listenButton != null) listenButton.setText("■ توقف ترجمه");
            if (callStatus != null) {
                callStatus.setText("حالت جمله‌ای روشن شد؛ اپ صبر می‌کند تا جمله ایتالیایی کامل‌تر شود و بعد فارسی را نمایش می‌دهد. اگر چیزی نوشته نشد، Android دسترسی هم‌زمان به صدای تماس را مسدود کرده است.");
            }
            startListeningCycle();
        }
    }

    private void stopListening() {
        listening = false;
        if (recognizer != null) recognizer.cancel();
        if (listenButton != null) listenButton.setText("🎙 ترجمه جمله‌ایِ طبیعی (آزمایشی)");
    }

    private void showOverlay(String stateText) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return;

        if (overlayView != null) {
            if (callStatus != null) callStatus.setText(stateText);
            return;
        }

        int bg = Color.rgb(12, 18, 32);
        int card = Color.rgb(25, 34, 55);
        int text = Color.WHITE;
        int muted = Color.rgb(185, 194, 210);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(14));
        root.setBackgroundColor(bg);

        TextView title = tv("Lili Call Assistant", 20, text, true);
        root.addView(title);

        callStatus = tv(stateText, 14, muted, true);
        callStatus.setTextDirection(View.TEXT_DIRECTION_RTL);
        add(root, callStatus, 6);

        routeHint = tv("Pixel 10a / Android 17: مسیر صدا را بررسی می‌کنم. اگر هندزفری وصل نباشد، Speaker بهترین حالت آزمایشی برای شنیدن صدای طرف مقابل است.", 13, muted, false);
        routeHint.setTextDirection(View.TEXT_DIRECTION_RTL);
        routeHint.setBackgroundColor(card);
        routeHint.setPadding(dp(10), dp(10), dp(10), dp(10));
        add(root, routeHint, 10);
        updateAudioRouteHint();

        LinearLayout routeRow = new LinearLayout(this);
        routeRow.setOrientation(LinearLayout.HORIZONTAL);

        Button headset = button("🎧 هندزفری دارم", Color.rgb(40, 48, 72));
        headset.setOnClickListener(v -> routeHint.setText("هندزفری وصل است. اگر ترجمه صوتی کار نکرد، محدودیت Android روی صدای تماس علت احتمالی است."));
        routeRow.addView(headset, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button speaker = button("🔊 بدون هندزفری", Color.rgb(40, 48, 72));
        speaker.setOnClickListener(v -> routeHint.setText("Speaker تماس را روشن کن و گوشی را طوری بگیر که صدای طرف مقابل واضح باشد. سپس «ترجمه زنده» را امتحان کن."));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        sp.leftMargin = dp(6);
        routeRow.addView(speaker, sp);
        add(root, routeRow, 8);

        heard = tv("Italiano: منتظر صدا…", 15, text, false);
        heard.setBackgroundColor(card);
        heard.setPadding(dp(10), dp(10), dp(10), dp(10));
        add(root, heard, 10);

        translated = tv("فارسی: ترجمه اینجا نمایش داده می‌شود.", 18, text, true);
        translated.setTextDirection(View.TEXT_DIRECTION_RTL);
        translated.setBackgroundColor(Color.rgb(32, 43, 72));
        translated.setPadding(dp(10), dp(12), dp(10), dp(12));
        add(root, translated, 8);

        listenButton = button("🎙 ترجمه جمله‌ایِ طبیعی (آزمایشی)", Color.rgb(109, 40, 217));
        listenButton.setOnClickListener(v -> toggleListening());
        add(root, listenButton, 10);

        notes = new EditText(this);
        notes.setHint("یادداشت تماس… مثلاً: سه‌شنبه ساعت ۱۰ مصاحبه");
        notes.setHintTextColor(muted);
        notes.setTextColor(text);
        notes.setTextDirection(View.TEXT_DIRECTION_RTL);
        notes.setMinLines(2);
        notes.setBackgroundColor(card);
        notes.setPadding(dp(10), dp(10), dp(10), dp(10));
        add(root, notes, 10);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);

        Button save = button("💾 ذخیره یادداشت", Color.rgb(22, 101, 52));
        save.setOnClickListener(v -> saveNote());
        actionRow.addView(save, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button calendar = button("📅 تقویم", Color.rgb(30, 64, 175));
        calendar.setOnClickListener(v -> openCalendar());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        cp.leftMargin = dp(6);
        actionRow.addView(calendar, cp);
        add(root, actionRow, 8);

        Button close = button("— جمع کردن پنل", Color.rgb(55, 65, 81));
        close.setOnClickListener(v -> hideOverlay());
        add(root, close, 8);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP;
        params.y = dp(56);

        overlayView = root;
        try {
            windowManager.addView(overlayView, params);
        } catch (Exception e) {
            overlayView = null;
            Toast.makeText(this, "پنل شناور باز نشد: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }

    private void hideOverlay() {
        stopListening();
        if (overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {}
            overlayView = null;
            callStatus = null;
            heard = null;
            translated = null;
            routeHint = null;
            notes = null;
            listenButton = null;
        }
    }

    private void saveNote() {
        if (notes == null) return;
        String value = notes.getText().toString().trim();
        if (value.isEmpty()) {
            Toast.makeText(this, "اول یادداشت را بنویس.", Toast.LENGTH_SHORT).show();
            return;
        }

        String key = "note_" + System.currentTimeMillis();
        getSharedPreferences("call_notes", MODE_PRIVATE).edit().putString(key, value).apply();

        String time = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(new Date());
        Toast.makeText(this, "یادداشت تماس ذخیره شد — " + time, Toast.LENGTH_LONG).show();
    }

    private void openCalendar() {
        String noteText = notes == null ? "" : notes.getText().toString().trim();

        Intent intent = new Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE,
                        noteText.isEmpty() ? "Appuntamento telefonico" : noteText)
                .putExtra(CalendarContract.Events.DESCRIPTION,
                        "Created from Lili Call Assistant")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "اپ تقویم پیدا نشد.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onDestroy() {
        hideOverlay();

        if (telephonyManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && telephonyCallback != null) {
                telephonyManager.unregisterTelephonyCallback(telephonyCallback);
            } else if (phoneStateListener != null) {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
            }
        }
        if (recognizer != null) recognizer.destroy();
        if (translator != null) translator.close();

        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return v;
    }

    private Button button(String s, int bg) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(bg);
        return b;
    }

    private void add(LinearLayout parent, View child, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        p.topMargin = dp(topDp);
        parent.addView(child, p);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
