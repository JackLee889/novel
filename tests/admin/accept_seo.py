"""Retained SEO admin HTTP fixtures, strictly limited to a named loopback Docker QA copy.

Uses private .env credentials in memory. Never logs passwords, cookies, CAPTCHA,
SQL input, old accounts, or response bodies. No business books/users are deleted.
Run only after root starts its isolated QA admin; this program never starts services.
"""
import argparse
import datetime
import hashlib
import json
import re
import subprocess
from pathlib import Path
from urllib.parse import urlparse
import requests

ROOT = Path(__file__).resolve().parents[2]
PERMISSIONS = ("seo:config:view", "seo:config:edit", "seo:book:view", "seo:book:edit",
               "seo:sitemap:view", "seo:sitemap:rebuild", "seo:diagnosis:view", "seo:diagnosis:run")

def read_env(path):
    values = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[key.strip()] = value
    return values

def shiro_hash(username, password):
    return hashlib.md5(hashlib.md5((username + "1qazxsw2").encode() + password.encode()).digest()).hexdigest()

def challenge_from_owned_session(value):
    """Read one exact string attribute from the owned QA session; never deserialize Java objects."""
    key = b"RANDOMVALIDATECODEKEY"
    marker = b"\x74" + len(key).to_bytes(2, "big") + key + b"\x74\x00\x04"
    match = re.search(re.escape(marker) + rb"([0-9]{4})", value)
    if len(value) > 65536 or not value.startswith(b"\xac\xed\x00\x05") or match is None:
        raise RuntimeError("Owned QA session CAPTCHA attribute was unavailable; refuse to bypass validation")
    return match.group(1).decode("ascii")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env", type=Path, required=True)
    parser.add_argument("--base", default="http://127.0.0.1:18888")
    parser.add_argument("--qa-project", required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--defer-book-write", action="store_true", help="Only stale CAS and invalid queue scope while another QA audit holds sitemap state stable")
    args = parser.parse_args()
    url = urlparse(args.base)
    if url.scheme != "http" or url.hostname not in {"localhost", "127.0.0.1"} or url.port != 18888 or url.path not in {"", "/"}:
        parser.error("Only the explicitly isolated loopback QA port 18888 is supported")
    args.base = args.base.rstrip("/")
    if not re.fullmatch(r"novel-seo-qa-\d{8}(?:-[a-z0-9]+)?", args.qa_project):
        parser.error("Refuse any project other than a named novel-seo-qa copy")
    if args.env.name != "qa.private.env" or ROOT / "backups" not in args.env.resolve().parents:
        parser.error("Credentials must come from the private QA env under this workspace's backups")
    env = read_env(args.env)
    password = env.get("ADMIN_INITIAL_PASSWORD", "")
    if not password:
        parser.error("Private QA ADMIN_INITIAL_PASSWORD is required")
    admin, mysql, redis = (args.qa_project + "-" + service + "-1" for service in ("admin", "mysql", "redis"))
    database = env.get("MYSQL_DATABASE", "novel_plus")
    if not re.fullmatch(r"[A-Za-z0-9_]+", database):
        parser.error("Invalid QA schema name")
    def docker(*command, stdin=None, binary=False):
        result = subprocess.run(["docker", *command], input=stdin, capture_output=True)
        if result.returncode:
            raise RuntimeError("Isolated Docker QA operation failed; output intentionally withheld")
        return result.stdout if binary else result.stdout.decode("utf-8")
    for container, service in ((admin, "admin"), (mysql, "mysql"), (redis, "redis")):
        info = json.loads(docker("inspect", container))[0]
        labels = info["Config"]["Labels"]
        if labels.get("com.docker.compose.project") != args.qa_project or labels.get("com.docker.compose.service") != service:
            raise RuntimeError("QA identity mismatch; refuse to operate")
        if not info["State"]["Running"]:
            raise RuntimeError("QA service is not ready; never start it here")
        if service == "admin":
            bindings = info["NetworkSettings"]["Ports"].get("8080/tcp", [])
            if not any(p["HostPort"] == "18888" and p["HostIp"] == "127.0.0.1" for p in bindings):
                raise RuntimeError("QA admin port is not bound exclusively to expected loopback")
    args.out.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d%H%M%S%f")
    report_file = args.out / ("admin-seo-acceptance-" + stamp + ".json")
    report = {"qaProject": args.qa_project, "started": stamp, "checks": [], "accountsAndRolesRetained": True,
              "businessRecordsDeleted": False, "originalRoleGrantsExpanded": False, "fixtureScope": "two new finite SEO roles/accounts only"}
    def save():
        report_file.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    def record(name, **detail):
        report["checks"].append({"name": name, "passed": True, **detail}); save(); print("PASS " + name)
    def query(sql):
        # The only MySQL target is the identity-checked QA container. Secret root credentials stay in container env.
        return docker("exec", "-i", mysql, "sh", "-c",
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 --batch --raw --skip-column-names ' + database,
            stdin=sql.encode())
    def json_rows(sql):
        return [json.loads(line) for line in query(sql).splitlines() if line.startswith("{")]
    def sql_text(value):
        return "CONVERT(UNHEX('" + value.encode().hex() + "') USING utf8mb4)"
    def login(username):
        session = requests.Session(); session.trust_env = False
        page = session.get(args.base + "/login", timeout=25); assert page.status_code == 200
        token = re.search(r'name="_csrf"\s+content="([^"]+)"', page.text).group(1)
        assert session.get(args.base + "/getVerify", timeout=25).status_code == 200
        # Production no longer logs CAPTCHA. Observe ONLY this test client's newly created session in owned QA Redis.
        session_id = session.cookies.get("JSESSIONID", "")
        if not re.fullmatch(r"[A-Za-z0-9-]{20,80}", session_id):
            raise RuntimeError("Unexpected owned QA session identifier")
        value = docker("exec", "-i", redis, "sh", "-ec",
            'REDISCLI_AUTH="$REDIS_PASSWORD" exec redis-cli --raw -x GET',
            stdin=("shiro_redis_session:" + session_id).encode(), binary=True)
        challenge = challenge_from_owned_session(value)
        response = session.post(args.base + "/login", headers={"X-CSRF-TOKEN": token},
            data={"username": username, "password": password, "verify": challenge}, timeout=25)
        assert response.status_code == 200 and response.json()["code"] == 0, "QA login failed"
        page = session.get(args.base + "/index", timeout=25); assert page.status_code == 200
        token = re.search(r'name="_csrf"\s+content="([^"]+)"', page.text).group(1)
        return session, token
    def call(session, method, path, status=200, token=None, payload=None, form=None, origin=True):
        headers = {"X-Requested-With": "XMLHttpRequest"}
        if token is not None: headers["X-CSRF-TOKEN"] = token
        if origin: headers["Origin"] = args.base
        response = session.request(method, args.base + path, headers=headers, json=payload, data=form,
            timeout=25, allow_redirects=False)
        assert response.status_code == status, f"{method} {path} expected HTTP {status}; received {response.status_code}"
        return response
    def digest_original(fixture_users=(), fixture_roles=()):
        users = ",".join(str(i) for i in fixture_users) or "-1"
        roles = ",".join(str(i) for i in fixture_roles) or "-1"
        # Passwords and old user details are neither selected nor printed. Digest only IDs/authorization relations.
        statements = [("users", "SELECT user_id id FROM sys_user WHERE user_id NOT IN (" + users + ") ORDER BY user_id"),
                      ("roles", "SELECT role_id id FROM sys_role WHERE role_id NOT IN (" + roles + ") ORDER BY role_id"),
                      ("roleMenus", "SELECT role_id a,menu_id b FROM sys_role_menu WHERE role_id NOT IN (" + roles + ") ORDER BY role_id,menu_id"),
                      ("userRoles", "SELECT user_id a,role_id b FROM sys_user_role WHERE user_id NOT IN (" + users + ") ORDER BY user_id,role_id")]
        result = {}
        for name, sql in statements:
            rows = query(sql); result[name] = hashlib.sha256(rows.encode()).hexdigest()
        return result
    try:
        before = digest_original(); operator, csrf = login("admin"); record("real CAPTCHA session CSRF login")
        # The initial account has no hard-coded Shiro wildcard: verify it before creating finite QA fixtures.
        for path in ("/config", "/books", "/sitemaps", "/diagnosis"):
            call(operator, "GET", "/novel/seo" + path)
        for path, payload in (("/config", {"expectedEpoch":"0"}),
                              ("/books/0", {"expectedRevision":"0"}),
                              ("/rebuild", {"scope":"invalid"}),
                              ("/diagnosis", {"pageType":"invalid"})):
            call(operator, "POST", "/novel/seo" + path, 400, csrf, payload)
        record("initial admin has all eight SEO permissions before fixture creation")
        menus = call(operator, "GET", "/sys/menu/list").json()
        ids = {}
        for menu in menus:
            for permission in (menu.get("perms") or "").split(","):
                if permission.strip() in PERMISSIONS: ids[permission.strip()] = int(menu["menuId"])
        parent = next(int(m["menuId"]) for m in menus if m.get("url") == "novel/seo")
        assert set(ids) == set(PERMISSIONS); record("eight SEO menu definitions available", permissionCount=8)
        viewer = "seo_view_" + stamp; editor = "seo_edit_" + stamp
        fixtures = []
        for kind, username, allowed in (("viewer", viewer, ("seo:book:view",)), ("editor", editor, PERMISSIONS)):
            role_name = "SEO QA " + kind + " " + stamp
            allowed_ids = [parent] + [ids[p] for p in allowed]
            sql = ("START TRANSACTION; SET @qa_role=GREATEST(900000000,COALESCE((SELECT MAX(role_id) FROM sys_role),0),"
                   "COALESCE((SELECT MAX(role_id) FROM sys_role_menu),0),COALESCE((SELECT MAX(role_id) FROM sys_user_role),0))+1;"
                   "INSERT INTO sys_role(role_id,role_name,role_sign,remark,user_id_create,gmt_create,gmt_modified) VALUES(@qa_role,"
                   + sql_text(role_name) + "," + sql_text(username) + ",'Retained isolated SEO acceptance',1,NOW(),NOW());"
                   + "INSERT INTO sys_role_menu(role_id,menu_id) VALUES " + ",".join("(@qa_role," + str(i) + ")" for i in allowed_ids) + ";"
                   + "SET @qa_user=GREATEST(900000000,COALESCE((SELECT MAX(user_id) FROM sys_user),0),COALESCE((SELECT MAX(user_id) FROM sys_user_role),0))+1;"
                   + "INSERT INTO sys_user(user_id,username,name,password,dept_id,status,user_id_create,gmt_create,gmt_modified) VALUES(@qa_user,"
                   + sql_text(username) + "," + sql_text("SEO QA " + kind) + ",'" + shiro_hash(username, password)
                   + "',(SELECT MIN(dept_id) FROM sys_dept),1,1,NOW(),NOW());"
                   "INSERT INTO sys_user_role(user_id,role_id) VALUES(@qa_user,@qa_role); COMMIT;"
                   "SELECT JSON_OBJECT('userId',@qa_user,'roleId',@qa_role);")
            fixture = json_rows(sql)[0]; fixture.update(kind=kind, username=username, roleName=role_name); fixtures.append(fixture)
            report["fixtures"] = fixtures; save()
        viewer_session, viewer_csrf = login(viewer); editor_session, editor_csrf = login(editor)
        viewer_page = call(viewer_session, "GET", "/novel/seo").text
        assert 'id="book-search"' in viewer_page and 'id="config-form"' not in viewer_page
        call(viewer_session, "GET", "/novel/seo/config", 403)
        call(viewer_session, "POST", "/novel/seo/rebuild", 403, viewer_csrf, {"scope": "all"})
        record("minimal viewer has book section but no settings or rebuild permission")
        for path in ("/config", "/books", "/sitemaps", "/diagnosis"):
            call(editor_session, "GET", "/novel/seo" + path)
        record("dedicated finite editor can read all four SEO interfaces")
        call(editor_session, "POST", "/novel/seo/rebuild", 403, payload={"scope": "all"})
        call(editor_session, "POST", "/novel/seo/rebuild", 403, editor_csrf, {"scope": "all"}, origin=False)
        cross = editor_session.post(args.base + "/novel/seo/rebuild", headers={"X-CSRF-TOKEN": editor_csrf,
            "Origin": "https://external.invalid", "X-Requested-With": "XMLHttpRequest"}, json={"scope": "all"}, timeout=25)
        assert cross.status_code == 403
        record("valid permission does not bypass missing CSRF or absent/cross origin")
        settings = call(editor_session, "GET", "/novel/seo/config").json()["data"]["settings"]
        config_body = dict(settings); config_body["expectedEpoch"] = str(int(settings["epoch"]) + 999); config_body.pop("epoch")
        call(editor_session, "POST", "/novel/seo/config", 409, editor_csrf, config_body)
        config_body["expectedEpoch"] = settings["epoch"]; config_body["baiduVerification"] = "<meta>"
        call(editor_session, "POST", "/novel/seo/config", 400, editor_csrf, config_body)
        record("settings CAS conflict and verification HTML rejection do not mutate configuration")
        books = call(editor_session, "GET", "/novel/seo/books?offset=0&limit=1").json()["data"]; assert books
        book_id = str(books[0]["bookId"]); path = "/novel/seo/books/" + book_id
        preview = call(editor_session, "GET", path).json()["data"]; override = preview["override"]
        payload = {k: override.get(k, "") for k in ("title", "description", "keywords")}
        payload.update(expectedRevision=str(preview["revision"]), indexMode="INHERIT" if override.get("indexEnabled") is None else "ALLOW" if override.get("indexEnabled") else "DENY")
        if args.defer_book_write:
            payload["expectedRevision"] = str(int(preview["revision"]) + 999)
            call(editor_session, "POST", path, 409, editor_csrf, payload)
            call(editor_session, "POST", "/novel/seo/rebuild", 400, editor_csrf, {"scope": "invalid"})
            report["deferredChecks"] = ["same-value override save", "valid single-book enqueue"]
            record("book permission and stale CAS verified without changing shared sitemap state", bookId=book_id)
        else:
            saved = call(editor_session, "POST", path, token=editor_csrf, payload=payload).json()["data"]
            assert int(saved["revision"]) == int(preview["revision"]), "Same-value save must preserve revision"
            assert saved["state"].get("modifiedAt") == preview["state"].get("modifiedAt"), "Same-value save must preserve lastmod"
            stale = dict(payload, expectedRevision=str(int(saved["revision"]) + 999))
            call(editor_session, "POST", path, 409, editor_csrf, stale)
            temporary = dict(payload, expectedRevision=str(saved["revision"]), title="SEO QA 校验 " + stamp)
            try:
                changed = call(editor_session, "POST", path, token=editor_csrf, payload=temporary).json()["data"]
                assert int(changed["revision"]) > int(saved["revision"])
                assert changed["override"]["title"] == temporary["title"]
                call(editor_session, "POST", path, 409, editor_csrf, payload)
            finally:
                latest = call(editor_session, "GET", path).json()["data"]
                restored = call(editor_session, "POST", path, token=editor_csrf,
                    payload=dict(payload, expectedRevision=str(latest["revision"]))).json()["data"]
                assert all(restored["override"].get(key) == override.get(key) for key in ("title", "description", "keywords", "indexEnabled"))
                report["bookOverrideRestored"] = True; save()
            job = call(editor_session, "POST", "/novel/seo/rebuild", token=editor_csrf, payload={"scope": "book", "bookId": book_id}).json()
            assert job["jobId"] and "任务已入队" in job["msg"]
            record("no-op lastmod actual override write restore revision CAS and async single-book enqueue work", bookId=book_id, jobId=job["jobId"])
        call(editor_session, "POST", "/novel/seo/diagnosis", 400, editor_csrf, {"pageType": "https://external.invalid"})
        record("diagnosis rejects arbitrary URLs before transport")
        # Change ONLY the newly created viewer role; prove existing sessions receive targeted authorization refresh.
        fixture = fixtures[0]
        form = [("roleId", str(fixture["roleId"])), ("roleName", fixture["roleName"]), ("roleSign", viewer),
                ("remark", "Retained isolated SEO acceptance")]
        form += [("menuIds", str(i)) for i in (parent, ids["seo:book:view"], ids["seo:config:view"])]
        assert call(operator, "POST", "/sys/role/update", token=csrf, form=form).json()["code"] == 0
        call(viewer_session, "GET", "/novel/seo/config")
        record("new QA role grant refreshes old authenticated viewer session")
        after = digest_original([f["userId"] for f in fixtures], [f["roleId"] for f in fixtures])
        assert before == after; report["originalAuthorizationDigestsBefore"] = before; report["originalAuthorizationDigestsAfter"] = after
        record("all original account IDs roles and authorization associations unchanged")
        final_config = call(operator, "GET", "/novel/seo/config").json()["data"]
        assert final_config["settings"] == settings
        assert all(settings[flag] is True for flag in ("indexEnabled", "chaptersIndexEnabled", "chapterSitemapEnabled"))
        assert final_config["siteUrl"].rstrip("/") == "https://xs001.xyz"
        site = json_rows("SELECT JSON_OBJECT('domain',domain,'keyword',keyword,'description',description) FROM website_info WHERE id=1;")[0]
        assert site["domain"].rstrip("/") == "https://xs001.xyz"
        assert site["keyword"] == settings["keywords"] and site["description"] == settings["defaultDescription"]
        record("three index flags domain default keywords and description remain deployed values")
        report["passed"] = True; save()
    except Exception as error:
        report["passed"] = False; report["errorType"] = type(error).__name__; save()
        if isinstance(error, AssertionError) and re.fullmatch(r"(?:GET|POST) /[^ ]+ expected HTTP \d{3}; received \d{3}", str(error)):
            report["httpFailure"] = str(error); save()
        print("FAIL SEO acceptance; inspect redacted report " + report_file.name)
        raise SystemExit(1) from None

if __name__ == "__main__":
    main()
