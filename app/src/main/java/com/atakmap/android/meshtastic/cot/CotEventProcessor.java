package com.atakmap.android.meshtastic.cot;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.meshtastic.util.Constants;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.time.CoordinatedTime;
import org.meshtastic.proto.ATAKProtos;
import com.google.protobuf.ByteString;

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
        }
    }
    
    private void parseLinkTag(XmlPullParser xpp, ParsedCotData data) {
        for (int i = 0; i < xpp.getAttributeCount(); i++) {
            if ("uid".equalsIgnoreCase(xpp.getAttributeName(i))) {
                data.deviceCallsign = xpp.getAttributeValue(i);
            }
        }
    }
    
    public ATAKProtos.TAKPacket buildPLIPacket(ParsedCotData data) {
        ATAKProtos.Contact.Builder contact = ATAKProtos.Contact.newBuilder()
            .setCallsign(data.callsign != null ? data.callsign : "")
            .setDeviceCallsign(data.deviceCallsign != null ? data.deviceCallsign : "");
        
        ATAKProtos.Group.Builder group = ATAKProtos.Group.newBuilder();
        if (data.role != null) {
            try {
                group.setRole(ATAKProtos.MemberRole.valueOf(data.role.replace(" ", "")));
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Unknown role: " + data.role);
                group.setRole(ATAKProtos.MemberRole.TeamMember);
            }
        }
        if (data.teamName != null) {
            try {
                group.setTeam(ATAKProtos.Team.valueOf(data.teamName.replace(" ", "")));
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Unknown team: " + data.teamName);
                group.setTeam(ATAKProtos.Team.White);
            }
        }
        
        ATAKProtos.Status.Builder status = ATAKProtos.Status.newBuilder()
            .setBattery(data.battery);
        
        ATAKProtos.PLI.Builder pli = ATAKProtos.PLI.newBuilder()
            .setAltitude(Double.valueOf(data.altitude).intValue())
            .setLatitudeI((int) (data.latitude / Constants.GPS_COORD_DIVISOR))
            .setLongitudeI((int) (data.longitude / Constants.GPS_COORD_DIVISOR))
            .setCourse(data.course)
            .setSpeed(data.speed);
        
        return ATAKProtos.TAKPacket.newBuilder()
            .setContact(contact)
            .setStatus(status)
            .setGroup(group)
            .setPli(pli)
            .build();
    }
    
    public ATAKProtos.TAKPacket buildChatPacket(ParsedCotData data) {
        ATAKProtos.Contact.Builder contact = ATAKProtos.Contact.newBuilder()
            .setCallsign(data.callsign != null ? data.callsign : "")
            .setDeviceCallsign(data.deviceCallsign != null ? data.deviceCallsign : "");
        
        ATAKProtos.GeoChat.Builder geochat = ATAKProtos.GeoChat.newBuilder()
            .setMessage(data.message != null ? data.message : "")
            .setTo(data.to != null ? data.to : "All Chat Rooms");
        
        return ATAKProtos.TAKPacket.newBuilder()
            .setContact(contact)
            .setChat(geochat)
            .build();
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