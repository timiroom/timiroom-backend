package com.timiroom.infra.slack;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public record SlackCommand(String team,String user,String channel,String text) {
    public enum Action { CONNECT, HELP, SPEC, REVIEW, PR, STATUS, HOME, PENDING, LINK, UNLINK }
    /** query: ID 앞부분, 레포 이름, 프로젝트 이름처럼 서비스가 찾아서 맞출 자유 입력 */
    public record Arguments(Action action,UUID id,int revision,Long repo,int pull,String head,String query) {
        public Arguments(Action action,UUID id,int revision,Long repo,int pull,String head) { this(action,id,revision,repo,pull,head,null); }
    }
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
    private static final Map<String,Action> WORDS=Map.ofEntries(
        Map.entry("connect",Action.CONNECT),Map.entry("계정연결",Action.CONNECT),
        Map.entry("help",Action.HELP),Map.entry("도움말",Action.HELP),Map.entry("?",Action.HELP),
        Map.entry("spec",Action.SPEC),Map.entry("명세",Action.SPEC),
        Map.entry("review",Action.REVIEW),Map.entry("검증",Action.REVIEW),
        Map.entry("pr",Action.PR),
        Map.entry("status",Action.STATUS),Map.entry("상태",Action.STATUS),
        Map.entry("pending",Action.PENDING),Map.entry("todo",Action.PENDING),Map.entry("대기",Action.PENDING),Map.entry("할일",Action.PENDING),
        Map.entry("link",Action.LINK),Map.entry("채널연결",Action.LINK),
        Map.entry("unlink",Action.UNLINK),Map.entry("연결해제",Action.UNLINK));
    /**
     * 짧게 쓸 수 있게 ID·버전·레포·커밋을 생략할 수 있다. 생략한 값은 서비스가 프로젝트에서 찾아 채운다.
     * 예전 전체 형식(review &lt;id&gt; &lt;rev&gt;, pr &lt;snapshot&gt; &lt;repo&gt; &lt;pull&gt; &lt;sha&gt;)도 그대로 받는다.
     */
    public static Arguments parse(String text) {
        String trimmed=text==null?"":text.trim();
        if(trimmed.isEmpty()) return new Arguments(Action.HOME,null,0,null,0,null);
        String[] words=trimmed.split("\\s+");
        Action action=WORDS.get(words[0].toLowerCase(Locale.ROOT));
        if(action==null) throw new IllegalArgumentException("SLACK_COMMAND_INVALID");
        String rest=String.join(" ",Arrays.copyOfRange(words,1,words.length)).trim();
        try {
            switch(action) {
                case CONNECT,HELP,SPEC,PENDING,UNLINK -> { if(words.length!=1) throw new IllegalArgumentException(); return new Arguments(action,null,0,null,0,null); }
                case LINK -> { if(rest.length()>100) throw new IllegalArgumentException(); return new Arguments(action,null,0,null,0,null,rest.isEmpty()?null:rest); }
                case STATUS -> {
                    if(words.length==1) return new Arguments(action,null,0,null,0,null);
                    if(words.length==2) return idOrPrefix(action,words[1],0);
                }
                case REVIEW -> {
                    if(words.length==1) return new Arguments(action,null,0,null,0,null);
                    if(words.length==2) return idOrPrefix(action,words[1],0);
                    if(words.length==3) return new Arguments(action,uuid(words[1]),positive(words[2]),null,0,null);
                }
                case PR -> {
                    if(words.length==5 && words[4].matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) {
                        long repo=Long.parseLong(words[2]);if(repo<=0) throw new IllegalArgumentException();
                        return new Arguments(action,uuid(words[1]),0,repo,positive(words[3]),words[4]);
                    }
                    if(words.length==2) return new Arguments(action,null,0,null,positive(words[1].replaceFirst("^#","")),null);
                    if(words.length==3 && words[1].matches("[A-Za-z0-9_./-]{1,100}"))
                        return new Arguments(action,null,0,null,positive(words[2].replaceFirst("^#","")),null,words[1]);
                }
                default -> {}
            }
        } catch(IllegalArgumentException e) { throw new IllegalArgumentException("SLACK_COMMAND_INVALID"); }
        throw new IllegalArgumentException("SLACK_COMMAND_INVALID");
    }
    private static Arguments idOrPrefix(Action action,String value,int revision) {
        if(value.length()==36) return new Arguments(action,uuid(value),revision,null,0,null);
        if(!value.matches("[0-9a-f]{4,35}")) throw new IllegalArgumentException();
        return new Arguments(action,null,revision,null,0,null,value);
    }
    private static int positive(String value) { int n=Integer.parseInt(value);if(n<=0) throw new IllegalArgumentException();return n; }
    private static UUID uuid(String value) { var id=UUID.fromString(value);if(!id.toString().equals(value)) throw new IllegalArgumentException();return id; }
}
