package com.java2nb.system.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import com.java2nb.system.dao.*;
import com.java2nb.system.domain.RoleDataPermDO;
import com.java2nb.system.shiro.AuthorizationCacheInvalidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.java2nb.system.domain.RoleDO;
import com.java2nb.system.domain.RoleMenuDO;
import com.java2nb.system.service.RoleService;


@Service
public class RoleServiceImpl implements RoleService {

    public static final String ROLE_ALL_KEY = "\"role_all\"";

    public static final String DEMO_CACHE_NAME = "role";

    @Autowired
    RoleDao roleMapper;
    @Autowired
    RoleMenuDao roleMenuMapper;
    @Autowired
    SysUserDao userMapper;
    @Autowired
    UserRoleDao userRoleMapper;
    @Autowired
    RoleDataPermDao roleDataPermDao;

    @Autowired
    AuthorizationCacheInvalidator authorizationCacheInvalidator;

    @Override
    public List<RoleDO> list() {
        List<RoleDO> roles = roleMapper.list(new HashMap<>(16));
        return roles;
    }


    @Override
    public List<RoleDO> list(Long userId) {
        List<Long> rolesIds = userRoleMapper.listRoleId(userId);
        List<RoleDO> roles = roleMapper.list(new HashMap<>(16));
        for (RoleDO roleDO : roles) {
            roleDO.setRoleSign("false");
            for (Long roleId : rolesIds) {
                if (Objects.equals(roleDO.getRoleId(), roleId)) {
                    roleDO.setRoleSign("true");
                    break;
                }
            }
        }
        return roles;
    }
    @Transactional
    @Override
    public int save(RoleDO role) {
        int count = roleMapper.save(role);
        List<Long> menuIds = role.getMenuIds();
        Long roleId = role.getRoleId();
        List<RoleMenuDO> rms = new ArrayList<>();
        for (Long menuId : menuIds) {
            RoleMenuDO rmDo = new RoleMenuDO();
            rmDo.setRoleId(roleId);
            rmDo.setMenuId(menuId);
            rms.add(rmDo);
        }
        roleMenuMapper.removeByRoleId(roleId);
        if (rms.size() > 0) {
            roleMenuMapper.batchSave(rms);
        }
        List<Long> permIds = role.getPermIds();
        if(permIds!=null) {
            List<RoleDataPermDO> rps = new ArrayList<>();
            for (Long permId : permIds) {
                RoleDataPermDO rpDo = new RoleDataPermDO();
                rpDo.setRoleId(roleId);
                rpDo.setPermId(permId);
                rps.add(rpDo);
            }
            roleDataPermDao.removeByRoleId(roleId);
            if (rps.size() > 0) {
                roleDataPermDao.batchSave(rps);
            }
        }
        return count;
    }

    @Transactional
    @Override
    public int remove(Long id) {
        List<Long> affectedUsers = userRoleMapper.listUserIdsByRoleId(id);
        int count = roleMapper.remove(id);
        userRoleMapper.removeByRoleId(id);
        roleMenuMapper.removeByRoleId(id);
        authorizationCacheInvalidator.afterCommit(affectedUsers);
        return count;
    }

    @Override
    public RoleDO get(Long id) {
        RoleDO roleDO = roleMapper.get(id);
        return roleDO;
    }

    @Transactional
    @Override
    public int update(RoleDO role) {
        List<Long> affectedUsers = userRoleMapper.listUserIdsByRoleId(role.getRoleId());
        int r = roleMapper.update(role);
        List<Long> menuIds = role.getMenuIds();
        Long roleId = role.getRoleId();
        roleMenuMapper.removeByRoleId(roleId);
        List<RoleMenuDO> rms = new ArrayList<>();
        for (Long menuId : menuIds) {
            RoleMenuDO rmDo = new RoleMenuDO();
            rmDo.setRoleId(roleId);
            rmDo.setMenuId(menuId);
            rms.add(rmDo);
        }
        if (rms.size() > 0) {
            roleMenuMapper.batchSave(rms);
        }
        List<Long> permIds = role.getPermIds();
        if(permIds!=null) {
            List<RoleDataPermDO> rps = new ArrayList<>();
            for (Long permId : permIds) {
                RoleDataPermDO rpDo = new RoleDataPermDO();
                rpDo.setRoleId(roleId);
                rpDo.setPermId(permId);
                rps.add(rpDo);
            }
            roleDataPermDao.removeByRoleId(roleId);
            if (rps.size() > 0) {
                roleDataPermDao.batchSave(rps);
            }
        }
        authorizationCacheInvalidator.afterCommit(affectedUsers);
        return r;
    }

    @Transactional
    @Override
    public int batchremove(Long[] ids) {
        int count = 0;
        for (Long id : ids) {
            // Reuse the single-role path so orphan bindings cannot retain permission.
            // The enclosing transaction covers every role and after-commit eviction.
            count += remove(id);
        }
        return count;
    }

}
