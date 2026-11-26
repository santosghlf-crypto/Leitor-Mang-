package br.app.koma;

import android.app.*;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.net.URI;
import java.util.List;
import org.json.JSONArray;
import org.mozilla.geckoview.*;

public class Reader extends Activity {
    private static final int PAPER = 0xff161819, CARD = 0xff26292b, MINT = 0xffa6e6bc;
    private GeckoRuntime engine;
    private GeckoSession session;
    private GeckoView browser;
    private LinearLayout root, bar;
    private JSONArray sites;
    private SharedPreferences prefs;
    private String source, pending;
    private boolean ready, full, canBack, failed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("shelf", MODE_PRIVATE);
        try { sites = new JSONArray(prefs.getString("sites", "[]")); }
        catch (org.json.JSONException e) { sites = new JSONArray(); }
        if (state != null) pending = state.getString("source");
        engine = GeckoRuntime.getDefault(this);
        prepare();
    }
    private void prepare() {
        failed = false;
        library();
        WebExtensionController extensions = engine.getWebExtensionController();
        extensions.ensureBuiltIn("resource://android/assets/ublock/", "uBlock0@raymondhill.net")
            .then(blocker -> {
                if (!blocker.metaData.enabled) throw new IllegalStateException("uBlock desativado");
                return extensions.ensureBuiltIn("resource://android/assets/paginas/", "pages@koma.local");
            }).accept(pages -> {
                if (isDestroyed() || isFinishing()) return;
                ready = true;
                library();
                if (pending != null) { String url = pending; pending = null; open(url); }
            }, error -> {
                if (!isDestroyed() && !isFinishing()) { failed = true; library(); }
            });
    }

    private int dp(int n) { return Math.round(getResources().getDisplayMetrics().density * n); }
    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }
    private TextView label(String words, int size, int color) {
        TextView t = new TextView(this);
        t.setText(words); t.setTextSize(size); t.setTextColor(color);
        return t;
    }
    private Button action(String title, Runnable task) {
        Button b = new Button(this);
        b.setAllCaps(false); b.setText(title);
        b.setOnClickListener(v -> task.run());
        return b;
    }
    private void layout(boolean reading) {
        root = column(); root.setBackgroundColor(reading ? Color.BLACK : PAPER);
        root.setFitsSystemWindows(!reading || !full);
        setContentView(root);
    }
    private void library() {
        full = false; immersive(); layout(false);
        ScrollView scroll = new ScrollView(this); root.addView(scroll);
        LinearLayout list = column(); scroll.addView(list);
        list.setPadding(dp(20),dp(24),dp(20),dp(24));
        list.addView(label("KOMA", 15, MINT));
        TextView heading = label("Minha estante", 32, Color.WHITE);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD); list.addView(heading);
        TextView status = label(ready ? "●  uBlock Origin ativo" : failed ? "Falha ao iniciar o leitor" : "Preparando o leitor…", 15, MINT);
        status.setPadding(0,dp(8),0,dp(20)); list.addView(status);
        if (failed) list.addView(action("Tentar novamente", this::prepare));
        list.addView(action("+  Adicionar site", this::addSite));
        if (sites.length() == 0) list.addView(label("Adicione o endereço de um site para começar a ler.", 16, Color.LTGRAY));
        for (int i = 0; i < sites.length(); i++) {
            final String url = sites.optString(i);
            Button tile = action(URI.create(url).getHost() + "\n" + url, () -> open(url));
            tile.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            tile.setPadding(dp(16),dp(12),dp(16),dp(12)); tile.setTextColor(Color.WHITE);
            GradientDrawable bg = new GradientDrawable(); bg.setColor(CARD); bg.setCornerRadius(dp(12)); tile.setBackground(bg);
            LinearLayout.LayoutParams box = new LinearLayout.LayoutParams(-1,-2); box.topMargin = dp(10);
            tile.setEnabled(ready); list.addView(tile,box);
            tile.setOnLongClickListener(v -> { options(url); return true; });
        }
    }
    private static String checked(String raw) {
        try {
            String s = raw.trim();
            URI u = new URI(s.contains("://") ? s : "https://" + s);
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null) return null;
            return u.normalize().toASCIIString();
        } catch (Exception e) { return null; }
    }
    private void addSite() {
        EditText field = new EditText(this);
        field.setSingleLine(); field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setHint("https://site.com");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Adicionar site").setView(field)
            .setPositiveButton("Salvar",null).setNegativeButton("Cancelar",null).create();
        dialog.show();
        dialog.getButton(-1).setOnClickListener(v -> {
            String url = checked(field.getText().toString());
            if (url == null) { field.setError("Digite um endereço HTTPS válido."); return; }
            for (int i=0;i<sites.length();i++) if (url.equals(sites.optString(i))) {
                field.setError("Esse site já está na estante."); return;
            }
            sites.put(url); prefs.edit().putString("sites",sites.toString()).apply();
            dialog.dismiss(); library();
        });
    }
    private void options(String url) {
        new AlertDialog.Builder(this).setTitle(URI.create(url).getHost())
          .setItems(new String[]{"Abrir página inicial", "Remover da estante"}, (d,index) -> {
              if (index == 0) { prefs.edit().remove("last/"+url).apply(); open(url); return; }
              for (int i=0;i<sites.length();i++) if (url.equals(sites.optString(i))) { sites.remove(i); break; }
              prefs.edit().remove("last/"+url).putString("sites",sites.toString()).apply(); library();
          }).show();
    }
    private void open(String url) {
        if (!ready || session != null) return;
        source = url; canBack = false; full = true; layout(true);
        bar = new LinearLayout(this); root.addView(bar);
        for (Button b : new Button[]{action("Estante",this::closeReader),
                action("Recarregar",() -> session.reload()),action("Tela cheia",() -> setFull(true))})
            bar.addView(b,new LinearLayout.LayoutParams(0,-2,1));
        browser = new GeckoView(this); root.addView(browser,new LinearLayout.LayoutParams(-1,0,1));
        session = new GeckoSession();
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public void onCanGoBack(GeckoSession s, boolean back) { if (s == session) canBack = back; }
            @Override public void onLocationChange(GeckoSession s, String location,
                    List<GeckoSession.PermissionDelegate.ContentPermission> permissions, Boolean gesture) {
                if (s == session && location != null && location.startsWith("https://") &&
                    URI.create(source).getHost().equalsIgnoreCase(android.net.Uri.parse(location).getHost()))
                    prefs.edit().putString("last/"+source,location).apply();
            }
            @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest r) {
                if (r.uri == null || !r.uri.startsWith("https://")) return GeckoResult.deny();
                if (r.target == TARGET_WINDOW_NEW) {
                    if (r.hasUserGesture && s == session) s.loadUri(r.uri);
                    return GeckoResult.deny();
                }
                return GeckoResult.allow();
            }
        });
        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStop(GeckoSession s, boolean ok) {
                if (!ok && s == session) { setFull(false); Toast.makeText(Reader.this,"Falha ao carregar. Toque em Recarregar.",Toast.LENGTH_SHORT).show(); }
            }
        });
        session.open(engine); browser.setSession(session);
        setFull(true); session.loadUri(prefs.getString("last/"+url,url));
        if (!prefs.getBoolean("hint",false)) {
            Toast.makeText(this,"Use Voltar para mostrar os controles.",Toast.LENGTH_LONG).show();
            prefs.edit().putBoolean("hint",true).apply();
        }
    }
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(full ?
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE : 0);
    }
    private void setFull(boolean value) {
        full = value; bar.setVisibility(full ? View.GONE : View.VISIBLE);
        root.setFitsSystemWindows(!full); root.requestApplyInsets(); immersive();
    }
    private void release() {
        if (session != null) {
            GeckoSession old = session; session = null;
            browser.releaseSession(); old.close(); browser = null;
        }
    }
    private void closeReader() { release(); source = null; library(); }
    @Override public void onBackPressed() {
        if (session == null) super.onBackPressed();
        else if (full) setFull(false);
        else if (canBack) session.goBack();
        else closeReader();
    }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus && full) immersive(); }
    @Override protected void onPause() { if (session != null) session.setActive(false); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (session != null) session.setActive(true); }
    @Override protected void onSaveInstanceState(Bundle b) { super.onSaveInstanceState(b); b.putString("source",source != null ? source : pending); }
    @Override protected void onDestroy() { release(); super.onDestroy(); }
}
