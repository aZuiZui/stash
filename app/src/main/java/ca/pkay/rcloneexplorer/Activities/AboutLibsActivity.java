package ca.pkay.rcloneexplorer.Activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.widget.Toolbar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ca.pkay.rcloneexplorer.util.ActivityHelper;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.RecyclerViewAdapters.AboutLibrariesAdapter;

import static ca.pkay.rcloneexplorer.util.ActivityHelper.tryStartActivity;

public class AboutLibsActivity extends AppCompatActivity implements AboutLibrariesAdapter.OnClickListener {

    private List<String> libraryNames;
    private Map<String, String> libraryUrls;
    private Map<String, String> libraryLicences;
    private Map<String, String> libraryLicenceUrls;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityHelper.applyTheme(this);
        setContentView(R.layout.activity_about_libs);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setDisplayShowHomeEnabled(true);
        }

        createData();
        RecyclerView recyclerView = findViewById(R.id.about_libs_list);
        recyclerView.setHasFixedSize(true);
        RecyclerView.LayoutManager layoutManager = new LinearLayoutManager(this);
        recyclerView.setLayoutManager(layoutManager);
        AboutLibrariesAdapter aboutLibrariesAdapter = new AboutLibrariesAdapter(libraryNames, libraryUrls, libraryLicences, libraryLicenceUrls, this);
        recyclerView.setAdapter(aboutLibrariesAdapter);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    private void createData() {
        libraryNames = new ArrayList<>();
        libraryUrls = new HashMap<>();
        libraryLicences = new HashMap<>();
        libraryLicenceUrls = new HashMap<>();

        String androidSupportLibraries = "Android Support Libraries";
        libraryNames.add(androidSupportLibraries);
        libraryUrls.put(androidSupportLibraries, "https://developer.android.com/topic/libraries/support-library/");
        libraryLicences.put(androidSupportLibraries, "Licensed under Apache-2.0");
        libraryLicenceUrls.put(androidSupportLibraries, "http://www.apache.org/licenses/LICENSE-2.0");

        String floatingActionButtonSpeedDial = "Floating Action Button Speed Dial";
        libraryNames.add(floatingActionButtonSpeedDial);
        libraryUrls.put(floatingActionButtonSpeedDial, "https://github.com/leinardi/FloatingActionButtonSpeedDial");
        libraryLicences.put(floatingActionButtonSpeedDial, "Licensed under Apache-2.0");
        libraryLicenceUrls.put(floatingActionButtonSpeedDial, "https://github.com/leinardi/FloatingActionButtonSpeedDial/blob/master/LICENSE");

        String glide = "Glide";
        libraryNames.add(glide);
        libraryUrls.put(glide, "https://github.com/bumptech/glide");
        libraryLicences.put(glide, "BSD, part MIT and Apache 2.0");
        libraryLicenceUrls.put(glide, "https://github.com/bumptech/glide/blob/master/LICENSE");

        String markDownView = "MarkDown View";
        libraryNames.add(markDownView);
        libraryUrls.put(markDownView, "https://github.com/falnatsheh/MarkdownView");
        libraryLicences.put(markDownView, "Licensed under Apache-2.0");
        libraryLicenceUrls.put(markDownView, "https://github.com/falnatsheh/MarkdownView/blob/master/license.txt");

        String materialDesignIcons = "Material Design";
        libraryNames.add(materialDesignIcons);
        libraryUrls.put(materialDesignIcons, "https://github.com/Templarian/MaterialDesign");
        libraryLicences.put(materialDesignIcons, "Licensed under SIL Open Font 1.1");
        libraryLicenceUrls.put(materialDesignIcons, "http://scripts.sil.org/cms/scripts/page.php?item_id=OFL_web");

        String rclone = "Rclone";
        libraryNames.add(rclone);
        libraryUrls.put(rclone, "https://github.com/ncw/rclone");
        libraryLicences.put(rclone, "Licensed under MIT");
        libraryLicenceUrls.put(rclone, "https://github.com/ncw/rclone/blob/master/COPYING");

        String recyclerViewAnimators = "Recyclerview Animators";
        libraryNames.add(recyclerViewAnimators);
        libraryUrls.put(recyclerViewAnimators, "https://github.com/wasabeef/recyclerview-animators");
        libraryLicences.put(recyclerViewAnimators, "Licensed under Apache 2.0");
        libraryLicenceUrls.put(recyclerViewAnimators, "https://github.com/wasabeef/recyclerview-animators/blob/master/LICENSE");

        String toasty = "Toasty";
        libraryNames.add(toasty);
        libraryUrls.put(toasty, "https://github.com/GrenderG/Toasty");
        libraryLicences.put(toasty, "Licensed under LGPL-3.0");
        libraryLicenceUrls.put(toasty, "https://github.com/GrenderG/Toasty/blob/master/LICENSE");

        String rcloneExplorer = "rcloneExplorer";
        libraryNames.add(rcloneExplorer);
        libraryUrls.put(rcloneExplorer, "https://github.com/kaczmarkiewiczp/rcloneExplorer");
        libraryLicences.put(rcloneExplorer, "Licensed under MIT");
        libraryLicenceUrls.put(rcloneExplorer, "https://github.com/kaczmarkiewiczp/rcloneExplorer/blob/e5afbfda50c747bc088543dab9576d80bf99ec63/LICENSE");

        String okhttp = "OkHttp";
        libraryNames.add(okhttp);
        libraryUrls.put(okhttp, "https://square.github.io/okhttp");
        libraryLicences.put(okhttp, "Licensed under Apache 2.0");
        libraryLicenceUrls.put(okhttp, "https://square.github.io/okhttp/#license");

        String rfc3339 = "RFC 3339 Date Parser";
        libraryNames.add(rfc3339);
        libraryUrls.put(rfc3339, "https://github.com/x0b/rfc3339parser");
        libraryLicences.put(rfc3339, "Licensed under MIT");
        libraryLicenceUrls.put(rfc3339, "https://github.com/x0b/rfc3339parser/blob/master/LICENSE");


        // stash: the projects Stash is built from and the components it bundles
        addLibrary("RCX by x0b", "https://github.com/x0b/rcx",
                "Licensed under GPL-3.0", "https://github.com/x0b/rcx/blob/master/LICENSE");
        addLibrary("Round Sync by Felix Nüsse", "https://github.com/newhinton/Round-Sync",
                "Licensed under GPL-3.0", "https://github.com/newhinton/Round-Sync/blob/master/LICENSE");
        addLibrary("Round-Sync (rclone-extra fork) by gulp79", "https://github.com/gulp79/Round-Sync",
                "Licensed under GPL-3.0", "https://github.com/gulp79/Round-Sync/blob/master/LICENSE");
        addLibrary("rclone-extra by gulp79", "https://github.com/gulp79/rclone-extra",
                "Licensed under MIT", "https://github.com/gulp79/rclone-extra/blob/master/COPYING");
        addLibrary("teldrive by divyam234 (tgdrive)", "https://github.com/tgdrive/teldrive",
                "Licensed under MIT", "https://github.com/tgdrive/teldrive/blob/0c0a5d0fbe648039360003f84c4dbd121477160e/LICENSE");
        addLibrary("teldrive-ui by tgdrive", "https://github.com/tgdrive/teldrive-ui",
                "Licensed under GPL-3.0", "https://github.com/tgdrive/teldrive-ui/blob/main/LICENSE");
        addLibrary("ONLYOFFICE editors by Ascensio System SIA (modified)", "https://github.com/ONLYOFFICE",
                "Licensed under AGPL-3.0 with additional terms", "https://www.gnu.org/licenses/agpl-3.0.html");
        addLibrary("office-web by wasmtools", "https://github.com/wasmtools/office-web",
                "Licensed under AGPL-3.0", "https://github.com/wasmtools/office-web/blob/main/LICENSE");
        addLibrary("PDF.js by Mozilla", "https://github.com/mozilla/pdf.js",
                "Licensed under Apache-2.0", "https://github.com/mozilla/pdf.js/blob/master/LICENSE");
        addLibrary("docx-preview by Volodymyr Baydalka", "https://github.com/VolodymyrBaydalka/docxjs",
                "Licensed under Apache-2.0", "https://github.com/VolodymyrBaydalka/docxjs/blob/master/LICENSE");
        addLibrary("SheetJS Community Edition", "https://sheetjs.com",
                "Licensed under Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0");
        addLibrary("JSZip by Stuart Knightley", "https://github.com/Stuk/jszip",
                "Licensed under MIT or GPL-3.0", "https://github.com/Stuk/jszip/blob/main/LICENSE.markdown");
        addLibrary("AndroidX Media3", "https://github.com/androidx/media",
                "Licensed under Apache-2.0", "https://github.com/androidx/media/blob/release/LICENSE");

        String undraw = "unDraw - Open Source Illustrations";
        libraryNames.add(undraw);
        libraryUrls.put(undraw, "https://undraw.co/");
        libraryLicences.put(undraw, "Licensed under Custom License");
        libraryLicenceUrls.put(undraw, "https://undraw.co/license");
    }

    private void addLibrary(String name, String url, String licence, String licenceUrl) {
        libraryNames.add(name);
        libraryUrls.put(name, url);
        libraryLicences.put(name, licence);
        libraryLicenceUrls.put(name, licenceUrl);
    }

    @Override
    public void onLibraryClick(String url) {
        Uri uri = Uri.parse(url);
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        if (intent.resolveActivity(getPackageManager()) != null) {
            tryStartActivity(this, intent);
        }
    }
}
