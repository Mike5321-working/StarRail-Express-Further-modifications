package com.habitrain.core.game.sre.role;

/**
 * Implemented by ephemeral role-spawned targetable props (such as {@code TailorDollEntity}
 * and {@code ReconDroneEntity}) so weapon targeting and hit dispatch mixins share a single
 * raycast and dispatch pipeline without copy-paste duplication.
 */
public interface AttackableRoleProp {
    boolean isAttackableRoleProp();
}
