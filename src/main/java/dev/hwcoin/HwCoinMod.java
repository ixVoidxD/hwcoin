package dev.hwcoin;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Показывает цены аукциона в коинах. Идея и источник курса (публичный API сервера)
 * совпадают с модом CoinPrice (MIT, Suobaru). Код написан заново под Fabric 1.21.11.
 */
public class HwCoinMod implements ClientModInitializer {

    static final String API = "https://api.holyworld.me/v1/coins-trades?limit=5";
    static final Pattern RATE = Pattern.compile("\"rate\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
    static final Pattern NUMBER = Pattern.compile("[0-9](?:[0-9\\s\\u00A0\\u202F.,'_])*");
    static final Pattern BID = Pattern.compile("(?iu)ставку\\s+в\\s+([0-9](?:[0-9\\s\\u00A0\\u202F.,'_])*)");
    static final Pattern LEGACY = Pattern.compile("(?i)\\u00A7[0-9A-FK-ORX]");
    static final Pattern HAS_SUFFIX = Pattern.compile(".*\\([0-9]+[.,][0-9]{2}\\s+коин\\.\\).*");

    static final List<String> LABELS = List.of(
        "текущая цена", "цена шага", "начальная цена", "цена выкупа",
        "цена за 1 ед", "цена за ед", "цена за 1 шт", "цена за шт", "сумма ставки", "цена");

    static volatile double rate = 0;          // монет за 1 коин (среднее по последним сделкам)
    static volatile boolean enabled = true;
    static volatile String status = "ещё не обновлялся";

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "HWCoin-API");
        t.setDaemon(true);
        return t;
    });

    static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("hwcoin.properties"); }

    static void loadConfig() {
        try {
            if (Files.isRegularFile(file())) {
                java.util.Properties p = new java.util.Properties();
                try (var in = Files.newInputStream(file())) { p.load(in); }
                enabled = Boolean.parseBoolean(p.getProperty("enabled", "true"));
            }
        } catch (Exception ignored) {}
    }

    static void saveConfig() {
        try {
            java.util.Properties p = new java.util.Properties();
            p.setProperty("enabled", Boolean.toString(enabled));
            try (var out = Files.newOutputStream(file())) { p.store(out, "HWCoin"); }
        } catch (Exception ignored) {}
    }

    static void refresh() {
        if (!RUNNING.compareAndSet(false, true)) return;
        EXEC.execute(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(API))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json")
                    .header("User-Agent", "HWCoin/1.0.0")
                    .GET().build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());
                Matcher m = RATE.matcher(resp.body());
                double sum = 0; int n = 0;
                while (m.find()) {
                    double v = Double.parseDouble(m.group(1));
                    if (v > 0 && !Double.isNaN(v) && !Double.isInfinite(v)) { sum += v; n++; }
                }
                if (n == 0) throw new RuntimeException("в ответе нет rate");
                rate = sum / n;
                status = "ok, сделок: " + n;
            } catch (Exception e) {
                status = "ошибка: " + e.getMessage();
            } finally {
                RUNNING.set(false);
            }
        });
    }

    static double parseNumber(String s) {
        String t = s.replaceAll("[^0-9]", "");
        if (t.isEmpty()) return Double.NaN;
        try { return Double.parseDouble(t); } catch (Exception e) { return Double.NaN; }
    }

    static String fmt(double v) { return String.format(Locale.US, "%.2f", v); }

    /** Возвращает число-цену из строки лора или NaN, если строка не ценовая. */
    static double extractPrice(String raw) {
        String s = LEGACY.matcher(raw).replaceAll("").trim();
        if (HAS_SUFFIX.matcher(s).matches()) return Double.NaN;

        Matcher b = BID.matcher(s);
        if (b.find()) return parseNumber(b.group(1));

        int colon = s.indexOf(':');
        if (colon < 0) return Double.NaN;
        String label = s.substring(0, colon).toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}0-9 ]", " ").replaceAll("\\s+", " ").trim();
        boolean ok = false;
        for (String l : LABELS) {
            if (label.equals(l) || label.endsWith(" " + l) || (label.contains(l) && !l.equals("цена"))) { ok = true; break; }
        }
        if (!ok) return Double.NaN;
        Matcher m = NUMBER.matcher(s.substring(colon + 1));
        if (!m.find()) return Double.NaN;
        return parseNumber(m.group());
    }

    @Override
    public void onInitializeClient() {
        loadConfig();
        EXEC.scheduleAtFixedRate(HwCoinMod::refresh, 0, 120, TimeUnit.SECONDS);

        ItemTooltipCallback.EVENT.register((stack, ctx, type, lines) -> {
            double r = rate;
            if (!enabled || r <= 0 || Double.isNaN(r) || Double.isInfinite(r)) return;
            for (int i = 0; i < lines.size(); i++) {
                double price = extractPrice(lines.get(i).getString());
                if (Double.isNaN(price)) continue;
                Text updated = lines.get(i).copy()
                    .append(Text.literal(" (" + fmt(price / r) + " коин.)").formatted(Formatting.GOLD));
                lines.set(i, updated);
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("hwrate")
                .executes(c -> {
                    c.getSource().sendFeedback(Text.literal("[HWCoin] Курс: " + (rate > 0 ? fmt(rate) : "нет данных")
                        + " монет за коин, " + status + ", мод " + (enabled ? "включён" : "выключен")));
                    return 1;
                })
                .then(ClientCommandManager.literal("update").executes(c -> {
                    refresh();
                    c.getSource().sendFeedback(Text.literal("[HWCoin] Обновляю курс..."));
                    return 1;
                }))
                .then(ClientCommandManager.literal("toggle").executes(c -> {
                    enabled = !enabled;
                    saveConfig();
                    c.getSource().sendFeedback(Text.literal("[HWCoin] " + (enabled ? "включён" : "выключен")));
                    return 1;
                }))));
    }
}
