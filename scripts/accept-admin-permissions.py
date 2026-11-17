"""Real HTTP permission acceptance for a dedicated novel-bugfix test deployment.

Creates only retained test roles/accounts, applies the additive migration, and
changes only their role configuration and newly inserted test menu definitions.
The real CAPTCHA, CSRF, Shiro sessions and permission advisor remain enabled.
"""
import argparse
import datetime
import hashlib
import importlib.util
import json
import re
import secrets
import shutil
import subprocess
from pathlib import Path
from urllib.parse import urlparse
import requests

ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location("migration", ROOT / "scripts/migrate-admin-permissions.py")
migration = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(migration)

# Retained deployments can contain orphan role references beyond AUTO_INCREMENT.
# Fixture IDs must avoid both defined roles and all existing role references.
SAFE_ROLE_ID_SQL = ("SET @fixture_role = GREATEST(900000000, "
    "COALESCE((SELECT MAX(role_id) FROM sys_role),0), "
    "COALESCE((SELECT MAX(role_id) FROM sys_role_menu),0), "
    "COALESCE((SELECT MAX(role_id) FROM sys_user_role),0)) + 1; ")


def password_hash(username, password):
    first = hashlib.md5((username + "1qazxsw2").encode() + password.encode()).digest()
    return hashlib.md5(first).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--base", required=True)
    parser.add_argument("--database", default="novel_plus")
    parser.add_argument("--apply-isolated-container", required=True)
    parser.add_argument("--resume-fixtures", action="store_true", help="Reuse retained fixtures and original snapshot after an interrupted attempt")
    args = parser.parse_args()
    cfg = json.loads(args.runtime.read_text(encoding="utf-8-sig"))
    suffix = re.fullmatch(r"novel-bugfix-mysql-(\d{8}-\d{6})", cfg["mysql"])
    if not suffix or args.apply_isolated_container != cfg["mysql"]:
        parser.error("Only the explicitly named novel-bugfix-mysql test container is allowed")
    if cfg["redis"] != "novel-bugfix-redis-" + suffix.group(1) or cfg["admin"] != "novel-bugfix-admin-" + suffix.group(1):
        parser.error("Admin/MySQL/Redis must belong to the same dedicated test run")
    url = urlparse(args.base)
    if url.scheme != "http" or url.hostname not in {"127.0.0.1", "localhost"} or not url.port:
        parser.error("Real acceptance requires an explicit loopback test URL")
    # Verify URL maps to the declared dedicated container rather than an existing local service.
    port_info = subprocess.run(["docker", "port", cfg["admin"]], capture_output=True, text=True, encoding="utf-8", errors="replace", check=True).stdout
    if f":{url.port}" not in port_info:
        parser.error("HTTP port does not match the declared test admin container")
    args.out.mkdir(parents=True, exist_ok=True)
    previous_report = args.out / "admin-permission-acceptance.json"
    if previous_report.exists():
        shutil.copy2(previous_report, args.out / ("admin-permission-acceptance-attempt-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S") + ".json"))
    report = dict(started=datetime.datetime.now(datetime.timezone.utc).isoformat(), checks=[], isolatedContainers=True,
                  originalBusinessWrites=False, businessRowsDeleted=False, testAccountsRetained=True)

    def save():
        (args.out / "admin-permission-acceptance.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    def record(name, detail=None):
        report["checks"].append(dict(name=name, passed=True, **(detail or {})))
        print("PASS " + name)
        save()

    def query(sql):
        return [json.loads(line) for line in migration.mysql(cfg["mysql"], args.database, sql).splitlines() if line.startswith("{")]

    def login(username, password):
        session = requests.Session()
        response = session.get(args.base + "/login", timeout=20)
        assert response.status_code == 200
        token = re.search(r'name="_csrf"\s+content="([^"]+)"', response.text).group(1)
        assert session.get(args.base + "/getVerify", timeout=20).status_code == 200
        # Observe the actual challenge on this owned isolated server, as prior UI tests do.
        logs = subprocess.run(["docker", "logs", "--tail", "40", cfg["admin"]], capture_output=True, text=True,
                              encoding="utf-8", errors="replace", check=True).stdout
        code = re.findall(r"RandomValidateCodeUtil\s*:\s*(\d{4})", logs)[-1]
        result = session.post(args.base + "/login", headers={"X-CSRF-TOKEN": token},
                              data=dict(username=username, password=password, verify=code), timeout=20)
        assert result.status_code == 200 and result.json()["code"] == 0, ("Real admin login failed: "
                + str(result.status_code) + " " + result.text[:200])
        response = session.get(args.base + "/index", timeout=20)
        assert response.status_code == 200
        token = re.search(r'name="_csrf"\s+content="([^"]+)"', response.text).group(1)
        return session, token

    def expect(session, method, path, status, token=None, data=None):
        response = session.request(method, args.base + path, headers={"X-CSRF-TOKEN": token} if token else {},
                                   data=data, allow_redirects=False, timeout=25)
        assert response.status_code == status, f"{method} {path}: expected {status}, got {response.status_code}"
        return response

    def role_update(operator, token, role, permissions, snapshot):
        index = migration.permission_index(snapshot)
        data = [("roleId", str(role)), ("roleName", "Bugfix content editor"), ("roleSign", "bugfix_content_editor"),
                ("remark", "Retained isolated acceptance configuration")]
        data += [("menuIds", str(index[p][0]["menu_id"])) for p in permissions]
        result = expect(operator, "POST", "/sys/role/update", 200, token, data)
        assert result.json()["code"] == 0

    credentials_file = args.out / "admin-role-fixtures.private.json"
    before_file = args.out / "before-permission-snapshot.json"
    if credentials_file.exists() and not args.resume_fixtures:
        parser.error("Existing fixtures are retained; use --resume-fixtures to continue")
    if args.resume_fixtures:
        if not credentials_file.exists() or not before_file.exists():
            parser.error("Resume requires retained credentials and the original permission snapshot")
        before = json.loads(before_file.read_text(encoding="utf-8-sig"))
    else:
        before = migration.live_snapshot(cfg["mysql"], args.database)
    base_role_links = before["roleMenus"]
    if not args.resume_fixtures:
        before_file.write_text(json.dumps(before, ensure_ascii=False, indent=2), encoding="utf-8")
    try:
        initial = migration.plan(before)
        assert len(initial["missingDefinitions"]) == 35, "Must start with the preserved original permission configuration"
        record("original 35 permission gaps reproduced", dict(required=80, missing=35))
        fixture_suffix = datetime.datetime.now().strftime("%Y%m%d%H%M%S") + secrets.token_hex(2)
        accounts = (json.loads(credentials_file.read_text(encoding="utf-8-sig")) if args.resume_fixtures else
                    {kind: dict(username="bugfix_" + kind + "_" + fixture_suffix, password=secrets.token_urlsafe(18))
                     for kind in ("editor", "restricted")})
        fixture_dept = int(query("SELECT JSON_OBJECT('deptId',MIN(dept_id)) FROM sys_dept;")[0]["deptId"])
        book_listing = migration.permission_index(before)["novel:book:book"][0]["menu_id"]
        for kind, account in ([] if args.resume_fixtures else accounts.items()):
            sql = ("START TRANSACTION; " + SAFE_ROLE_ID_SQL +
                f"INSERT INTO sys_role(role_id,role_name,role_sign,remark,user_id_create,gmt_create,gmt_modified) VALUES(@fixture_role,'Bugfix {kind}','bugfix_{kind}_{fixture_suffix}','Retained isolated permission test',1,NOW(),NOW()); "
                f"INSERT INTO sys_role_menu(role_id,menu_id) VALUES(@fixture_role,{book_listing}); "
                f"INSERT INTO sys_user(username,name,password,dept_id,status,user_id_create,gmt_create,gmt_modified) "
                f"VALUES('{account['username']}','Bugfix {kind}','{password_hash(account['username'], account['password'])}',{fixture_dept},1,1,NOW(),NOW()); "
                "SET @fixture_user=LAST_INSERT_ID(); INSERT INTO sys_user_role(user_id,role_id) VALUES(@fixture_user,@fixture_role); "
                "COMMIT; SELECT JSON_OBJECT('roleId',@fixture_role,'userId',@fixture_user);")
            account.update(query(sql)[0])
        if not args.resume_fixtures:
            credentials_file.write_text(json.dumps(accounts, indent=2), encoding="utf-8")
        if "operator" not in accounts:
            assert re.fullmatch(r"[A-Za-z0-9_.@-]+", cfg["username"])
            assert query("SELECT JSON_OBJECT('count',COUNT(*)) FROM sys_user WHERE username='" + cfg["username"] + "';")[0]["count"] == 0
            operator_sql = ("START TRANSACTION; " + SAFE_ROLE_ID_SQL +
                "INSERT INTO sys_role(role_id,role_name,role_sign,remark,user_id_create,gmt_create,gmt_modified) "
                f"VALUES(@fixture_role,'Bugfix QA only','bugfix_qa_{fixture_suffix}','Explicit finite UI test permissions only',1,NOW(),NOW()); "
                "INSERT INTO sys_role_menu(role_id,menu_id) SELECT @fixture_role,menu_id FROM sys_menu WHERE COALESCE(perms,'')<>'*'; "
                "INSERT INTO sys_user(username,name,password,dept_id,status,user_id_create,gmt_create,gmt_modified) "
                f"VALUES('{cfg['username']}','Bugfix QA operator','{password_hash(cfg['username'], cfg['password'])}',{fixture_dept},1,1,NOW(),NOW()); "
                "SET @fixture_user=LAST_INSERT_ID(); INSERT INTO sys_user_role(user_id,role_id) VALUES(@fixture_user,@fixture_role); "
                "COMMIT; SELECT JSON_OBJECT('roleId',@fixture_role,'userId',@fixture_user);")
            accounts["operator"] = dict(username=cfg["username"], **query(operator_sql)[0])
            credentials_file.write_text(json.dumps(accounts, indent=2), encoding="utf-8")
            cfg["userId"] = accounts["operator"]["userId"]
            cfg["qaRoleId"] = accounts["operator"]["roleId"]
            args.runtime.write_text(json.dumps(cfg, indent=2), encoding="utf-8")
        for account in accounts.values():
            query(f"UPDATE sys_user SET dept_id={fixture_dept} WHERE user_id={account['userId']} AND username='{account['username']}';")
        operator, operator_token = login(cfg["username"], cfg["password"])
        if args.resume_fixtures:
            role_update(operator, operator_token, accounts["editor"]["roleId"], ["novel:book:book"], before)
            role_update(operator, operator_token, accounts["restricted"]["roleId"], ["novel:book:book"], before)
        editor_a, editor_token = login(accounts["editor"]["username"], accounts["editor"]["password"])
        editor_b, _ = login(accounts["editor"]["username"], accounts["editor"]["password"])
        restricted, restricted_token = login(accounts["restricted"]["username"], accounts["restricted"]["password"])
        for session in (editor_a, editor_b, restricted):
            expect(session, "GET", "/novel/book/add", 403)
        record("real CAPTCHA CSRF login and denied existing sessions")
        snapshot = migration.live_snapshot(cfg["mysql"], args.database)
        planned = migration.plan(snapshot, accounts["editor"]["roleId"], "content-editor")
        migration.evidence(args.out / "migration", planned, snapshot)
        query(migration.migration_sql(planned, snapshot))
        after_migration = migration.live_snapshot(cfg["mysql"], args.database)
        rerun = migration.plan(after_migration, accounts["editor"]["roleId"], "content-editor")
        assert not rerun["missingDefinitions"] and not rerun["missingGrants"]
        query(migration.migration_sql(rerun, after_migration))
        after_rerun = migration.live_snapshot(cfg["mysql"], args.database)
        assert after_migration == after_rerun
        migration.evidence(args.out / "migration-after", rerun, after_rerun)
        record("35 additive definitions and explicit 12 permissions; repeat is idempotent")
        # Finite full permissions are confined to the new QA role for page coverage.
        qa_data = [("roleId", str(accounts["operator"]["roleId"])), ("roleName", "Bugfix QA only"),
                   ("roleSign", "bugfix_qa_only"), ("remark", "Explicit UI test fixture, not original role remediation")]
        qa_data += [("menuIds", str(m["menu_id"])) for m in after_rerun["menus"] if (m.get("perms") or "") != "*"]
        qa_result = expect(operator, "POST", "/sys/role/update", 200, operator_token, qa_data)
        assert qa_result.json()["code"] == 0
        report["qaFixture"] = dict(roleId=accounts["operator"]["roleId"], userId=accounts["operator"]["userId"],
                                    definedNovelPermissions=80, wildcard=False, originalRolesExpanded=False)
        record("explicit full page QA permissions confined to a new test role")
        # Apply through the real service to verify commit-driven eviction for the old sessions.
        role = accounts["editor"]["roleId"]
        role_update(operator, operator_token, role, sorted(migration.CONTENT_EDITOR), after_rerun)
        for session in (editor_a, editor_b):
            for module in ("book", "bookIndex", "bookContent"):
                expect(session, "GET", f"/novel/{module}/add", 200)
        expect(restricted, "GET", "/novel/book/add", 403)
        record("role grant refreshes both existing editor sessions; restricted stays denied")
        granted_html = expect(editor_a, "GET", "/novel/book", 200).text
        denied_html = expect(restricted, "GET", "/novel/book", 200).text
        assert re.search(r"s_detail_h\s*=\s*''", granted_html)
        assert not re.search(r"s_detail_h\s*=\s*''", denied_html)
        record("rendered detail visibility matches novel book permission")
        sentinel_id = "9223372036854775807"
        for module in ("book", "bookIndex", "bookContent"):
            expect(editor_a, "POST", f"/novel/{module}/remove", 403, editor_token, {"id": sentinel_id})
        for module in ("pay", "user", "userFeedback"):
            expect(editor_a, "GET", f"/novel/{module}/add", 403)
            expect(editor_a, "POST", f"/novel/{module}/save", 403, editor_token, {})
        expect(restricted, "POST", "/novel/book/save", 403, restricted_token, {})
        record("payment user feedback writes and all destructive submissions remain denied")
        without_add = sorted(migration.CONTENT_EDITOR - {"novel:book:add"})
        role_update(operator, operator_token, role, without_add, after_rerun)
        for session in (editor_a, editor_b):
            expect(session, "GET", "/novel/book/add", 403)
        record("role revoke immediately rejects both existing sessions")
        role_update(operator, operator_token, role, sorted(migration.CONTENT_EDITOR), after_rerun)
        for session in (editor_a, editor_b):
            expect(session, "GET", "/novel/book/add", 200)
        add_id = migration.permission_index(after_rerun)["novel:book:add"][0]["menu_id"]
        assert int(add_id) not in {int(m["menu_id"]) for m in before["menus"]}
        response = expect(operator, "POST", "/sys/menu/update", 200, operator_token,
                          {"menuId": str(add_id), "perms": "novel:bugfixTemporary:add"})
        assert response.json()["code"] == 0
        for session in (editor_a, editor_b):
            expect(session, "GET", "/novel/book/add", 403)
        response = expect(operator, "POST", "/sys/menu/update", 200, operator_token,
                          {"menuId": str(add_id), "perms": "novel:book:add"})
        assert response.json()["code"] == 0
        for session in (editor_a, editor_b):
            expect(session, "GET", "/novel/book/add", 200)
        record("menu permission change and restoration refresh existing sessions")
        final = migration.live_snapshot(cfg["mysql"], args.database)
        original_link_ids = {r["id"] for r in base_role_links}
        assert sorted([r for r in final["roleMenus"] if r["id"] in original_link_ids], key=lambda r: r["id"]) == sorted(base_role_links, key=lambda r: r["id"])
        assert [m for m in final["menus"] if int(m["menu_id"]) in {int(m["menu_id"]) for m in before["menus"]}] == before["menus"]
        record("preexisting role grants and menu definitions unchanged; test fixtures retained")
        report["passed"] = True
    except Exception as error:
        report["passed"] = False
        report["error"] = str(error)
        save()
        raise
    finally:
        save()


if __name__ == "__main__":
    main()
