package com.timiroom.infra.slack;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public record SlackCommand(String team,String user,String channel,String text) {
    public enum Action { CONNECT, HELP, SPEC, REVIEW, PR, STATUS }
    public record Arguments(Action action,UUID id,int revision,Long repo,int pull,String head) {}
    public static SlackCommand from(String raw,String expectedTeam,String expectedApp) {
        var fields=new HashMap<String,String>();
        try {
            for(String pair:raw.split("&")) {
                String[] parts=pair.split("=",2);
                String key=URLDecoder.decode(parts[0],StandardCharsets.UTF_8);
                String value=parts.length==2?URLDecoder.decode(parts[1],StandardCharsets.UTF_8):"";
                if(fields.putIfAbsent(key,value)!=null) throw new SecurityException("SLACK_REQUEST_INVALID");
            }
        } catch(IllegalArgumentException e) { throw new SecurityException("SLACK_REQUEST_INVALID"); }
        if(!Objects.equals(expectedTeam,fields.get("team_id")) || !Objects.equals(expectedApp,fields.get("api_app_id"))
            || !"/timiroom".equals(fields.get("command")) || !id(fields.get("user_id"),"[UW][A-Z0-9]+")
            || !id(fields.get("channel_id"),"[CGD][A-Z0-9]+") || fields.getOrDefault("text","").length()>1000)
            throw new SecurityException("SLACK_REQUEST_INVALID");
        return new SlackCommand(expectedTeam,fields.get("user_id"),fields.get("channel_id"),fields.getOrDefault("text","").trim());
    }
    private static boolean id(String value,String pattern) { return value!=null && value.matches(pattern); }
    public static Arguments parse(String text) {
        String[] words=text.trim().split("\\s+");
        try {
            if(words.length==1) return new Arguments(switch(words[0]) {
                case "connect" -> Action.CONNECT; case "spec" -> Action.SPEC; case "help","" -> Action.HELP;
                default -> throw new IllegalArgumentException();
            },null,0,null,0,null);
            if(words[0].equals("status") && words.length==2) return new Arguments(Action.STATUS,uuid(words[1]),0,null,0,null);
            if(words[0].equals("review") && words.length==3) return new Arguments(Action.REVIEW,uuid(words[1]),positive(words[2]),null,0,null);
            if(words[0].equals("pr") && words.length==5 && words[4].matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) {
                long repo=Long.parseLong(words[2]);if(repo<=0) throw new IllegalArgumentException();
                return new Arguments(Action.PR,uuid(words[1]),0,repo,positive(words[3]),words[4]);
            }
        } catch(IllegalArgumentException e) { throw new IllegalArgumentException("SLACK_COMMAND_INVALID"); }
        throw new IllegalArgumentException("SLACK_COMMAND_INVALID");
    }
    private static int positive(String value) { int n=Integer.parseInt(value);if(n<=0) throw new IllegalArgumentException();return n; }
    private static UUID uuid(String value) { var id=UUID.fromString(value);if(!id.toString().equals(value)) throw new IllegalArgumentException();return id; }
}
