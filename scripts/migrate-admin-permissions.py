"""Preview/apply additive permission definitions and an explicit content role allowlist.

Defaults to preview. Writes are restricted to a named bugfix test container and
must be acknowledged with --apply-isolated-container matching --container.
No original rows are updated/deleted, and no wildcard role is created.
"""
import argparse
import csv
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONTENT_EDITOR = frozenset(f"novel:{module}:{action}" for module in ("book", "bookIndex", "bookContent")
                           for action in (module, "detail", "add", "edit"))
CONTENT_READER = frozenset(f"novel:{module}:{action}" for module in ("book", "bookIndex", "bookContent")
                           for action in (module, "detail"))
POLICIES = {"content-editor": CONTENT_EDITOR, "content-reader": CONTENT_READER}
LABELS = {"book": "书籍", "bookIndex": "章节目录", "bookContent": "正文", "bookComment": "评论",
          "user": "用户", "userFeedback": "反馈", "pay": "支付"}
ACTIONS = {"add": "新增", "edit": "修改", "detail": "查看", "remove": "删除", "batchRemove": "批量删除"}


def controller_routes(root=ROOT):
    rows = []
    for path in sorted((root / "novel-admin/src/main/java/com/java2nb/novel/controller").glob("*.java")):
        text = path.read_text(encoding="utf-8-sig")
        class_prefix = text[:text.index("public class")]
        class_route = re.findall(r'@RequestMapping\(\s*"([^"]*)"', class_prefix)
        prefix = class_route[-1] if class_route else ""
        annotations = []
        for number, line in enumerate(text.splitlines(), 1):
            if line.strip().startswith("@"):
                annotations.append(line.strip())
            elif line.rstrip().endswith("{"):
                block = "\n".join(annotations)
                permissions = re.findall(r'@RequiresPermissions\(\s*"([^"]+)"', block)
                mapping = re.search(r'@(GetMapping|PostMapping|RequestMapping)(?:\(([^\n]*)\))?', block)
                if permissions and mapping:
                    route = re.search(r'"([^"]*)"', mapping.group(2) or "")
                    verb = {"GetMapping": "GET", "PostMapping": "POST", "RequestMapping": "ANY"}[mapping.group(1)]
                    for permission in permissions:
                        rows.append(dict(permission=permission, method=verb, route=prefix + (route.group(1) if route else ""),
                                         source=path.relative_to(root).as_posix(), line=number))
                annotations = []
            elif line.strip().endswith(";"):
                annotations = []
    if not rows:
        raise ValueError("No permission-protected controller routes found")
    return rows


def template_permissions(root=ROOT):
    rows = {}
    for path in sorted((root / "novel-admin/src/main/resources/templates/novel").rglob("*.html")):
        for number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
            for permission in re.findall(r'shiro:hasPermission="([^"]+)"', line):
                rows.setdefault(permission, []).append(f"{path.relative_to(root).as_posix()}:{number}")
    return rows


def snapshot_from_report(report):
    # Offline report mode retains historical evidence; live mode supplies real parents/roles.
    return dict(menus=[dict(menu_id=int(key), parent_id=0, name=perms, perms=perms, type=1)
                       for key, perms in report["definedNovelPermissionRecords"]], roles=[], roleMenus=[], userRoles=[], historical=True)


def permission_index(snapshot):
    index = {}
    for menu in snapshot["menus"]:
        for permission in (menu.get("perms") or "").split(","):
            if permission.strip():
                index.setdefault(permission.strip(), []).append(menu)
    return index


def plan(snapshot, role_id=None, policy=None, root=ROOT):
    if (role_id is None) != (policy is None):
        raise ValueError("--role-id and --policy must be supplied together")
    routes = controller_routes(root)
    required = sorted({row["permission"] for row in routes})
    index = permission_index(snapshot)
    duplicates = {p: [m["menu_id"] for m in menus] for p, menus in index.items() if len(menus) > 1}
    if duplicates:
        raise ValueError("Duplicate permission definitions require review: " + json.dumps(duplicates))
    grants = sorted(POLICIES[policy]) if policy else []
    if role_id is not None and not any(int(role["role_id"]) == role_id for role in snapshot["roles"]):
        raise ValueError("Target role does not exist")
    if set(grants) - set(required):
        raise ValueError("Policy contains permissions not declared by controllers")
    for permission in grants:
        menu = index.get(permission, [])
        if menu and len([p for p in menu[0]["perms"].split(",") if p.strip()]) != 1:
            raise ValueError("Grant would include a multi-permission menu: " + permission)
    existing_links = {(int(row["role_id"]), int(row["menu_id"])) for row in snapshot["roleMenus"]
                      if row["role_id"] is not None and row["menu_id"] is not None}
    missing_grants = [p for p in grants if p not in index or (role_id, int(index[p][0]["menu_id"])) not in existing_links]
    return dict(requiredPermissions=required, missingDefinitions=[p for p in required if p not in index],
                roleId=role_id, policy=policy, grants=grants, missingGrants=missing_grants,
                routes=routes, affectedUserIds=sorted({int(row["user_id"]) for row in snapshot["userRoles"]
                    if role_id is not None and row["role_id"] is not None and row["user_id"] is not None
                    and int(row["role_id"]) == role_id}),
                originalRowsUpdated=False, originalRowsDeleted=False, wildcardAuthorization=False)


def literal(value):
    # Every permission and identifier comes from a restricted source alphabet.
    if not re.fullmatch(r"[\w:]+", value, re.ASCII):
        raise ValueError("Unsafe permission string")
    return "'" + value + "'"


def migration_sql(migration, snapshot):
    index = permission_index(snapshot)
    statements = ["SET @admin_migration_lock = GET_LOCK(CONCAT('novel-admin-perms-v1:', MD5(DATABASE())), 10);", "START TRANSACTION;"]
    missing = sorted(migration["missingDefinitions"], key=lambda p: (p.split(":")[1], p.split(":")[2] != p.split(":")[1], p))
    for permission in missing:
        _, module, action = permission.split(":")
        listing = f"novel:{module}:{module}"
        if action == module:
            # New chapter/content listing entries inherit the existing book menu's group.
            if "novel:book:book" not in index:
                raise ValueError("Existing book listing is required to place new module entries")
            parent = "parent_id"
            anchor = "novel:book:book"
            entry_type = 1
            url = f"novel/{module}"
        else:
            parent, anchor, entry_type, url = "menu_id", listing, 2, ""
            if anchor not in index and anchor not in missing:
                raise ValueError("Module parent permission is missing: " + anchor)
        label = LABELS.get(module, module) + ACTIONS.get(action, "列表")
        # Do not reactivate a preexisting orphan role_menu reference by reusing its menu ID.
        statements.append("SET @admin_permission_id = GREATEST(900000000, COALESCE((SELECT MAX(menu_id) FROM sys_menu),0), "
                          "COALESCE((SELECT MAX(menu_id) FROM sys_role_menu),0)) + 1;")
        statements.append(f"INSERT INTO sys_menu(menu_id,parent_id,name,url,perms,type,order_num,gmt_create,gmt_modified) "
            f"SELECT @admin_permission_id,{parent},'{label}','{url}',{literal(permission)},{entry_type},90,NOW(),NOW() FROM sys_menu "
            f"WHERE perms={literal(anchor)} AND @admin_migration_lock=1 "
            f"AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE FIND_IN_SET({literal(permission)},REPLACE(perms,' ',''))>0);")
    for permission in migration["grants"]:
        statements.append(f"INSERT INTO sys_role_menu(role_id,menu_id) SELECT {migration['roleId']},m.menu_id FROM sys_menu m "
            f"WHERE m.perms={literal(permission)} AND @admin_migration_lock=1 "
            f"AND EXISTS (SELECT 1 FROM sys_role WHERE role_id={migration['roleId']}) "
            f"AND NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id={migration['roleId']} AND menu_id=m.menu_id);")
    statements += ["COMMIT;", "SELECT JSON_OBJECT('migrationLockAcquired',@admin_migration_lock);",
                   "SELECT RELEASE_LOCK(CONCAT('novel-admin-perms-v1:', MD5(DATABASE())));"]
    return "\n".join(statements) + "\n"


def mysql(container, database, sql):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", container) or not re.fullmatch(r"[A-Za-z0-9_]+", database):
        raise ValueError("Invalid container/database identifier")
    # Use the container's existing root secret; credentials never enter host argv/output.
    command = ["docker", "exec", "-i", container, "sh", "-c",
               'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --default-character-set=utf8mb4 --batch --raw --skip-column-names ' + database]
    result = subprocess.run(command, input=sql.encode("utf-8"), capture_output=True, timeout=90)
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", errors="replace")[-1200:])
    return result.stdout.decode("utf-8")


def live_snapshot(container, database):
    queries = {"menus": "SELECT JSON_OBJECT('menu_id',menu_id,'parent_id',parent_id,'name',name,'perms',perms,'type',type) FROM sys_menu ORDER BY menu_id;",
               "roles": "SELECT JSON_OBJECT('role_id',role_id,'role_name',role_name,'role_sign',role_sign) FROM sys_role ORDER BY role_id;",
               "roleMenus": "SELECT JSON_OBJECT('id',id,'role_id',role_id,'menu_id',menu_id) FROM sys_role_menu ORDER BY id;",
               "userRoles": "SELECT JSON_OBJECT('user_id',user_id,'role_id',role_id) FROM sys_user_role ORDER BY user_id,role_id;"}
    return {key: [json.loads(row) for row in mysql(container, database, query).splitlines()] for key, query in queries.items()}


def evidence(out, migration, snapshot, root=ROOT):
    out.mkdir(parents=True, exist_ok=True)
    (out / "permission-snapshot.json").write_text(json.dumps(snapshot, ensure_ascii=False, indent=2), encoding="utf-8")
    (out / "permission-plan.json").write_text(json.dumps(migration, ensure_ascii=False, indent=2), encoding="utf-8")
    (out / "permission-migration.sql").write_text(migration_sql(migration, snapshot), encoding="utf-8")
    index = permission_index(snapshot)
    templates = template_permissions(root)
    with (out / "permission-matrix.csv").open("w", newline="", encoding="utf-8-sig") as stream:
        writer = csv.DictWriter(stream, fieldnames=["module", "permission", "method", "route", "controller", "template", "databaseMenuId", "targetRole", "policyDecision"])
        writer.writeheader()
        for row in migration["routes"]:
            permission = row["permission"]
            writer.writerow(dict(module=permission.split(":")[1], permission=permission, method=row["method"], route=row["route"],
                controller=f"{row['source']}:{row['line']}", template=";".join(templates.get(permission, [])),
                databaseMenuId=";".join(str(m["menu_id"]) for m in index.get(permission, [])), targetRole=migration["roleId"],
                policyDecision="explicit allowlist" if permission in migration["grants"] else "no new grant"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container")
    parser.add_argument("--database")
    parser.add_argument("--snapshot-report", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--role-id", type=int)
    parser.add_argument("--policy", choices=sorted(POLICIES))
    parser.add_argument("--apply-isolated-container", help="Must exactly match the novel-bugfix-mysql-YYYYMMDD-HHMMSS container")
    args = parser.parse_args()
    if args.snapshot_report:
        if args.apply_isolated_container:
            parser.error("Historical report mode cannot apply a migration")
        snapshot = snapshot_from_report(json.loads(args.snapshot_report.read_text(encoding="utf-8-sig")))
    else:
        if not args.container or not args.database:
            parser.error("Live preview requires --container and --database")
        if args.apply_isolated_container and (args.apply_isolated_container != args.container or
                not re.fullmatch(r"novel-bugfix-mysql-\d{8}-\d{6}", args.container)):
            parser.error("Writes require the exact novel-bugfix-mysql-YYYYMMDD-HHMMSS container name")
        snapshot = live_snapshot(args.container, args.database)
    migration = plan(snapshot, args.role_id, args.policy)
    evidence(args.out, migration, snapshot)
    if args.apply_isolated_container:
        output = mysql(args.container, args.database, migration_sql(migration, snapshot))
        if not any(json.loads(line).get("migrationLockAcquired") == 1 for line in output.splitlines() if line.startswith("{")):
            raise RuntimeError("Migration lock was not acquired; no changes were applied")
        after = live_snapshot(args.container, args.database)
        after_plan = plan(after, args.role_id, args.policy)
        evidence(args.out / "after", after_plan, after)
        if after_plan["missingDefinitions"] or after_plan["missingGrants"]:
            raise RuntimeError("Post-migration validation found remaining gaps")
        migration["applied"] = True
    print(json.dumps({key: migration[key] for key in ("missingDefinitions", "roleId", "policy", "missingGrants", "affectedUserIds")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
