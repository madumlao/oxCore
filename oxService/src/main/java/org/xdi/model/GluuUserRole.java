/*
 * oxCore is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */package org.xdi.model;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.gluu.site.ldap.persistence.annotation.LdapEnum;

/**
 * User role
 *
 * @author Yuriy Movchan Date: 11.03.2010
 */
public enum GluuUserRole implements LdapEnum {

    ADMIN("admin"), OWNER("owner"), MANAGER("manager"), USER("user"), WHITEPAGES("whitePages");

    private String value;

    private static Map<String, GluuUserRole> MAP_BY_VALUES = new HashMap<String, GluuUserRole>();

    static {
        for (GluuUserRole enumType : values()) {
            MAP_BY_VALUES.put(enumType.getValue(), enumType);
        }
    }

    GluuUserRole(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public String getRoleName() {
        return value;
    }

    public String getDisplayName() {
        return value;
    }

    public static GluuUserRole getByValue(String value) {
        return MAP_BY_VALUES.get(value);
    }

    public static GluuUserRole[] getByValues(String[] values) {
        GluuUserRole[] roles = new GluuUserRole[values.length];
        for (int i = 0; i < values.length; i++) {
            roles[i] = getByValue(values[i]);
        }

        return roles;
    }

    public static boolean equals(GluuUserRole[] roles1, GluuUserRole[] roles2) {
        Arrays.sort(roles1);
        Arrays.sort(roles2);
        return Arrays.equals(roles1, roles2);
    }

    public static boolean containsRole(GluuUserRole[] roles, GluuUserRole role) {
        if ((roles == null) || (role == null)) {
            return false;
        }

        for (int i = 0; i < roles.length; i++) {
            if (role.equals(roles[i])) {
                return true;
            }
        }

        return false;
    }

    public Enum<? extends LdapEnum> resolveByValue(String value) {
        return getByValue(value);
    }

    @Override
    public String toString() {
        return value;
    }

}
