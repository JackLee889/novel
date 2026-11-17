       
     


update crawl_source
set crawl_rule = replace(crawl_rule, 'ibiquzw.org', 'biquxs.info')
where id = 16;

delete
from sys_menu
where menu_id = 104;

delete
from sys_menu
where menu_id = 57;