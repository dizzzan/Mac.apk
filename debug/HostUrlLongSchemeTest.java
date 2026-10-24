package com.macapk.loader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Offline regression for manifest-declared OAuth callbacks longer than 64 characters. */
public final class HostUrlLongSchemeTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        String scheme = "com.googleusercontent.apps.123456789012-abcdefghijklmnopqrstuvwxyz012345";
        check(scheme.length() > 64, "fixture exercises the former cutoff");
        check(HostUrlIntake.validScheme(scheme), "long OAuth callback is valid");
        check(HostUrlIntake.validScheme("a".repeat(64)), "64-character scheme");
        check(HostUrlIntake.validScheme("a".repeat(65)), "65-character scheme");
        for (String invalid : Arrays.asList(null, "", "1bad", scheme + ":", scheme + "/",
                scheme + "_", scheme + "\n", scheme + "é")) {
            check(!HostUrlIntake.validScheme(invalid), "invalid scheme rejected: " + invalid);
        }
        check(HostUrlIntake.hostSchemes(Arrays.asList("https", scheme,
                scheme.toUpperCase(Locale.ROOT), "otpauth", "x-apple-example", "itms-apps",
                "file", "intent", "bad_scheme", "example+auth"))
                .equals(List.of(scheme, "example+auth")), "filtering and deduplication preserved");

        Path xml = Files.createTempFile("long-callback-", ".xml");
        try {
            Files.writeString(xml, "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\""
                    + " package=\"com.example.callback\"><application><activity android:name=\".Redirect\""
                    + " android:exported=\"true\"><intent-filter>"
                    + "<action android:name=\"android.intent.action.VIEW\"/>"
                    + "<category android:name=\"android.intent.category.DEFAULT\"/>"
                    + "<category android:name=\"android.intent.category.BROWSABLE\"/>"
                    + "<data android:scheme=\"" + scheme + "\"/>"
                    + "</intent-filter></activity></application></manifest>");
            Manifest manifest = Manifest.parse(xml.toFile());
            check(HostUrlIntake.hostSchemes(MacApkPackageManager.macapkBrowsableSchemes(manifest))
                    .equals(List.of(scheme)), "manifest callback survives host registration filtering");
            android.content.Intent callback = HostUrlIntake.browserIntentFor(scheme + ":/oauthredirect");
            check(scheme.equals(callback.getData().getScheme()), "callback URI preserves full scheme");
        } finally {
            Files.delete(xml);
        }
        System.out.println(checks + " long callback scheme checks passed");
    }
}
