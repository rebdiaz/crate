/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.role;

import static io.crate.role.Securable.CLUSTER;
import static io.crate.role.Securable.SCHEMA;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.jetbrains.annotations.Nullable;

import io.crate.metadata.pgcatalog.OidHash;

public class Role implements Writeable, ToXContent {

    public static final Role CRATE_USER = new Role(
        "crate",
        new RolePrivileges(Set.of()),
        Set.of(),
        new Properties(true, null),
        true);

    public record Properties(boolean login, @Nullable SecureHash password) implements Writeable, ToXContent {

        public static Properties fromXContent(XContentParser parser) throws IOException {
            boolean login = false;
            SecureHash secureHash = null;
            while (parser.nextToken() == XContentParser.Token.FIELD_NAME) {
                switch (parser.currentName()) {
                    case "login":
                        parser.nextToken();
                        login = parser.booleanValue();
                        break;
                    case "secure_hash":
                        secureHash = SecureHash.fromXContent(parser);
                        break;
                    default:
                        throw new ElasticsearchParseException(
                            "failed to parse role properties, unexpected field name: " + parser.currentName()
                        );
                }
            }
            return new Properties(login, secureHash);
        }

        public Properties(StreamInput in) throws IOException {
            this(in.readBoolean(), in.readOptionalWriteable(SecureHash::readFrom));
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.field("login", login);
            if (password != null) {
                password.toXContent(builder, params);
            }
            return builder;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeBoolean(login);
            out.writeOptionalWriteable(password);
        }
    }

    private final String name;
    private final RolePrivileges privileges;
    private final Set<GrantedRole> grantedRoles;
    private final boolean isSuperUser;

    private final Properties properties;

    public Role(String name,
                boolean login,
                Set<Privilege> privileges,
                Set<GrantedRole> grantedRoles,
                @Nullable SecureHash password) {
        this(name, new RolePrivileges(privileges), grantedRoles, new Properties(login, password), false);
    }

    private Role(String name,
                 RolePrivileges privileges,
                 Set<GrantedRole> grantedRoles,
                 Properties properties,
                 boolean isSuperUser) {
        if (properties.login == false) {
            assert properties.password == null : "Cannot create a Role with password";
            assert isSuperUser == false : "Cannot create a Role which is superUser";
        }
        this.name = name;
        this.privileges = privileges;
        this.grantedRoles = Collections.unmodifiableSet(grantedRoles);
        this.isSuperUser = isSuperUser;
        this.properties = properties;
    }

    public Role(StreamInput in) throws IOException {
        name = in.readString();
        int privSize = in.readVInt();
        var privilegesList = new ArrayList<Privilege>(privSize);
        for (int i = 0; i < privSize; i++) {
            privilegesList.add(new Privilege(in));
        }
        privileges = new RolePrivileges(privilegesList);
        int grantedRolesSize = in.readVInt();
        Set<GrantedRole> grantedRoleSet = HashSet.newHashSet(grantedRolesSize);
        for (int i = 0; i < grantedRolesSize; i++) {
            grantedRoleSet.add(new GrantedRole(in));
        }
        grantedRoles = Collections.unmodifiableSet(grantedRoleSet);
        isSuperUser = false;
        properties = new Properties(in);

    }

    public Role with(Set<Privilege> privileges) {
        return new Role(name, new RolePrivileges(privileges), grantedRoles, properties, false);
    }

    public Role with(SecureHash password) {
        return new Role(name, privileges, grantedRoles, new Properties(properties.login, password), false);
    }


    public String name() {
        return name;
    }

    @Nullable
    public SecureHash password() {
        return properties.password();
    }

    public boolean isUser() {
        return properties.login();
    }

    public boolean isSuperUser() {
        return isSuperUser;
    }

    public RolePrivileges privileges() {
        return privileges;
    }

    public Set<GrantedRole> grantedRoles() {
        return grantedRoles;
    }

    public Policy matchSchema(Permission permission, int oid) {
        Policy result = Policy.REVOKE;
        for (var privilege : privileges) {
            Subject ident = privilege.subject();
            if (ident.permission() != permission) {
                continue;
            }
            if (ident.securable() == SCHEMA && OidHash.schemaOid(ident.ident()) == oid) {
                return privilege.policy();
            }
            if (ident.securable() == CLUSTER) {
                result = privilege.policy();
            }
        }
        return result;
    }

    public Set<String> grantedRoleNames() {
        Set<String> grantedRoleNames = HashSet.newHashSet(grantedRoles().size());
        for (GrantedRole grantedRole : grantedRoles) {
            grantedRoleNames.add(grantedRole.roleName());
        }
        return grantedRoleNames;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Role that = (Role) o;
        return Objects.equals(name, that.name) &&
               Objects.equals(privileges, that.privileges) &&
               Objects.equals(grantedRoles, that.grantedRoles) &&
               Objects.equals(isSuperUser, that.isSuperUser) &&
               Objects.equals(properties, that.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, privileges, grantedRoles, properties, isSuperUser);
    }

    @Override
    public String toString() {
        return (isUser() ? "User{" : "Role{") + name + ", " + (password() == null ? "null" : "*****") + '}';
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(name);
        out.writeVInt(privileges.size());
        for (var privilege : privileges) {
            privilege.writeTo(out);
        }
        out.writeCollection(grantedRoles);
        properties.writeTo(out);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(name);

        builder.startArray("privileges");
        for (Privilege privilege : privileges) {
            privilege.toXContent(builder, params);
        }
        builder.endArray();

        builder.startArray("granted_roles");
        for (var grantedRole : grantedRoles) {
            grantedRole.toXContent(builder, params);
        }
        builder.endArray();

        builder.startObject("properties");
        properties.toXContent(builder, params);
        builder.endObject();

        builder.endObject();
        return builder;
    }

    /**
     * A role is stored in the form of:
     * <p>
     *   "role1": {
     *     "privileges": [
     *       {"policy": 1, "permission": 2, "securable": 3, "ident": "some_table", "grantor": "grantor_username"},
     *       ...
     *     ],
     *     "granted_roles: [{"role1", "grantor1"}, {"role2", "grantor2"}],
     *     "properties" {
     *       "login" : true,
     *       "secure_hash": {
     *         "iterations": INT,
     *         "hash": BYTE[],
     *         "salt": BYTE[]
     *       }
     *     }
     *   }
     */
    public static Role fromXContent(XContentParser parser) throws IOException {
        if (parser.currentToken() != XContentParser.Token.FIELD_NAME) {
            throw new ElasticsearchParseException(
                "failed to parse a role, expecting the current token to be a field name, got " + parser.currentToken()
            );
        }

        String roleName = parser.currentName();
        Properties properties = null;
        Set<Privilege> privileges = new HashSet<>();
        Set<GrantedRole> grantedRoles = new HashSet<>();

        if (parser.nextToken() == XContentParser.Token.START_OBJECT) {
            while (parser.nextToken() == XContentParser.Token.FIELD_NAME) {
                switch (parser.currentName()) {
                    case "properties":
                        if (parser.nextToken() != XContentParser.Token.START_OBJECT) {
                            throw new ElasticsearchParseException(
                                "failed to parse a role, expected an start object token but got " + parser.currentToken()
                            );
                        }
                        properties = Properties.fromXContent(parser);
                        if (parser.currentToken() != XContentParser.Token.END_OBJECT) {
                            throw new ElasticsearchParseException(
                                "failed to parse a role, expected an end object token but got " + parser.currentToken()
                            );
                        }
                        break;
                    case "privileges":
                        if (parser.nextToken() != XContentParser.Token.START_ARRAY) {
                            throw new ElasticsearchParseException(
                                "failed to parse a role, expected an array token for privileges, got: " + parser.currentToken()
                            );
                        }
                        while (parser.nextToken() != XContentParser.Token.END_ARRAY) {
                            privileges.add(Privilege.fromXContent(parser));
                        }
                        break;
                    case "granted_roles":
                        if (parser.nextToken() != XContentParser.Token.START_ARRAY) {
                            throw new ElasticsearchParseException(
                                "failed to parse a role, expected an array token for granted_roles, got: " + parser.currentToken()
                            );
                        }
                        while (parser.nextToken() != XContentParser.Token.END_ARRAY) {
                            grantedRoles.add(GrantedRole.fromXContent(parser));
                        }
                        break;
                    default:
                        throw new ElasticsearchParseException(
                                "failed to parse a Role, unexpected field name: " + parser.currentName()
                        );
                }
            }
            if (parser.currentToken() != XContentParser.Token.END_OBJECT) {
                throw new ElasticsearchParseException(
                    "failed to parse a role, expected an object token at the end, got: " + parser.currentToken()
                );
            }
        }
        if (properties == null) {
            throw new ElasticsearchParseException("failed to parse role properties, not found");
        }
        return new Role(
            roleName,
            new RolePrivileges(privileges),
            grantedRoles,
            properties,
            false);
    }
}
