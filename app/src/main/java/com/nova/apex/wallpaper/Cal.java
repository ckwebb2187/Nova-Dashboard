package com.nova.apex.wallpaper;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.CalendarContract;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

// Reads the shift days (Work / OFF / weekend) for the dashboard's shift strip.
// Read-only, and only titles that match one of the three shift words leave this class,
// so no other event ever reaches the page.
final class Cal {
    private Cal() { }

    static boolean allowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    private static Pattern word(String w) {
        w = w == null ? "" : w.trim();
        if (w.isEmpty()) return null;
        return Pattern.compile("(?i)(^|[^\\p{L}\\p{N}])" + Pattern.quote(w) + "($|[^\\p{L}\\p{N}])");
    }

    // Returns {"perm":true,"days":{"2026-11-23":2,...}} where each day is a bit mask:
    // 1 = a Work event, 2 = an OFF event, 4 = a weekend event. Days with none are left out.
    static String shifts(Context ctx, int days, String workWord, String offWord, String wkndWord) {
        if (!allowed(ctx)) return "{\"perm\":false,\"days\":{}}";
        Pattern pw = word(workWord), po = word(offWord), pk = word(wkndWord);
        days = Math.max(1, Math.min(days, 31));
        JSONObject out = new JSONObject();
        Cursor cur = null;
        try {
            Calendar start = Calendar.getInstance();
            start.set(Calendar.HOUR_OF_DAY, 0);
            start.set(Calendar.MINUTE, 0);
            start.set(Calendar.SECOND, 0);
            start.set(Calendar.MILLISECOND, 0);
            long from = start.getTimeInMillis() - 24L * 3600 * 1000;   // a day of slack for all-day events stored in UTC
            long to = start.getTimeInMillis() + (days + 1) * 24L * 3600 * 1000;
            String[] proj = {CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.STATUS};
            ContentResolver cr = ctx.getContentResolver();
            cur = CalendarContract.Instances.query(cr, proj, from, to);
            SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            utc.setTimeZone(TimeZone.getTimeZone("UTC"));
            SimpleDateFormat local = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            JSONObject map = new JSONObject();
            while (cur != null && cur.moveToNext()) {
                String title = cur.getString(0);
                if (title == null) continue;
                if (!cur.isNull(4) && cur.getInt(4) == CalendarContract.Instances.STATUS_CANCELED) continue;
                int bits = 0;
                if (pw != null && pw.matcher(title).find()) bits |= 1;
                if (po != null && po.matcher(title).find()) bits |= 2;
                if (pk != null && pk.matcher(title).find()) bits |= 4;
                if (bits == 0) continue;
                long b = cur.getLong(1), e = cur.getLong(2);
                boolean allDay = cur.getInt(3) == 1;
                SimpleDateFormat f = allDay ? utc : local;
                if (e <= b) e = b + 1;
                // Walk every day the event covers (end is exclusive)
                for (long t = b; t < e; t += 24L * 3600 * 1000) {
                    String d = f.format(new java.util.Date(t));
                    map.put(d, map.optInt(d, 0) | bits);
                }
            }
            out.put("perm", true);
            out.put("days", map);
        } catch (Exception ex) {
            try {
                out.put("perm", true);
                out.put("error", String.valueOf(ex.getMessage()));
                if (!out.has("days")) out.put("days", new JSONObject());
            } catch (Exception ignored) { }
        } finally {
            if (cur != null) cur.close();
        }
        return out.toString();
    }
}
