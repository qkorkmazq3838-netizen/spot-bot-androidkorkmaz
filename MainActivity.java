package com.spotbot.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private Button scanButton;
    private ProgressBar progress;
    private TextView status, results;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        scanButton = findViewById(R.id.scanButton);
        progress = findViewById(R.id.progress);
        status = findViewById(R.id.status);
        results = findViewById(R.id.results);

        scanButton.setOnClickListener(v -> scanMarket());
    }

    private void scanMarket() {
        scanButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setText("Binance Spot verisi alınıyor…");
        results.setText("");

        pool.submit(() -> {
            try {
                JSONArray tickers = new JSONArray(get("https://api.binance.com/api/v3/ticker/24hr"));
                List<Pair> list = new ArrayList<>();

                for (int i = 0; i < tickers.length(); i++) {
                    JSONObject t = tickers.getJSONObject(i);
                    String s = t.optString("symbol");
                    if (!s.endsWith("USDT") || isStable(s)) continue;

                    double q = t.optDouble("quoteVolume", 0);
                    if (q >= 5_000_000) list.add(new Pair(s, q));
                }

                list.sort((a, b) -> Double.compare(b.vol, a.vol));
                if (list.size() > 20) list = new ArrayList<>(list.subList(0, 20));

                List<Result> out = new ArrayList<>();
                int total = list.size();

                for (int i = 0; i < total; i++) {
                    Pair p = list.get(i);
                    final int pos = i + 1;
                    runOnUiThread(() -> status.setText("Taranıyor " + pos + "/" + total + " • " + p.symbol));

                    try {
                        out.add(analyze(p.symbol));
                    } catch (Exception ignored) {
                    }
                }

                out.sort((a, b) -> Integer.compare(b.score, a.score));

                StringBuilder sb = new StringBuilder();
                for (Result r : out) {
                    String tag = r.score >= 70 ? "VALID" : (r.score >= 55 ? "WATCH" : "SKIP");
                    sb.append(String.format(Locale.US, "%s  %-12s  %3d\n", tag, r.symbol, r.score));
                    sb.append("Rejim: ").append(r.regime).append("\n");
                    sb.append(String.format(Locale.US, "Fiyat: %.8f  RSI1H: %.1f\n", r.price, r.rsi));
                    sb.append(r.reason).append("\n\n");
                }

                String text = sb.length() == 0 ? "Sonuç üretilemedi." : sb.toString();
                runOnUiThread(() -> finishUi("Tarama tamamlandı • " + out.size() + " coin", text));

            } catch (Exception e) {
                runOnUiThread(() -> finishUi(
                        "Bağlantı/Veri hatası",
                        e.getMessage() == null ? e.toString() : e.getMessage()
                ));
            }
        });
    }

    private Result analyze(String symbol) throws Exception {
        Candle[] h4 = klines(symbol, "4h", 240);
        Candle[] h1 = klines(symbol, "1h", 240);
        Candle[] m15 = klines(symbol, "15m", 120);

        double[] c4 = closes(h4);
        double[] c1 = closes(h1);
        double[] c15 = closes(m15);

        double e20_4 = emaLast(c4, 20);
        double e50_4 = emaLast(c4, 50);
        double e200_4 = emaLast(c4, 200);

        double e20_1 = emaLast(c1, 20);
        double e50_1 = emaLast(c1, 50);
        double e200_1 = emaLast(c1, 200);

        double e20_15 = emaLast(c15, 20);

        double rsi = rsi(c1, 14);
        double macd1 = emaLast(c1, 12) - emaLast(c1, 26);
        double macdSignal = emaSeriesLastDiff(c1, 12, 26, 9);

        double macd15 = emaLast(c15, 12) - emaLast(c15, 26);
        double macd15Signal = emaSeriesLastDiff(c15, 12, 26, 9);

        double px = c15[c15.length - 1];

        String r4 = trend(c4[c4.length - 1], e20_4, e50_4, e200_4);
        String r1 = trend(c1[c1.length - 1], e20_1, e50_1, e200_1);

        String regime =
                (bull(r4) && bull(r1)) ? "RISK_ON" :
                (bear(r4) && bear(r1)) ? "RISK_OFF" :
                ("RANGE".equals(r4) || "RANGE".equals(r1)) ? "RANGE" :
                "TRANSITION";

        int score = 0;
        StringBuilder why = new StringBuilder();

        if ("RISK_ON".equals(regime)) {
            score += 25;
            why.append("4H+1H trend uyumlu; ");
        }

        if ("RISK_OFF".equals(regime)) {
            score -= 25;
            why.append("risk-off; ");
        }

        if (c1[c1.length - 1] > e20_1 && e20_1 > e50_1) {
            score += 15;
            why.append("1H EMA; ");
        }

        if (rsi >= 48 && rsi <= 68) {
            score += 15;
            why.append("RSI sağlıklı; ");
        } else if (rsi >= 78) {
            score -= 8;
        }

        if (macd1 > macdSignal) {
            score += 12;
            why.append("1H MACD+; ");
        }

        if (px > e20_15 && macd15 > macd15Signal) {
            score += 13;
            why.append("15M momentum; ");
        }

        if (higherHighLow(h4)) {
            score += 10;
            why.append("4H HH/HL; ");
        }

        if (higherHighLow(h1)) {
            score += 10;
            why.append("1H HH/HL; ");
        }

        score = Math.max(0, Math.min(100, score));
        return new Result(symbol, px, score, regime, rsi, why.toString());
    }

    private Candle[] klines(String symbol, String interval, int limit) throws Exception {
        JSONArray a = new JSONArray(get(
                "https://api.binance.com/api/v3/klines?symbol=" + symbol +
                        "&interval=" + interval + "&limit=" + limit
        ));

        Candle[] x = new Candle[a.length()];
        for (int i = 0; i < a.length(); i++) {
            JSONArray k = a.getJSONArray(i);
            x[i] = new Candle(k.getDouble(2), k.getDouble(3), k.getDouble(4));
        }
        return x;
    }

    private static String get(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", "SpotBot-Android/0.1");

        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);

        BufferedReader br = new BufferedReader(
                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8)
        );

        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private void finishUi(String s, String text) {
        status.setText(s);
        results.setText(text);
        progress.setVisibility(View.GONE);
        scanButton.setEnabled(true);
    }

    private static boolean isStable(String s) {
        return s.startsWith("USDC") ||
                s.startsWith("FDUSD") ||
                s.startsWith("TUSD") ||
                s.startsWith("DAI") ||
                s.startsWith("USDP");
    }

    private static boolean bull(String x) {
        return x.startsWith("BULL");
    }

    private static boolean bear(String x) {
        return x.startsWith("BEAR");
    }

    private static String trend(double p, double e20, double e50, double e200) {
        if (p > e20 && e20 > e50 && e50 > e200) return "BULL_STRONG";
        if (p > e50 && e50 > e200) return "BULL";
        if (p < e20 && e20 < e50 && e50 < e200) return "BEAR_STRONG";
        if (p < e50 && e50 < e200) return "BEAR";
        return "RANGE";
    }

    private static double[] closes(Candle[] x) {
        double[] a = new double[x.length];
        for (int i = 0; i < x.length; i++) a[i] = x[i].close;
        return a;
    }

    private static double emaLast(double[] a, int n) {
        double alpha = 2.0 / (n + 1);
        double e = a[0];
        for (int i = 1; i < a.length; i++) {
            e = alpha * a[i] + (1 - alpha) * e;
        }
        return e;
    }

    private static double rsi(double[] a, int n) {
        double up = 0, dn = 0;
        int start = Math.max(1, a.length - n);

        for (int i = start; i < a.length; i++) {
            double d = a[i] - a[i - 1];
            if (d > 0) up += d;
            else dn -= d;
        }

        if (dn == 0) return 100;
        double rs = (up / n) / (dn / n);
        return 100 - 100 / (1 + rs);
    }

    private static double emaSeriesLastDiff(double[] a, int fast, int slow, int sig) {
        double af = 2.0 / (fast + 1);
        double as = 2.0 / (slow + 1);
        double ag = 2.0 / (sig + 1);

        double ef = a[0];
        double es = a[0];
        double signal = 0;

        for (int i = 1; i < a.length; i++) {
            ef = af * a[i] + (1 - af) * ef;
            es = as * a[i] + (1 - as) * es;
            double m = ef - es;
            signal = ag * m + (1 - ag) * signal;
        }

        return signal;
    }

    private static boolean higherHighLow(Candle[] x) {
        if (x.length < 20) return false;

        double h1 = -1;
        double h2 = -1;
        double l1 = Double.MAX_VALUE;
        double l2 = Double.MAX_VALUE;

        int mid = x.length - 10;

        for (int i = x.length - 20; i < mid; i++) {
            h1 = Math.max(h1, x[i].high);
            l1 = Math.min(l1, x[i].low);
        }

        for (int i = mid; i < x.length; i++) {
            h2 = Math.max(h2, x[i].high);
            l2 = Math.min(l2, x[i].low);
        }

        return h2 > h1 && l2 > l1;
    }

    static class Candle {
        double high, low, close;
        Candle(double h, double l, double c) {
            high = h;
            low = l;
            close = c;
        }
    }

    static class Pair {
        String symbol;
        double vol;
        Pair(String s, double v) {
            symbol = s;
            vol = v;
        }
    }

    static class Result {
        String symbol, regime, reason;
        double price, rsi;
        int score;

        Result(String s, double p, int sc, String rg, double r, String w) {
            symbol = s;
            price = p;
            score = sc;
            regime = rg;
            rsi = r;
            reason = w;
        }
    }
}
