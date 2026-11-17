"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const rules = require("../../main/resources/static/js/crawl-category-rules.js");

const categories = Array.from({length: 8}, (_, i) => ({
    id: i + 1, name: i === 7 ? "其他小说" : "分类" + (i + 1), workDirection: i === 6 ? 1 : 0
}));

function element(tag) {
    return {
        tag, children: [], textContent: "", value: "", disabled: false,
        get firstChild() { return this.children[0]; },
        appendChild(child) { this.children.push(child); },
        removeChild(child) { this.children.splice(this.children.indexOf(child), 1); }
    };
}

function fixture() {
    const container = element("ul");
    const status = element("p");
    const readiness = [];
    const editor = rules.createEditor({createElement: element}, container, status, value => readiness.push(value));
    return {editor, container, status, readiness,
        input(key) { return container.children.flatMap(item => item.children).find(child => child.id === key); }};
}

function updatePageFixture(sourceRule, loadOrder) {
    const fields = new Map();
    const container = element("ul");
    const status = element("p");
    const pendingGets = [];
    const posts = [];
    const alerts = [];
    function getField(id) {
        const categoryInput = container.children.flatMap(item => item.children).find(child => child.id === id);
        if (categoryInput) return categoryInput;
        if (!fields.has(id)) fields.set(id, element("input"));
        return fields.get(id);
    }
    function $(selector) {
        const selected = selector.split(",").map(value => getField(value.trim().replace(/^#/, "")));
        return {
            val(value) {
                if (!arguments.length) return selected[0].value;
                selected.forEach(field => { field.value = value == null ? "" : String(value); });
                return this;
            },
            prop(key, value) { selected.forEach(field => { field[key] = value; }); return this; }
        };
    }
    $.ajax = request => {
        if (request.type === "POST") posts.push(request);
        else pendingGets.push(request);
    };
    const context = vm.createContext({
        CrawlCategoryRules: rules, $, document: {
            createElement: element,
            getElementById(id) { return id === "categoryFields" ? container : id === "categoryStatus" ? status : getField(id); }
        },
        localStorage: {getItem() { return "123"; }},
        layer: {alert(message) { alerts.push(message); }},
        location: {href: "https://service1.xs001.xyz/crawl/crawlSource_update.html"}, window: {location: {href: ""}}
    });
    const html = fs.readFileSync(path.join(__dirname, "../../main/resources/templates/crawl/crawlSource_update.html"), "utf8");
    for (const script of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)) {
        if (script[1].trim()) vm.runInContext(script[1], context);
    }
    const loadCategories = pendingGets.find(request => request.url === "/crawl/listBookCategories");
    const loadSource = pendingGets.find(request => request.url === "/crawl/getCrawlSource/123");
    const categoriesReady = () => loadCategories.success({code: 200, data: categories});
    const sourceReady = () => loadSource.success({code: 200, data: {id: 123, sourceName: "香书小说", crawlRule: JSON.stringify(sourceRule)}});
    if (loadOrder === "source-first") { sourceReady(); categoriesReady(); }
    else if (loadOrder === "categories-first") { categoriesReady(); sourceReady(); }
    return {context, posts, alerts, getField, loadCategories, loadSource, categoriesReady, sourceReady};
}

const tests = [
    ["旧规则兼容且列表字段按每个分类优先", () => {
        const old = {catIdRule: {catId1: "1", catId2: "2", catId3: "3"}, catIdRules: {catId1: ["11", "12"], catId3: []}};
        assert.deepEqual(rules.valuesFor(old, "catId1"), ["11", "12"]);
        assert.deepEqual(rules.valuesFor(old, "catId2"), ["2"]);
        assert.deepEqual(rules.valuesFor(old, "catId3"), []);
        const view = fixture();
        view.editor.setRule(old);
        view.editor.setCategories(categories);
        assert.equal(view.input("catId1").value, "11\n12");
        assert.equal(view.input("catId2").value, "2");
        assert.equal(view.input("catId3").value, "");
        assert.equal(view.input("catId8").value, "");
        const saved = view.editor.collect(old);
        assert.equal(saved.catIdRule.catId1, "11");
        assert.deepEqual(saved.catIdRules.catId1, ["11", "12"]);
        assert.equal(Object.hasOwn(saved.catIdRule, "catId3"), false);
    }],
    ["多ID去空白去重，URL的查询参数逗号保留", () => {
        assert.deepEqual(rules.parseValues(" 1,2，3\n2\r\n 4 \n"), ["1", "2", "3", "4"]);
        assert.deepEqual(rules.parseValues("https://www.biquge365.net/type?ids=5,6\nhttps://www.xxiangshu.com/type/8,9/\n/genre?id=1,2\n7,8"), [
            "https://www.biquge365.net/type?ids=5,6", "https://www.xxiangshu.com/type/8,9/", "/genre?id=1,2", "7", "8"
        ]);
    }],
    ["多值保存往返、分类8、未知规则字段均保留", () => {
        const original = {
            bookIndexNextPagePatten: "next=(\\d+)", bookIndexUrlRule: {page: "{page}", max: 99},
            bookIndexPageUrl: "https://example.com/{bookId}/{page}/", bookIndexTotalPagePatten: "total=(\\d+)",
            contentNextPagePatten: "next=([^\\s]+)",
            requestHeaders: {Referer: "https://www.xxiangshu.com/"}, charset: "gbk",
            catIdRule: {catId1: "1", catId2: "2", catId99: "future"},
            catIdRules: {catId1: ["1"], catId2: ["2"], catId99: ["future", "more"]}
        };
        const before = JSON.stringify(original);
        const view = fixture();
        view.editor.setCategories(categories);
        view.editor.setRule(original);
        view.input("catId1").value = "1,9\n9";
        view.input("catId2").value = "";
        view.input("catId8").value = "8\nhttps://www.xxiangshu.com/other?a=1,2";
        const saved = view.editor.collect(original);
        assert.equal(JSON.stringify(original), before);
        assert.deepEqual(saved.bookIndexUrlRule, original.bookIndexUrlRule);
        assert.deepEqual(saved.requestHeaders, original.requestHeaders);
        assert.equal(saved.bookIndexNextPagePatten, original.bookIndexNextPagePatten);
        assert.equal(saved.bookIndexPageUrl, original.bookIndexPageUrl);
        assert.equal(saved.bookIndexTotalPagePatten, original.bookIndexTotalPagePatten);
        assert.equal(saved.contentNextPagePatten, original.contentNextPagePatten);
        assert.equal(saved.charset, "gbk");
        assert.deepEqual(saved.catIdRules.catId1, ["1", "9"]);
        assert.equal(saved.catIdRule.catId8, "8");
        assert.deepEqual(saved.catIdRules.catId99, ["future", "more"]);
        assert.equal(Object.hasOwn(saved.catIdRule, "catId2"), false);
        assert.equal(Object.hasOwn(saved.catIdRules, "catId2"), false);
        const again = fixture();
        again.editor.setRule(JSON.parse(JSON.stringify(saved)));
        again.editor.setCategories(categories);
        assert.deepEqual(again.editor.collect(saved), saved);
    }],
    ["字典未加载、错误响应、网络错误、无效字典阻止保存", () => {
        const view = fixture();
        assert.equal(view.editor.isReady(), false);
        assert.throws(() => view.editor.collect({}), /尚未成功加载/);
        rules.loadCategories(view.editor, (url, success) => {
            assert.equal(url, "/crawl/listBookCategories");
            success({code: 500, data: categories});
        });
        assert.equal(view.editor.isReady(), false);
        assert.match(view.status.textContent, /不能保存/);
        rules.loadCategories(view.editor, (url, success, failure) => failure());
        assert.equal(view.editor.isReady(), false);
        assert.throws(() => view.editor.collect({}), /尚未成功加载/);
        rules.loadCategories(view.editor, (url, success) => success({code: 200, data: []}));
        assert.equal(view.editor.isReady(), false);
        rules.loadCategories(view.editor, (url, success) => success({code: 200, data: categories}));
        assert.equal(view.editor.isReady(), true);
        view.editor.fail("later failure");
        assert.equal(view.input("catId8").disabled, true);
        assert.throws(() => view.editor.collect({}), /尚未成功加载/);
        assert.throws(() => view.editor.setCategories([{id: "8<script>", name: "invalid"}]), /字典格式/);
        assert.equal(view.editor.isReady(), false);
        assert.throws(() => view.editor.setCategories([categories[0], categories[0]]), /字典格式/);
    }],
    ["空分类删除，全部空分类禁止保存", () => {
        const view = fixture();
        view.editor.setCategories(categories);
        assert.throws(() => view.editor.collect({}), /至少要填一项/);
        view.input("catId8").value = "8";
        assert.deepEqual(view.editor.collect({}).catIdRules, {catId8: ["8"]});
        assert.throws(() => rules.valuesFor({catIdRules: {catId8: "8"}}, "catId8"), /列表格式/);
    }],
    ["状态未编辑保留全部别名，只替换发生编辑的状态", () => {
        const original = {"全本": 1, "连载中": 0, "连载": 0, "已完结": 1, "完结": 1, "暂停": 2};
        const before = JSON.stringify(original);
        assert.deepEqual(rules.writeBookStatuses(original, ["连载", "完结"], ["连载", "完结"]), original);
        assert.deepEqual(rules.writeBookStatuses(original, ["连载", "完结"], ["正在连载", "完结"]),
            {"全本": 1, "已完结": 1, "完结": 1, "暂停": 2, "正在连载": 0});
        assert.deepEqual(rules.writeBookStatuses(original, ["连载", "完结"], ["连载", ""]),
            {"连载中": 0, "连载": 0, "暂停": 2});
        assert.throws(() => rules.writeBookStatuses(original, ["连载", "完结"], ["完结", "完结"]), /不能相同/);
        assert.throws(() => rules.writeBookStatuses(original, ["连载", "完结"], ["已完结", "完结"]), /别名冲突/);
        assert.equal(JSON.stringify(original), before);
    }],
    ["分类名称通过textContent渲染", () => {
        const view = fixture();
        const injectedName = '<img src=x onerror="alert(1)">';
        view.editor.setCategories([{id: 8, name: injectedName, workDirection: 0}]);
        const label = view.container.children[0].children[0];
        assert.equal(label.textContent, injectedName + "（本站分类ID：8）");
        assert.equal(Object.hasOwn(label, "innerHTML"), false);
    }],
    ["编辑页实际保存脚本保留隐藏字段并正确往返，与加载顺序无关", () => {
        const original = {
            bookListUrl: "https://www.xxiangshu.com/type/{catId}/{page}/", catIdRule: {catId1: "1"},
            bookIdPatten: "(book)", bookDetailUrl: "https://example.com/{bookId}", bookNamePatten: "(name)",
            authorNamePatten: "(author)", descStart: "<desc>", descEnd: "</desc>", bookIndexUrl: "https://example.com/{bookId}/",
            indexIdPatten: "(index)", indexNamePatten: "(title)", bookContentUrl: "https://example.com/{indexId}",
            contentStart: "<content>", contentEnd: "</content>", charset: "gbk", pagePatten: "oldpage=(\\d+)",
            bookStatusRule: {"全本": 1, "连载中": 0, "连载": 0, "已完结": 1, "完结": 1}, bookIndexNextPagePatten: "next=(\\d+)",
            bookIndexPageUrl: "https://example.com/{bookId}/{page}/", bookIndexTotalPagePatten: "total=(\\d+)",
            contentNextPagePatten: "next=([^\\s]+)",
            bookIndexUrlRule: {page: "{page}", max: 99}, customRule: {keep: [1, 2]}
        };
        for (const order of ["source-first", "categories-first"]) {
            const page = updatePageFixture(original, order);
            assert.equal(page.getField("btnRegister").disabled, false);
            assert.equal(page.getField("bookStatus0").value, "连载");
            assert.equal(page.getField("bookStatus1").value, "完结");
            page.getField("catId1").value = "1,2";
            page.getField("catId8").value = "8\nhttps://www.xxiangshu.com/other?a=1,2";
            page.getField("pagePatten").value = "";
            vm.runInContext("updateCrawlSource()", page.context);
            assert.equal(page.posts.length, 1);
            const saved = JSON.parse(page.posts[0].data.crawlRule);
            assert.equal(page.posts[0].url, "/crawl/updateCrawlSource");
            assert.deepEqual(saved.catIdRules.catId1, ["1", "2"]);
            assert.deepEqual(saved.catIdRules.catId8, ["8", "https://www.xxiangshu.com/other?a=1,2"]);
            assert.equal(saved.catIdRule.catId8, "8");
            assert.deepEqual(saved.bookIndexUrlRule, original.bookIndexUrlRule);
            assert.deepEqual(saved.customRule, original.customRule);
            assert.equal(saved.bookIndexNextPagePatten, original.bookIndexNextPagePatten);
            assert.equal(saved.bookIndexPageUrl, original.bookIndexPageUrl);
            assert.equal(saved.bookIndexTotalPagePatten, original.bookIndexTotalPagePatten);
            assert.equal(saved.contentNextPagePatten, original.contentNextPagePatten);
            assert.equal(saved.charset, "gbk");
            assert.equal(Object.hasOwn(saved, "pagePatten"), false);
            assert.deepEqual(saved.bookStatusRule, original.bookStatusRule);
        }
        const edited = updatePageFixture(original, "source-first");
        edited.getField("bookStatus0").value = "正在连载";
        vm.runInContext("updateCrawlSource()", edited.context);
        assert.deepEqual(JSON.parse(edited.posts[0].data.crawlRule).bookStatusRule,
            {"全本": 1, "已完结": 1, "完结": 1, "正在连载": 0});
        const conflict = updatePageFixture(original, "source-first");
        conflict.getField("bookStatus0").value = "已完结";
        vm.runInContext("updateCrawlSource()", conflict.context);
        assert.equal(conflict.posts.length, 0);
        assert.match(conflict.alerts.at(-1), /别名冲突/);
    }],
    ["编辑页缺少字典或原规则时实际提交函数不会发起写请求", () => {
        const noDictionary = updatePageFixture({catIdRule: {catId8: "8"}});
        noDictionary.sourceReady();
        noDictionary.loadCategories.error();
        vm.runInContext("updateCrawlSource()", noDictionary.context);
        assert.equal(noDictionary.getField("btnRegister").disabled, true);
        assert.equal(noDictionary.posts.length, 0);
        const noSource = updatePageFixture({});
        noSource.categoriesReady();
        noSource.loadSource.error();
        vm.runInContext("updateCrawlSource()", noSource.context);
        assert.equal(noSource.getField("btnRegister").disabled, true);
        assert.equal(noSource.posts.length, 0);
        assert.match(noSource.alerts.at(-1), /规则尚未成功加载/);
    }],
    ["新书采集范围默认全部，三种选项保存回显且不覆盖状态映射", () => {
        const template = JSON.parse(fs.readFileSync(path.join(__dirname,
            "../../main/resources/static/crawl-templates/bqg616.json"), "utf8"));
        for (const selection of [undefined, null, "ALL", "COMPLETED", "SERIALIZING"]) {
            const original = {...template, newBookStatus: selection};
            const expected = selection == null ? "ALL" : selection;
            for (const order of ["source-first", "categories-first"]) {
                const page = updatePageFixture(original, order);
                assert.equal(page.getField("newBookStatus").value, expected);
                vm.runInContext("updateCrawlSource()", page.context);
                assert.equal(page.posts.length, 1);
                const saved = JSON.parse(page.posts[0].data.crawlRule);
                assert.equal(saved.newBookStatus, expected);
                assert.deepEqual(saved.bookStatusRule, template.bookStatusRule);
                const reloaded = updatePageFixture(saved, order);
                assert.equal(reloaded.getField("newBookStatus").value, expected);
                reloaded.getField("newBookStatus").value = "SERIALIZING";
                vm.runInContext("updateCrawlSource()", reloaded.context);
                assert.equal(JSON.parse(reloaded.posts[0].data.crawlRule).newBookStatus, "SERIALIZING");
                assert.equal(page.getField("newBookStatus").value, expected);
            }
        }
        const invalid = updatePageFixture({...template, newBookStatus: "INVALID"}, "source-first");
        vm.runInContext("updateCrawlSource()", invalid.context);
        assert.equal(invalid.posts.length, 0);
        assert.match(invalid.alerts.at(-1), /新书采集范围/);
    }],
    ["两个模板使用动态字典并保持可解析脚本", () => {
        for (const name of ["crawlSource_add.html", "crawlSource_update.html"]) {
            const html = fs.readFileSync(path.join(__dirname, "../../main/resources/templates/crawl", name), "utf8");
            assert.match(html, /id="categoryFields"/);
            assert.match(html, /CrawlCategoryRules\.loadCategories/);
            assert.match(html, /categoryEditor\.collect/);
            assert.doesNotMatch(html, /for \(var i = 1; i <= 7; i\+\+\)/);
            assert.match(html, /id="btnRegister" class="btn_red" disabled="disabled"/);
            for (const script of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)) {
                if (script[1].trim()) new vm.Script(script[1], {filename: name});
            }
            if (name.includes("update")) {
                assert.match(html, /cloneRule\(loadedCrawlRule\)/);
                assert.match(html, /!sourceRuleLoaded/);
            }
        }
    }]
];

for (const [name, run] of tests) {
    run();
    console.log("PASS " + name);
}
console.log(tests.length + " category editor checks passed");
