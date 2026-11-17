package com.java2nb.system.shiro;

import java.util.*;

import com.java2nb.common.config.ApplicationContextRegister;
import com.java2nb.system.dao.DataPermDao;
import com.java2nb.system.dao.DeptDao;
import com.java2nb.system.domain.DataPermDO;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.IncorrectCredentialsException;
import org.apache.shiro.authc.LockedAccountException;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.UnknownAccountException;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;

import com.java2nb.system.dao.SysUserDao;
import com.java2nb.system.domain.UserDO;
import com.java2nb.system.service.MenuService;

public class UserRealm extends AuthorizingRealm {
    public UserRealm() {
        // A new namespace avoids reusing legacy keys serialized from mutable UserDOs.
        setName("novel-admin-user-v2");
        setAuthorizationCacheName("novel-admin-user-v2.authorizationCache");
    }

    @Override
    protected Object getAuthorizationCacheKey(PrincipalCollection principals) {
        UserDO user = (UserDO) principals.getPrimaryPrincipal();
        return authorizationKey(user.getUserId());
    }

    private String authorizationKey(Long userId) {
        return "user:" + Objects.requireNonNull(userId, "userId");
    }

    public void clearAuthorizationCacheForUser(Long userId) {
        var cache = getAuthorizationCache();
        if (cache == null && getCacheManager() != null) {
            cache = getCacheManager().getCache(getAuthorizationCacheName());
        }
        if (cache != null) {
            cache.remove(authorizationKey(userId));
        }
    }
/*	@Autowired
	UserDao userMapper;
	@Autowired
	MenuService menuService;*/

    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection arg0) {
        Long userId = ((UserDO) arg0.getPrimaryPrincipal()).getUserId();
        MenuService menuService = ApplicationContextRegister.getBean(MenuService.class);
        Set<String> perms = menuService.listPerms(userId);
        SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();
        info.setStringPermissions(perms);
        return info;
    }

    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
        String username = (String) token.getPrincipal();
        Map<String, Object> map = new HashMap<>(16);
        map.put("username", username);
        String password = new String((char[]) token.getCredentials());

        SysUserDao userMapper = ApplicationContextRegister.getBean(SysUserDao.class);
        // 查询用户信息
        UserDO user = userMapper.list(map).get(0);

        // 账号不存在
        if (user == null) {
            throw new UnknownAccountException("账号或密码不正确");
        }

        // 密码错误
        if (!password.equals(user.getPassword())) {
            throw new IncorrectCredentialsException("账号或密码不正确");
        }

        // 账号锁定
        if (user.getStatus() == 0) {
            throw new LockedAccountException("账号已被锁定,请联系管理员");
        }

        //查询下级部门
        DeptDao deptDao = ApplicationContextRegister.getBean(DeptDao.class);
        user.setSupDeptIds(deptDao.getDeptIdsByParentId(user.getDeptId()));
        //查询数据权限
        DataPermDao dataPermDao = ApplicationContextRegister.getBean(DataPermDao.class);
        List<DataPermDO> dataPerms = dataPermDao.selectDataPermsByUserId(user.getUserId());
        Map<String, List<DataPermDO>> permsMap = new HashMap<>();
        for (DataPermDO perm : dataPerms) {
            String key = perm.getTableName();
            List<DataPermDO> value = permsMap.get(key);
            if (value == null) {
                value = new ArrayList<>();
                permsMap.put(key, value);
            }
            value.add(perm);

        }
        user.setDataPerms(permsMap);


        SimpleAuthenticationInfo info = new SimpleAuthenticationInfo(user, password, getName());
        return info;
    }

}
