package org.ngengine.platform.android;

import java.io.File;

import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.VStore;
import org.ngengine.platform.jvm.FileSystemVStore;
import org.ngengine.platform.jvm.JVMAsyncPlatform;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

public class AndroidAsyncPlatform extends JVMAsyncPlatform {
    private Context androidContext;

    public AndroidAsyncPlatform(Context context) {
        super();
        this.androidContext = context;
    }

    @Override
    public void setClipboardContent(String data) {
        ClipboardManager clipboard = (ClipboardManager) androidContext.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText("NGE Clipboard Data", data);
            clipboard.setPrimaryClip(clip);
        }
    }

    @Override
    public String getClipboardContent() {        
        ClipboardManager clipboard = (ClipboardManager) androidContext.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).getText();
                return text != null ? text.toString() : "";
            }
        }
        return "";
    }

    @Override
    public void openInWebBrowser(String url) {        
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            androidContext.startActivity(browserIntent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public VStore getCacheStore(String appName, String storeName) {
        appName = NGEUtils.censorSpecial(appName);
        storeName = NGEUtils.censorSpecial(storeName);
        File cacheDir = new File(androidContext.getCacheDir(),  appName);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        logger.fine("Cache store path: " + cacheDir.getAbsolutePath());
        return new FileSystemVStore(cacheDir.toPath().resolve(storeName));

    }

    @Override
    public VStore getDataStore(String appName, String storeName) {
        appName = NGEUtils.censorSpecial(appName);
        storeName = NGEUtils.censorSpecial(storeName);
        File dataDir = new File(androidContext.getFilesDir(), appName );
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        logger.fine("Data store path: " + dataDir.getAbsolutePath());
        return new FileSystemVStore(dataDir.toPath().resolve(storeName));
    }

}
