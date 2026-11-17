"""Source contracts for the additive SEO menu and safe admin integration. No services or DB."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ADMIN = ROOT / "novel-admin/src/main"

class SeoContractTest(unittest.TestCase):
    def test_menu_permissions_match_controller_and_buttons(self):
        controller = (ADMIN / "java/com/java2nb/novel/controller/SeoController.java").read_text(encoding="utf-8")
        template = (ADMIN / "resources/templates/novel/seo/manage.html").read_text(encoding="utf-8")
        sql = (ROOT / "doc/sql/repair-seo-menu-20261008.sql").read_text(encoding="utf-8")
        permissions = set(re.findall(r"seo:[a-z]+:[a-z]+", controller))
        self.assertEqual(8, len(permissions))
        self.assertEqual(permissions, set(re.findall(r"seo:[a-z]+:[a-z]+", template)))
        self.assertEqual(permissions, set(re.findall(r"seo:[a-z]+:[a-z]+", sql)))
        self.assertEqual(9, len(re.findall(r"INSERT INTO sys_menu", sql)))
        for line in sql.splitlines():
            if line.lstrip().startswith("--"):
                continue
            self.assertNotRegex(line, r"(?i)\b(DROP|DELETE|TRUNCATE|UPDATE)\b")
            self.assertNotRegex(line, r"(?i)INSERT\s+INTO\s+(sys_role_menu|sys_role|sys_user)\b")
        self.assertEqual(9, sql.count("WHERE NOT EXISTS"))

    def test_all_seo_writes_have_permission_checks_and_same_origin_scope(self):
        controller = (ADMIN / "java/com/java2nb/novel/controller/SeoController.java").read_text(encoding="utf-8")
        writes = [line for line in controller.splitlines() if "@PostMapping" in line]
        self.assertEqual(4, len(writes))
        self.assertTrue(all("@RequiresPermissions" in line and "@Log" in line for line in writes))
        config = (ADMIN / "java/com/java2nb/common/config/SeoAdminWebConfiguration.java").read_text(encoding="utf-8")
        self.assertIn('addPathPatterns("/novel/seo", "/novel/seo/**")', config)
        client = (ADMIN / "resources/static/js/appjs/novel/seo/manage.js").read_text(encoding="utf-8")
        self.assertIn("X-CSRF-TOKEN", client)
        self.assertIn("X-Requested-With", client)
        self.assertNotIn("innerHTML", client)

    def test_shared_portal_advice_is_not_component_scanned_into_admin(self):
        source = (ADMIN / "java/com/java2nb/AdminApplication.java").read_text(encoding="utf-8")
        scans = re.search(r"scanBasePackages\s*=\s*\{([^}]+)\}", source).group(1)
        self.assertIn('"com.java2nb.novel.seo"', scans)
        self.assertNotIn('"com.java2nb"', scans)
        self.assertNotIn('"com.java2nb.novel"', scans)
        self.assertNotIn('"com.java2nb.novel.core"', scans)

if __name__ == "__main__":
    unittest.main()
