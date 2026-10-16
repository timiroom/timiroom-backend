package com.timiroom.domain.integration.dto;
public enum IntegrationScope {
    PROJECTS_READ("projects:read"),SPECS_READ("specs:read"),SPECS_PROPOSE("specs:propose"),
    CONSISTENCY_RUN("consistency:run"),CONSISTENCY_READ("consistency:read");
    private final String value;
    IntegrationScope(String value) {this.value=value;}
    public String value() {return value;}
    public static IntegrationScope from(String value) {
        for(var scope:values()) if(scope.value.equals(value)) return scope;
        throw new IllegalArgumentException("INVALID_SCOPE");
    }
}
