package com.java2nb.system.service.impl;

import com.java2nb.system.dao.*;
import com.java2nb.system.domain.MenuDO;
import com.java2nb.system.domain.RoleDO;
import com.java2nb.system.domain.UserDO;
import com.java2nb.system.shiro.AuthorizationCacheInvalidator;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;

class PermissionMutationTest {
    @Test void bulkRoleRemovalUsesTransactionalSingleRoleCleanupRatherThanLeavingOrphanGrants() throws Exception {
        var service = new RoleServiceImpl();
        service.roleMapper = mock(RoleDao.class);
        service.roleMenuMapper = mock(RoleMenuDao.class);
        service.userRoleMapper = mock(UserRoleDao.class);
        service.authorizationCacheInvalidator = mock(AuthorizationCacheInvalidator.class);
        when(service.roleMapper.remove(anyLong())).thenReturn(1);
        when(service.userRoleMapper.listUserIdsByRoleId(5L)).thenReturn(List.of(11L));
        when(service.userRoleMapper.listUserIdsByRoleId(6L)).thenReturn(List.of(12L));
        org.junit.jupiter.api.Assertions.assertEquals(2, service.batchremove(new Long[]{5L, 6L}));
        var order = inOrder(service.userRoleMapper, service.roleMapper, service.roleMenuMapper, service.authorizationCacheInvalidator);
        for (long id : new long[]{5L, 6L}) {
            order.verify(service.userRoleMapper).listUserIdsByRoleId(id);
            order.verify(service.roleMapper).remove(id);
            order.verify(service.userRoleMapper).removeByRoleId(id);
            order.verify(service.roleMenuMapper).removeByRoleId(id);
            order.verify(service.authorizationCacheInvalidator).afterCommit(List.of(id + 6L));
        }
        verify(service.roleMapper, never()).batchRemove(any());
        org.junit.jupiter.api.Assertions.assertNotNull(RoleServiceImpl.class.getMethod("batchremove", Long[].class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class));
    }

    @Test void roleUpdateAndRemovalCaptureAffectedMembersBeforeBindingsChange() {
        var service = new RoleServiceImpl();
        service.roleMapper = mock(RoleDao.class);
        service.roleMenuMapper = mock(RoleMenuDao.class);
        service.userRoleMapper = mock(UserRoleDao.class);
        service.authorizationCacheInvalidator = mock(AuthorizationCacheInvalidator.class);
        when(service.userRoleMapper.listUserIdsByRoleId(5L)).thenReturn(List.of(11L, 12L));
        var role = new RoleDO(); role.setRoleId(5L); role.setMenuIds(List.of(8L));
        service.update(role);
        verify(service.authorizationCacheInvalidator).afterCommit(List.of(11L, 12L));
        reset(service.authorizationCacheInvalidator);
        service.remove(5L);
        var order = inOrder(service.userRoleMapper, service.authorizationCacheInvalidator);
        order.verify(service.userRoleMapper, times(2)).listUserIdsByRoleId(5L);
        order.verify(service.userRoleMapper).removeByRoleId(5L);
        order.verify(service.authorizationCacheInvalidator).afterCommit(List.of(11L, 12L));
    }

    @Test void menuPermissionUpdateAndRemovalInvalidateOnlyLinkedUsers() {
        var service = new MenuServiceImpl();
        service.menuMapper = mock(MenuDao.class); service.userRoleMapper = mock(UserRoleDao.class);
        service.authorizationCacheInvalidator = mock(AuthorizationCacheInvalidator.class);
        when(service.userRoleMapper.listUserIdsByMenuId(8L)).thenReturn(List.of(11L));
        var menu = new MenuDO(); menu.setMenuId(8L); menu.setPerms("novel:book:add");
        service.update(menu); service.remove(8L);
        verify(service.authorizationCacheInvalidator, times(2)).afterCommit(List.of(11L));
        verify(service.userRoleMapper, times(2)).listUserIdsByMenuId(8L);
    }

    @Test void assigningDifferentRolesAlsoInvalidatesTheExistingUsersSession() {
        var service = new SysUserServiceImpl();
        service.userMapper = mock(SysUserDao.class); service.userRoleMapper = mock(UserRoleDao.class);
        service.authorizationCacheInvalidator = mock(AuthorizationCacheInvalidator.class);
        var user = new UserDO(); user.setUserId(11L); user.setRoleIds(List.of(5L));
        service.update(user);
        verify(service.authorizationCacheInvalidator).afterCommit(List.of(11L));
    }
}
