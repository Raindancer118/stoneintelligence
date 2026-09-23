package de.raindancer118.stoneintelligence.platform.invitation;

import java.util.EnumSet;
import java.util.Set;
import de.raindancer118.stoneintelligence.platform.identity.Permission;

/**
 * Die zwei Stufen, die man beim Einladen waehlt. Bewusst ohne MANAGE: eingeladene Personen
 * bearbeiten mit, verwalten aber keine Mitglieder - das bleibt eine ausdrueckliche Entscheidung
 * im Rollen-Editor.
 */
public enum InviteAccess {
    EDIT("Mitbearbeiter", EnumSet.of(Permission.READ, Permission.WRITE, Permission.CREATE, Permission.DELETE)),
    READ("Leser", EnumSet.of(Permission.READ));

    private final String groupName;
    private final Set<Permission> permissions;

    InviteAccess(String groupName, Set<Permission> permissions) {
        this.groupName = groupName;
        this.permissions = permissions;
    }

    public String groupName() {
        return groupName;
    }

    public Set<Permission> permissions() {
        return permissions;
    }

    public String label() {
        return this == EDIT ? "bearbeiten" : "lesen";
    }
}
