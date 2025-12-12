package com.megatrex4;

import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.command.permission.PermissionPredicate;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

public class Permissions {

    /**
     * Check permission level for a player
     */
    public static boolean hasPermissionLevel(ServerPlayerEntity player, PermissionLevel requiredLevel) {
        PermissionPredicate permissions = player.getPermissions();
        Permission.Level levelPermission = new Permission.Level(requiredLevel);
        return permissions.hasPermission(levelPermission);
    }

    /**
     * Check permission level for a command source (for use in .requires())
     */
    public static boolean hasPermissionLevel(ServerCommandSource source, PermissionLevel requiredLevel) {
        PermissionPredicate permissions = source.getPermissions();
        Permission.Level levelPermission = new Permission.Level(requiredLevel);
        return permissions.hasPermission(levelPermission);
    }
}
