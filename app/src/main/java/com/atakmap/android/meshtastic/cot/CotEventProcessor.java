package com.atakmap.android.meshtastic.cot;

import android.os.Bundle;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.meshtastic.util.Constants;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.time.CoordinatedTime;
import org.meshtastic.proto.Contact;
import org.meshtastic.proto.GeoChat;
import org.meshtastic.proto.Group;
import org.meshtastic.proto.MemberRole;
import org.meshtastic.proto.PLI;
import org.meshtastic.proto.Status;
import org.meshtastic.proto.TAKPacket;
import org.meshtastic.proto.Team;
import okio.ByteString;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class CotEventProcessor {
    private static final String TAG = "CotEventProcessor";

    private long lastPLITime = 0;

    public static class ParsedCotData {
        public String callsign;
        public String deviceCallsign;
        public String message;
        public String messageId;  // Original messageId for read receipts
        public String to;
        public String role;
        public String teamName;
        public int battery;
        public int course;
        public int speed;
        public double altitude;
        public double latitude;
        public double longitude;
    }
    public long getLastPLITime() {
        return lastPLITime;
    }

    public void setLastPLITime(long time) {
        this.lastPLITime = time;
    }

    public ParsedCotData parseCotEvent(CotEvent cotEvent) {
        ParsedCotData data = new ParsedCotData();

        if (cotEvent == null) {
            Log.w(TAG, "Null CoT event provided");
            return data;
        }

        // Get location from self marker
        MapView mapView = MapView.getMapView();
        if (mapView != null && mapView.getSelfMarker() != null) {
            com.atakmap.coremap.maps.coords.GeoPoint point = mapView.getSelfMarker().getPoint();
            if (point != null) {
                data.altitude = point.getAltitude();
                data.latitude = point.getLatitude();
                data.longitude = point.getLongitude();
            }
            data.deviceCallsign = mapView.getSelfMarker().getUID();
        }

        // Extract UUID from GeoChat UID format: GeoChat.<deviceCallsign>.<chatroom>.<UUID>
        String uid = cotEvent.getUID();
        if (uid != null && uid.startsWith("GeoChat.")) {
            Log.d(TAG, "Parsing GeoChat UID: " + uid);
            int lastDotIndex = uid.lastIndexOf('.');
            if (lastDotIndex > 0 && lastDotIndex < uid.length() - 1) {
                data.messageId = uid.substring(lastDotIndex + 1);
                Log.d(TAG, "Extracted UUID from GeoChat UID: " + data.messageId);
            }
        }

        CotDetail cotDetail = cotEvent.getDetail();
        if (cotDetail == null) {
            return data;
        }

        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser xpp = factory.newPullParser();
            xpp.setInput(new StringReader(cotDetail.toString()));

            parseXml(xpp, data);
        } catch (XmlPullParserException | IOException e) {
            Log.e(TAG, "Failed to parse CoT detail", e);
        }

        return data;
    }
    
    private void parseXml(XmlPullParser xpp, ParsedCotData data) throws XmlPullParserException, IOException {
        int eventType = xpp.getEventType();
        
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                String tagName = xpp.getName();
                
                switch (tagName.toLowerCase()) {
                    case "contact":
                        parseContactTag(xpp, data);
                        break;
                    case "__group":
                        parseGroupTag(xpp, data);
                        break;
                    case "status":
                        parseStatusTag(xpp, data);
                        break;
                    case "track":
                        parseTrackTag(xpp, data);
                        break;
                    case "remarks":
                        parseRemarksTag(xpp, data);
                        break;
                    case "__chat":
                        parseChatTag(xpp, data);
                        break;
                    case "link":
                        parseLinkTag(xpp, data);
                        break;
                }
            }
            eventType = xpp.next();
        }
    }
    
    private void parseContactTag(XmlPullParser xpp, ParsedCotData data) {
        for (int i = 0; i < xpp.getAttributeCount(); i++) {
            if ("callsign".equalsIgnoreCase(xpp.getAttributeName(i))) {
                data.callsign = xpp.getAttributeValue(i);
            }
        }
    }
    
    private void parseGroupTag(XmlPullParser xpp, ParsedCotData data) {
        for (int i = 0; i < xpp.getAttributeCount(); i++) {
            String attrName = xpp.getAttributeName(i);
            if ("role".equalsIgnoreCase(attrName)) {
                data.role = xpp.getAttributeValue(i);
            } else if ("name".equalsIgnoreCase(attrName)) {
                data.teamName = xpp.getAttributeValue(i);
            }
        }
    }
    
    private void parseStatusTag(XmlPullParser xpp, ParsedCotData data) {
        for (int i = 0; i < xpp.getAttributeCount(); i++) {
            if ("battery".equalsIgnoreCase(xpp.getAttributeName(i))) {
                try {
                    data.battery = Integer.parseInt(xpp.getAttributeValue(i));
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Invalid battery value", e);
                }
            }
        }
    }
    
    private void parseTrackTag(XmlPullParser xpp, ParsedCotData data) {
        for (int i = 0; i < xpp.getAttributeCount(); i++) {
            String attrName = xpp.getAttributeName(i);
            String attrValue = xpp.getAttributeValue(i);
            
            try {
                if ("course".equalsIgnoreCase(attrName)) {
                    data.course = Double.valueOf(attrValue).intValue();
                } else if ("speed".equalsIgnoreCase(attrName)) {
                    data.speed = Double.valueOf(attrValue).intValue();
                }
            } catch (NumberFormatException e) {
                Log.w(TAG, "Invalid track value: " + attrName, e);
            }
        }
    }
    
    private void parseRemarksTag(XmlPullParser xpp, ParsedCotData data) throws XmlPullParserException, IOException {
        if (xpp.next() == XmlPullParser.TEXT) {
            data.message = xpp.getText();
        }
    }
    
    private void parseChatTag(XmlPullParser xpp, ParsedCotData data) {
        for (int i = 0; i < xpp.getAttributeCount(); i++) {
            String attrName = xpp.getAttributeName(i);
            if ("senderCallsign".equalsIgnoreCase(attrName)) {
                data.callsign = xpp.getAttributeValue(i);
            } else if ("id".equalsIgnoreCase(attrName)) {
                data.to = xpp.getAttributeValue(i);
            }
            // Note: messageId is extracted from the CotEvent UID, not from __chat attribute
        }
    }
    
    private void parseLinkTag(XmlPullParser xpp, ParsedCotData data) {
        for (int i = 0; i < xpp.getAttributeCount(); i++) {
            if ("uid".equalsIgnoreCase(xpp.getAttributeName(i))) {
                data.deviceCallsign = xpp.getAttributeValue(i);
            }
        }
    }
    
    public TAKPacket buildPLIPacket(ParsedCotData data) {
        Contact contact = new Contact(
            data.callsign != null ? data.callsign : "",
            data.deviceCallsign != null ? data.deviceCallsign : "",
            ByteString.EMPTY
        );

        MemberRole role = MemberRole.TeamMember;
        if (data.role != null) {
            try {
                role = MemberRole.valueOf(data.role.replace(" ", ""));
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Unknown role: " + data.role);
            }
        }

        Team team = Team.White;
        if (data.teamName != null) {
            try {
                team = Team.valueOf(data.teamName.replace(" ", ""));
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Unknown team: " + data.teamName);
            }
        }

        Group group = new Group(role, team, ByteString.EMPTY);
        Status status = new Status(data.battery, ByteString.EMPTY);
        PLI pli = new PLI(
            (int) (data.latitude / Constants.GPS_COORD_DIVISOR),
            (int) (data.longitude / Constants.GPS_COORD_DIVISOR),
            Double.valueOf(data.altitude).intValue(),
            data.course,
            data.speed,
            ByteString.EMPTY
        );

        return new TAKPacket(
            false,  // is_compressed
            contact,
            group,
            status,
            pli,
            null,  // chat
            null,  // detail (oneof - must be null when pli is set)
            ByteString.EMPTY   // unknownFields
        );
    }
    
    // Separator used to smuggle messageId in deviceCallsign field
    public static final String MSG_ID_SEPARATOR = "|";

    public TAKPacket buildChatPacket(ParsedCotData data) {
        // Smuggle the messageId in the deviceCallsign field for read receipt support
        // Format: "deviceCallsign|messageId"
        String deviceCallsignWithMsgId = data.deviceCallsign != null ? data.deviceCallsign : "";
        if (data.messageId != null && !data.messageId.isEmpty()) {
            deviceCallsignWithMsgId = deviceCallsignWithMsgId + MSG_ID_SEPARATOR + data.messageId;
        }

        Contact contact = new Contact(
            data.callsign != null ? data.callsign : "",
            deviceCallsignWithMsgId,
            ByteString.EMPTY
        );

        GeoChat geochat = new GeoChat(
            data.message != null ? data.message : "",
            data.to != null ? data.to : "All Chat Rooms",
            null,  // from
            ByteString.EMPTY
        );

        return new TAKPacket(
            false,  // is_compressed
            contact,
            null,  // group
            null,  // status
            null,  // pli
            geochat,
            null,  // detail (oneof - must be null when chat is set)
            ByteString.EMPTY   // unknownFields
        );
    }

    /**
     * Extract deviceCallsign and messageId from a potentially combined field.
     * Format: "deviceCallsign|messageId" or just "deviceCallsign"
     * @return String[2] where [0] = deviceCallsign, [1] = messageId (or null)
     */
    public static String[] parseDeviceCallsignAndMessageId(String combined) {
        if (combined == null || combined.isEmpty()) {
            return new String[] { "", null };
        }
        int sepIndex = combined.indexOf(MSG_ID_SEPARATOR);
        if (sepIndex >= 0) {
            return new String[] {
                combined.substring(0, sepIndex),
                combined.substring(sepIndex + 1)
            };
        }
        return new String[] { combined, null };
    }
    
    public CotEvent createGeoChatEvent(String from, String to, String message, boolean isAllChat) {
        CotEvent cotEvent = new CotEvent();
        CoordinatedTime time = new CoordinatedTime();
        cotEvent.setTime(time);
        cotEvent.setStart(time);
        cotEvent.setStale(time.addMinutes(10));
        
        String chatroom = isAllChat ? "All Chat Rooms" : to;
        cotEvent.setUID("GeoChat." + from + "." + chatroom + "." + UUID.randomUUID());
        
        CotPoint gp = new CotPoint(0, 0, 0, 0, 0);
        cotEvent.setPoint(gp);
        cotEvent.setHow("m-g");
        cotEvent.setType("b-t-f");
        
        CotDetail cotDetail = new CotDetail("detail");
        cotEvent.setDetail(cotDetail);
        
        CotDetail chatDetail = new CotDetail("__chat");
        chatDetail.setAttribute("parent", "RootContactGroup");
        chatDetail.setAttribute("groupOwner", "false");
        chatDetail.setAttribute("messageId", UUID.randomUUID().toString());
        chatDetail.setAttribute("chatroom", chatroom);
        chatDetail.setAttribute("id", chatroom);
        chatDetail.setAttribute("senderCallsign", from);
        cotDetail.addChild(chatDetail);
        
        CotDetail chatgrp = new CotDetail("chatgrp");
        chatgrp.setAttribute("uid0", from);
        chatgrp.setAttribute("uid1", chatroom);
        chatgrp.setAttribute("id", chatroom);
        chatDetail.addChild(chatgrp);
        
        CotDetail linkDetail = new CotDetail("link");
        linkDetail.setAttribute("uid", from);
        linkDetail.setAttribute("type", "a-f-G-U-C");
        linkDetail.setAttribute("relation", "p-p");
        cotDetail.addChild(linkDetail);
        
        CotDetail serverDestinationDetail = new CotDetail("__serverdestination");
        serverDestinationDetail.setAttribute("destination", "0.0.0.0:4242:tcp");
        cotDetail.addChild(serverDestinationDetail);
        
        CotDetail remarksDetail = new CotDetail("remarks");
        remarksDetail.setAttribute("source", "BAO.F.ATAK." + from);
        remarksDetail.setAttribute("to", chatroom);
        remarksDetail.setAttribute("time", time.toString());
        remarksDetail.setInnerText(message);
        cotDetail.addChild(remarksDetail);
        
        return cotEvent;
    }
    
    public void dispatchCotEvent(CotEvent cotEvent, boolean sendToServer) {
        if (cotEvent.isValid()) {
            CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
            if (sendToServer) {
                CotMapComponent.getExternalDispatcher().dispatch(cotEvent);
            }
        } else {
            Log.e(TAG, "CotEvent is not valid");
        }
    }
}