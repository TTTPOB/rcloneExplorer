package ca.pkay.rcloneexplorer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.PreferenceManager;
import ca.pkay.rcloneexplorer.Dialogs.InputDialog;
import ca.pkay.rcloneexplorer.Dialogs.LoadingDialog;
import ca.pkay.rcloneexplorer.Fragments.FileExplorerFragment;
import ca.pkay.rcloneexplorer.Fragments.RemotesFragment;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.RemoteConfig.RemoteConfigHelper;
import ca.pkay.rcloneexplorer.Settings.SettingsActivity;
import com.crashlytics.android.Crashlytics;
import com.google.android.material.navigation.NavigationView;
import es.dmoral.toasty.Toasty;
import io.fabric.sdk.android.Fabric;
import io.github.x0b.rfc3339parser.Rfc3339Parser;
import io.github.x0b.rfc3339parser.Rfc3339Strict;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static ca.pkay.rcloneexplorer.ActivityHelper.tryStartActivity;
import static ca.pkay.rcloneexplorer.ActivityHelper.tryStartActivityForResult;

public class MainActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener,
        RemotesFragment.OnRemoteClickListener,
        RemotesFragment.AddRemoteToNavDrawer,
        InputDialog.OnPositive {

    private static final String TAG = "MainActivity";
    private static final int READ_REQUEST_CODE = 42; // code when opening rclone config file
    private static final int REQUEST_PERMISSION_CODE = 62; // code when requesting permissions
    private static final int SETTINGS_CODE = 71; // code when coming back from settings
    private static final int WRITE_REQUEST_CODE = 81; // code when exporting config
    private static final int UPDATE_AVAILABLE = 201;
    private final String FILE_EXPLORER_FRAGMENT_TAG = "ca.pkay.rcexplorer.MAIN_ACTIVITY_FILE_EXPLORER_TAG";
    private NavigationView navigationView;
    private DrawerLayout drawer;
    private Rclone rclone;
    private Fragment fragment;
    private Context context;
    private Boolean isDarkTheme;
    private HashMap<Integer, RemoteItem> drawerPinnedRemoteIds;
    private int availableDrawerPinnedRemoteId;

    private Handler handler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(@NonNull Message msg) {
            if(UPDATE_AVAILABLE == msg.what) {
                Toasty.info(context, context.getString(R.string.app_update_notification_title), Toast.LENGTH_LONG).show();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent() != null) {
            String s = getIntent().getStringExtra(getString(R.string.firebase_msg_app_updates_topic));
            if (s != null && s.equals("true")) {
                openAppUpdate();
                finish();
                return;
            }

            s = getIntent().getStringExtra(getString(R.string.firebase_msg_beta_app_updates_topic));
            if (s != null) {
                openBetaUpdate(s);
                finish();
                return;
            }
        }

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean enableCrashReports = sharedPreferences.getBoolean(getString(R.string.pref_key_crash_reports), false);
        if (enableCrashReports) {
            Fabric.with(this, new Crashlytics());
        }

        applyTheme();
        context = this;
        drawerPinnedRemoteIds = new HashMap<>();
        availableDrawerPinnedRemoteId = 2;
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionbar = getSupportActionBar();
        if (actionbar != null) {
            actionbar.setDisplayHomeAsUpEnabled(true);
            actionbar.setHomeAsUpIndicator(R.drawable.ic_menu);
        }

        drawer = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);

        requestPermissions();

        rclone = new Rclone(this);

        findViewById(R.id.locked_config_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askForConfigPassword();
            }
        });

        boolean appUpdates = sharedPreferences.getBoolean(getString(R.string.pref_key_app_updates), true);
        if (appUpdates) {
            checkForUpdate(false);
        }

        Intent intent = getIntent();
        Bundle bundle = intent.getExtras();

        int lastVersionCode = sharedPreferences.getInt(getString(R.string.pref_key_version_code), -1);
        String lastVersionName = sharedPreferences.getString(getString(R.string.pref_key_version_name), "");
        int currentVersionCode = BuildConfig.VERSION_CODE;
        String currentVersionName = BuildConfig.VERSION_NAME;

        if (lastVersionCode < currentVersionCode || !lastVersionName.equals(currentVersionName)) {
            // In version code 24 there were changes to app shortcuts
            // Remove this in the long future
            if (lastVersionCode <= 23) {
                AppShortcutsHelper.removeAllAppShortcuts(this);
                AppShortcutsHelper.populateAppShortcuts(this, rclone.getRemotes());
            }

            startRemotesFragment();

            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putInt(getString(R.string.pref_key_version_code), currentVersionCode);
            editor.putString(getString(R.string.pref_key_version_name), currentVersionName);
            editor.apply();
        } else if (rclone.isConfigEncrypted()) {
            askForConfigPassword();
        } else if (savedInstanceState != null) {
            fragment = getSupportFragmentManager().findFragmentByTag(FILE_EXPLORER_FRAGMENT_TAG);
            if (fragment instanceof FileExplorerFragment) {
                FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
                transaction.replace(R.id.flFragment, fragment, FILE_EXPLORER_FRAGMENT_TAG);
                transaction.commit();
            } else {
                startRemotesFragment();
            }
        } else if (bundle != null && bundle.containsKey(AppShortcutsHelper.APP_SHORTCUT_REMOTE_NAME)) {
            String remoteName = bundle.getString(AppShortcutsHelper.APP_SHORTCUT_REMOTE_NAME);
            RemoteItem remoteItem = getRemoteItemFromName(remoteName);
            if (remoteItem != null) {
                AppShortcutsHelper.reportAppShortcutUsage(this, remoteItem.getName());
                startRemote(remoteItem, false);
            } else {
                Toasty.error(this, getString(R.string.remote_not_found), Toast.LENGTH_SHORT, true).show();
                finish();
            }
        } else {
            startRemotesFragment();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        pinRemotesToDrawer();
    }

    private void applyTheme() {
        isDarkTheme = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(getString(R.string.pref_key_dark_theme), false);
        ActivityHelper.applyTheme(this);
        TypedValue typedValue = new TypedValue();
        getTheme().resolveAttribute(R.attr.colorPrimaryDark, typedValue, true);
        getWindow().setStatusBarColor(typedValue.data);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home && !(fragment instanceof FileExplorerFragment)) {
            drawer.openDrawer(GravityCompat.START);
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    public void openNavigationDrawer() {
        drawer.openDrawer(GravityCompat.START);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // result from file picker (for importing config file)
        if (requestCode == READ_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            Uri uri;
            if (data != null) {
                uri = data.getData();
                new CopyConfigFile().execute(uri);
            }
        } else if (requestCode == SETTINGS_CODE && resultCode == RESULT_OK) {
            boolean themeChanged = data.getBooleanExtra(SettingsActivity.THEME_CHANGED, false);
            if (themeChanged) {
                recreate();
            }
        } else if (requestCode == WRITE_REQUEST_CODE && resultCode == RESULT_OK) {
            Uri uri;
            if (data != null) {
                uri = data.getData();
                try {
                    rclone.exportConfigFile(uri);
                } catch (IOException e) {
                    e.printStackTrace();
                    Toasty.error(this, getString(R.string.error_exporting_config_file), Toast.LENGTH_SHORT, true).show();
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // TODO: document deletion on exit
        File dir = getExternalCacheDir();
        if (dir != null && dir.isDirectory()) {
            String[] children = dir.list();
            for (String aChildren : children) {
                new File(dir, aChildren).delete();
            }
        }
    }

    @Override
    public void onBackPressed() {
        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
        } else if (fragment != null && fragment instanceof FileExplorerFragment) {
            if (((FileExplorerFragment) fragment).onBackButtonPressed()) {
                return;
            } else {
                fragment = null;
            }
        }
        super.onBackPressed();
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        // Handle navigation view item clicks here.
        int id = item.getItemId();

        if (drawerPinnedRemoteIds.containsKey(id)) {
            startPinnedRemote(drawerPinnedRemoteIds.get(id));
            return true;
        }

        switch (id) {
            case R.id.nav_remotes:
                startRemotesFragment();
                break;
            case R.id.nav_import:
                Uri configUri;
                if (rclone.isConfigFileCreated()) {
                    warnUserAboutOverwritingConfiguration();
                } else if(null != (configUri = rclone.searchExternalConfig())) {
                    askUseExternalConfig(configUri);
                } else {
                    importConfigFile();
                }
                break;
            case R.id.nav_export:
                if (rclone.isConfigFileCreated()) {
                    exportConfigFile();
                } else {
                    Toasty.info(this,  getString(R.string.no_config_found), Toast.LENGTH_SHORT, true).show();
                }
                break;
            case R.id.nav_settings:
                Intent settingsIntent = new Intent(this, SettingsActivity.class);
                startActivityForResult(settingsIntent, SETTINGS_CODE);
                break;
            case R.id.nav_about:
                Intent aboutIntent = new Intent(this, AboutActivity.class);
                startActivity(aboutIntent);
                break;
        }

        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        drawer.closeDrawer(GravityCompat.START);
        return true;
    }

    private void pinRemotesToDrawer() {
        Menu menu = navigationView.getMenu();
        MenuItem existingMenu = menu.findItem(1);
        if (existingMenu != null) {
            return;
        }

        SubMenu subMenu = menu.addSubMenu(R.id.drawer_pinned_header, 1, Menu.NONE, R.string.nav_drawer_pinned_header);

        List<RemoteItem> remoteItems = rclone.getRemotes();
        Collections.sort(remoteItems);
        for (RemoteItem remoteItem : remoteItems) {
            if (remoteItem.isDrawerPinned()) {
                MenuItem menuItem = subMenu.add(R.id.nav_pinned, availableDrawerPinnedRemoteId, Menu.NONE, remoteItem.getName());
                drawerPinnedRemoteIds.put(availableDrawerPinnedRemoteId, remoteItem);
                availableDrawerPinnedRemoteId++;
                menuItem.setIcon(remoteItem.getRemoteIcon());
            }
        }
    }

    private void startRemotesFragment() {
        fragment = RemotesFragment.newInstance();
        FragmentManager fragmentManager = getSupportFragmentManager();

        for (int i = 0; i < fragmentManager.getBackStackEntryCount(); i++) {
            fragmentManager.popBackStack();
        }

        if (!isFinishing()) {
            fragmentManager.beginTransaction().replace(R.id.flFragment, fragment).commitAllowingStateLoss();
        }
    }

    private RemoteItem getRemoteItemFromName(String remoteName) {
        List<RemoteItem> remoteItemList = rclone.getRemotes();
        for (RemoteItem remoteItem : remoteItemList) {
            if (remoteItem.getName().equals(remoteName)) {
                return remoteItem;
            }
        }
        return null;
    }

    private void warnUserAboutOverwritingConfiguration() {
        AlertDialog.Builder builder;
        if (isDarkTheme) {
            builder = new AlertDialog.Builder(this, R.style.DarkDialogTheme);
        } else {
            builder = new AlertDialog.Builder(this);
        }
        builder.setTitle(R.string.replace_config_file_question);
        builder.setMessage(R.string.config_file_lost_statement);
        builder.setPositiveButton(R.string.continue_statement, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                dialogInterface.cancel();
                Uri configUri;
                if(null != (configUri = rclone.searchExternalConfig())){
                    askUseExternalConfig(configUri);
                } else {
                    importConfigFile();
                }
            }
        });
        builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                dialogInterface.cancel();
            }
        });
        builder.show();
    }

    public void askUseExternalConfig(final Uri uri) {
        AlertDialog.Builder builder;
        if (isDarkTheme) {
            builder = new AlertDialog.Builder(this, R.style.DarkDialogTheme);
        } else {
            builder = new AlertDialog.Builder(this);
        }
        builder.setTitle(R.string.config_use_external_question);
        builder.setMessage(context.getString(R.string.config_import_external_explain, uri.toString()));
        builder.setPositiveButton(R.string.continue_statement, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                dialogInterface.cancel();
                new CopyConfigFile().execute(uri);
            }
        });
        builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                dialogInterface.cancel();
                importConfigFile();
            }
        });
        builder.show();
    }

    private void askForConfigPassword() {
        findViewById(R.id.locked_config).setVisibility(View.VISIBLE);
        new InputDialog()
                .setTitle(R.string.config_password_protected)
                .setMessage(R.string.please_enter_password)
                .setNegativeButton(R.string.cancel)
                .setPositiveButton(R.string.okay_confirmation)
                .setDarkTheme(isDarkTheme)
                .setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
                .show(getSupportFragmentManager(), "input dialog");
    }

    /*
     * Input Dialog callback
     */
    @Override
    public void onPositive(String tag, String input) {
        new DecryptConfig().execute(input);
    }

    public void importConfigFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");

        startActivityForResult(intent, READ_REQUEST_CODE);
    }

    public void exportConfigFile() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        intent.putExtra(Intent.EXTRA_TITLE, "rclone.conf");
        tryStartActivityForResult(this, intent, WRITE_REQUEST_CODE);
    }

    public void requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_PERMISSION_CODE);
        }
    }

    private void openAppUpdate() {
        Uri uri = Uri.parse(getString(R.string.app_latest_release_url));
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        tryStartActivity(this, intent);
    }

    private void openBetaUpdate(String url) {
        Uri uri = Uri.parse(url);
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        tryStartActivity(this, intent);
    }

    @Override
    public void onRemoteClick(RemoteItem remote) {
        startRemote(remote, true);
    }

    private void startRemote(RemoteItem remote, boolean addToBackStack) {
        fragment = FileExplorerFragment.newInstance(remote);
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.flFragment, fragment, FILE_EXPLORER_FRAGMENT_TAG);
        if (addToBackStack) {
            transaction.addToBackStack(null);
        }
        transaction.commit();

        AppShortcutsHelper.reportAppShortcutUsage(this, remote.getName());
        navigationView.getMenu().getItem(0).setChecked(false);
    }

    private void startPinnedRemote(RemoteItem remoteItem) {
        if (fragment != null && fragment instanceof FileExplorerFragment) {
            FragmentManager fragmentManager = getSupportFragmentManager();

            // this is the case when remote gets started from a shortcut
            // therefore back should exit the app, and not go into remotes screen
            if (fragmentManager.getBackStackEntryCount() == 0) {
                startRemote(remoteItem, false);
            } else {
                for (int i = 0; i < fragmentManager.getBackStackEntryCount(); i++) {
                    fragmentManager.popBackStack();
                }

                startRemote(remoteItem, true);
            }
        } else {
            startRemote(remoteItem, true);
        }

        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        drawer.closeDrawer(GravityCompat.START);
    }

    @Override
    public void addRemoteToNavDrawer() {
        Menu menu = navigationView.getMenu();

        // remove all items and add them again so that it's in alpha order
        menu.removeItem(1);
        drawerPinnedRemoteIds.clear();
        availableDrawerPinnedRemoteId = 1;

        pinRemotesToDrawer();
    }

    @Override
    public void removeRemoteFromNavDrawer() {
        Menu menu = navigationView.getMenu();

        // remove all items and add them again so that it's in alpha order
        menu.removeItem(1);
        drawerPinnedRemoteIds.clear();
        availableDrawerPinnedRemoteId = 1;

        pinRemotesToDrawer();
    }

    private void checkForUpdate(boolean force) {
        Context context = this;
        // only check if not disabled
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        if(!force && !sharedPreferences.getBoolean(getString(R.string.pref_key_app_updates), true)){
            Log.i(TAG, "checkForUpdate: Not checking, updates are disabled");
            return;
        }
        // only check if the last check was >6 hours ago
        long lastUpdateCheck = sharedPreferences.getLong(context.getString(R.string.pref_key_update_last_check), 0);
        long now = System.currentTimeMillis();
        if(lastUpdateCheck + 1000 * 60 * 60 * 6 > now){
            Log.i(TAG, "checkForUpdate: recent check to new, not checking for updates");
            return;
        }

        OkHttpClient client = new OkHttpClient();
        boolean checkBeta = sharedPreferences.getBoolean(getString(R.string.pref_key_app_updates_beta), false);
        String url;
        if (checkBeta) {
            url = context.getString(R.string.app_pre_release_api_url);
        } else {
            url = context.getString(R.string.app_relase_api_url);
        }
        Request request = new Request.Builder().url(url).build();
        client.newCall(request).enqueue(new UpdateRequestResultHandler(this, checkBeta, handler));
    }

    private static class UpdateRequestResultHandler implements Callback {

        private SharedPreferences sharedPreferences;
        private Context context;
        private boolean betaCheck;
        private Handler handler;

        public UpdateRequestResultHandler(Context context, boolean betaCheck, Handler handler) {
            this.context = context;
            this.betaCheck = betaCheck;
            this.handler = handler;
            sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        }

        @Override
        public void onFailure(@NotNull Call call, @NotNull IOException e) {
            Log.w(TAG, "onFailure: Update check failed", e);
            updateLastUpdateRequest();
        }

        @Override
        public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
            updateLastUpdateRequest();
            long publishedAt = getLastPublishTimestamp(response);
            // Since the app is not published immediately during build, 15 minutes are added
            long publishBarrier = publishedAt + 1000 * 60 * 15;
            if(BuildConfig.BUILD_TIME < publishBarrier) {
                Log.i(TAG, "onResponse: App is not up-to-date");
                handler.obtainMessage(MainActivity.UPDATE_AVAILABLE).sendToTarget();
            } else {
                Log.i(TAG, "onResponse: App is up-to-date");
            }
        }

        private long getLastPublishTimestamp(Response response) throws IOException {
            try (ResponseBody body = response.body()) {
                long publishedAt = -1;
                if (null == body) {
                    return publishedAt;
                }
                try {
                    Rfc3339Parser parser = new Rfc3339Strict();
                    if(betaCheck){
                        JSONArray releases = new JSONArray(body.string());
                        for(int i = 0; i < releases.length(); i++) {
                            JSONObject release = releases.getJSONObject(i);
                            long timestamp = parser.parse(release.getString("published_at")).getTime();
                            if(timestamp > publishedAt) {
                                publishedAt = timestamp;
                            }
                        }
                    } else {
                        JSONObject json = new JSONObject(body.string());
                        publishedAt = parser.parse(json.getString("published_at")).getTime();
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "Update check failed: JSON error", e);
                } catch (ParseException e) {
                    Log.e(TAG, "Update check failed: time format error", e);
                }
                return publishedAt;
            }
        }

        private void updateLastUpdateRequest(){
            long now = System.currentTimeMillis();
            sharedPreferences.edit().putLong(context.getString(R.string.pref_key_update_last_check), now).apply();
        }
    }

    @SuppressLint("StaticFieldLeak")
    private class CopyConfigFile extends AsyncTask<Uri, Void, Boolean> {

        private LoadingDialog loadingDialog;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            findViewById(R.id.locked_config).setVisibility(View.GONE);
            loadingDialog = new LoadingDialog()
                    .setTitle(R.string.copying_rclone_config)
                    .setCanCancel(false);
            loadingDialog.show(getSupportFragmentManager(), "loading dialog");
        }

        @Override
        protected Boolean doInBackground(Uri... uris) {
            try {
                return rclone.copyConfigFile(uris[0]);
            } catch (IOException e) {
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean success) {
            super.onPostExecute(success);
            if (loadingDialog.isStateSaved()) {
                loadingDialog.dismissAllowingStateLoss();
            } else {
                loadingDialog.dismiss();
            }
            if (!success) {
                Toasty.error(context, getString(R.string.copying_rclone_config_fail), Toast.LENGTH_LONG, true).show();
                return;
            }

            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
            if(sharedPreferences.getBoolean(getString(R.string.pref_key_enable_saf), false)){
                RemoteConfigHelper.enableSaf(context);
            }

            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.remove(getString(R.string.shared_preferences_pinned_remotes));
            editor.remove(getString(R.string.shared_preferences_drawer_pinned_remotes));
            editor.remove(getString(R.string.shared_preferences_hidden_remotes));
            editor.apply();

            if (rclone.isConfigEncrypted()) {
                pinRemotesToDrawer(); // this will clear any previous pinned remotes
                askForConfigPassword();
            } else {
                AppShortcutsHelper.removeAllAppShortcuts(context);
                AppShortcutsHelper.populateAppShortcuts(context, rclone.getRemotes());
                pinRemotesToDrawer();
                startRemotesFragment();
            }
        }
    }

    @SuppressLint("StaticFieldLeak")
    private class DecryptConfig extends AsyncTask<String, Void, Boolean> {

        private LoadingDialog loadingDialog;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            loadingDialog = new LoadingDialog()
                    .setTitle(R.string.working)
                    .setCanCancel(false);
            loadingDialog.show(getSupportFragmentManager(), "loading dialog");
        }

        @Override
        protected Boolean doInBackground(String... strings) {
            return rclone.decryptConfig(strings[0]);
        }

        @Override
        protected void onPostExecute(Boolean success) {
            super.onPostExecute(success);
            loadingDialog.dismiss();
            if (!success) {
                Toasty.error(context, getString(R.string.error_unlocking_config), Toast.LENGTH_LONG, true).show();
                askForConfigPassword();
            } else {
                findViewById(R.id.locked_config).setVisibility(View.GONE);
                AppShortcutsHelper.removeAllAppShortcuts(context);
                AppShortcutsHelper.populateAppShortcuts(context, rclone.getRemotes());
                startRemotesFragment();
            }
        }
    }
}
