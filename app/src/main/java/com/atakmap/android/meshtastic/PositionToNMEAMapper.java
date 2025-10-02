package com.atakmap.android.meshtastic;

import org.meshtastic.core.model.Position;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class PositionToNMEAMapper {
    public String createGAANmeaString(Position position) {
        Date timestamp = new Date(position.getTime() * 1000L);
        // Get current UTC time in HHmmss.SS format for the NMEA sentence.
        SimpleDateFormat sdf = new SimpleDateFormat("HHmmss.SS", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        String timeStr = sdf.format(timestamp);

        // Convert coordinates to NMEA format.
        String latStr = convertToNmeaLat(position.getLatitude());
        String lonStr = convertToNmeaLon(position.getLongitude());
        String latDir = position.getLatitude() >= 0 ? "N" : "S";
        String lonDir = position.getLongitude() >= 0 ? "E" : "W";

        String sentenceWithoutChecksum = String.format(Locale.US,
                "$GPGGA,%s,%s,%s,%s,%s,1,%d,0.9,%d,M,46.9,M,,",
                timeStr, latStr, latDir, lonStr, lonDir, position.getSatellitesInView(), position.getAltitude());

        String checksum = calculateNmeaChecksum(sentenceWithoutChecksum);
        return sentenceWithoutChecksum + "*" + checksum;
    }

    public String createRMCNmeaString(Position position) {
        Date timestamp = new Date(position.getTime() * 1000L);
        // Format time as HHmmss.SSS (UTC)
        SimpleDateFormat timeFormat = new SimpleDateFormat("HHmmss.SSS", Locale.US);
        timeFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        String timeStr = timeFormat.format(timestamp);

        // Format date as ddMMyy (UTC)
        SimpleDateFormat dateFormat = new SimpleDateFormat("ddMMyy", Locale.US);
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        String dateStr = dateFormat.format(timestamp);

        String latStr = convertToNmeaLat(position.getLatitude());
        String lonStr = convertToNmeaLon(position.getLongitude());
        String latDir = position.getLatitude() >= 0 ? "N" : "S";
        String lonDir = position.getLongitude() >= 0 ? "E" : "W";

        String sentenceWithoutChecksum = String.format(Locale.US,
                "$GPRMC,%s,A,%s,%s,%s,%s,%d,%d,%s,,",
                timeStr, latStr, latDir, lonStr, lonDir, position.getGroundSpeed(), position.getGroundTrack(), dateStr);

        String checksum = calculateNmeaChecksum(sentenceWithoutChecksum);
        return sentenceWithoutChecksum + "*" + checksum;
    }

    // Converts a decimal latitude to the NMEA format (ddmm.mmm)
    private String convertToNmeaLat(double lat) {
        double absLat = Math.abs(lat);
        int degrees = (int) absLat;
        double minutes = (absLat - degrees) * 60;
        return String.format(Locale.US, "%02d%06.3f", degrees, minutes);
    }

    // Converts a decimal longitude to the NMEA format (dddmm.mmm)
    private String convertToNmeaLon(double lon) {
        double absLon = Math.abs(lon);
        int degrees = (int) absLon;
        double minutes = (absLon - degrees) * 60;
        return String.format(Locale.US, "%03d%06.3f", degrees, minutes);
    }

    // Calculates the NMEA checksum (XOR of all characters between '$' and '*')
    private String calculateNmeaChecksum(String sentence) {
        int checksum = 0;
        // Skip the starting '$' and stop at '*' if present.
        for (int i = 1; i < sentence.length(); i++) {
            char ch = sentence.charAt(i);
            if (ch == '*') break;
            checksum ^= ch;
        }
        return String.format("%02X", checksum);
    }
}
