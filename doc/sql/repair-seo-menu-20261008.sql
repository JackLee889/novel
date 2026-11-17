-- SEO 20261008: menu/permission definitions only. No roles are automatically expanded.
-- Shiro obtains permissions only through sys_user_role/sys_role_menu, including user 1.
-- Grant the nine SEO menu rows explicitly to a reviewed administrator role before rollout.
-- A fresh site's initial role 1 is a deployment-specific grant; existing roles are never auto-expanded here.
SET @seo_menu_lock = GET_LOCK(CONCAT('np-seo-menu-', MD5(DATABASE())), 15);
SET @seo_menu_guard = IF(@seo_menu_lock=1, 'SELECT 1', 'SELECT * FROM information_schema.novel_seo_menu_lock_refused');
PREPARE seo_menu_guard FROM @seo_menu_guard;
EXECUTE seo_menu_guard;
DEALLOCATE PREPARE seo_menu_guard;
SET @seo_menu_id = GREATEST(910000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified)
SELECT @seo_menu_id,0,'SEO管理','novel/seo','',1,80,NOW(),NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE url='novel/seo');
SET @seo_menu_parent = (SELECT MIN(menu_id) FROM sys_menu WHERE url='novel/seo');
SET @seo_menu_id = GREATEST(910000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified)
SELECT @seo_menu_id,@seo_menu_parent,'SEO站点设置查看','','seo:config:view',2,0,NOW(),NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('seo:config:view',REPLACE(perms,' ',''))>0);
SET @seo_menu_id = GREATEST(910000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified)
SELECT @seo_menu_id,@seo_menu_parent,'SEO站点设置修改','','seo:config:edit',2,1,NOW(),NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('seo:config:edit',REPLACE(perms,' ',''))>0);
SET @seo_menu_id = GREATEST(910000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified)
SELECT @seo_menu_id,@seo_menu_parent,'SEO书籍查看','','seo:book:view',2,2,NOW(),NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('seo:book:view',REPLACE(perms,' ',''))>0);
SET @seo_menu_id = GREATEST(910000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified)
SELECT @seo_menu_id,@seo_menu_parent,'SEO书籍修改','','seo:book:edit',2,3,NOW(),NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('seo:book:edit',REPLACE(perms,' ',''))>0);
SET @seo_menu_id = GREATEST(910000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified)
SELECT @seo_menu_id,@seo_menu_parent,'SEO地图查看','','seo:sitemap:view',2,4,NOW(),NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('seo:sitemap:view',REPLACE(perms,' ',''))>0);
SET @seo_menu_id = GREATEST(910000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified)
SELECT @seo_menu_id,@seo_menu_parent,'SEO地图重建','','seo:sitemap:rebuild',2,5,NOW(),NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('seo:sitemap:rebuild',REPLACE(perms,' ',''))>0);
SET @seo_menu_id = GREATEST(910000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified)
SELECT @seo_menu_id,@seo_menu_parent,'SEO诊断查看','','seo:diagnosis:view',2,6,NOW(),NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('seo:diagnosis:view',REPLACE(perms,' ',''))>0);
SET @seo_menu_id = GREATEST(910000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;
INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified)
SELECT @seo_menu_id,@seo_menu_parent,'SEO诊断运行','','seo:diagnosis:run',2,7,NOW(),NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET('seo:diagnosis:run',REPLACE(perms,' ',''))>0);
SELECT RELEASE_LOCK(CONCAT('np-seo-menu-', MD5(DATABASE())));
