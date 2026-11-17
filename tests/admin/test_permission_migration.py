import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("migration", ROOT / "scripts/migrate-admin-permissions.py")
migration = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(migration)


class PermissionMigrationTest(unittest.TestCase):
    def setUp(self):
        report = json.loads((ROOT / "tests/admin/fixtures/original-admin-permissions.json").read_text(encoding="utf-8-sig"))
        self.snapshot = migration.snapshot_from_report(report)

    def test_controller_matrix_matches_original_35_gaps_without_authorizing_roles(self):
        planned = migration.plan(self.snapshot)
        self.assertEqual(80, len(planned["requiredPermissions"]))
        self.assertEqual(35, len(planned["missingDefinitions"]))
        self.assertEqual([], planned["grants"])
        sql = migration.migration_sql(planned, self.snapshot)
        self.assertNotIn("INSERT INTO sys_role_menu", sql)
        self.assertNotIn("DELETE ", sql)
        self.assertNotIn("UPDATE ", sql)
        self.assertIn("START TRANSACTION;", sql)
        self.assertIn("NOT EXISTS", sql)
        self.assertLess(sql.index("'novel:bookContent:bookContent',1"), sql.index("'novel:bookContent:add',2"))

    def test_allowlist_requires_named_existing_role_and_omits_payment_deletion_and_user_writes(self):
        with self.assertRaisesRegex(ValueError, "supplied together"):
            migration.plan(self.snapshot, 5)
        with self.assertRaisesRegex(ValueError, "does not exist"):
            migration.plan(self.snapshot, 5, "content-editor")
        self.snapshot["roles"] = [dict(role_id=5)]
        planned = migration.plan(self.snapshot, 5, "content-editor")
        self.assertEqual(12, len(planned["grants"]))
        self.assertTrue(all(p.startswith(("novel:book:", "novel:bookIndex:", "novel:bookContent:")) for p in planned["grants"]))
        self.assertFalse(any(p.endswith((":remove", ":batchRemove")) for p in planned["grants"]))

    def test_completed_migration_has_no_missing_definitions_or_role_links(self):
        self.snapshot["roles"] = [dict(role_id=5)]
        planned = migration.plan(self.snapshot, 5, "content-editor")
        for index, permission in enumerate(planned["missingDefinitions"], 900):
            self.snapshot["menus"].append(dict(menu_id=index, parent_id=0, perms=permission, type=2))
        by_permission = migration.permission_index(self.snapshot)
        self.snapshot["roleMenus"] = [dict(role_id=5, menu_id=by_permission[p][0]["menu_id"]) for p in planned["grants"]]
        rerun = migration.plan(self.snapshot, 5, "content-editor")
        self.assertEqual([], rerun["missingDefinitions"])
        self.assertEqual([], rerun["missingGrants"])

    def test_duplicate_and_multi_permission_definitions_fail_closed(self):
        self.snapshot["menus"].append(copy.deepcopy(self.snapshot["menus"][0]))
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            migration.plan(self.snapshot)
        self.snapshot["menus"].pop()
        self.snapshot["roles"] = [dict(role_id=5)]
        self.snapshot["menus"].append(dict(menu_id=950, perms="novel:book:add,novel:pay:remove", parent_id=601))
        with self.assertRaisesRegex(ValueError, "multi-permission"):
            migration.plan(self.snapshot, 5, "content-editor")

    def test_affected_users_are_exactly_members_of_selected_role(self):
        self.snapshot["roles"] = [dict(role_id=5), dict(role_id=6)]
        self.snapshot["userRoles"] = [dict(role_id=5, user_id=11), dict(role_id=6, user_id=12), dict(role_id=5, user_id=11)]
        self.assertEqual([11], migration.plan(self.snapshot, 5, "content-reader")["affectedUserIds"])

    def test_legacy_null_role_links_are_preserved_and_never_authorize(self):
        self.snapshot["roles"] = [dict(role_id=5)]
        self.snapshot["roleMenus"] = [dict(role_id=5, menu_id=None), dict(role_id=None, menu_id=601)]
        self.snapshot["userRoles"] = [dict(role_id=5, user_id=None), dict(role_id=None, user_id=11)]
        before = copy.deepcopy(self.snapshot)
        planned = migration.plan(self.snapshot, 5, "content-reader")
        self.assertEqual(6, len(planned["missingGrants"]))
        self.assertEqual([], planned["affectedUserIds"])
        self.assertEqual(before, self.snapshot)

    def test_template_details_match_controller_module(self):
        templates = migration.template_permissions()
        self.assertNotIn("test:order:detail", templates)
        self.assertEqual(13, len([p for p in templates if p.endswith(":detail")]))
        for permission, paths in templates.items():
            for path in paths:
                self.assertEqual(permission.split(":")[1], Path(path.split(":")[0]).parent.name)


if __name__ == "__main__":
    unittest.main(verbosity=2)
