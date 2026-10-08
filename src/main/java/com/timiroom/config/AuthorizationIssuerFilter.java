package com.timiroom.config;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** RFC 9207 identification on validated authorization redirects only. */
final class AuthorizationIssuerFilter extends OncePerRequestFilter {
    private final RegisteredClientRepository clients;
    private final String issuer;
    AuthorizationIssuerFilter(RegisteredClientRepository clients,String issuer) {this.clients=clients;this.issuer=issuer;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(request.getContextPath()+"/oauth2/authorize").equals(request.getRequestURI());
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        chain.doFilter(request,new HttpServletResponseWrapper(response) {
            @Override public void sendRedirect(String location) throws IOException {super.sendRedirect(identified(request,location));}
            @Override public void setHeader(String name,String value) {super.setHeader(name,"Location".equalsIgnoreCase(name)?identified(request,value):value);}
            @Override public void addHeader(String name,String value) {super.addHeader(name,"Location".equalsIgnoreCase(name)?identified(request,value):value);}
        });
    }
    private String identified(HttpServletRequest request,String location) {
        try {
            var ids=request.getParameterValues("client_id");if(ids==null || ids.length!=1) return location;
            var client=clients.findByClientId(ids[0]);if(client==null) return location;
            var target=URI.create(location);var query=parse(target.getRawQuery());
            if(!(query.containsKey("code") || query.containsKey("error")) || query.containsKey("iss")) return location;
            boolean registered=client.getRedirectUris().stream().anyMatch(value->{
                var allowed=URI.create(value);
                return Objects.equals(allowed.getScheme(),target.getScheme()) && Objects.equals(allowed.getRawAuthority(),target.getRawAuthority())
                    && Objects.equals(allowed.getRawPath(),target.getRawPath()) && target.getFragment()==null
                    && parse(allowed.getRawQuery()).entrySet().stream().allMatch(e->Objects.equals(query.get(e.getKey()),e.getValue()));
            });
            return registered?location+"&iss="+URLEncoder.encode(issuer,StandardCharsets.UTF_8):location;
        } catch(IllegalArgumentException invalid) {return location;}
    }
    private Map<String,String> parse(String raw) {
        var values=new HashMap<String,String>();if(raw==null) return values;
        for(String part:raw.split("&")) {
            var pair=part.split("=",2);var key=URLDecoder.decode(pair[0],StandardCharsets.UTF_8);
            if(values.putIfAbsent(key,pair.length==2?URLDecoder.decode(pair[1],StandardCharsets.UTF_8):"")!=null) throw new IllegalArgumentException();
        }
        return values;
    }
}
